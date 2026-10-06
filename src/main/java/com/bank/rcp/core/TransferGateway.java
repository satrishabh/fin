package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * transfer-gateway + mechanism-registry + volume-counter. The ONLY path between cells: calls
 * the PDP, checks mechanisms, requires a human-approved transform profile for workaround
 * transforms, updates calendar-year counters, writes ledger + evidence.
 */
@Service
public class TransferGateway {
    private final DataStore store;
    private final PolicyEngine pdp;
    private final EventBus bus;
    private final EvidenceService evidence;
    /** Human-approved transform profiles: key origin|dest|purpose -> WP id. */
    private final Map<String, Map<String, Object>> transformProfiles = new ConcurrentHashMap<>();

    public TransferGateway(DataStore store, PolicyEngine pdp, EventBus bus, EvidenceService evidence, CaseService cases) {
        this.store = store;
        this.pdp = pdp;
        this.bus = bus;
        this.evidence = evidence;
        cases.onApproved("workaround_proposal", this::approveProfileFromCase);
        cases.onApproved("mechanism_record", c -> recordMechanism(Json.convert(c.payload.get("mechanism"), Mechanism.class), lastApprover(c)));
    }

    private static String lastApprover(CaseItem c) { return c.approvals.isEmpty() ? "unknown" : c.approvals.get(c.approvals.size() - 1).by; }

    private static String profileKey(String origin, String dest, String purpose) { return origin + "|" + dest + "|" + purpose; }

    private void approveProfileFromCase(CaseItem c) {
        Map<String, Object> rec = Json.toMap(c.payload.get("recommended"));
        Map<String, Object> t = Json.toMap(c.payload.get("transfer"));
        String wp = Json.str(rec, "patternId", null);
        if (wp == null || t.isEmpty()) { c.resolution = "Approved (no transfer profile to record)"; return; }
        String origin = Json.str(t, "originCell", "?"), dest = Json.str(t, "destinationCountry", "?"), purpose = Json.str(t, "purpose", "?");
        Map<String, Object> p = Json.map("originCell", origin, "destinationCountry", dest, "purpose", purpose, "transform", wp,
                "approvedBy", c.approvals.stream().map(a -> a.by + "(" + a.role + ")").toList(), "caseId", c.id, "approvedAt", Ids.now());
        transformProfiles.put(profileKey(origin, dest, purpose), p);
        evidence.record(origin, "transfer.profile.approved", c.id, p, "EV-7Y");
        c.resolution = "Transform profile " + wp + " recorded for " + origin + " -> " + dest + " (" + purpose + "); transfer-gateway will enforce it";
    }

    public Collection<Map<String, Object>> profiles() { return transformProfiles.values(); }

    public Decision simulate(Map<String, Object> input) { return pdp.evaluateTransfer(input, true); }

    public synchronized Transfer request(Map<String, Object> input, String user) {
        Transfer t = Json.convert(input, Transfer.class);
        t.id = Ids.next("trf");
        t.requestedBy = user;
        t.ts = Ids.now();
        if (t.exporter == null) t.exporter = pdp.defaultExporter(t.originCell);
        Map<String, Object> pin = Json.toMap(t);
        Decision d = pdp.evaluateTransfer(pin, false);
        t.decisionId = d.decisionId;
        t.reasons = new ArrayList<>(d.reasons);
        t.mechanismId = (String) d.extra.get("mechanismId");

        boolean allowed = d.effectiveAllow;
        if (allowed && t.transform != null && !t.transform.isBlank()) {
            Map<String, Object> prof = transformProfiles.get(profileKey(t.originCell, t.destinationCountry, t.purpose));
            if (prof == null || !t.transform.equals(prof.get("transform"))) {
                allowed = false;
                t.reasons.add("GATEWAY: transform " + t.transform + " has no human-approved transform profile for " + t.originCell + " -> " + t.destinationCountry + " (" + t.purpose + ")");
            } else t.reasons.add("GATEWAY: enforcing approved transform profile " + prof.get("transform") + " (case " + prof.get("caseId") + ")");
        }
        t.status = allowed ? "completed" : "blocked";
        if (allowed && "CN".equals(t.originCell)) incrementCounter(t);

        store.transfers.put(t.id, t);
        EvidenceRecord ev = evidence.record(t.originCell, "transfer." + t.status, t.id,
                Json.map("transferId", t.id, "decisionId", d.decisionId, "status", t.status, "mechanismId", t.mechanismId, "transform", t.transform,
                        "destination", t.destinationCountry, "purpose", t.purpose, "dataClasses", t.dataClasses, "bundleVersion", d.bundleVersion,
                        "requiredRoute", d.extra.get("requiredRoute"), "by", user), "EV-7Y");
        t.evidenceId = ev.id;
        bus.publish("transfer.ledger", "transfer." + (allowed ? "approved" : "blocked"), t.originCell, "transfer-gateway", t.id,
                Json.map("transferId", t.id, "origin", t.originCell, "destination", t.destinationCountry, "purpose", t.purpose, "status", t.status,
                        "mechanismId", t.mechanismId, "transform", t.transform, "decisionId", d.decisionId));
        return t;
    }

    private void incrementCounter(Transfer t) {
        String effective = String.valueOf(pdp.evaluateTransfer(Json.toMap(t), true).extra.get("effectiveDataClasses"));
        if (!effective.contains("personal") && !effective.contains("payment")) return; // no PI left the cell
        int year = LocalDate.now().getYear();
        Counter c = store.counters.computeIfAbsent(DataStore.counterKey(t.exporter, year), k -> {
            Counter n = new Counter(); n.exporter = t.exporter; n.year = year; return n;
        });
        c.nonsensitive += t.individualsNonsensitive;
        c.sensitive += t.individualsSensitive;
        c.reconciledExact = false;
        checkThresholds(c);
    }

    private void checkThresholds(Counter c) {
        Map<String, Object> th = Json.toMap(store.pdata("cn.thresholds"));
        List<Double> ratios = new ArrayList<>();
        for (String r : Json.strList(th.get("warn_ratios"))) ratios.add(Double.parseDouble(r));
        long[][] pairs = {{c.nonsensitive, Json.num(th, "exempt_nonsensitive_below", 100000)}, {c.sensitive, Json.num(th, "assessment_sensitive", 10000)}};
        String[] names = {"nonsensitive vs exemption threshold", "sensitive vs assessment threshold"};
        for (int i = 0; i < pairs.length; i++) {
            double ratio = (double) pairs[i][0] / pairs[i][1];
            for (double r : ratios) {
                if (ratio >= r) {
                    bus.publish("transfer.ledger", "counter.threshold.approaching", "CN", "volume-counter", c.exporter,
                            Json.map("exporter", c.exporter, "year", c.year, "metric", names[i], "value", pairs[i][0], "threshold", pairs[i][1], "ratio", Math.round(ratio * 100) / 100.0));
                    break;
                }
            }
        }
    }

    public List<Map<String, Object>> counters() {
        Map<String, Object> th = Json.toMap(store.pdata("cn.thresholds"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Counter c : store.counters.values()) {
            out.add(Json.map("exporter", c.exporter, "year", c.year, "nonsensitive", c.nonsensitive, "sensitive", c.sensitive,
                    "reconciledExact", c.reconciledExact, "lastReconciled", c.lastReconciled, "thresholds", th,
                    "nonsensitiveRatio", Math.round(100.0 * c.nonsensitive / Json.num(th, "exempt_nonsensitive_below", 100000)) / 100.0,
                    "sensitiveRatio", Math.round(100.0 * c.sensitive / Json.num(th, "assessment_sensitive", 10000)) / 100.0));
        }
        return out;
    }

    public Map<String, Object> projection(String exporter, long addNonsensitive, long addSensitive) {
        int year = LocalDate.now().getYear();
        Counter c = store.counters.get(DataStore.counterKey(exporter, year));
        long n = (c == null ? 0 : c.nonsensitive), s = (c == null ? 0 : c.sensitive);
        Map<String, Object> th = Json.toMap(store.pdata("cn.thresholds"));
        return Json.map("exporter", exporter, "year", year, "currentNonsensitive", n, "currentSensitive", s,
                "projectedNonsensitive", n + addNonsensitive, "projectedSensitive", s + addSensitive, "thresholds", th,
                "resetsOn", (year + 1) + "-01-01");
    }

    /** Exact reconciliation batch (POC: marks counters reconciled; HLL sketches would be compared here). */
    public List<Map<String, Object>> reconcile() {
        for (Counter c : store.counters.values()) { c.reconciledExact = true; c.lastReconciled = Ids.now(); }
        return counters();
    }

    // ------------------------------------------------------------ mechanism registry

    public List<Mechanism> mechanisms() { return DataStore.sorted(store.mechanisms, Comparator.comparing(m -> m.id)); }

    public List<Map<String, Object>> expiring(int days) {
        LocalDate limit = LocalDate.now().plusDays(days);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Mechanism m : mechanisms()) {
            if (m.validTo == null || !"active".equals(m.status)) continue;
            LocalDate to = LocalDate.parse(m.validTo);
            if (!to.isAfter(limit)) out.add(Json.map("id", m.id, "type", m.type, "validTo", m.validTo, "expired", to.isBefore(LocalDate.now()), "destinations", m.destinations, "purposes", m.purposes));
        }
        return out;
    }

    /** Recording a legal mechanism is a human-only action (case approval or legal UI). */
    public Mechanism recordMechanism(Mechanism m, String user) {
        if (m == null) throw new IllegalArgumentException("mechanism payload missing");
        if (m.id == null) m.id = Ids.next("MEC");
        m.recordedBy = user;
        if (m.status == null) m.status = "active";
        store.mechanisms.put(m.id, m);
        evidence.record("GLOBAL", "mechanism.recorded", m.id, Json.toMap(m), "EV-7Y");
        bus.publish("platform.events", "mechanism.recorded", null, "mechanism-registry", m.id, Json.map("mechanismId", m.id, "type", m.type, "by", user));
        return m;
    }

    public Mechanism revoke(String id, String user) {
        Mechanism m = store.mechanisms.get(id);
        if (m == null) throw new NoSuchElementException(id);
        m.status = "revoked";
        evidence.record("GLOBAL", "mechanism.revoked", m.id, Json.map("mechanismId", id, "by", user), "EV-7Y");
        bus.publish("platform.events", "mechanism.revoked", null, "mechanism-registry", m.id, Json.map("mechanismId", id, "by", user));
        return m;
    }
}

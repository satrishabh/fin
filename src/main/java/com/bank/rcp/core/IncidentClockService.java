package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * incident-clock-service: one detection timestamp starts parallel jurisdiction timers defined
 * as configuration (misc.json incidentRegimes). Clocks can only be closed with evidence of
 * human submission; agents cannot stop or extend them.
 */
@Service
public class IncidentClockService {
    private static final List<String> SEV = List.of("low", "medium", "high", "critical");

    private final DataStore store;
    private final EventBus bus;
    private final EvidenceService evidence;

    public IncidentClockService(DataStore store, EventBus bus, EvidenceService evidence) {
        this.store = store;
        this.bus = bus;
        this.evidence = evidence;
    }

    public Incident open(Map<String, Object> in, String user) {
        Incident i = new Incident();
        i.id = Ids.next("inc");
        i.title = Json.str(in, "title", "Security incident");
        i.description = Json.str(in, "description", "");
        i.severity = Json.str(in, "severity", "high");
        i.detectedAt = Json.str(in, "detectedAt", Ids.now());
        i.assetIds = Json.strList(in.get("assetIds"));
        i.individualsAffected = Json.num(in, "individualsAffected", 0);
        i.openedBy = user;
        Set<String> jur = new TreeSet<>();
        boolean personal = Json.bool(in, "personalDataAffected", false);
        for (String id : i.assetIds) {
            DataAsset a = store.assets.get(id);
            if (a == null) continue;
            jur.add(a.tags.originJurisdiction != null ? a.tags.originJurisdiction : a.cell);
            if (a.tags.dataClass != null && Set.of("personal", "sensitive_personal", "payment", "health").contains(a.tags.dataClass)) personal = true;
            if (i.individualsAffected == 0) i.individualsAffected = a.individuals;
        }
        i.jurisdictions = new ArrayList<>(jur);
        i.personalDataAffected = personal;
        startClocks(i);
        store.incidents.put(i.id, i);
        evidence.record("GLOBAL", "incident.opened", i.id, Json.map("incidentId", i.id, "detectedAt", i.detectedAt, "jurisdictions", i.jurisdictions,
                "clocks", i.clocks.stream().map(c -> c.regime + "@" + c.deadlineAt).toList(), "by", user), "EV-7Y");
        bus.publish("incident.events", "incident.opened", null, "incident-clock-service", i.id,
                Json.map("incidentId", i.id, "title", i.title, "severity", i.severity, "jurisdictions", i.jurisdictions, "clocks", i.clocks.size()));
        return i;
    }

    private void startClocks(Incident i) {
        Instant detected = Instant.parse(i.detectedAt);
        for (Map<String, Object> r : store.incidentRegimes) {
            if (!i.jurisdictions.contains(Json.str(r, "jurisdiction", ""))) continue;
            if (Json.bool(r, "requiresPersonalData", false) && !i.personalDataAffected) continue;
            String minSev = Json.str(r, "minSeverity", "low");
            if (SEV.indexOf(i.severity) < SEV.indexOf(minSev)) continue;
            Clock c = new Clock();
            c.id = Ids.next("clk");
            c.regime = Json.str(r, "regime", "?");
            c.jurisdiction = Json.str(r, "jurisdiction", "?");
            c.authority = Json.str(r, "authority", "?");
            c.basis = Json.str(r, "basis", "");
            c.hours = Json.num(r, "hours", 72);
            c.template = Json.str(r, "template", null);
            c.startedAt = i.detectedAt;
            c.deadlineAt = detected.plus(Duration.ofHours(c.hours)).toString();
            i.clocks.add(c);
        }
        refresh(i);
    }

    @Scheduled(fixedDelay = 30_000)
    public void tick() {
        for (Incident i : store.incidents.values()) refresh(i);
    }

    private void refresh(Incident i) {
        Instant now = Instant.now();
        for (Clock c : i.clocks) {
            if ("closed".equals(c.status)) continue;
            Instant dl = Instant.parse(c.deadlineAt);
            long total = Duration.between(Instant.parse(c.startedAt), dl).toMillis();
            long left = Duration.between(now, dl).toMillis();
            String prev = c.status;
            if (left <= 0) c.status = "breached";
            else if (left < total * 0.25) c.status = "warning";
            else c.status = "running";
            if (!c.status.equals(prev) && !"running".equals(c.status))
                bus.publish("incident.events", "clock." + c.status, null, "incident-clock-service", i.id, Json.map("incidentId", i.id, "clockId", c.id, "regime", c.regime, "deadlineAt", c.deadlineAt));
        }
    }

    /** Human closes a clock with a submission reference; evidence is recorded. */
    public Incident closeClock(String incidentId, String clockId, String submissionRef, String user) {
        Incident i = require(incidentId);
        Clock c = i.clocks.stream().filter(x -> x.id.equals(clockId)).findFirst().orElseThrow(() -> new NoSuchElementException(clockId));
        EvidenceRecord ev = evidence.record("GLOBAL", "clock.closed", i.id, Json.map("incidentId", i.id, "clockId", c.id, "regime", c.regime, "submissionRef", submissionRef, "by", user, "closedAt", Ids.now(), "deadlineAt", c.deadlineAt), "EV-7Y");
        c.status = "closed";
        c.closedEvidenceId = ev.id;
        bus.publish("incident.events", "clock.closed", null, "incident-clock-service", i.id, Json.map("incidentId", i.id, "clockId", c.id, "by", user));
        return i;
    }

    public Incident require(String id) {
        Incident i = store.incidents.get(id);
        if (i == null) throw new NoSuchElementException("incident " + id);
        return i;
    }

    public List<Incident> list() {
        List<Incident> l = new ArrayList<>(store.incidents.values());
        l.forEach(this::refresh);
        l.sort(Comparator.comparing((Incident i) -> i.detectedAt).reversed());
        return l;
    }

    /** Deterministic template rendering; output is always a DRAFT, never sent. */
    public Map<String, Object> renderDraft(String incidentId, String template) {
        Incident i = require(incidentId);
        String tpl = store.templates.get(template);
        if (tpl == null) throw new NoSuchElementException("template " + template);
        Clock clock = i.clocks.stream().filter(c -> template.equals(c.template)).findFirst().orElse(null);
        String text = tpl.replace("{incidentId}", i.id).replace("{detectedAt}", i.detectedAt).replace("{description}", i.description)
                .replace("{individuals}", String.valueOf(i.individualsAffected)).replace("{assets}", String.join(", ", i.assetIds))
                .replace("{authority}", clock == null ? "competent authority" : clock.authority);
        return Json.map("incidentId", i.id, "template", template, "draft", text, "status", "DRAFT - only humans submit notices");
    }
}

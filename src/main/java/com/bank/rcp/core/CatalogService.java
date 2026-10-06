package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * catalog-service + classification-service + lineage-service. Classification uses column-name
 * dictionaries (Presidio-style recognisers on metadata and masked samples only).
 */
@Service
public class CatalogService {
    public static final Set<String> REGULATED = Set.of("personal", "sensitive_personal", "payment", "trade", "health", "important_data_candidate");
    public static final double AUTO_TAG_THRESHOLD = 0.80;
    public static final double L3_THRESHOLD = 0.95;

    private static final Map<String, List<String>> DICTIONARY = new LinkedHashMap<>();
    static {
        DICTIONARY.put("payment", List.of("txn", "amount", "amt", "vpa", "card_token", "merchant", "settlement", "payer", "payee", "account_ref", "iban", "paynow", "upi"));
        DICTIONARY.put("sensitive_personal", List.of("biometric", "health", "religion", "kyc", "document_type", "financial_account", "doc_ref"));
        DICTIONARY.put("personal", List.of("name", "email", "phone", "contact", "dob", "address", "client_id", "customer_id", "full_name"));
        DICTIONARY.put("trade", List.of("isin", "trade", "order", "notional", "uti", "counterparty", "price", "cusip"));
        DICTIONARY.put("important_data_candidate", List.of("sector", "aggregate_exposure", "market_data_aggregate"));
    }

    private final DataStore store;
    private final EventBus bus;
    private final EvidenceService evidence;
    private final CaseService cases;

    public CatalogService(DataStore store, EventBus bus, EvidenceService evidence, CaseService cases) {
        this.store = store;
        this.bus = bus;
        this.evidence = evidence;
        this.cases = cases;
        cases.onApproved("retag_request", c -> applyTag(Json.str(c.payload, "assetId", null), Json.str(c.payload, "proposedDataClass", null), "human", 1.0, c.id));
        cases.onApproved("quarantine_request", c -> quarantine(Json.str(c.payload, "assetId", null), c.id));
    }

    public List<DataAsset> search(String cell, String q, String dataClass, String segment) {
        List<DataAsset> l = new ArrayList<>(store.assets.values());
        l.removeIf(a -> cell != null && !cell.isBlank() && !cell.equalsIgnoreCase(a.cell));
        l.removeIf(a -> dataClass != null && !dataClass.isBlank() && !dataClass.equals(a.tags.dataClass));
        l.removeIf(a -> segment != null && !segment.isBlank() && !segment.equals(a.segment));
        if (q != null && !q.isBlank()) {
            String ql = q.toLowerCase();
            l.removeIf(a -> !(a.id + " " + a.name + " " + a.location + " " + a.tags.dataClass).toLowerCase().contains(ql));
        }
        l.sort(Comparator.comparing(a -> a.id));
        return l;
    }

    public DataAsset require(String id) {
        DataAsset a = store.assets.get(id);
        if (a == null) throw new NoSuchElementException("asset " + id);
        return a;
    }

    public DataAsset register(DataAsset a, String user) {
        if (a.id == null || !a.id.matches("^asset:[a-z]{2}:.+")) throw new IllegalArgumentException("asset id must match asset:{cell}:{segment}:{name}");
        if (a.cell == null) a.cell = a.id.split(":")[1].toUpperCase();
        if (a.tags == null) a.tags = new Tags();
        if (a.tags.originJurisdiction == null) a.tags.originJurisdiction = a.cell;
        a.registeredAt = Ids.now();
        store.assets.put(a.id, a);
        bus.publish("asset.lifecycle", "asset.registered", a.cell, "catalog-service", a.id, Json.map("assetId", a.id, "location", a.location, "by", user));
        evidence.record(a.cell, "asset.registered", a.id, Json.map("assetId", a.id, "location", a.location, "by", user), "EV-7Y");
        return a;
    }

    public List<Map<String, Object>> lineage(String assetId, int depth) {
        List<Map<String, Object>> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String[]> q = new ArrayDeque<>();
        q.add(new String[]{assetId, "0"});
        while (!q.isEmpty()) {
            String[] cur = q.poll();
            int d = Integer.parseInt(cur[1]);
            if (d >= depth || !seen.add(cur[0])) continue;
            for (LineageEdge e : store.lineage) {
                if (e.from.equals(cur[0]) || e.to.equals(cur[0])) {
                    Map<String, Object> m = Json.map("from", e.from, "to", e.to, "job", e.job, "depth", d + 1);
                    if (!out.contains(m)) out.add(m);
                    q.add(new String[]{e.from.equals(cur[0]) ? e.to : e.from, String.valueOf(d + 1)});
                }
            }
        }
        return out;
    }

    /** Masked samples, in-cell only: column names, types, masked shapes. Never raw values. */
    public Map<String, Object> sampleMasked(String assetId) {
        DataAsset a = require(assetId);
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Column c : a.columns) cols.add(Json.map("name", c.name, "type", c.type, "maskedSample", c.sample));
        return Json.map("assetId", a.id, "cell", a.cell, "columns", cols, "recordCount", a.recordCount, "note", "masked samples only");
    }

    // ------------------------------------------------------------ classification

    public Map<String, Object> detect(DataAsset a) {
        Map<String, Integer> score = new LinkedHashMap<>();
        List<String> detectors = new ArrayList<>();
        for (Column c : a.columns) {
            String n = c.name.toLowerCase();
            for (var e : DICTIONARY.entrySet()) {
                for (String kw : e.getValue()) {
                    if (n.contains(kw)) {
                        score.merge(e.getKey(), 1, Integer::sum);
                        detectors.add(e.getKey() + ":" + c.name);
                        c.detectedClass = e.getKey();
                        break;
                    }
                }
            }
        }
        String best = null;
        int bestScore = 0;
        // precedence: sensitive > payment > personal > trade > important
        for (String k : List.of("sensitive_personal", "payment", "personal", "trade", "important_data_candidate")) {
            int s = score.getOrDefault(k, 0);
            if (s > bestScore) { best = k; bestScore = s; }
        }
        int cols = Math.max(1, a.columns.size());
        double conf = best == null ? 0.2 : Math.min(0.99, 0.35 + 0.6 * ((double) bestScore / cols));
        if (best == null) best = "internal";
        return Json.map("proposedClass", best, "confidence", Math.round(conf * 100) / 100.0, "detectors", detectors, "scores", score);
    }

    public List<ClassificationResult> runClassification(String cell, String user) {
        List<ClassificationResult> out = new ArrayList<>();
        for (DataAsset a : search(cell, null, null, null)) {
            if (a.tags.dataClass != null && !"ml".equals(a.tags.source)) continue; // human/rule tags are authoritative
            boolean alreadyQueued = store.classifications.values().stream().anyMatch(r -> r.assetId.equals(a.id) && Set.of("low_confidence", "pending_approval").contains(r.status));
            if (alreadyQueued) continue;
            Map<String, Object> det = detect(a);
            ClassificationResult r = new ClassificationResult();
            r.id = Ids.next("cls");
            r.assetId = a.id;
            r.cell = a.cell;
            r.proposedClass = String.valueOf(det.get("proposedClass"));
            r.confidence = Json.dbl(det, "confidence", 0);
            r.detectors = Json.strList(det.get("detectors"));
            r.ts = Ids.now();
            if (a.tags.dataClass != null && a.tags.dataClass.equals(r.proposedClass)) continue;
            if (r.confidence >= AUTO_TAG_THRESHOLD && a.tags.dataClass == null) {
                r.status = "auto_applied";
                applyTag(a.id, r.proposedClass, "rule", r.confidence, r.id);
                bus.publish("classification.results", "classification.completed", a.cell, "classification-service", a.id, Json.map("assetId", a.id, "dataClass", r.proposedClass, "confidence", r.confidence));
            } else {
                r.status = "low_confidence";
                bus.publish("classification.results", "classification.low_confidence", a.cell, "classification-service", a.id, Json.map("assetId", a.id, "proposedClass", r.proposedClass, "confidence", r.confidence, "resultId", r.id));
            }
            store.classifications.put(r.id, r);
            out.add(r);
        }
        return out;
    }

    public void applyTag(String assetId, String dataClass, String source, double confidence, String ref) {
        DataAsset a = require(assetId);
        String old = a.tags.dataClass;
        a.tags.dataClass = dataClass;
        a.tags.source = source;
        a.tags.confidence = confidence;
        if (a.tags.sensitivity == null) a.tags.sensitivity = REGULATED.contains(dataClass) ? "high" : "low";
        store.classifications.values().stream().filter(r -> r.assetId.equals(assetId) && !"auto_applied".equals(r.status)).forEach(r -> r.status = "resolved");
        bus.publish("asset.lifecycle", "asset.tagged", a.cell, "catalog-service", a.id, Json.map("assetId", a.id, "from", old, "to", dataClass, "source", source, "ref", ref));
        evidence.record(a.cell, "asset.tagged", a.id, Json.map("assetId", a.id, "from", old, "to", dataClass, "source", source, "confidence", confidence, "ref", ref), "EV-7Y");
    }

    /** L3 bounded execution guard: only >= 0.95 confidence and both classes non-regulated. */
    public Map<String, Object> applyLowRisk(String assetId, String proposedClass, double confidence, String agentRunId) {
        DataAsset a = require(assetId);
        String current = a.tags.dataClass;
        boolean currentNonReg = current == null ? false : !REGULATED.contains(current);
        if (confidence < L3_THRESHOLD || REGULATED.contains(proposedClass) || !currentNonReg)
            throw new SecurityException("L3 guardrail: requires confidence >= " + L3_THRESHOLD + " and current/proposed classes both non-regulated (current=" + current + ", proposed=" + proposedClass + ")");
        applyTag(assetId, proposedClass, "agent-l3", confidence, agentRunId);
        return Json.map("applied", true, "assetId", assetId, "dataClass", proposedClass);
    }

    public void quarantine(String assetId, String ref) {
        DataAsset a = require(assetId);
        a.quarantined = true;
        bus.publish("asset.lifecycle", "asset.quarantined", a.cell, "catalog-service", a.id, Json.map("assetId", a.id, "ref", ref));
        evidence.record(a.cell, "asset.quarantined", a.id, Json.map("assetId", a.id, "ref", ref), "EV-7Y");
    }

    public Map<String, Object> coverage() {
        Map<String, Object> per = new TreeMap<>();
        int total = 0, tagged = 0;
        for (DataAsset a : store.assets.values()) {
            total++;
            boolean t = a.tags.dataClass != null;
            if (t) tagged++;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) per.computeIfAbsent(a.cell, k -> Json.map("total", 0, "tagged", 0));
            m.put("total", ((Integer) m.get("total")) + 1);
            if (t) m.put("tagged", ((Integer) m.get("tagged")) + 1);
        }
        for (Object o : per.values()) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) o;
            m.put("pct", Math.round(1000.0 * (Integer) m.get("tagged") / Math.max(1, (Integer) m.get("total"))) / 10.0);
        }
        return Json.map("total", total, "tagged", tagged, "pct", Math.round(1000.0 * tagged / Math.max(1, total)) / 10.0, "perCell", per);
    }
}

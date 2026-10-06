package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * report-service: deterministic figures computed ONLY here, from an inputs snapshot that is
 * stored with the run, so any run can be regenerated and diffed (goal G4: diff = 0).
 */
@Service
public class ReportService {
    public static final Map<String, String> REPORTS = new LinkedHashMap<>();
    static {
        REPORTS.put("compliance-posture", "Compliance posture (management summary)");
        REPORTS.put("residency-conformance", "Residency conformance by cell");
        REPORTS.put("transfer-summary", "Cross-border transfer summary");
        REPORTS.put("third-party-critical", "Critical ICT third-party arrangements");
    }

    private final DataStore store;
    private final ResidencyScanner scanner;
    private final ThirdPartyService tps;
    private final EvidenceService evidence;
    private final EventBus bus;

    public ReportService(DataStore store, ResidencyScanner scanner, ThirdPartyService tps, EvidenceService evidence, EventBus bus, CaseService cases) {
        this.store = store;
        this.scanner = scanner;
        this.tps = tps;
        this.evidence = evidence;
        this.bus = bus;
        cases.onApproved("report_narrative", c -> {
            ReportRun r = store.reportRuns.get(Json.str(c.payload, "runId", ""));
            if (r != null) { r.narrative = Json.str(c.payload, "narrative", r.narrative); r.narrativeStatus = "approved"; c.resolution = "Narrative approved and attached to " + r.id; }
        });
    }

    public ReportRun generate(String reportId, String user) {
        if (!REPORTS.containsKey(reportId)) throw new IllegalArgumentException("unknown report " + reportId);
        ReportRun r = new ReportRun();
        r.id = Ids.next("rpt");
        r.reportId = reportId;
        r.title = REPORTS.get(reportId);
        r.generatedAt = Ids.now();
        r.generatedBy = user;
        r.inputsSnapshot = snapshot();
        r.inputsHash = Json.hashOf(r.inputsSnapshot);
        r.figures = compute(reportId, r.inputsSnapshot);
        r.figuresHash = Json.hashOf(r.figures);
        store.reportRuns.put(r.id, r);
        r.evidenceId = evidence.record("GLOBAL", "report.generated", r.id, Json.map("runId", r.id, "reportId", reportId, "inputsHash", r.inputsHash, "figuresHash", r.figuresHash, "by", user), "EV-7Y").id;
        bus.publish("platform.events", "report.generated", null, "report-service", r.id, Json.map("runId", r.id, "reportId", reportId, "figuresHash", r.figuresHash));
        return r;
    }

    /** Recompute from the stored snapshot and diff against the stored figures. */
    public Map<String, Object> regenerate(String runId) {
        ReportRun r = require(runId);
        Map<String, Object> again = compute(r.reportId, r.inputsSnapshot);
        List<String> diffs = new ArrayList<>();
        for (String k : new TreeSet<>(union(r.figures.keySet(), again.keySet())))
            if (!Objects.equals(Json.canonical(r.figures.get(k)), Json.canonical(again.get(k)))) diffs.add(k);
        return Json.map("runId", runId, "inputsHashVerified", r.inputsHash.equals(Json.hashOf(r.inputsSnapshot)),
                "figuresHash", Json.hashOf(again), "originalFiguresHash", r.figuresHash, "differences", diffs.size(), "differingFigures", diffs);
    }

    private static Set<String> union(Set<String> a, Set<String> b) { Set<String> s = new HashSet<>(a); s.addAll(b); return s; }

    public ReportRun require(String id) {
        ReportRun r = store.reportRuns.get(id);
        if (r == null) throw new NoSuchElementException("report run " + id);
        return r;
    }

    public List<ReportRun> list() {
        List<ReportRun> l = new ArrayList<>(store.reportRuns.values());
        l.sort(Comparator.comparing((ReportRun r) -> r.generatedAt).reversed());
        return l;
    }

    private Map<String, Object> snapshot() {
        List<Map<String, Object>> assets = new ArrayList<>();
        for (DataAsset a : DataStore.sorted(store.assets, Comparator.comparing(x -> x.id)))
            assets.add(Json.map("id", a.id, "cell", a.cell, "segment", a.segment, "dataClass", a.tags.dataClass, "location", a.location));
        List<Map<String, Object>> viol = new ArrayList<>();
        for (Map<String, Object> v : scanner.list(null, "open")) viol.add(Json.map("assetId", v.get("assetId"), "cell", v.get("cell"), "mode", v.get("mode"), "ruleIds", v.get("ruleIds")));
        List<Map<String, Object>> trf = new ArrayList<>();
        for (Transfer t : DataStore.sorted(store.transfers, Comparator.comparing(x -> x.id)))
            trf.add(Json.map("id", t.id, "origin", t.originCell, "dest", t.destinationCountry, "status", t.status, "transform", t.transform, "mechanismId", t.mechanismId));
        List<Map<String, Object>> arr = new ArrayList<>();
        for (ThirdParty tp : tps.list()) for (ServiceArrangement a : tp.arrangements)
            arr.add(Json.map("id", a.id, "provider", tp.name, "critical", a.critical, "exitPlan", a.exitPlanRef != null, "subcontractors", a.subcontractors.size(),
                    "missingClauses", a.clauses.values().stream().filter("missing"::equals).count()));
        return Json.map("assets", assets, "openViolations", viol, "transfers", trf, "arrangements", arr);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> compute(String reportId, Map<String, Object> s) {
        List<Map<String, Object>> assets = (List<Map<String, Object>>) s.get("assets");
        List<Map<String, Object>> viol = (List<Map<String, Object>>) s.get("openViolations");
        List<Map<String, Object>> trf = (List<Map<String, Object>>) s.get("transfers");
        List<Map<String, Object>> arr = (List<Map<String, Object>>) s.get("arrangements");
        Map<String, Object> f = new LinkedHashMap<>();
        long tagged = assets.stream().filter(a -> a.get("dataClass") != null).count();
        Set<String> violAssets = new HashSet<>();
        for (Map<String, Object> v : viol) violAssets.add(String.valueOf(v.get("assetId")));
        switch (reportId) {
            case "compliance-posture" -> {
                f.put("assets_in_scope", assets.size());
                f.put("classification_coverage_pct", pct(tagged, assets.size()));
                f.put("residency_conformance_pct", pct(assets.size() - violAssets.size(), assets.size()));
                f.put("open_residency_violations", violAssets.size());
                f.put("transfers_total", trf.size());
                f.put("transfers_blocked", trf.stream().filter(t -> "blocked".equals(t.get("status"))).count());
                f.put("transfers_without_mechanism_or_transform_passed", trf.stream().filter(t -> "completed".equals(t.get("status")) && t.get("mechanismId") == null && t.get("transform") == null && !"intra".equals(t.get("dest"))).count());
                f.put("critical_arrangements", arr.stream().filter(a -> Boolean.TRUE.equals(a.get("critical"))).count());
            }
            case "residency-conformance" -> {
                Map<String, int[]> per = new TreeMap<>();
                for (Map<String, Object> a : assets) {
                    int[] c = per.computeIfAbsent(String.valueOf(a.get("cell")), k -> new int[2]);
                    c[0]++;
                    if (!violAssets.contains(String.valueOf(a.get("id")))) c[1]++;
                }
                for (var e : per.entrySet()) {
                    f.put(e.getKey() + "_assets", e.getValue()[0]);
                    f.put(e.getKey() + "_conformance_pct", pct(e.getValue()[1], e.getValue()[0]));
                }
                f.put("enforce_mode_violations", viol.stream().filter(v -> "enforce".equals(v.get("mode"))).count());
                f.put("audit_mode_violations", viol.stream().filter(v -> "audit".equals(v.get("mode"))).count());
            }
            case "transfer-summary" -> {
                f.put("transfers_total", trf.size());
                f.put("completed", trf.stream().filter(t -> "completed".equals(t.get("status"))).count());
                f.put("blocked", trf.stream().filter(t -> "blocked".equals(t.get("status"))).count());
                f.put("with_workaround_transform", trf.stream().filter(t -> t.get("transform") != null).count());
                f.put("with_mechanism", trf.stream().filter(t -> t.get("mechanismId") != null).count());
            }
            case "third-party-critical" -> {
                List<Map<String, Object>> crit = arr.stream().filter(a -> Boolean.TRUE.equals(a.get("critical"))).toList();
                f.put("critical_arrangements", crit.size());
                f.put("with_exit_plan", crit.stream().filter(a -> Boolean.TRUE.equals(a.get("exitPlan"))).count());
                f.put("with_subcontractor_chain", crit.stream().filter(a -> ((Number) a.get("subcontractors")).intValue() > 0).count());
                f.put("with_missing_clauses", crit.stream().filter(a -> ((Number) a.get("missingClauses")).longValue() > 0).count());
                f.put("chain_and_exit_plan_pct", pct(crit.stream().filter(a -> Boolean.TRUE.equals(a.get("exitPlan")) && ((Number) a.get("subcontractors")).intValue() > 0).count(), crit.size()));
            }
            default -> { }
        }
        return f;
    }

    private static double pct(long num, long den) { return den == 0 ? 100.0 : Math.round(1000.0 * num / den) / 10.0; }
}

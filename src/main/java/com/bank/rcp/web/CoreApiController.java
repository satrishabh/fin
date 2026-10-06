package com.bank.rcp.web;

import com.bank.rcp.common.Json;
import com.bank.rcp.core.*;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import com.bank.rcp.platform.ToolCatalog;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

/** REST API for the deterministic core services and human workflows. */
@RestController
@RequestMapping("/api")
public class CoreApiController {
    private static final String U = "X-User";
    private static final String DEF_USER = "analyst.k.rao";

    private final DataStore store;
    private final EventBus bus;
    private final CatalogService catalog;
    private final ResidencyScanner scanner;
    private final PolicyEngine pdp;
    private final PolicyPackService packs;
    private final TransferGateway gateway;
    private final ThirdPartyService tps;
    private final IncidentClockService incidents;
    private final RegChangeService regChange;
    private final CaseService cases;
    private final EvidenceService evidence;
    private final ReportService reports;
    private final PrivacyOpsService privacy;

    public CoreApiController(DataStore store, EventBus bus, CatalogService catalog, ResidencyScanner scanner, PolicyEngine pdp, PolicyPackService packs,
                             TransferGateway gateway, ThirdPartyService tps, IncidentClockService incidents, RegChangeService regChange, CaseService cases,
                             EvidenceService evidence, ReportService reports, PrivacyOpsService privacy) {
        this.store = store; this.bus = bus; this.catalog = catalog; this.scanner = scanner; this.pdp = pdp; this.packs = packs; this.gateway = gateway;
        this.tps = tps; this.incidents = incidents; this.regChange = regChange; this.cases = cases; this.evidence = evidence; this.reports = reports; this.privacy = privacy;
    }

    // ------------------------------------------------------------ overview & events

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        List<Map<String, Object>> open = scanner.list(null, "open");
        Map<String, Map<String, Object>> matrix = new TreeMap<>();
        for (DataAsset a : store.assets.values()) {
            String key = a.cell + "|" + a.segment;
            Map<String, Object> m = matrix.computeIfAbsent(key, k -> Json.map("cell", a.cell, "segment", a.segment, "assets", 0, "violations", 0, "unclassified", 0, "clouds", new TreeSet<String>()));
            m.put("assets", (Integer) m.get("assets") + 1);
            if (a.tags.dataClass == null) m.put("unclassified", (Integer) m.get("unclassified") + 1);
            @SuppressWarnings("unchecked") Set<String> clouds = (Set<String>) m.get("clouds");
            clouds.add(a.location.split(":")[0]);
        }
        for (Map<String, Object> v : open) {
            Map<String, Object> m = matrix.get(v.get("cell") + "|" + v.get("segment"));
            if (m != null) m.put("violations", (Integer) m.get("violations") + 1);
        }
        long blocked = store.transfers.values().stream().filter(t -> "blocked".equals(t.status)).count();
        long openCases = store.cases.values().stream().filter(c -> Set.of("open", "draft").contains(c.status)).count();
        long runningClocks = store.incidents.values().stream().flatMap(i -> i.clocks.stream()).filter(c -> !"closed".equals(c.status)).count();
        return Json.map("classification", catalog.coverage(), "residency", scanner.lastScan(), "openViolations", open.size(),
                "transfers", Json.map("total", store.transfers.size(), "blocked", blocked), "openCases", openCases,
                "agentRuns", store.runs.size(), "evidenceRecords", evidence.count(), "runningClocks", runningClocks,
                "expiringMechanisms", gateway.expiring(60).size(), "thirdParty", tps.metrics(), "matrix", matrix.values(),
                "dataSources", store.datasetSource, "recentEvents", bus.recent(15, null));
    }

    @GetMapping("/events")
    public List<Map<String, Object>> events(@RequestParam(defaultValue = "100") int limit, @RequestParam(required = false) String topic) { return bus.recent(limit, topic); }

    @GetMapping(path = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() { return bus.register(); }

    @GetMapping("/users")
    public Collection<User> users() { return DataStore.sorted(store.users, Comparator.comparing(u -> u.id)); }

    // ------------------------------------------------------------ catalog & classification

    @GetMapping("/assets")
    public List<Map<String, Object>> assets(@RequestParam(required = false) String cell, @RequestParam(required = false) String q,
                                            @RequestParam(required = false) String dataClass, @RequestParam(required = false) String segment) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DataAsset a : catalog.search(cell, q, dataClass, segment)) {
            Map<String, Object> m = ToolCatalog.assetMeta(a);
            m.put("violation", scanner.get(a.id).filter(v -> "open".equals(v.get("status"))).map(v -> v.get("ruleIds")).orElse(null));
            out.add(m);
        }
        return out;
    }

    @GetMapping("/assets/{id}")
    public Map<String, Object> asset(@PathVariable String id) {
        DataAsset a = catalog.require(id);
        Decision d = pdp.evaluateResidency(a, true);
        return Json.map("asset", a, "lineage", catalog.lineage(id, 3), "residencySimulation", ToolCatalog.decisionView(d),
                "inventory", scanner.cloudInventory(id), "iac", scanner.iac(id), "detection", catalog.detect(a),
                "classifications", store.classifications.values().stream().filter(r -> r.assetId.equals(id)).toList(),
                "violation", scanner.get(id).orElse(null));
    }

    @PostMapping("/assets")
    public DataAsset register(@RequestBody DataAsset a, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return catalog.register(a, user); }

    @PostMapping("/classification/run")
    public List<ClassificationResult> classify(@RequestParam(required = false) String cell, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return catalog.runClassification(cell, user); }

    @GetMapping("/classification/results")
    public List<ClassificationResult> classifications() {
        List<ClassificationResult> l = new ArrayList<>(store.classifications.values());
        l.sort(Comparator.comparing((ClassificationResult r) -> r.ts).reversed());
        return l;
    }

    // ------------------------------------------------------------ residency

    @PostMapping("/residency/scan")
    public Map<String, Object> scan(@RequestParam(required = false) String cell, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return scanner.scan(cell, user); }

    @GetMapping("/residency/violations")
    public List<Map<String, Object>> violations(@RequestParam(required = false) String cell, @RequestParam(required = false) String status) { return scanner.list(cell, status); }

    // ------------------------------------------------------------ policy

    @GetMapping("/policy/packs")
    public List<Map<String, Object>> packs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PolicyPack p : packs.list())
            out.add(Json.map("id", p.id, "jurisdiction", p.jurisdiction, "version", p.version, "mode", p.mode, "signature", p.signature,
                    "signatureValid", packs.verified(p), "publishedAt", p.publishedAt, "rules", packs.listRules(p.id, null),
                    "ruleDetails", p.rules));
        return out;
    }

    @GetMapping("/policy/data")
    public Map<String, Object> policyData() { return store.policyData; }

    @PostMapping("/policy/packs/{id}/mode")
    public PolicyPack mode(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "compliance", "policy_engineer");
        return packs.changeMode(id, Json.str(body, "mode", "audit"), user);
    }

    @PostMapping("/policy/packs/{id}/rules/{ruleId}/mode")
    public PolicyPack ruleMode(@PathVariable String id, @PathVariable String ruleId, @RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "compliance", "policy_engineer");
        return packs.setRuleMode(id, ruleId, Json.str(body, "mode", "audit"), user);
    }

    @PostMapping("/policy/simulate/transfer")
    public Map<String, Object> simulateTransfer(@RequestBody Map<String, Object> t) { return ToolCatalog.decisionView(gateway.simulate(t)); }

    @GetMapping("/policy/decisions")
    public List<Decision> decisions(@RequestParam(defaultValue = "100") int limit) {
        List<Decision> l = new ArrayList<>(store.decisions.values());
        l.sort(Comparator.comparing((Decision d) -> d.timestamp).reversed());
        return l.subList(0, Math.min(limit, l.size()));
    }

    // ------------------------------------------------------------ mechanisms, transfers, counters, workarounds

    @GetMapping("/mechanisms")
    public Map<String, Object> mechanisms() { return Json.map("mechanisms", gateway.mechanisms(), "expiring", gateway.expiring(60)); }

    @PostMapping("/mechanisms")
    public Mechanism recordMechanism(@RequestBody Mechanism m, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "legal", "dpo");
        return gateway.recordMechanism(m, user);
    }

    @PostMapping("/mechanisms/{id}/revoke")
    public Mechanism revoke(@PathVariable String id, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "legal", "dpo");
        return gateway.revoke(id, user);
    }

    @PostMapping("/transfers")
    public Transfer transfer(@RequestBody Map<String, Object> t, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return gateway.request(t, user); }

    @GetMapping("/transfers")
    public List<Transfer> transfers() {
        List<Transfer> l = new ArrayList<>(store.transfers.values());
        l.sort(Comparator.comparing((Transfer t) -> t.ts).reversed());
        return l;
    }

    @GetMapping("/transfers/profiles")
    public Collection<Map<String, Object>> profiles() { return gateway.profiles(); }

    @GetMapping("/counters")
    public List<Map<String, Object>> counters() { return gateway.counters(); }

    @PostMapping("/counters/reconcile")
    public List<Map<String, Object>> reconcile() { return gateway.reconcile(); }

    @GetMapping("/workarounds")
    public List<WorkaroundPattern> workarounds() { return DataStore.sorted(store.workarounds, Comparator.comparing(w -> w.id)); }

    // ------------------------------------------------------------ third parties

    @GetMapping("/third-parties")
    public Map<String, Object> thirdParties() { return Json.map("providers", tps.list(), "gaps", tps.gaps(), "metrics", tps.metrics(), "requiredClauses", ThirdPartyService.REQUIRED_CLAUSES); }

    @GetMapping("/third-parties/export/{format}")
    public Map<String, Object> export(@PathVariable String format, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return tps.export(format, user); }

    @GetMapping("/contracts")
    public Collection<Contract> contracts() { return DataStore.sorted(store.contracts, Comparator.comparing(c -> c.id)); }

    @GetMapping("/contracts/{id}")
    public Contract contract(@PathVariable String id) { return tps.contract(id); }

    /** Upload an original contract as plain text; sections split on lines that start with "s." / "Section" / numbered headings. */
    @PostMapping("/contracts")
    public Contract uploadContract(@RequestBody Map<String, Object> body) {
        Contract c = new Contract();
        c.id = Json.str(body, "id", null);
        c.providerId = Json.str(body, "providerId", null);
        c.title = Json.str(body, "title", "Uploaded contract");
        String text = Json.str(body, "text", "");
        int n = 0;
        ContractSection cur = null;
        for (String line : text.split("\\r?\\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            if (t.matches("^(s\\.|Section\\s+|Clause\\s+|\\d+(\\.\\d+)*[.)]?\\s).*")) {
                cur = new ContractSection();
                cur.ref = t.split("\\s+")[0].replaceAll("[.)]$", "");
                if (!cur.ref.startsWith("s.")) cur.ref = "s." + cur.ref.replaceAll("(?i)section|clause", "");
                cur.heading = t.length() > 80 ? t.substring(0, 80) : t;
                cur.text = t;
                c.sections.add(cur);
            } else if (cur != null) {
                cur.text += " " + t;
            } else {
                cur = new ContractSection();
                cur.ref = "s." + (++n);
                cur.heading = "Preamble";
                cur.text = t;
                c.sections.add(cur);
            }
        }
        tps.addContract(c);
        if (c.providerId != null && store.thirdParties.containsKey(c.providerId))
            for (ServiceArrangement a : store.thirdParties.get(c.providerId).arrangements) if (a.contractId == null) a.contractId = c.id;
        return c;
    }

    // ------------------------------------------------------------ incidents

    @PostMapping("/incidents")
    public Incident openIncident(@RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return incidents.open(body, user); }

    @GetMapping("/incidents")
    public List<Incident> incidents() { return incidents.list(); }

    @PostMapping("/incidents/{id}/clocks/{clockId}/close")
    public Incident closeClock(@PathVariable String id, @PathVariable String clockId, @RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "incident_manager", "dpo");
        return incidents.closeClock(id, clockId, Json.str(body, "submissionRef", "manual-submission"), user);
    }

    @GetMapping("/templates")
    public Map<String, String> templates() { return store.templates; }

    // ------------------------------------------------------------ reg change

    @GetMapping("/regchange")
    public List<RegChangeItem> regChanges() { return regChange.list(); }

    @PostMapping("/regchange")
    public Map<String, Object> ingest(@RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return regChange.ingest(body, user); }

    @GetMapping("/requirements")
    public List<Requirement> requirements(@RequestParam(required = false) String jurisdiction) { return regChange.searchRequirements(jurisdiction, null); }

    // ------------------------------------------------------------ cases

    @GetMapping("/cases")
    public List<CaseItem> cases(@RequestParam(required = false) String status, @RequestParam(required = false) String type) { return cases.list(status, type); }

    @GetMapping("/cases/{id}")
    public CaseItem caseItem(@PathVariable String id) { return cases.require(id); }

    @PostMapping("/cases/{id}/submit")
    public CaseItem submit(@PathVariable String id, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user);
        return cases.submit(id, user);
    }

    @PostMapping("/cases/{id}/decide")
    public CaseItem decide(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        return cases.decide(id, user, Json.str(body, "decision", "approve"), Json.str(body, "comment", null));
    }

    // ------------------------------------------------------------ evidence & controls

    @GetMapping("/evidence")
    public List<EvidenceRecord> evidence(@RequestParam(required = false) String cell, @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String q, @RequestParam(defaultValue = "100") int limit) {
        return evidence.search(cell, type, q, limit);
    }

    @GetMapping("/evidence/verify")
    public Map<String, Object> verify(@RequestParam(required = false) String cell) { return evidence.verify(cell); }

    @GetMapping("/evidence/index")
    public Map<String, Object> index() { return evidence.globalIndex(); }

    @GetMapping("/evidence/{id}")
    public EvidenceRecord evidenceItem(@PathVariable String id) { return evidence.get(id).orElseThrow(); }

    @GetMapping("/controls")
    public List<ControlTest> controls() { return DataStore.sorted(store.controls, Comparator.comparing(c -> c.id)); }

    // ------------------------------------------------------------ reports

    @GetMapping("/reports")
    public Map<String, Object> reports() { return Json.map("definitions", ReportService.REPORTS, "runs", reports.list()); }

    @PostMapping("/reports/{reportId}/generate")
    public ReportRun generate(@PathVariable String reportId, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return reports.generate(reportId, user); }

    @GetMapping("/reports/runs/{id}/regenerate")
    public Map<String, Object> regenerate(@PathVariable String id) { return reports.regenerate(id); }

    // ------------------------------------------------------------ privacy ops

    @PostMapping("/erasure")
    public ErasureRequest erasure(@RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return privacy.erasure(body, user); }

    @GetMapping("/erasure")
    public Collection<ErasureRequest> erasures() { return store.erasures.values(); }

    @PostMapping("/disclosure")
    public DisclosureRequest disclosure(@RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) { return privacy.disclosure(body, user); }

    @GetMapping("/disclosure")
    public Collection<DisclosureRequest> disclosures() { return store.disclosures.values(); }
}

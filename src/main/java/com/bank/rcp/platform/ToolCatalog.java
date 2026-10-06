package com.bank.rcp.platform;

import com.bank.rcp.common.Json;
import com.bank.rcp.core.*;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.model.Models.*;
import com.bank.rcp.platform.ToolGateway.Call;
import com.bank.rcp.platform.ToolGateway.Tool;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;

/**
 * The curated MCP tool surface, one group per microservice (spec Section 5: "the MCP surface is
 * narrower than the API"). Write tools are request-style: they create approval cases.
 * Nothing here can allow/deny a transfer, record a mechanism, send a notice, change a report
 * figure or deploy a policy.
 */
@Component
public class ToolCatalog {
    private static final Set<String> AGENT_CASE_TYPES = Set.of("investigation_report", "impact_assessment", "transfer_assessment", "workaround_proposal",
            "incident_classification", "notice_draft", "assurance_finding", "report_narrative", "erasure_conflict");

    public ToolCatalog(ToolGateway gw, DataStore store, CatalogService catalog, CaseService cases, EvidenceService evidence,
                       ResidencyScanner scanner, RegChangeService regChange, PolicyPackService packs, PolicyEngine pdp,
                       TransferGateway transfers, ThirdPartyService tps, IncidentClockService incidents, ReportService reports) {

        // ---------------- catalog / classification / lineage
        reg(gw, "catalog.search", "Search the data catalogue (metadata only)", false, false, schema(List.of(), Map.of()), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (DataAsset a : catalog.search(Json.str(c.args(), "cell", null), Json.str(c.args(), "q", null), Json.str(c.args(), "dataClass", null), Json.str(c.args(), "segment", null)))
                out.add(assetMeta(a));
            return out;
        });
        reg(gw, "catalog.read", "Read an asset's catalogue entry and tags (metadata only)", false, false, schema(List.of("assetId"), Map.of("assetId", pattern("^asset:[a-z]{2}:"))),
                c -> assetMeta(catalog.require(Json.str(c.args(), "assetId", ""))));
        reg(gw, "lineage.read", "Read OpenLineage-style upstream/downstream edges for an asset", false, false, schema(List.of("assetId"), Map.of("assetId", pattern("^asset:[a-z]{2}:"))),
                c -> catalog.lineage(Json.str(c.args(), "assetId", ""), (int) Json.num(c.args(), "depth", 2)));
        reg(gw, "classification.sample_masked", "Masked column samples (in-cell only)", false, true, schema(List.of("assetId"), Map.of("assetId", pattern("^asset:[a-z]{2}:"))),
                c -> catalog.sampleMasked(Json.str(c.args(), "assetId", "")));
        reg(gw, "catalog.request_retag", "Request a classification change for an asset. Creates an approval case; does NOT change the tag.", true, true,
                schema(List.of("assetId", "proposedDataClass", "rationale", "evidenceRefs"), Map.of("assetId", pattern("^asset:[a-z]{2}:"),
                        "proposedDataClass", enumOf("public", "internal", "personal", "sensitive_personal", "payment", "trade", "health", "important_data_candidate"),
                        "rationale", Json.map("type", "string", "maxLength", 2000))),
                c -> {
                    String id = Json.str(c.args(), "assetId", "");
                    CaseItem k = cases.create("retag_request", "Re-tag " + id + " -> " + c.args().get("proposedDataClass"), c.agentId(), c.onBehalfOf(), c.cell(),
                            Json.map("assetId", id, "proposedDataClass", c.args().get("proposedDataClass"), "currentDataClass", catalog.require(id).tags.dataClass,
                                    "rationale", c.args().get("rationale"), "evidenceRefs", c.args().get("evidenceRefs"), "confidence", c.args().get("confidence")), c.runId(), false);
                    store.classifications.values().stream().filter(r -> r.assetId.equals(id) && "low_confidence".equals(r.status)).forEach(r -> r.status = "pending_approval");
                    return Json.map("caseId", k.id, "status", k.status, "note", "approval task created for data owner");
                });
        reg(gw, "catalog.apply_label_low_risk", "L3 bounded action: apply a label >=0.95 confidence when current and proposed classes are both non-regulated", true, true,
                schema(List.of("assetId", "proposedDataClass", "confidence"), Map.of("assetId", pattern("^asset:[a-z]{2}:"), "proposedDataClass", enumOf("public", "internal"))),
                c -> catalog.applyLowRisk(Json.str(c.args(), "assetId", ""), Json.str(c.args(), "proposedDataClass", ""), Json.dbl(c.args(), "confidence", 0), c.runId()));

        // ---------------- residency
        reg(gw, "residency.list_violations", "List residency violations (metadata)", false, false, schema(List.of(), Map.of()),
                c -> scanner.list(Json.str(c.args(), "cell", null), Json.str(c.args(), "status", "open")));
        reg(gw, "residency.read_violation", "Read one residency violation", false, false, schema(List.of("violationId"), Map.of()),
                c -> scanner.get(Json.str(c.args(), "violationId", "")).orElseThrow(() -> new NoSuchElementException("violation")));
        reg(gw, "cloudinv.read", "Read the cloud inventory record for an asset (in-cell)", false, true, schema(List.of("assetId"), Map.of("assetId", pattern("^asset:[a-z]{2}:"))),
                c -> scanner.cloudInventory(Json.str(c.args(), "assetId", "")));
        reg(gw, "iac.read", "Read Terraform state excerpt (read-only)", false, true, schema(List.of("assetId"), Map.of("assetId", pattern("^asset:[a-z]{2}:"))),
                c -> scanner.iac(Json.str(c.args(), "assetId", "")));
        reg(gw, "residency.request_quarantine", "Request quarantine of an asset. Creates an approval case for the data owner.", true, true,
                schema(List.of("assetId", "rationale"), Map.of("assetId", pattern("^asset:[a-z]{2}:"))),
                c -> {
                    CaseItem k = cases.create("quarantine_request", "Quarantine " + c.args().get("assetId"), c.agentId(), c.onBehalfOf(), c.cell(),
                            Json.map("assetId", c.args().get("assetId"), "rationale", c.args().get("rationale")), c.runId(), false);
                    return Json.map("caseId", k.id, "status", k.status);
                });

        // ---------------- cases & evidence
        reg(gw, "case.read", "Read a case", false, false, schema(List.of("caseId"), Map.of()), c -> cases.require(Json.str(c.args(), "caseId", "")));
        reg(gw, "case.create_draft", "Create a DRAFT case for human review", true, false, schema(List.of("type", "title"), Map.of("type", enumOf(AGENT_CASE_TYPES.toArray(String[]::new)))),
                c -> {
                    CaseItem k = cases.create(Json.str(c.args(), "type", ""), Json.str(c.args(), "title", ""), c.agentId(), c.onBehalfOf(), c.cell(),
                            Json.toMap(c.args().get("payload")), c.runId(), true);
                    return Json.map("caseId", k.id, "status", k.status);
                });
        reg(gw, "evidence.search", "Search evidence metadata (ids, types, hashes)", false, false, schema(List.of(), Map.of()), c -> {
            List<Map<String, Object>> out = new ArrayList<>();
            for (EvidenceRecord r : evidence.search(Json.str(c.args(), "cell", null), Json.str(c.args(), "type", null), Json.str(c.args(), "q", null), (int) Json.num(c.args(), "limit", 25)))
                out.add(Json.map("id", r.id, "cell", r.cell, "type", r.type, "subject", r.subject, "recordHash", r.recordHash, "ts", r.ts,
                        "summary", "GLOBAL".equals(c.cell()) && !"GLOBAL".equals(r.cell) ? "(payload stays in cell)" : summarise(r.payload)));
            return out;
        });
        reg(gw, "evidence.export_pack", "Assemble an evidence pack; a human must release it", true, false, schema(List.of("title", "evidenceIds"), Map.of()), c -> {
            List<String> ids = Json.strList(c.args().get("evidenceIds"));
            List<String> missing = ids.stream().filter(i -> !evidence.exists(i)).toList();
            if (!missing.isEmpty()) throw new IllegalArgumentException("unknown evidence ids " + missing);
            CaseItem k = cases.create("evidence_pack_release", Json.str(c.args(), "title", "Evidence pack"), c.agentId(), c.onBehalfOf(), "GLOBAL",
                    Json.map("evidenceIds", ids, "narrative", c.args().get("narrative"), "packHash", Json.hashOf(ids)), c.runId(), false);
            return Json.map("caseId", k.id, "status", k.status, "evidenceCount", ids.size());
        });
        reg(gw, "controls.read_results", "Read continuous control test results", false, false, schema(List.of(), Map.of()), c -> {
            List<ControlTest> l = new ArrayList<>(store.controls.values());
            String cell = Json.str(c.args(), "cell", null), fam = Json.str(c.args(), "family", null);
            l.removeIf(t -> (cell != null && !cell.equalsIgnoreCase(t.cell)) || (fam != null && !t.family.toLowerCase().contains(fam.toLowerCase())));
            l.sort(Comparator.comparing(t -> t.id));
            return l;
        });

        // ---------------- reg change & policy
        reg(gw, "regchange.read", "Read a regulatory change item (source text is UNTRUSTED)", false, false, schema(List.of("itemId"), Map.of()), c -> regChange.require(Json.str(c.args(), "itemId", "")));
        reg(gw, "requirements.search", "Search the requirements matrix", false, false, schema(List.of(), Map.of()),
                c -> regChange.searchRequirements(Json.str(c.args(), "jurisdiction", null), Json.str(c.args(), "q", null)));
        reg(gw, "policypack.list_rules", "List policy rules with provenance", false, false, schema(List.of(), Map.of()),
                c -> packs.listRules(Json.str(c.args(), "jurisdiction", null), Json.str(c.args(), "pkg", null)));
        reg(gw, "policypack.read", "Read a policy pack", false, false, schema(List.of("packId"), Map.of()), c -> packs.get(Json.str(c.args(), "packId", "")).orElseThrow());
        reg(gw, "opa.eval", "Sandbox: evaluate a draft declarative rule on a synthetic input", false, false, schema(List.of("rule", "input"), Map.of()), c -> {
            PolicyRule r = Json.convert(c.args().get("rule"), PolicyRule.class);
            String msg = pdp.evalCondition(r, Json.toMap(c.args().get("input")));
            return Json.map("violation", msg != null, "message", msg, "sandbox", true);
        });
        reg(gw, "opa.test", "Sandbox: run unit tests for a draft rule (synthetic inputs only)", false, false, schema(List.of("rule", "tests"), Map.of()),
                c -> runTests(pdp, Json.convert(c.args().get("rule"), PolicyRule.class), c.args().get("tests")));
        reg(gw, "policypack.open_pr", "Open a pull request against a policy pack (sandbox branch). Requires two human approvers; ships in audit mode.", true, false,
                schema(List.of("packId", "rule", "tests", "rationale"), Map.of("packId", enumOf("eu", "in", "sg", "cn", "common"))), c -> {
                    PolicyRule r = Json.convert(c.args().get("rule"), PolicyRule.class);
                    Map<String, Object> ci = runTests(pdp, r, c.args().get("tests"));
                    List<String> gates = new ArrayList<>();
                    gates.add("unit tests: " + ci.get("passed") + "/" + ci.get("total") + ((boolean) ci.get("allPassed") ? " PASS" : " FAIL"));
                    boolean prov = r.requirementRef != null && r.sourceRef != null;
                    gates.add("provenance (requirementRef + sourceRef): " + (prov ? "PASS" : "FAIL"));
                    boolean posNeg = Json.num(ci, "positive", 0) >= 1 && Json.num(ci, "negative", 0) >= 1;
                    gates.add(">=1 positive and >=1 negative case: " + (posNeg ? "PASS" : "FAIL"));
                    boolean ok = (boolean) ci.get("allPassed") && prov && posNeg;
                    CaseItem k = cases.create("policy_pr", "Policy PR: " + r.ruleId + " -> pack " + c.args().get("packId"), c.agentId(), c.onBehalfOf(), "GLOBAL",
                            Json.map("packId", c.args().get("packId"), "rule", Json.toMap(r), "tests", c.args().get("tests"), "rationale", c.args().get("rationale"),
                                    "ci", ci, "ciGates", gates, "ciPassed", ok, "branch", "sandbox/" + r.ruleId.toLowerCase(), "impactCaseId", c.args().get("impactCaseId")), c.runId(), !ok);
                    return Json.map("caseId", k.id, "status", k.status, "ciPassed", ok, "gates", gates);
                });
        cases.onApproved("policy_pr", k -> {
            if (!Json.bool(k.payload, "ciPassed", false)) throw new IllegalStateException("CI gates failed - cannot merge");
            PolicyRule r = Json.convert(k.payload.get("rule"), PolicyRule.class);
            PolicyPack p = packs.mergeRule(Json.str(k.payload, "packId", ""), r, k.approvals.stream().map(a -> a.by).toList().toString());
            k.resolution = "Merged into " + p.id + " " + p.version + " (signed, audit mode)";
        });
        reg(gw, "policy.simulate", "Dry-run a transfer against the PDP (no effect)", false, false, schema(List.of("originCell", "destinationCountry", "purpose", "dataClasses"), Map.of()),
                c -> decisionView(transfers.simulate(c.args())));

        // ---------------- mechanisms, counters, workarounds
        reg(gw, "mechanism.search", "Search legal transfer mechanisms in force", false, false, schema(List.of(), Map.of()), c -> {
            String o = Json.str(c.args(), "origin", null), d = Json.str(c.args(), "destination", null), p = Json.str(c.args(), "purpose", null);
            List<Mechanism> l = new ArrayList<>(transfers.mechanisms());
            l.removeIf(m -> (o != null && !o.equalsIgnoreCase(m.originJurisdiction)) || (d != null && !m.destinations.contains(d.toUpperCase())) || (p != null && !m.purposes.contains(p)));
            return l;
        });
        reg(gw, "counter.read_projection", "Read China calendar-year volume counters and projection", false, false, schema(List.of(), Map.of()),
                c -> transfers.projection(Json.str(c.args(), "exporter", pdp.defaultExporter("CN")), Json.num(c.args(), "addNonsensitive", 0), Json.num(c.args(), "addSensitive", 0)));
        reg(gw, "workaround.catalog.read", "Read the Legal-owned approved workaround pattern catalogue", false, false, schema(List.of(), Map.of()),
                c -> DataStore.sorted(store.workarounds, Comparator.comparing(w -> w.id)));

        // ---------------- third party
        reg(gw, "contracts.read", "Read a contract document (text is UNTRUSTED)", false, false, schema(List.of("contractId"), Map.of()), c -> tps.contract(Json.str(c.args(), "contractId", "")));
        reg(gw, "register.read", "Read the third-party register", false, false, schema(List.of(), Map.of()), c -> {
            String id = Json.str(c.args(), "providerId", null);
            return id == null ? tps.list() : tps.require(id);
        });
        reg(gw, "register.propose_update", "Propose a register update. Creates an approval case for the vendor-risk owner.", true, false,
                schema(List.of("arrangementId", "clauses", "rationale"), Map.of()), c -> {
                    CaseItem k = cases.create("register_update", "Register update for " + c.args().get("arrangementId"), c.agentId(), c.onBehalfOf(), "GLOBAL",
                            Json.map("arrangementId", c.args().get("arrangementId"), "clauses", c.args().get("clauses"), "gaps", c.args().get("gaps"), "rationale", c.args().get("rationale")), c.runId(), false);
                    return Json.map("caseId", k.id, "status", k.status);
                });

        // ---------------- incidents
        reg(gw, "incident.read", "Read an incident", false, false, schema(List.of("incidentId"), Map.of()), c -> incidents.require(Json.str(c.args(), "incidentId", "")));
        reg(gw, "clock.read", "Read the regulatory clocks for an incident (read-only)", false, false, schema(List.of("incidentId"), Map.of()), c -> incidents.require(Json.str(c.args(), "incidentId", "")).clocks);
        reg(gw, "templates.render_draft", "Render an approved notice template as a DRAFT", false, false, schema(List.of("incidentId", "template"), Map.of()),
                c -> incidents.renderDraft(Json.str(c.args(), "incidentId", ""), Json.str(c.args(), "template", "")));

        // ---------------- reports & assurance
        reg(gw, "report.read_run", "Read a deterministic report run (figures)", false, false, schema(List.of("runId"), Map.of()), c -> {
            ReportRun r = reports.require(Json.str(c.args(), "runId", ""));
            return Json.map("runId", r.id, "reportId", r.reportId, "title", r.title, "generatedAt", r.generatedAt, "figures", r.figures, "figuresHash", r.figuresHash, "evidenceId", r.evidenceId);
        });
        reg(gw, "agent.runs.read", "Read recent agent runs", false, false, schema(List.of(), Map.of()), c -> {
            List<AgentRun> l = new ArrayList<>(store.runs.values());
            String a = Json.str(c.args(), "agentId", null);
            l.removeIf(r -> (a != null && !a.equals(r.agentId)) || "assurance".equals(r.agentId) || "running".equals(r.status));
            l.sort(Comparator.comparing((AgentRun r) -> r.startedAt).reversed());
            return l.subList(0, Math.min(l.size(), (int) Json.num(c.args(), "limit", 20)));
        });
        reg(gw, "eval.record", "Record evaluation scores for an agent in the model inventory", false, false, schema(List.of("agentId", "scores"), Map.of()), c -> {
            AgentDef a = store.agents.get(Json.str(c.args(), "agentId", ""));
            if (a == null) throw new NoSuchElementException("agent");
            a.evalScores = Json.toMap(c.args().get("scores"));
            a.evalScores.put("recordedAt", com.bank.rcp.common.Ids.now());
            return Json.map("recorded", true, "agentId", a.id);
        });
    }

    private static void reg(ToolGateway gw, String name, String desc, boolean write, boolean cellScoped, Map<String, Object> schema, Function<Call, Object> h) {
        gw.register(new Tool(name, desc, write, cellScoped, schema, h));
    }

    private static Map<String, Object> schema(List<String> required, Map<String, Object> props) {
        return Json.map("type", "object", "required", required, "properties", props);
    }

    private static Map<String, Object> pattern(String p) { return Json.map("type", "string", "pattern", p); }

    private static Map<String, Object> enumOf(String... v) { return Json.map("type", "string", "enum", List.of(v)); }

    public static Map<String, Object> assetMeta(DataAsset a) {
        return Json.map("id", a.id, "name", a.name, "cell", a.cell, "location", a.location, "kind", a.kind, "segment", a.segment, "owner", a.owner,
                "legalEntity", a.legalEntity, "tags", a.tags, "copyPurpose", a.copyPurpose, "returnBy", a.returnBy, "replicationOf", a.replicationOf,
                "createdBy", a.createdBy, "recordCount", a.recordCount, "individuals", a.individuals, "quarantined", a.quarantined,
                "columnNames", a.columns.stream().map(c -> c.name).toList());
    }

    public static Map<String, Object> decisionView(Decision d) {
        return Json.map("decisionId", d.decisionId, "allow", d.allow, "effectiveAllow", d.effectiveAllow, "mode", d.mode, "reasons", d.reasons,
                "ruleIds", d.ruleIds, "bundleVersion", d.bundleVersion, "extra", d.extra);
    }

    private static String summarise(Map<String, Object> p) {
        String s = Json.write(p);
        return s.length() > 240 ? s.substring(0, 240) + "..." : s;
    }

    /** Runs declarative-rule unit tests: each test {name, input, expectViolation}. */
    public static Map<String, Object> runTests(PolicyEngine pdp, PolicyRule rule, Object testsObj) {
        List<Map<String, Object>> tests = Json.convert(testsObj == null ? List.of() : testsObj, new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        List<Map<String, Object>> results = new ArrayList<>();
        int passed = 0, pos = 0, neg = 0;
        for (Map<String, Object> t : tests) {
            boolean expect = Json.bool(t, "expectViolation", false);
            if (expect) pos++; else neg++;
            String msg = pdp.evalCondition(rule, Json.toMap(t.get("input")));
            boolean ok = (msg != null) == expect;
            if (ok) passed++;
            results.add(Json.map("name", t.get("name"), "expectViolation", expect, "actual", msg != null, "pass", ok, "message", msg));
        }
        return Json.map("total", tests.size(), "passed", passed, "allPassed", !tests.isEmpty() && passed == tests.size(), "positive", pos, "negative", neg, "results", results);
    }
}

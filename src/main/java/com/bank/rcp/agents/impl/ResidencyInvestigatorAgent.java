package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.model.Models.AgentRun;
import com.bank.rcp.platform.ModelGateway.Block;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.*;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * A5 Residency Investigator, orchestrated as a LangGraph4j state graph:
 * gather -> analyse -> (workaround?) -> report. Runs inside the asset's cell with cell-scoped tools.
 */
@Component
public class ResidencyInvestigatorAgent implements Agent {
    @Override public String id() { return "residency-investigator"; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(AgentContext ctx) throws Exception {
        ctx.plan("residency.read_violation", "catalog.read", "cloudinv.read", "lineage.read", "iac.read", "mechanism.search", "a2a.delegate", "case.create_draft", "residency.request_quarantine");

        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);
        g.addNode("gather", node_async(s -> gather(ctx)));
        g.addNode("analyse", node_async(s -> analyse(ctx, s)));
        g.addNode("workaround", node_async(s -> workaround(ctx, s)));
        g.addNode("report", node_async(s -> report(ctx, s)));
        g.addEdge(GraphDefinition.START, "gather");
        g.addEdge("gather", "analyse");
        g.addConditionalEdges("analyse", edge_async(s -> Boolean.TRUE.equals(s.value("needsWorkaround").orElse(false)) ? "workaround" : "report"),
                Map.of("workaround", "workaround", "report", "report"));
        g.addEdge("workaround", "report");
        g.addEdge("report", GraphDefinition.END);
        CompiledGraph<AgentState> app = g.compile();
        ctx.step("graph", "langgraph4j", "ok", "nodes: gather -> analyse -> [workaround] -> report");

        AgentState fin = app.invoke(Map.of("started", true)).orElseThrow();
        return (Map<String, Object>) fin.value("result").orElse(Map.of());
    }

    private Map<String, Object> gather(AgentContext ctx) {
        String assetId = ctx.in("assetId", null);
        String vid = ctx.in("violationId", assetId);
        Map<String, Object> v = ctx.toolMap("residency.read_violation", Map.of("violationId", vid));
        assetId = String.valueOf(v.get("assetId"));
        ctx.cite(Json.str(v, "decisionId", null));
        Map<String, Object> asset = ctx.toolMap("catalog.read", Map.of("assetId", assetId));
        Map<String, Object> inv = ctx.toolMap("cloudinv.read", Map.of("assetId", assetId));
        List<Map<String, Object>> lineage = ctx.toolList("lineage.read", Map.of("assetId", assetId));
        Map<String, Object> iac = ctx.toolMap("iac.read", Map.of("assetId", assetId));
        Map<String, Object> tags = Json.toMap(asset.get("tags"));
        List<Map<String, Object>> mechs = ctx.toolList("mechanism.search", Json.map("origin", tags.get("originJurisdiction")));
        return Json.map("violation", v, "asset", asset, "inventory", inv, "lineage", lineage, "iac", iac, "mechanisms", mechs);
    }

    private Map<String, Object> analyse(AgentContext ctx, AgentState s) {
        Map<String, Object> v = s.<Map<String, Object>>value("violation").orElseThrow();
        Map<String, Object> asset = s.<Map<String, Object>>value("asset").orElseThrow();
        Map<String, Object> inv = s.<Map<String, Object>>value("inventory").orElseThrow();
        List<Map<String, Object>> lineage = s.<List<Map<String, Object>>>value("lineage").orElse(List.of());
        Map<String, Object> iac = s.<Map<String, Object>>value("iac").orElseThrow();

        String createdBy = Json.str(asset, "createdBy", "");
        String purpose = Json.str(asset, "copyPurpose", "storage");
        Map<String, Object> tags = Json.toMap(asset.get("tags"));
        String cat;
        String cause;
        if (createdBy.startsWith("replication-rule")) { cat = "replication_rule"; cause = "A storage replication rule (" + createdBy + ") copies data to " + asset.get("location") + ", outside the permitted regions."; }
        else if (createdBy.startsWith("backup-policy")) { cat = "backup_policy"; cause = "A cross-region backup policy places copies in " + asset.get("location") + "."; }
        else if (createdBy.contains("vendor")) { cat = "vendor_default"; cause = "A vendor-managed export uses the vendor's default region (" + asset.get("location") + "); not under IaC."; }
        else if ("analytics".equals(purpose)) { cat = "analytics_copy"; cause = "An analytics copy was created in " + asset.get("location") + " by " + createdBy + "."; }
        else if ("foreign_processing".equals(purpose)) { cat = "foreign_processing_copy"; cause = "Copy processed abroad without the controls the rule requires (e.g. return-by deadline)."; }
        else if ("ml".equals(tags.get("source")) && Json.dbl(tags, "confidence", 1) < 0.9) { cat = "mis_tag"; cause = "Possible mis-classification (ML tag, low confidence)."; }
        else if (tags.get("dataClass") == null) { cat = "unclassified"; cause = "Asset is unclassified and treated as restricted."; }
        else { cat = "wrong_region"; cause = "Asset provisioned in a non-permitted region."; }

        List<String> downstream = new ArrayList<>();
        for (Map<String, Object> e : lineage) if (asset.get("id").equals(e.get("from"))) downstream.add(String.valueOf(e.get("to")));
        List<Map<String, Object>> options = remediation(cat, asset);
        Map<String, Object> blast = Json.map("records", asset.get("recordCount"), "individualsEstimate", asset.get("individuals"), "downstreamAssets", downstream,
                "upstream", asset.get("replicationOf"), "kmsKey", inv.get("kmsKey"));

        Map<String, Object> out = ctx.reason(
                "You are the Residency Investigator agent. From the violation, inventory, lineage and IaC excerpt, determine the root cause and remediation. "
                        + "Return JSON: root_cause_category (one of replication_rule, backup_policy, vendor_default, analytics_copy, foreign_processing_copy, mis_tag, unclassified, wrong_region), "
                        + "root_cause (string), explanation (string), remediation_options (array of {option, effort, owner}), confidence (0-1). You may only recommend; humans approve.",
                List.of(Block.meta("Violation", v), Block.meta("Asset (catalogue metadata)", asset), Block.meta("Cloud inventory", inv),
                        Block.meta("Lineage", lineage), Block.meta("IaC excerpt", iac)),
                () -> Json.map("root_cause_category", cat, "root_cause", cause,
                        "explanation", "Rule(s) " + v.get("ruleIds") + " in " + v.get("mode") + " mode. " + cause + " Since: first detected " + v.get("detectedAt") + ".",
                        "remediation_options", options, "confidence", 0.8));
        out.put("blast_radius", blast);
        out.put("since", v.get("detectedAt"));
        boolean needs = Json.bool(ctx.input(), "businessNeedsFlow", false);
        return Json.map("analysis", out, "needsWorkaround", needs);
    }

    private Map<String, Object> workaround(AgentContext ctx, AgentState s) {
        Map<String, Object> v = s.<Map<String, Object>>value("violation").orElseThrow();
        Map<String, Object> asset = s.<Map<String, Object>>value("asset").orElseThrow();
        Map<String, Object> tags = Json.toMap(asset.get("tags"));
        String loc = Json.str(asset, "location", "");
        Map<String, Object> transfer = Json.map("originCell", asset.get("cell"), "destinationCountry", countryGuess(loc),
                "purpose", Json.str(asset, "copyPurpose", "analytics"), "dataClasses", List.of(Json.str(tags, "dataClass", "personal")),
                "individualsNonsensitive", asset.get("individuals"), "individualsSensitive", 0);
        AgentRun w = ctx.delegate("conflict-workaround", Json.map("conflictType", "localisation", "transfer", transfer, "assetId", asset.get("id"),
                "ruleIds", v.get("ruleIds"), "description", "Business needs the flow behind " + asset.get("id") + " to continue"));
        return Json.map("workaround", Json.map("runId", w.id, "status", w.status, "recommended", w.output.get("recommended_pattern"), "caseId", w.output.get("caseId")));
    }

    private Map<String, Object> report(AgentContext ctx, AgentState s) {
        Map<String, Object> v = s.<Map<String, Object>>value("violation").orElseThrow();
        Map<String, Object> analysis = new LinkedHashMap<>(s.<Map<String, Object>>value("analysis").orElseThrow());
        s.<Map<String, Object>>value("workaround").ifPresent(w -> analysis.put("workaround", w));
        analysis.put("violation", Json.map("id", v.get("id"), "assetId", v.get("assetId"), "ruleIds", v.get("ruleIds"), "observedLocation", v.get("observedLocation"), "mode", v.get("mode"), "decisionId", v.get("decisionId")));
        Object k = ctx.tool("case.create_draft", Json.map("type", "investigation_report", "title", "Residency investigation: " + v.get("assetId"),
                "payload", Json.map("violationId", v.get("id"), "assetId", v.get("assetId"), "report", analysis)));
        ctx.caseCreated(k);
        analysis.put("caseId", Json.toMap(k).get("caseId"));
        if (Json.bool(ctx.input(), "requestQuarantine", false)) {
            Object q = ctx.tool("residency.request_quarantine", Json.map("assetId", v.get("assetId"), "rationale", "Residency violation " + v.get("ruleIds") + ": " + analysis.get("root_cause")));
            ctx.caseCreated(q);
            analysis.put("quarantineCaseId", Json.toMap(q).get("caseId"));
        }
        return Json.map("result", analysis);
    }

    private static List<Map<String, Object>> remediation(String cat, Map<String, Object> a) {
        List<Map<String, Object>> o = new ArrayList<>();
        switch (cat) {
            case "replication_rule" -> o.add(Json.map("option", "Re-point the replication rule to an in-country region and delete the out-of-region copy", "effort", "low", "owner", "platform team"));
            case "backup_policy" -> o.add(Json.map("option", "Move DR copies to a permitted secondary region, or confirm the transfer tool covers backup", "effort", "medium", "owner", "platform team"));
            case "vendor_default" -> { o.add(Json.map("option", "Configure vendor export region / customer-managed keys in-country", "effort", "medium", "owner", "vendor management"));
                o.add(Json.map("option", "Record outsourcing assessment covering the location (Legal / vendor-risk only)", "effort", "medium", "owner", "vendor risk")); }
            case "analytics_copy" -> { o.add(Json.map("option", "Rebuild the analytics workload in-cell (WP-03) and delete the copy", "effort", "medium", "owner", "data owner"));
                o.add(Json.map("option", "Export aggregates only (WP-01)", "effort", "low", "owner", "data owner")); }
            case "foreign_processing_copy" -> o.add(Json.map("option", "Add a return-by deadline and deletion job for the foreign-processed copy", "effort", "low", "owner", "data owner"));
            case "mis_tag" -> o.add(Json.map("option", "Request re-classification after data owner review", "effort", "low", "owner", "data owner"));
            default -> o.add(Json.map("option", "Re-provision in a permitted region via IaC (Conftest blocks out-of-region apply)", "effort", "medium", "owner", "platform team"));
        }
        o.add(Json.map("option", "Quarantine the asset pending remediation (data owner approval)", "effort", "low", "owner", "data owner"));
        return o;
    }

    private static String countryGuess(String loc) {
        if (loc.contains("us-")) return "US";
        if (loc.contains("eu-west-1")) return "IE";
        if (loc.contains("eu-central")) return "DE";
        if (loc.contains("ap-southeast")) return "SG";
        if (loc.contains("ap-east")) return "HK";
        if (loc.contains("ap-south")) return "IN";
        return "US";
    }
}

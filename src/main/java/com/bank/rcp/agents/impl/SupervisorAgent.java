package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.model.Models.*;
import com.bank.rcp.platform.ModelGateway.Block;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * A1 Supervisor as a LangGraph4j graph: plan -> (delegate) -> merge -> verify.
 * Plans the work, delegates to specialists over A2A, merges results and cites evidence/decision ids.
 */
@Component
public class SupervisorAgent implements Agent {
    private static final Pattern IDS = Pattern.compile("\\b(ev-[a-z]+-\\d{6}|dec-\\d{8}-\\d+|case-\\d{8}-\\d+|viol-\\d{8}-\\d+|run-\\d{8}-\\d+)\\b");
    private static final Map<String, String> SPECIALIST_INPUTS = new LinkedHashMap<>();
    static {
        SPECIALIST_INPUTS.put("residency-investigator", "{assetId}");
        SPECIALIST_INPUTS.put("transfer-assessment", "{transfer:{originCell,destinationCountry,purpose,dataClasses,individualsNonsensitive,individualsSensitive,containsImportantData}}");
        SPECIALIST_INPUTS.put("reg-change-analyst", "{itemId}");
        SPECIALIST_INPUTS.put("third-party-analyst", "{providerId}");
        SPECIALIST_INPUTS.put("incident-triage", "{incidentId}");
        SPECIALIST_INPUTS.put("evidence-audit", "{question}");
        SPECIALIST_INPUTS.put("report-drafter", "{runId}");
        SPECIALIST_INPUTS.put("classification-assistant", "{assetId}");
        SPECIALIST_INPUTS.put("conflict-workaround", "{conflictType, description}");
    }

    private final DataStore store;

    public SupervisorAgent(DataStore store) { this.store = store; }

    @Override public String id() { return "supervisor"; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(AgentContext ctx) throws Exception {
        ctx.plan("catalog.search", "case.read", "evidence.search", "residency.list_violations", "a2a.delegate");
        StateGraph<AgentState> g = new StateGraph<>(AgentState::new);
        g.addNode("plan", node_async(s -> plan(ctx)));
        g.addNode("delegate", node_async(s -> delegate(ctx, s)));
        g.addNode("merge", node_async(s -> merge(ctx, s)));
        g.addNode("verify", node_async(s -> verify(ctx, s)));
        g.addEdge(GraphDefinition.START, "plan");
        g.addConditionalEdges("plan", edge_async(s -> ((List<Object>) s.value("steps").orElse(List.of())).isEmpty() ? "merge" : "delegate"),
                Map.of("merge", "merge", "delegate", "delegate"));
        g.addEdge("delegate", "merge");
        g.addEdge("merge", "verify");
        g.addEdge("verify", GraphDefinition.END);
        CompiledGraph<AgentState> app = g.compile();
        ctx.step("graph", "langgraph4j", "ok", "nodes: plan -> [delegate] -> merge -> verify");
        AgentState fin = app.invoke(Map.of("question", ctx.in("question", ""))).orElseThrow();
        return (Map<String, Object>) fin.value("result").orElse(Map.of());
    }

    // ------------------------------------------------------------------ plan

    private Map<String, Object> plan(AgentContext ctx) {
        String q = ctx.in("question", "");
        List<Map<String, Object>> violations = ctx.toolList("residency.list_violations", Json.map("status", "open"));
        List<Map<String, Object>> fallback = keywordPlan(q, violations);
        Map<String, Object> options = Json.map("open_violations", violations.stream().map(v -> Json.map("assetId", v.get("assetId"), "cell", v.get("cell"), "segment", v.get("segment"), "ruleIds", v.get("ruleIds"))).toList(),
                "reg_change_items", store.regChanges.values().stream().map(r -> Json.map("itemId", r.id, "title", r.title, "jurisdiction", r.jurisdiction)).toList(),
                "third_parties", store.thirdParties.values().stream().map(t -> Json.map("providerId", t.id, "name", t.name)).toList(),
                "incidents", store.incidents.values().stream().map(i -> Json.map("incidentId", i.id, "title", i.title)).toList(),
                "report_runs", store.reportRuns.values().stream().map(r -> Json.map("runId", r.id, "title", r.title)).toList());
        Map<String, Object> out = ctx.reason(
                "You are the Supervisor agent of a regulatory control plane. Decompose the analyst question into at most 3 delegations to specialists. "
                        + "Specialists and their inputs: " + Json.write(SPECIALIST_INPUTS) + ". Use only ids from the available options. "
                        + "Return JSON: {\"steps\":[{\"agent\":\"...\",\"input\":{...},\"why\":\"...\"}], \"rationale\":\"...\"}. If no specialist is needed return steps: [].",
                List.of(Block.pub("Analyst question", q), Block.meta("Available options", options)),
                () -> Json.map("steps", fallback, "rationale", "keyword routing"));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Object o : Json.convert(out.getOrDefault("steps", List.of()), new com.fasterxml.jackson.core.type.TypeReference<List<Object>>() {})) {
            Map<String, Object> st = Json.toMap(o);
            String agent = Json.str(st, "agent", "");
            if (!SPECIALIST_INPUTS.containsKey(agent)) { ctx.step("guardrail", "plan-validation", "dropped", "unknown specialist " + agent); continue; }
            steps.add(st);
            if (steps.size() >= 3) break;
        }
        if (steps.isEmpty() && !fallback.isEmpty() && !"deterministic".equals(out.get("_reasoning"))) steps.addAll(fallback);
        ctx.step("plan", "supervisor-plan", "ok", steps);
        return Json.map("steps", steps, "violations", violations);
    }

    private List<Map<String, Object>> keywordPlan(String q, List<Map<String, Object>> violations) {
        String l = q.toLowerCase();
        String cell = cellOf(l);
        List<Map<String, Object>> steps = new ArrayList<>();
        if (l.contains("residency") || l.contains("violation") || l.contains("stored") || l.contains("failing")) {
            violations.stream().filter(v -> cell == null || cell.equals(v.get("cell")))
                    .filter(v -> !l.contains("payment") || "payments".equals(v.get("segment")))
                    .limit(2).forEach(v -> steps.add(Json.map("agent", "residency-investigator", "input", Json.map("assetId", v.get("assetId")), "why", "open violation " + v.get("ruleIds"))));
        }
        if (l.contains("transfer") || l.contains("send") || l.contains("export")) {
            String origin = cell == null ? "CN" : cell;
            steps.add(Json.map("agent", "transfer-assessment", "input", Json.map("transfer", Json.map("originCell", origin, "destinationCountry", origin.equals("EU") ? "SG" : "IE",
                    "purpose", l.contains("aml") ? "group_aml" : "analytics", "dataClasses", List.of(l.contains("kyc") ? "sensitive_personal" : "personal"),
                    "individualsNonsensitive", 0, "individualsSensitive", l.contains("kyc") ? 40000 : 0)), "why", "proposed cross-border flow"));
        }
        if (l.contains("guideline") || l.contains("regulat") || l.contains("new rule") || l.contains("change for us")) {
            store.regChanges.values().stream().filter(r -> cell == null || cell.equals(r.jurisdiction)).max(Comparator.comparing(r -> String.valueOf(r.publishedAt)))
                    .ifPresent(r -> steps.add(Json.map("agent", "reg-change-analyst", "input", Json.map("itemId", r.id), "why", "regulatory change " + r.title)));
        }
        if (l.contains("contract") || l.contains("vendor") || l.contains("third-party") || l.contains("third party") || l.contains("crm")) {
            store.thirdParties.values().stream().filter(t -> l.contains("crm") ? t.name.toLowerCase().contains("crm") : true).findFirst()
                    .ifPresent(t -> steps.add(Json.map("agent", "third-party-analyst", "input", Json.map("providerId", "TP-003"), "why", "contract clause review")));
        }
        if (l.contains("incident") || l.contains("breach")) {
            store.incidents.values().stream().max(Comparator.comparing(i -> i.detectedAt))
                    .ifPresent(i -> steps.add(Json.map("agent", "incident-triage", "input", Json.map("incidentId", i.id), "why", "incident classification")));
        }
        if (l.contains("evidence") || l.contains("show that") || l.contains("prove") || l.contains("auditor")) {
            steps.add(Json.map("agent", "evidence-audit", "input", Json.map("question", q), "why", "evidence pack"));
        }
        if (l.contains("board") || l.contains("narrative") || l.contains("report")) {
            store.reportRuns.values().stream().max(Comparator.comparing(r -> r.generatedAt))
                    .ifPresent(r -> steps.add(Json.map("agent", "report-drafter", "input", Json.map("runId", r.id), "why", "narrative for latest report run")));
        }
        return steps.size() > 3 ? new ArrayList<>(steps.subList(0, 3)) : steps;
    }

    private static String cellOf(String l) {
        if (l.contains("india") || l.matches(".*\\bin\\b cell.*")) return "IN";
        if (l.contains("singapore") || l.contains(" sg")) return "SG";
        if (l.contains("china") || l.contains(" cn")) return "CN";
        if (l.contains(" eu") || l.contains("europe") || l.startsWith("eu")) return "EU";
        return null;
    }

    // ------------------------------------------------------------------ delegate / merge / verify

    @SuppressWarnings("unchecked")
    private Map<String, Object> delegate(AgentContext ctx, AgentState s) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object o : (List<Object>) s.value("steps").orElse(List.of())) {
            Map<String, Object> st = Json.toMap(o);
            try {
                AgentRun r = ctx.delegate(Json.str(st, "agent", ""), Json.toMap(st.get("input")));
                results.add(Json.toMap(Json.plain(Json.map("agent", st.get("agent"), "runId", r.id, "status", r.status, "output", trim(r.output),
                        "evidenceIds", r.evidenceIds, "caseIds", r.caseIds, "error", r.error))));
                r.evidenceIds.forEach(ctx::cite);
            } catch (AgentContext.ToolDeniedException e) {
                results.add(Json.map("agent", st.get("agent"), "status", "denied", "error", e.getMessage()));
            }
        }
        return Json.map("results", results);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> merge(AgentContext ctx, AgentState s) {
        String q = ctx.in("question", "");
        List<Map<String, Object>> results = (List<Map<String, Object>>) s.value("results").orElse(List.of());
        List<Map<String, Object>> violations = (List<Map<String, Object>>) s.value("violations").orElse(List.of());
        String answer = ctx.narrate(
                "You are the Supervisor agent. Write the final answer for a compliance analyst in markdown (<= 250 words). Base every statement on the specialist results; "
                        + "cite evidence, decision, case or run ids in parentheses, e.g. (dec-20261005-1012). Mark anything not supported by a result as 'unverified'. "
                        + "Never state that something was approved, sent or deployed by an agent.",
                List.of(Block.pub("Analyst question", q), Block.meta("Specialist results", results),
                        Block.meta("Open residency violations (context)", violations.stream().map(v -> Json.map("assetId", v.get("assetId"), "ruleIds", v.get("ruleIds"), "decisionId", v.get("decisionId"))).toList())),
                () -> fallbackAnswer(q, results, violations));
        return Json.map("answer", answer);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> verify(AgentContext ctx, AgentState s) {
        String answer = s.<String>value("answer").orElse("");
        List<Map<String, Object>> results = (List<Map<String, Object>>) s.value("results").orElse(List.of());
        Matcher m = IDS.matcher(answer);
        List<String> cited = new ArrayList<>();
        while (m.find()) cited.add(m.group());
        List<String> flags = new ArrayList<>();
        if (cited.isEmpty()) flags.add("unverified: answer cites no evidence, decision or case ids");
        Set<String> caseIds = new LinkedHashSet<>();
        for (Map<String, Object> r : results) caseIds.addAll(Json.strList(r.get("caseIds")));
        for (String c : cited) if (c.startsWith("case-") && !store.cases.containsKey(c)) flags.add("unverified id " + c);
        for (String c : cited) if (c.startsWith("dec-") && !store.decisions.containsKey(c)) flags.add("unverified id " + c);
        if (!flags.isEmpty()) ctx.step("guardrail", "citation-verification", "flagged", flags);
        return Json.map("result", Json.map("answer", answer, "plan", s.value("steps").orElse(List.of()), "delegations", results,
                "citedIds", cited, "verificationFlags", flags, "draftCases", caseIds));
    }

    private static Map<String, Object> trim(Map<String, Object> out) {
        Map<String, Object> t = new LinkedHashMap<>();
        for (var e : out.entrySet()) {
            String v = Json.write(e.getValue());
            t.put(e.getKey(), v.length() > 1200 ? v.substring(0, 1200) + "..." : e.getValue());
        }
        return t;
    }

    private static String fallbackAnswer(String q, List<Map<String, Object>> results, List<Map<String, Object>> violations) {
        StringBuilder sb = new StringBuilder("**Question:** ").append(q).append("\n\n");
        if (results.isEmpty()) {
            sb.append("No specialist was required. Current open residency violations: ").append(violations.size()).append(".\n");
            for (Map<String, Object> v : violations) sb.append("- ").append(v.get("assetId")).append(" ").append(v.get("ruleIds")).append(" (").append(v.get("decisionId")).append(")\n");
            return sb.toString();
        }
        for (Map<String, Object> r : results) {
            Map<String, Object> o = Json.toMap(r.get("output"));
            sb.append("**").append(r.get("agent")).append("** (").append(r.get("runId")).append(", ").append(r.get("status")).append("): ");
            Object headline = o.getOrDefault("root_cause", o.getOrDefault("summary", o.getOrDefault("answer", o.getOrDefault("conflict_statement", o.getOrDefault("narrative", r.get("error"))))));
            sb.append(headline);
            if (o.get("recommended_pattern") != null) sb.append(" Recommended pattern: ").append(o.get("recommended_pattern")).append(".");
            if (o.get("caseId") != null) sb.append(" Draft case (").append(o.get("caseId")).append(") awaits human review.");
            sb.append("\n\n");
        }
        sb.append("_Agents analyse and draft only; all decisions remain with deterministic services and named approvers._");
        return sb.toString();
    }
}

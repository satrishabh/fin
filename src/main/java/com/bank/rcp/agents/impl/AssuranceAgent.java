package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/** A12 Assurance / Evaluator: samples other agents' runs, scores them against rubrics, records evaluations. Never edits their work. */
@Component
public class AssuranceAgent implements Agent {
    @Override public String id() { return "assurance"; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("agent.runs.read", "evidence.search", "eval.record");
        List<Map<String, Object>> runs = ctx.toolList("agent.runs.read", Json.map("limit", (int) Json.num(ctx.input(), "sample", 25)));
        Set<String> evidenceIds = new HashSet<>();
        for (Map<String, Object> e : ctx.toolList("evidence.search", Json.map("limit", 1000))) evidenceIds.add(String.valueOf(e.get("id")));

        Map<String, Map<String, int[]>> perAgent = new TreeMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (Map<String, Object> r : runs) {
            String agent = String.valueOf(r.get("agentId"));
            Map<String, Object> out = Json.toMap(r.get("output"));
            Map<String, int[]> s = perAgent.computeIfAbsent(agent, k -> new LinkedHashMap<>());
            score(s, "schema_valid", !out.isEmpty() || "blocked".equals(r.get("status")));
            List<String> cites = Json.strList(r.get("evidenceIds"));
            boolean citationsOk = cites.stream().allMatch(i -> !i.startsWith("ev-") || evidenceIds.contains(i));
            score(s, "citation_validity", citationsOk);
            if (!citationsOk) findings.add(Json.map("runId", r.get("id"), "agent", agent, "finding", "cites unknown evidence ids"));
            score(s, "boundary_adherence", !"failed".equals(r.get("status")));
            if ("conflict-workaround".equals(agent)) {
                String rec = Json.str(out, "recommended_pattern", null);
                boolean consistent = true;
                for (Object c : (List<Object>) out.getOrDefault("candidates", List.of())) {
                    Map<String, Object> cm = Json.toMap(c);
                    if (Objects.equals(cm.get("patternId"), rec) && "fail".equals(cm.get("simulation"))) consistent = false;
                }
                score(s, "policy_consistency", consistent);
                if (!consistent) findings.add(Json.map("runId", r.get("id"), "agent", agent, "finding", "recommended pattern fails simulation"));
            }
            if ("report-drafter".equals(agent)) score(s, "number_integrity", Json.strList(out.get("mismatches")).isEmpty() || String.valueOf(out.get("numberCheck")).contains("substituted"));
            if (Boolean.TRUE.equals(out.get("injection_suspected"))) score(s, "injection_flagged", true);
        }
        Map<String, Object> scores = new LinkedHashMap<>();
        for (var e : perAgent.entrySet()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var d : e.getValue().entrySet()) m.put(d.getKey(), Math.round(1000.0 * d.getValue()[0] / Math.max(1, d.getValue()[1])) / 10.0);
            m.put("sampled_runs", runs.stream().filter(r -> e.getKey().equals(r.get("agentId"))).count());
            scores.put(e.getKey(), m);
            ctx.tryTool("eval.record", Json.map("agentId", e.getKey(), "scores", m));
        }
        Map<String, Object> out = ctx.reason(
                "You are the Assurance agent. Summarise the scores and findings for model risk. Return JSON: summary, top_risks (array). Do not change any scores.",
                List.of(Block.meta("Scores (percent)", scores), Block.meta("Findings", findings)),
                () -> Json.map("summary", "Sampled " + runs.size() + " runs across " + scores.size() + " agents; " + findings.size() + " findings.",
                        "top_risks", findings.isEmpty() ? List.of("No rubric failures in sample") : findings.stream().map(f -> f.get("agent") + ": " + f.get("finding")).distinct().toList()));
        out.put("scores", scores);   // deterministic; model may not alter
        out.put("findings", findings);
        out.put("sampled", runs.size());
        return out;
    }

    private static void score(Map<String, int[]> s, String k, boolean ok) {
        int[] v = s.computeIfAbsent(k, x -> new int[2]);
        if (ok) v[0]++;
        v[1]++;
    }
}

package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * A11 Conflict & Workaround. Ranks ONLY patterns from the Legal-owned catalogue; each candidate is
 * checked by policy simulation. Ideas outside the catalogue are labelled NOVEL and cannot be executed.
 */
@Component
public class ConflictWorkaroundAgent implements Agent {
    private static final Map<String, Integer> RISK_RANK = Map.of("WP-03", 1, "WP-01", 1, "WP-09", 2, "WP-04", 2, "WP-06", 1, "WP-07", 2, "WP-08", 3, "WP-05", 3, "WP-02", 4);
    private static final Set<String> TRANSFER_TRANSFORMS = Set.of("aggregate", "pseudonymise", "in_place_scores", "federated", "synthetic", "volume_managed");

    @Override public String id() { return "conflict-workaround"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("workaround.catalog.read", "policy.simulate", "lineage.read", "mechanism.search", "case.create_draft");
        Map<String, Object> transfer = Json.toMap(ctx.input().get("transfer"));
        String conflictType = ctx.in("conflictType", transfer.isEmpty() ? "localisation" : "transfer_blocked");
        String description = ctx.in("description", "");
        List<Map<String, Object>> catalogue = ctx.toolList("workaround.catalog.read", Map.of());

        Map<String, Object> direct = null;
        if (!transfer.isEmpty()) {
            Map<String, Object> t = new LinkedHashMap<>(transfer);
            t.remove("transform");
            direct = ctx.toolMap("policy.simulate", t);
            ctx.cite(Json.str(direct, "decisionId", null));
        }
        if (ctx.input().get("assetId") != null) ctx.tryTool("lineage.read", Map.of("assetId", ctx.in("assetId", "")));

        List<Map<String, Object>> candidates = new ArrayList<>();
        for (Map<String, Object> wp : catalogue) {
            List<String> types = Json.strList(wp.get("conflictTypes"));
            String transform = Json.str(wp, "transform", "");
            boolean relevant = types.contains(conflictType) || (!transfer.isEmpty() && TRANSFER_TRANSFORMS.contains(transform) && types.contains("transfer_blocked"));
            if (!relevant) continue;
            Map<String, Object> cand = Json.map("patternId", wp.get("id"), "name", wp.get("name"), "residualRisk", wp.get("residualRisk"),
                    "costImpact", wp.get("costImpact"), "latencyImpact", wp.get("latencyImpact"), "preconditions", wp.get("preconditions"));
            if (!transfer.isEmpty() && TRANSFER_TRANSFORMS.contains(transform)) {
                Map<String, Object> t = new LinkedHashMap<>(transfer);
                t.put("transform", wp.get("id"));
                t.putIfAbsent("kMin", 20);
                t.putIfAbsent("rowCap", 200);
                if ("volume_managed".equals(transform)) {
                    t.put("individualsNonsensitive", Json.num(transfer, "individualsNonsensitive", 0) / 2);
                    t.put("individualsSensitive", 0);
                    cand.put("assumption", "flow minimised to non-sensitive fields and split across calendar years");
                }
                Map<String, Object> sim = ctx.toolMap("policy.simulate", t);
                ctx.cite(Json.str(sim, "decisionId", null));
                boolean pass = Json.bool(sim, "effectiveAllow", false) && Json.bool(sim, "allow", false);
                cand.put("simulation", pass ? "pass" : "fail");
                cand.put("decisionId", sim.get("decisionId"));
                cand.put("simulationReasons", sim.get("reasons"));
                cand.put("requiredRoute", Json.toMap(sim.get("extra")).get("requiredRoute"));
            } else {
                cand.put("simulation", "n/a (process control - verified by Legal)");
            }
            cand.put("rank", RISK_RANK.getOrDefault(String.valueOf(wp.get("id")), 5) + (Objects.equals(cand.get("simulation"), "fail") ? 10 : 0));
            candidates.add(cand);
        }
        candidates.sort(Comparator.comparingInt(c -> ((Number) c.get("rank")).intValue()));
        List<String> collidingRules = direct == null ? Json.strList(ctx.input().get("ruleIds")) : Json.strList(direct.get("ruleIds"));

        Map<String, Object> best = candidates.stream().filter(c -> !"fail".equals(c.get("simulation"))).findFirst().orElse(null);
        final Map<String, Object> directF = direct;
        Map<String, Object> out = ctx.reason(
                "You are the Conflict & Workaround agent of a bank's regulatory control plane. Given a blocked business flow, the policy simulation results and "
                        + "the APPROVED workaround catalogue, write: conflict_statement (string), colliding_rules (array of rule ids), recommended_pattern (a catalogue id such as WP-03), "
                        + "rationale (string), open_legal_questions (array of strings), novel_ideas (array of strings - anything not in the catalogue). "
                        + "You MUST recommend only a catalogue pattern whose simulation is 'pass' or process control. You never approve anything.",
                List.of(Block.meta("Business request", Json.map("conflictType", conflictType, "description", description, "transfer", transfer)),
                        Block.meta("Direct route simulation", directF == null ? "n/a" : directF),
                        Block.meta("Candidates with simulation results", candidates)),
                () -> Json.map(
                        "conflict_statement", conflictStatement(conflictType, description, transfer, directF),
                        "colliding_rules", collidingRules,
                        "recommended_pattern", best == null ? null : best.get("patternId"),
                        "rationale", best == null ? "No catalogue pattern passes simulation; escalate to Legal." :
                                best.get("patternId") + " (" + best.get("name") + ") passes policy simulation with the lowest residual risk among candidates: " + best.get("residualRisk"),
                        "open_legal_questions", openQuestions(transfer, candidates),
                        "novel_ideas", List.of()));

        // Guardrail: recommendation must be a catalogue pattern that passed (policy consistency = 100%).
        String rec = Json.str(out, "recommended_pattern", null);
        Map<String, Object> recCand = candidates.stream().filter(c -> c.get("patternId").equals(rec)).findFirst().orElse(null);
        List<String> guard = new ArrayList<>();
        if (rec != null && (recCand == null || "fail".equals(recCand.get("simulation")))) {
            guard.add("Model recommendation '" + rec + "' is " + (recCand == null ? "not in the approved catalogue (NOVEL - legal review required)" : "failing policy simulation") + "; replaced by best passing candidate");
            out.put("recommended_pattern", best == null ? null : best.get("patternId"));
            recCand = best;
        }
        List<String> novel = new ArrayList<>();
        for (String n : Json.strList(out.get("novel_ideas"))) novel.add("NOVEL - legal review required: " + n);
        out.put("novel_ideas", novel);
        out.put("candidates", candidates);
        out.put("direct_route", directF == null ? null : Json.map("allow", directF.get("effectiveAllow"), "decisionId", directF.get("decisionId"), "reasons", directF.get("reasons")));
        out.put("guardrails", guard);
        if (!guard.isEmpty()) ctx.step("guardrail", "policy-consistency", "corrected", guard);

        Object k = ctx.tool("case.create_draft", Json.map("type", "workaround_proposal",
                "title", "Workaround proposal: " + (recCand == null ? "none passing" : recCand.get("patternId") + " " + recCand.get("name")),
                "payload", Json.map("conflictType", conflictType, "transfer", transfer, "recommended", recCand, "candidates", candidates,
                        "colliding_rules", out.get("colliding_rules"), "open_legal_questions", out.get("open_legal_questions"), "novel_ideas", novel)));
        ctx.caseCreated(k);
        out.put("caseId", Json.toMap(k).get("caseId"));
        return out;
    }

    private static String conflictStatement(String type, String desc, Map<String, Object> t, Map<String, Object> direct) {
        if (!t.isEmpty())
            return "Business flow " + t.get("originCell") + " -> " + t.get("destinationCountry") + " (" + t.get("purpose") + ", " + t.get("dataClasses") + ") is blocked: "
                    + (direct == null ? "" : String.join("; ", Json.strList(direct.get("reasons"))));
        return "Conflict (" + type + "): " + desc;
    }

    private static List<String> openQuestions(Map<String, Object> t, List<Map<String, Object>> cands) {
        List<String> q = new ArrayList<>();
        if (cands.stream().anyMatch(c -> "WP-02".equals(c.get("patternId"))))
            q.add("Is pseudonymised data still personal information for the origin regime (likely yes under GDPR/PIPL)?");
        if (cands.stream().anyMatch(c -> "WP-03".equals(c.get("patternId"))))
            q.add("Confirm the output schema of in-cell scores contains no personal data or quasi-identifiers.");
        if ("CN".equals(String.valueOf(t.get("originCell")))) q.add("Counsel to confirm current CN threshold tiers and whether any exemption scenario applies.");
        q.add("Business owner to confirm the reduced data set still meets the stated purpose.");
        return q;
    }
}

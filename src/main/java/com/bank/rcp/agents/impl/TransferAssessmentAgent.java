package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.model.Models.AgentRun;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/** A6 Transfer Assessment: what a proposed cross-border flow needs before it can run. */
@Component
public class TransferAssessmentAgent implements Agent {
    @Override public String id() { return "transfer-assessment"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("policy.simulate", "mechanism.search", "counter.read_projection", "catalog.read", "case.create_draft", "a2a.delegate");
        Map<String, Object> t = Json.toMap(ctx.input().get("transfer"));
        if (t.isEmpty()) throw new IllegalArgumentException("input.transfer is required (originCell, destinationCountry, purpose, dataClasses, individuals...)");
        String origin = Json.str(t, "originCell", "EU");
        String dest = Json.str(t, "destinationCountry", "");
        Map<String, Object> direct = new LinkedHashMap<>(t);
        direct.remove("transform");
        Map<String, Object> sim = ctx.toolMap("policy.simulate", direct);
        ctx.cite(Json.str(sim, "decisionId", null));
        boolean allowed = Json.bool(sim, "effectiveAllow", false) && Json.bool(sim, "allow", false);
        List<Map<String, Object>> mechs = ctx.toolList("mechanism.search", Json.map("origin", origin, "destination", dest));
        Map<String, Object> projection = null;
        if ("CN".equals(origin))
            projection = ctx.toolMap("counter.read_projection", Json.map("addNonsensitive", Json.num(t, "individualsNonsensitive", 0), "addSensitive", Json.num(t, "individualsSensitive", 0)));
        for (String a : Json.strList(ctx.input().get("assetIds"))) ctx.tryTool("catalog.read", Map.of("assetId", a));

        Map<String, Object> workaround = null;
        if (!allowed) {
            AgentRun w = ctx.delegate("conflict-workaround", Json.map("conflictType", "transfer_blocked", "transfer", direct,
                    "description", ctx.in("request", "Proposed cross-border flow is blocked by policy")));
            workaround = Json.map("runId", w.id, "status", w.status, "recommended", w.output.get("recommended_pattern"), "caseId", w.output.get("caseId"),
                    "candidates", w.output.get("candidates"));
        }
        Map<String, Object> extra = Json.toMap(sim.get("extra"));
        final Map<String, Object> proj = projection;
        final Map<String, Object> wk = workaround;
        Map<String, Object> out = ctx.reason(
                "You are the Transfer Assessment agent. Produce a transfer assessment memo as JSON with keys: summary, applicable_regimes (array), "
                        + "required_mechanism (string), existing_mechanism_coverage (string), documentation_pack (array of strings), workaround_summary (string), "
                        + "open_questions (array). Do not contradict the policy simulation result. You never approve transfers; Privacy/Legal decides.",
                List.of(Block.meta("Requested flow", t), Block.meta("Policy simulation (direct route)", sim), Block.meta("Mechanisms in registry for this lane", mechs),
                        Block.meta("China volume projection", proj == null ? "n/a" : proj), Block.meta("Workaround agent result", wk == null ? "not needed" : wk)),
                () -> Json.map(
                        "summary", (allowed ? "Direct route permitted" : "Direct route NOT permitted") + " for " + origin + " -> " + dest + " (" + t.get("purpose") + "): " + String.join("; ", Json.strList(sim.get("reasons"))),
                        "applicable_regimes", regimes(origin, dest),
                        "required_mechanism", requiredMechanism(origin, extra),
                        "existing_mechanism_coverage", extra.get("mechanismId") != null ? "Covered by " + extra.get("mechanismId") : (mechs.isEmpty() ? "No mechanism recorded for this lane" : "Mechanisms exist on this lane but none covers the purpose/data class: " + mechs.stream().map(m -> String.valueOf(m.get("id"))).toList()),
                        "documentation_pack", docs(origin),
                        "workaround_summary", wk == null ? "Not required" : "Recommended pattern " + wk.get("recommended") + " (proposal case " + wk.get("caseId") + ")",
                        "open_questions", List.of("Confirm purpose limitation and data minimisation with the business owner", "Counsel to confirm mechanism scope and validity")));
        out.put("direct_route_allowed", allowed);   // forced: policy consistency with the PDP
        out.put("simulation", Json.map("decisionId", sim.get("decisionId"), "reasons", sim.get("reasons"), "requiredRoute", extra.get("requiredRoute"), "projected", extra.get("projected")));
        out.put("workaround", wk);
        Object k = ctx.tool("case.create_draft", Json.map("type", "transfer_assessment", "title", "Transfer assessment " + origin + " -> " + dest + " (" + t.get("purpose") + ")",
                "payload", Json.map("transfer", t, "memo", out, "decisionId", sim.get("decisionId"))));
        ctx.caseCreated(k);
        out.put("caseId", Json.toMap(k).get("caseId"));
        return out;
    }

    private static List<String> regimes(String origin, String dest) {
        List<String> r = new ArrayList<>();
        switch (origin) {
            case "EU" -> r.add("GDPR Chapter V (Arts. 44-49)");
            case "IN" -> { r.add("RBI payment data storage"); r.add("DPDP Act s.16"); }
            case "SG" -> { r.add("PDPA s.26 transfer limitation"); r.add("MAS Outsourcing Guidelines"); }
            case "CN" -> { r.add("PIPL Art. 38-39 (route, consent)"); r.add("2024 Cross-Border Data Provisions (thresholds)"); r.add("DSL important data"); }
            default -> r.add("origin regime unknown");
        }
        r.add("Destination (" + dest + ") onward-transfer and access considerations");
        return r;
    }

    private static String requiredMechanism(String origin, Map<String, Object> extra) {
        return switch (origin) {
            case "EU" -> "Adequacy, or SCCs/IGA/BCR plus a transfer impact assessment (TIA)";
            case "CN" -> "China route: " + extra.getOrDefault("requiredRoute", "see simulation") + " (" + extra.getOrDefault("routeReason", "") + ")";
            case "IN" -> "No raw payment data export; personal data permitted unless restricted country";
            case "SG" -> "Contractual comparable-protection instrument (IGA/SCC-equivalent)";
            default -> "unknown";
        };
    }

    private static List<String> docs(String origin) {
        return switch (origin) {
            case "EU" -> List.of("Executed SCC module or IGA reference", "Transfer impact assessment", "Record of processing update", "DPIA if high risk");
            case "CN" -> List.of("PIPIA (personal information protection impact assessment)", "Separate consent records for sensitive PI", "Standard contract filing or certification, or CAC security assessment application", "Important data screening record");
            case "IN" -> List.of("RBI payment data storage attestation", "Return-to-India deadline evidence for foreign processing");
            case "SG" -> List.of("Contract clauses evidencing comparable protection", "Outsourcing risk assessment (if a service provider is involved)");
            default -> List.of();
        };
    }
}

package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/** A9 Evidence & Audit: assembles evidence packs (queries, ids, hashes, narrative) that a human releases. */
@Component
public class EvidenceAuditAgent implements Agent {
    @Override public String id() { return "evidence-audit"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("evidence.search", "controls.read_results", "evidence.export_pack");
        String q = ctx.in("question", "Show evidence of encryption with in-region keys");
        String lower = q.toLowerCase();
        String cell = lower.contains(" eu") || lower.contains("europe") ? "EU" : lower.contains("india") || lower.contains(" in ") ? "IN" : lower.contains("singapore") ? "SG" : lower.contains("china") ? "CN" : null;
        String family = lower.contains("encrypt") || lower.contains("key") ? "Data residency" : lower.contains("log") || lower.contains("backup") ? "Operational" : lower.contains("vendor") || lower.contains("third") ? "Third-party" : null;
        List<Map<String, Object>> controls = ctx.toolList("controls.read_results", Json.map("cell", cell, "family", family));
        if (lower.contains("encrypt") || lower.contains("key")) controls.removeIf(c -> !String.valueOf(c.get("control")).toLowerCase().contains("key"));
        if (lower.contains("payment")) controls.removeIf(c -> !String.valueOf(c.get("resource")).contains("payments"));
        String evQuery = lower.contains("transfer") ? "transfer" : lower.contains("residency") ? "residency" : lower.contains("policy") ? "policy" : lower.contains("incident") ? "incident" : "decision";
        List<Map<String, Object>> ev = ctx.toolList("evidence.search", Json.map("q", evQuery, "cell", cell, "limit", 15));
        Set<String> known = new LinkedHashSet<>();
        for (Map<String, Object> e : ev) known.add(String.valueOf(e.get("id")));
        long failed = controls.stream().filter(c -> "failed".equals(c.get("status"))).count();

        Map<String, Object> out = ctx.reason(
                "You are the Evidence & Audit agent. Answer the auditor question using ONLY the control results and evidence records provided. Return JSON: answer, "
                        + "evidence_ids (array - must be ids from the evidence list), control_ids (array), gaps (array), query_used (string). Do not overstate: failed controls are exceptions.",
                List.of(Block.pub("Auditor question", q), Block.meta("Control results", controls), Block.meta("Evidence records", ev)),
                () -> Json.map("answer", controls.isEmpty() ? "No control results match the question scope." :
                                (controls.size() - failed) + " of " + controls.size() + " in-scope control results passed" + (failed > 0 ? "; exceptions: " + controls.stream().filter(c -> "failed".equals(c.get("status"))).map(c -> c.get("id") + " " + c.get("resource") + " (" + c.get("detail") + ")").toList() : "") + ".",
                        "evidence_ids", new ArrayList<>(known).subList(0, Math.min(8, known.size())), "control_ids", controls.stream().map(c -> c.get("id")).toList(),
                        "gaps", failed > 0 ? List.of("Remediate failed controls before attesting") : List.of(), "query_used", "controls(cell=" + cell + ", family=" + family + ") + evidence(q=" + evQuery + ")"));
        List<String> cited = Json.strList(out.get("evidence_ids"));
        List<String> fabricated = cited.stream().filter(i -> !known.contains(i)).toList();
        if (!fabricated.isEmpty()) {
            ctx.step("guardrail", "citation-check", "corrected", "removed fabricated evidence ids " + fabricated);
            out.put("evidence_ids", cited.stream().filter(known::contains).toList());
            out.put("removed_citations", fabricated);
        }
        for (String id : Json.strList(out.get("evidence_ids"))) ctx.cite(id);
        if (!Json.strList(out.get("evidence_ids")).isEmpty()) {
            Object k = ctx.tool("evidence.export_pack", Json.map("title", "Evidence pack: " + (q.length() > 80 ? q.substring(0, 80) : q),
                    "evidenceIds", out.get("evidence_ids"), "narrative", out.get("answer")));
            ctx.caseCreated(k);
            out.put("caseId", Json.toMap(k).get("caseId"));
        }
        return out;
    }
}

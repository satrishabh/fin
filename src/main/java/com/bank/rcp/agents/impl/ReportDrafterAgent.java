package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A10 Report Drafter: narrative around deterministic figures. A post-check fails any number not in the run. */
@Component
public class ReportDrafterAgent implements Agent {
    private static final Pattern NUM = Pattern.compile("(?<![A-Za-z0-9_-])\\d+(?:[.,]\\d+)?(?![A-Za-z0-9_-])");

    @Override public String id() { return "report-drafter"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("report.read_run", "evidence.search", "case.create_draft");
        String runId = ctx.in("runId", null);
        Map<String, Object> run = ctx.toolMap("report.read_run", Map.of("runId", runId));
        Map<String, Object> figures = Json.toMap(run.get("figures"));
        ctx.cite(Json.str(run, "evidenceId", null));

        String narrative = ctx.narrate(
                "You are the Report Drafter. Write a concise management narrative (120-200 words) for the report below. Use ONLY the numbers in 'figures', written exactly as given. "
                        + "Do not compute new numbers, percentages, totals, dates or counts. Refer to figures by meaning. Do not use digits anywhere else.",
                List.of(Block.meta("Report", Json.map("title", run.get("title"), "figures", figures))),
                () -> deterministic(String.valueOf(run.get("title")), figures));
        List<String> mismatches = numberCheck(narrative, figures);
        String status = "passed";
        if (!mismatches.isEmpty()) {
            ctx.step("guardrail", "number-integrity", "failed", "numbers not in report run: " + mismatches);
            status = "failed_number_check";
        }
        Map<String, Object> out = Json.map("runId", runId, "narrative", narrative, "numberCheck", status, "mismatches", mismatches, "figuresHash", run.get("figuresHash"));
        if (!mismatches.isEmpty()) {
            String safe = deterministic(String.valueOf(run.get("title")), figures);
            out.put("rejectedNarrative", narrative);
            out.put("narrative", safe);
            out.put("numberCheck", "model draft failed; deterministic narrative substituted");
        }
        Object k = ctx.tool("case.create_draft", Json.map("type", "report_narrative", "title", "Narrative for " + run.get("title") + " (" + runId + ")",
                "payload", Json.map("runId", runId, "narrative", out.get("narrative"), "numberCheck", out.get("numberCheck"))));
        ctx.caseCreated(k);
        out.put("caseId", Json.toMap(k).get("caseId"));
        return out;
    }

    public static List<String> numberCheck(String text, Map<String, Object> figures) {
        Set<String> allowed = new HashSet<>();
        for (Object v : figures.values()) {
            if (v instanceof Number n) {
                allowed.add(String.valueOf(n));
                if (n.doubleValue() == Math.rint(n.doubleValue())) allowed.add(String.valueOf(n.longValue()));
                allowed.add(String.format(Locale.ROOT, "%.1f", n.doubleValue()));
            } else allowed.add(String.valueOf(v));
        }
        List<String> bad = new ArrayList<>();
        Matcher m = NUM.matcher(text);
        while (m.find()) {
            String g = m.group().replace(",", "");
            if (!allowed.contains(g)) bad.add(m.group());
        }
        return bad;
    }

    private static String deterministic(String title, Map<String, Object> f) {
        StringBuilder sb = new StringBuilder(title).append(". Figures are produced by report-service from the stored evidence snapshot: ");
        List<String> parts = new ArrayList<>();
        for (var e : f.entrySet()) parts.add(e.getKey().replace('_', ' ') + " " + e.getValue());
        sb.append(String.join("; ", parts)).append(". Management attention is drawn to any open violations and blocked transfers listed above; remediation owners are tracked in case-service.");
        return sb.toString();
    }
}

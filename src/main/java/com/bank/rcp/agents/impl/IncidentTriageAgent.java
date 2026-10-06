package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/** A8 Incident Triage: per-regime classification, clock verification, notice drafts from templates. Cannot stop or extend clocks. */
@Component
public class IncidentTriageAgent implements Agent {
    @Override public String id() { return "incident-triage"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("incident.read", "catalog.read", "clock.read", "templates.render_draft", "case.create_draft");
        String incidentId = ctx.in("incidentId", null);
        Map<String, Object> inc = ctx.toolMap("incident.read", Map.of("incidentId", incidentId));
        List<Map<String, Object>> assets = new ArrayList<>();
        for (String a : Json.strList(inc.get("assetIds"))) {
            var o = ctx.tryTool("catalog.read", Map.of("assetId", a));
            if (o.ok()) assets.add(Json.toMap(o.result()));
        }
        List<Map<String, Object>> clocks = ctx.toolList("clock.read", Map.of("incidentId", incidentId));
        String sev = Json.str(inc, "severity", "high");
        boolean personal = Json.bool(inc, "personalDataAffected", false);
        long individuals = Json.num(inc, "individualsAffected", 0);
        Set<String> jur = new TreeSet<>(Json.strList(inc.get("jurisdictions")));
        boolean paymentsCritical = assets.stream().anyMatch(a -> "payments".equals(a.get("segment")));
        boolean high = Set.of("high", "critical").contains(sev);

        List<Map<String, Object>> assess = new ArrayList<>();
        if (jur.contains("EU")) {
            assess.add(regime("GDPR Art. 33", personal, personal ? "personal data affected for EU-origin assets" : "no personal data identified"));
            assess.add(regime("DORA major ICT incident - initial", high && paymentsCritical, high && paymentsCritical ? "high severity affecting a critical function (payments)" : "major-incident criteria not evidently met (severity/critical function)"));
        }
        if (jur.contains("IN")) {
            assess.add(regime("CERT-In cyber incident", true, "reportable cyber incident category under CERT-In Directions"));
            assess.add(regime("RBI cyber incident", !"low".equals(sev), "severity " + sev));
            assess.add(regime("DPDP breach - detailed report", personal, personal ? "personal data breach" : "no personal data"));
        }
        if (jur.contains("SG")) {
            assess.add(regime("MAS TRM relevant incident", high, "severity " + sev));
            assess.add(regime("PDPA notifiable breach", personal && individuals >= 500, personal ? (individuals >= 500 ? "significant scale (>= 500 individuals)" : "below significant-scale threshold of 500; assess significant harm") : "no personal data"));
        }
        if (jur.contains("CN")) assess.add(regime("CN cybersecurity incident (SIM)", high, "simulated regime; severity " + sev));

        Set<String> started = new HashSet<>();
        for (Map<String, Object> c : clocks) started.add(String.valueOf(c.get("regime")));
        List<String> disagreements = new ArrayList<>();
        for (Map<String, Object> a : assess) {
            String r = String.valueOf(a.get("regime"));
            boolean applies = Boolean.TRUE.equals(a.get("applies"));
            if (applies && !started.contains(r)) disagreements.add("Expected clock not running: " + r + " - flag to incident manager");
            if (!applies && started.contains(r)) disagreements.add("Clock running for " + r + " but triage suggests it may not apply (" + a.get("rationale") + ") - human to confirm; clock NOT changed");
        }

        Map<String, Object> out = ctx.reason(
                "You are the Incident Triage agent. Classify this incident against each regime's criteria and verify the regulatory clocks. Return JSON: summary, "
                        + "regime_assessments (array of {regime, applies, rationale}), disagreements (array), recommended_actions (array). You cannot stop, extend or send anything.",
                List.of(Block.meta("Incident", inc), Block.meta("Affected assets", assets), Block.meta("Running clocks", clocks), Block.meta("Rule-based assessment", assess)),
                () -> Json.map("summary", "Incident " + incidentId + " (" + sev + ") affects " + jur + "; " + clocks.size() + " clocks running.",
                        "regime_assessments", assess, "disagreements", disagreements,
                        "recommended_actions", List.of("Incident manager and DPO confirm classification per regime", "Review draft notices; humans submit via notification-service", "Close each clock with submission evidence")));
        out.put("disagreements", disagreements.isEmpty() ? out.get("disagreements") : disagreements);

        List<Map<String, Object>> drafts = new ArrayList<>();
        for (Map<String, Object> c : clocks) {
            if (c.get("template") == null) continue;
            var d = ctx.tryTool("templates.render_draft", Json.map("incidentId", incidentId, "template", c.get("template")));
            if (d.ok()) drafts.add(Json.map("regime", c.get("regime"), "deadlineAt", c.get("deadlineAt"), "draft", Json.toMap(d.result()).get("draft")));
        }
        out.put("notice_drafts", drafts);
        Object k = ctx.tool("case.create_draft", Json.map("type", "incident_classification", "title", "Incident classification: " + inc.get("title"),
                "payload", Json.map("incidentId", incidentId, "assessment", out.get("regime_assessments"), "disagreements", out.get("disagreements"), "drafts", drafts)));
        ctx.caseCreated(k);
        out.put("caseId", Json.toMap(k).get("caseId"));
        return out;
    }

    private static Map<String, Object> regime(String r, boolean applies, String why) { return Json.map("regime", r, "applies", applies, "rationale", why); }
}

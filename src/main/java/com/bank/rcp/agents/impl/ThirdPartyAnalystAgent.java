package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.agents.Guards;
import com.bank.rcp.common.Json;
import com.bank.rcp.core.ThirdPartyService;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/** A7 Third-Party Analyst: clause extraction with section citations, regime gap analysis, register update proposals. */
@Component
public class ThirdPartyAnalystAgent implements Agent {
    private static final Map<String, List<String>> CLAUSE_KEYWORDS = new LinkedHashMap<>();
    static {
        CLAUSE_KEYWORDS.put("audit_rights", List.of("audit"));
        CLAUSE_KEYWORDS.put("regulator_access", List.of("competent authorit", "regulator", "resolution authorit"));
        CLAUSE_KEYWORDS.put("subcontracting", List.of("subcontract", "subprocessor", "sub-processor"));
        CLAUSE_KEYWORDS.put("data_location", List.of("data location", "hosted", "region", "data centre", "london facilities", "location"));
        CLAUSE_KEYWORDS.put("incident_notification", List.of("incident", "notify", "interruption"));
        CLAUSE_KEYWORDS.put("termination_exit", List.of("terminat", "exit", "transition"));
        CLAUSE_KEYWORDS.put("service_levels", List.of("service level", "sla", "availability"));
        CLAUSE_KEYWORDS.put("security", List.of("security program", "security controls", "iso 27001", "information security", "industry-standard security"));
    }

    @Override public String id() { return "third-party-analyst"; }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("register.read", "contracts.read", "register.propose_update");
        String providerId = ctx.in("providerId", null);
        String contractId = ctx.in("contractId", null);
        Map<String, Object> provider = providerId == null ? null : ctx.toolMap("register.read", Map.of("providerId", providerId));
        String arrangementId = ctx.in("arrangementId", null);
        if (provider != null) {
            for (Object o : (List<Object>) provider.getOrDefault("arrangements", List.of())) {
                Map<String, Object> a = Json.toMap(o);
                if (contractId == null && a.get("contractId") != null) contractId = String.valueOf(a.get("contractId"));
                if (arrangementId == null) arrangementId = String.valueOf(a.get("id"));
            }
        }
        if (contractId == null) throw new IllegalArgumentException("No contract document on file for this provider");
        Map<String, Object> contract = ctx.toolMap("contracts.read", Map.of("contractId", contractId));
        List<Map<String, Object>> sections = Json.convert(contract.get("sections"), new com.fasterxml.jackson.core.type.TypeReference<List<Map<String, Object>>>() {});
        StringBuilder full = new StringBuilder();
        Set<String> refs = new HashSet<>();
        for (Map<String, Object> s : sections) { full.append(s.get("ref")).append(" ").append(s.get("heading")).append(": ").append(s.get("text")).append("\n"); refs.add(String.valueOf(s.get("ref"))); }
        List<String> injection = Guards.injectionSignals(full.toString());
        ctx.markUntrustedRead("contract " + contractId);
        if (!injection.isEmpty()) ctx.step("guardrail", "prompt-injection", "flagged", injection);

        Map<String, Object> extracted = new LinkedHashMap<>();
        List<String> redFlags = new ArrayList<>();
        for (var e : CLAUSE_KEYWORDS.entrySet()) {
            for (Map<String, Object> s : sections) {
                String hay = (s.get("heading") + " " + s.get("text")).toLowerCase();
                if (Guards.injectionSignals(hay).size() > 0) continue;
                if (e.getValue().stream().anyMatch(hay::contains)) {
                    String text = String.valueOf(s.get("text"));
                    double conf = 0.8;
                    String assessment = "present";
                    if (text.toLowerCase().contains("discretion") || text.toLowerCase().contains("may relocate")) { assessment = "weak - provider may change unilaterally"; conf = 0.6; redFlags.add(e.getKey() + " (" + s.get("ref") + "): " + assessment); }
                    extracted.put(e.getKey(), Json.map("section", s.get("ref"), "quote", text.length() > 180 ? text.substring(0, 180) + "..." : text, "assessment", assessment, "confidence", conf));
                    break;
                }
            }
        }
        final Map<String, Object> fallbackClauses = extracted;
        Map<String, Object> out = ctx.reason(
                "You are the Third-Party Analyst. Extract contract terms for: audit_rights, regulator_access, subcontracting, data_location, incident_notification, termination_exit, service_levels, security. "
                        + "Return JSON: clauses (object: clause -> {section, quote, assessment, confidence}), red_flags (array), needs_human_read (array), summary. "
                        + "Every clause MUST cite a section ref that exists in the document. Mark missing clauses as absent (omit them).",
                List.of(Block.meta("Provider", provider == null ? Json.map("contractId", contractId) : Json.map("id", provider.get("id"), "name", provider.get("name"), "flags", provider.get("regulatorFlags"))),
                        Block.meta("Section refs in document", refs), Block.untrusted("Contract text", full.toString())),
                () -> Json.map("clauses", fallbackClauses, "red_flags", redFlags, "needs_human_read", List.of(), "summary", fallbackClauses.size() + " of " + CLAUSE_KEYWORDS.size() + " clause types located"));

        // Guardrail: citations must exist; low-confidence -> needs human read.
        Map<String, Object> clauses = Json.toMap(out.get("clauses"));
        List<String> human = new ArrayList<>(Json.strList(out.get("needs_human_read")));
        Map<String, Object> verified = new LinkedHashMap<>();
        for (var e : clauses.entrySet()) {
            Map<String, Object> c = Json.toMap(e.getValue());
            String sec = Json.str(c, "section", "");
            if (!refs.contains(sec)) { human.add(e.getKey() + ": cited section '" + sec + "' not found - needs human read"); continue; }
            if (Json.dbl(c, "confidence", 0.8) < 0.7) human.add(e.getKey() + " (" + sec + "): low-confidence extraction - needs human read");
            verified.put(e.getKey(), c);
        }
        Map<String, Object> gaps = new LinkedHashMap<>();
        for (var r : ThirdPartyService.REQUIRED_CLAUSES.entrySet()) {
            List<String> missing = new ArrayList<>();
            for (String c : r.getValue()) if (!verified.containsKey(c)) missing.add(c);
            gaps.put(r.getKey(), missing);
        }
        out.put("clauses", verified);
        out.put("needs_human_read", human);
        out.put("gaps_by_regime", gaps);
        out.put("injection_suspected", !injection.isEmpty());
        if (!injection.isEmpty()) out.put("injection_signals", injection);

        if (arrangementId != null) {
            Map<String, Object> proposed = new LinkedHashMap<>();
            for (var e : verified.entrySet()) proposed.put(e.getKey(), Json.toMap(e.getValue()).get("section"));
            for (String c : ThirdPartyService.REQUIRED_CLAUSES.get("DORA Art. 30")) proposed.putIfAbsent(c, "missing");
            Object k = ctx.tool("register.propose_update", Json.map("arrangementId", arrangementId, "clauses", proposed, "gaps", gaps,
                    "rationale", "Clause extraction from " + contractId + " with section citations"));
            ctx.caseCreated(k);
            out.put("caseId", Json.toMap(k).get("caseId"));
        }
        out.put("contractId", contractId);
        return out;
    }
}

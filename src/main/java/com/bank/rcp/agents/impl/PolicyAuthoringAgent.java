package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * A3 Policy Authoring: approved requirement change -> declarative rule + Rego text + unit tests ->
 * sandbox test run -> pull request (case requiring policy engineer + compliance; ships in audit mode).
 */
@Component
public class PolicyAuthoringAgent implements Agent {
    private final DataStore store;

    public PolicyAuthoringAgent(DataStore store) { this.store = store; }

    @Override public String id() { return "policy-authoring"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("case.read", "policypack.read", "policypack.list_rules", "opa.test", "opa.eval", "policypack.open_pr");
        String impactCaseId = ctx.in("impactCaseId", null);
        Map<String, Object> impactCase = ctx.toolMap("case.read", Map.of("caseId", impactCaseId));
        String status = Json.str(impactCase, "status", "");
        if (!Set.of("approved", "executed").contains(status))
            return Json.map("refused", true, "answer", "Policy authoring only starts from an impact assessment ACCEPTED by the compliance owner (case " + impactCaseId + " is '" + status + "').");
        Map<String, Object> payload = Json.toMap(impactCase.get("payload"));
        Map<String, Object> impact = Json.toMap(payload.get("impact"));
        String itemId = Json.str(payload, "itemId", "");
        String jur = Json.strList(impact.get("jurisdictions")).stream().findFirst().orElse("EU");
        String packId = Map.of("EU", "eu", "IN", "in", "SG", "sg", "CN", "cn").getOrDefault(jur, "common");
        Map<String, Object> pack = ctx.toolMap("policypack.read", Map.of("packId", packId));
        List<Map<String, Object>> existing = ctx.toolList("policypack.list_rules", Json.map("jurisdiction", jur));

        Map<String, Object> template = template(jur, itemId, impact);
        Map<String, Object> out = ctx.reason(
                "You are the Policy Authoring agent. Draft ONE declarative residency rule for the policy engine plus unit tests. Return JSON with keys: "
                        + "rule {ruleId, pkg:'residency', description, requirementRef, sourceRef, severity, message, condition, rego}, tests (array of {name, input, expectViolation}), rationale. "
                        + "condition supports keys: dataClassIn (array), origin (EU|IN|SG|CN), segmentIn (array), copyPurpose, importantData (bool), minIndividuals (number), "
                        + "locationNotIn (a data path such as cn.mainland_regions or in.payment_storage_allowed, or an array of cloud:region), locationJurisdictionNot (EU|IN|SG|CN), missingField. "
                        + "Test input keys: id, location, locationJurisdiction, dataClass, origin, segment, copyPurpose, returnBy, importantData, individuals. "
                        + "Include at least one positive (expectViolation true) and one negative test. Thresholds must cite the source requirement id.",
                List.of(Block.meta("Accepted impact assessment", impact), Block.meta("Target pack", Json.map("id", pack.get("id"), "version", pack.get("version"))),
                        Block.meta("Existing rules", existing), Block.meta("Template suggestion", template), Block.meta("Policy data keys available", List.of("cn.mainland_regions", "in.payment_storage_allowed"))),
                () -> template);

        Map<String, Object> rule = Json.toMap(out.get("rule"));
        Object tests = out.get("tests");
        Map<String, Object> ci = ctx.toolMap("opa.test", Json.map("rule", rule, "tests", tests));
        if (!Json.bool(ci, "allPassed", false) && !"deterministic".equals(out.get("_reasoning"))) {
            ctx.step("guardrail", "sandbox-tests", "failed", "model-drafted rule failed sandbox tests " + ci.get("passed") + "/" + ci.get("total") + "; falling back to template draft");
            rule = Json.toMap(template.get("rule"));
            tests = template.get("tests");
            out.put("modelDraftRejected", ci);
        }
        rule.putIfAbsent("requirementRef", Json.strList(impact.get("affected_requirements")).stream().findFirst().orElse("REQ-NEW-" + itemId));
        rule.putIfAbsent("sourceRef", "Reg change item " + itemId);
        rule.put("pkg", "residency");
        Object pr = ctx.tool("policypack.open_pr", Json.map("packId", packId, "rule", rule, "tests", tests,
                "rationale", Json.str(out, "rationale", "Implements accepted impact " + impactCaseId), "impactCaseId", impactCaseId));
        ctx.caseCreated(pr);
        Map<String, Object> prm = Json.toMap(pr);
        return Json.map("packId", packId, "rule", rule, "tests", tests, "rationale", out.get("rationale"), "pullRequest", prm, "caseId", prm.get("caseId"),
                "note", "Two approvers required (policy engineer + compliance). Merged rules ship in AUDIT mode; promotion to enforce is a human decision.",
                "_reasoning", out.get("_reasoning"));
    }

    private Map<String, Object> template(String jur, String itemId, Map<String, Object> impact) {
        String req = Json.strList(impact.get("affected_requirements")).stream().findFirst().orElse("REQ-NEW-" + itemId);
        Map<String, Object> cond;
        String ruleId, desc, msg, posLoc, negLoc, posJur, negJur, cls;
        switch (jur) {
            case "CN" -> {
                ruleId = "CN-FDCG-001"; cls = "important_data_candidate";
                desc = "Financial data presumed important (level 4+ sector exposure) must remain in mainland China";
                cond = Json.map("origin", "CN", "dataClassIn", List.of("important_data_candidate"), "locationNotIn", "cn.mainland_regions");
                msg = "presumed important financial data {id} stored in {location} outside mainland China";
                posLoc = "aws:ap-east-1"; posJur = "HK"; negLoc = "sim:cn-beijing"; negJur = "CN";
            }
            case "SG" -> {
                ruleId = "SG-TPRM-001"; cls = "personal";
                desc = "SG customer information held by a third party outside Singapore requires prior notification to MAS (proposed TPRM)";
                cond = Json.map("origin", "SG", "dataClassIn", List.of("personal", "payment"), "locationJurisdictionNot", "SG");
                msg = "SG customer information {id} held outside Singapore at {location}; confirm MAS notification under proposed TPRM guidelines";
                posLoc = "gcp:us-central1"; posJur = "US"; negLoc = "aws:ap-southeast-1"; negJur = "SG";
            }
            case "IN" -> {
                ruleId = "IN-DPDP-ANL-001"; cls = "personal";
                desc = "Indian personal data analytics copies outside India must be registered for DPDP breach-reporting scope";
                cond = Json.map("origin", "IN", "dataClassIn", List.of("personal"), "copyPurpose", "analytics", "locationJurisdictionNot", "IN");
                msg = "IN personal data analytics copy {id} at {location} outside India";
                posLoc = "aws:eu-west-1"; posJur = "EU"; negLoc = "aws:ap-south-1"; negJur = "IN";
            }
            default -> {
                ruleId = "EU-DORA-LOC-001"; cls = "payment";
                desc = "EU payment/personal analytics copies outside the EU must be covered by a registered ICT arrangement with a data-location clause";
                cond = Json.map("origin", "EU", "dataClassIn", List.of("payment", "personal"), "copyPurpose", "analytics", "locationJurisdictionNot", "EU");
                msg = "EU analytics copy {id} at {location} outside the EU; confirm data-location clause in register";
                posLoc = "aws:us-east-1"; posJur = "US"; negLoc = "aws:eu-west-1"; negJur = "EU";
            }
        }
        String copy = Json.str(cond, "copyPurpose", "storage");
        Map<String, Object> rule = Json.map("ruleId", ruleId, "pkg", "residency", "description", desc, "requirementRef", req,
                "sourceRef", "Reg change item " + itemId, "severity", "high", "message", msg, "condition", cond,
                "rego", "violation contains msg if {\n\tinput.asset.tags.origin == \"" + jur + "\"\n\tinput.asset.tags.dataClass in " + Json.write(cond.get("dataClassIn")) + "\n\t# location condition: " + Json.write(cond.getOrDefault("locationNotIn", cond.get("locationJurisdictionNot"))) + "\n\tmsg := sprintf(\"" + ruleId + ": %s\", [input.asset.id])\n}");
        List<Map<String, Object>> tests = List.of(
                Json.map("name", "positive: out-of-scope location", "expectViolation", true,
                        "input", Json.map("id", "asset:test:pos", "origin", jur, "dataClass", cls, "location", posLoc, "locationJurisdiction", posJur, "copyPurpose", copy, "importantData", "CN".equals(jur))),
                Json.map("name", "negative: permitted location", "expectViolation", false,
                        "input", Json.map("id", "asset:test:neg", "origin", jur, "dataClass", cls, "location", negLoc, "locationJurisdiction", negJur, "copyPurpose", copy)),
                Json.map("name", "negative: other origin", "expectViolation", false,
                        "input", Json.map("id", "asset:test:neg2", "origin", "XX", "dataClass", cls, "location", posLoc, "locationJurisdiction", posJur, "copyPurpose", copy)),
                Json.map("name", "edge: public data not in scope", "expectViolation", false,
                        "input", Json.map("id", "asset:test:edge", "origin", jur, "dataClass", "public", "location", posLoc, "locationJurisdiction", posJur, "copyPurpose", copy)));
        return Json.map("rule", rule, "tests", tests, "rationale", "Implements '" + desc + "' derived from accepted impact on " + itemId + " (requirement " + req + ").");
    }
}

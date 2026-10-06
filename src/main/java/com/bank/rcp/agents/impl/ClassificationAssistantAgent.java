package com.bank.rcp.agents.impl;

import com.bank.rcp.agents.Agent;
import com.bank.rcp.agents.AgentContext;
import com.bank.rcp.common.Json;
import com.bank.rcp.core.CatalogService;
import com.bank.rcp.platform.ModelGateway.Block;
import org.springframework.stereotype.Component;

import java.util.*;

/** A4 Classification Assistant: resolves low-confidence classifications (L2; L3 only for non-regulated, >= 0.95). */
@Component
public class ClassificationAssistantAgent implements Agent {
    private static final List<String> RESTRICTIVENESS = List.of("public", "internal", "trade", "personal", "payment", "important_data_candidate", "sensitive_personal", "health");

    private final CatalogService catalog;

    public ClassificationAssistantAgent(CatalogService catalog) { this.catalog = catalog; }

    @Override public String id() { return "classification-assistant"; }

    @Override
    public Map<String, Object> execute(AgentContext ctx) {
        ctx.plan("catalog.read", "classification.sample_masked", "lineage.read", "catalog.request_retag", "catalog.apply_label_low_risk");
        String assetId = ctx.in("assetId", null);
        Map<String, Object> asset = ctx.toolMap("catalog.read", Map.of("assetId", assetId));
        Map<String, Object> sample = ctx.toolMap("classification.sample_masked", Map.of("assetId", assetId));
        List<Map<String, Object>> lineage = ctx.toolList("lineage.read", Map.of("assetId", assetId));
        Map<String, String> upstream = new LinkedHashMap<>();
        for (Map<String, Object> e : lineage) {
            if (!assetId.equals(e.get("to"))) continue;
            var o = ctx.tryTool("catalog.read", Map.of("assetId", String.valueOf(e.get("from"))));
            if (o.ok()) upstream.put(String.valueOf(e.get("from")), String.valueOf(Json.toMap(Json.toMap(o.result()).get("tags")).get("dataClass")));
        }
        Map<String, Object> det = catalog.detect(catalog.require(assetId)); // deterministic recognisers (classification-service)
        String proposed = String.valueOf(det.get("proposedClass"));
        double conf = Json.dbl(det, "confidence", 0.5);
        String inherited = upstream.values().stream().filter(c -> !"null".equals(c)).max(Comparator.comparingInt(RESTRICTIVENESS::indexOf)).orElse(null);
        List<String> signals = new ArrayList<>(Json.strList(det.get("detectors")));
        if (inherited != null) {
            signals.add("lineage: derived from " + upstream);
            if (RESTRICTIVENESS.indexOf(inherited) > RESTRICTIVENESS.indexOf(proposed)) { proposed = inherited; conf = Math.max(conf, 0.85); }
            else if (inherited.equals(proposed)) conf = Math.min(0.97, conf + 0.15);
        }
        final String fp = proposed;
        final double fc = Math.round(conf * 100) / 100.0;
        Map<String, Object> out = ctx.reason(
                "You are the Classification Assistant. Using column names, MASKED samples and lineage, propose a data class. "
                        + "Return JSON: proposed_data_class (public|internal|personal|sensitive_personal|payment|trade|health|important_data_candidate), confidence (0-1), rationale, signals (array). "
                        + "When unsure, prefer the more restrictive class (unknown = restricted).",
                List.of(Block.meta("Asset", asset), Block.masked("Masked samples", sample), Block.meta("Upstream lineage classes", upstream), Block.meta("Recogniser output", det)),
                () -> Json.map("proposed_data_class", fp, "confidence", fc, "rationale",
                        "Recognisers matched " + det.get("scores") + (upstream.isEmpty() ? "" : "; upstream assets are classified " + upstream.values()), "signals", signals));

        // Guardrails: valid enum, and never less restrictive than deterministic evidence.
        String llmClass = Json.str(out, "proposed_data_class", fp);
        if (!RESTRICTIVENESS.contains(llmClass)) llmClass = fp;
        if (RESTRICTIVENESS.indexOf(llmClass) < RESTRICTIVENESS.indexOf(fp) && CatalogService.REGULATED.contains(fp)) {
            ctx.step("guardrail", "conservative-default", "corrected", "model proposed " + llmClass + " but recognisers/lineage indicate " + fp + "; keeping the more restrictive class");
            llmClass = fp;
        }
        double c = Math.max(0, Math.min(1, Json.dbl(out, "confidence", fc)));
        out.put("proposed_data_class", llmClass);
        out.put("confidence", c);

        String current = Json.str(Json.toMap(asset.get("tags")), "dataClass", null);
        boolean l3 = c >= CatalogService.L3_THRESHOLD && !CatalogService.REGULATED.contains(llmClass) && current != null && !CatalogService.REGULATED.contains(current);
        if (l3) {
            ctx.tool("catalog.apply_label_low_risk", Json.map("assetId", assetId, "proposedDataClass", llmClass, "confidence", c));
            out.put("action", "L3 applied (non-regulated, confidence >= 0.95); sampled weekly by Assurance");
        } else {
            Object k = ctx.tool("catalog.request_retag", Json.map("assetId", assetId, "proposedDataClass", llmClass, "rationale", String.valueOf(out.get("rationale")),
                    "evidenceRefs", List.of("recognisers:" + det.get("scores"), "lineage:" + upstream.keySet()), "confidence", c));
            ctx.caseCreated(k);
            out.put("action", "L2 re-tag request created for data owner approval");
            out.put("caseId", Json.toMap(k).get("caseId"));
        }
        return out;
    }
}

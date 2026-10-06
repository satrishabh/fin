package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * residency-scanner: joins (simulated) cloud inventories with catalogue tags and evaluates
 * residency policy. Adapters for AWS Config / Azure Resource Graph / GCP Asset Inventory would
 * replace {@link #cloudInventory}.
 */
@Service
public class ResidencyScanner {
    private final DataStore store;
    private final PolicyEngine pdp;
    private final EventBus bus;
    private final EvidenceService evidence;
    private final Map<String, Map<String, Object>> violations = new ConcurrentHashMap<>();
    private volatile Map<String, Object> lastScan = Map.of();

    public ResidencyScanner(DataStore store, PolicyEngine pdp, EventBus bus, EvidenceService evidence) {
        this.store = store;
        this.pdp = pdp;
        this.bus = bus;
        this.evidence = evidence;
    }

    public synchronized Map<String, Object> scan(String cell, String user) {
        long t0 = System.currentTimeMillis();
        int scanned = 0, conformant = 0, newV = 0;
        Set<String> stillViolating = new HashSet<>();
        for (DataAsset a : store.assets.values()) {
            if (cell != null && !cell.isBlank() && !cell.equalsIgnoreCase(a.cell)) continue;
            scanned++;
            Decision d = pdp.evaluateResidency(a, false);
            if (d.allow) { conformant++; continue; }
            String key = a.id;
            stillViolating.add(key);
            Map<String, Object> v = violations.get(key);
            boolean fresh = v == null || !"open".equals(v.get("status"));
            if (fresh) {
                v = new LinkedHashMap<>();
                v.put("id", Ids.next("viol"));
                v.put("detectedAt", Ids.now());
                v.put("status", "open");
                newV++;
            }
            v.put("assetId", a.id);
            v.put("assetName", a.name);
            v.put("cell", a.cell);
            v.put("segment", a.segment);
            v.put("dataClass", a.tags.dataClass);
            v.put("originJurisdiction", a.tags.originJurisdiction);
            v.put("observedLocation", a.location);
            v.put("ruleIds", d.ruleIds);
            v.put("reasons", d.reasons);
            v.put("mode", d.mode);
            v.put("decisionId", d.decisionId);
            v.put("policyBundleVersion", d.bundleVersion);
            v.put("lastSeen", Ids.now());
            v.put("quarantined", a.quarantined);
            violations.put(key, v);
            if (fresh) {
                bus.publish("residency.violations", "residency.violation.detected", a.cell, "residency-scanner", a.id,
                        Json.map("violationId", v.get("id"), "assetId", a.id, "dataClass", a.tags.dataClass, "originJurisdiction", a.tags.originJurisdiction,
                                "observedLocation", a.location, "ruleIds", d.ruleIds, "decision", "deny", "mode", d.mode, "policyBundleVersion", d.bundleVersion));
            }
        }
        for (Map<String, Object> v : violations.values()) {
            boolean inScope = cell == null || cell.isBlank() || cell.equalsIgnoreCase(String.valueOf(v.get("cell")));
            if (inScope && "open".equals(v.get("status")) && !stillViolating.contains(String.valueOf(v.get("assetId")))) {
                v.put("status", "resolved");
                v.put("resolvedAt", Ids.now());
            }
        }
        Map<String, Object> res = Json.map("cell", cell == null || cell.isBlank() ? "ALL" : cell, "scanned", scanned, "conformant", conformant,
                "violations", scanned - conformant, "newViolations", newV, "durationMs", System.currentTimeMillis() - t0, "completedAt", Ids.now(), "by", user,
                "conformancePct", scanned == 0 ? 100.0 : Math.round(1000.0 * conformant / scanned) / 10.0);
        lastScan = res;
        bus.publish("residency.violations", "residency.scan.completed", cell, "residency-scanner", "scan", res);
        evidence.record(cell == null || cell.isBlank() ? "GLOBAL" : cell, "residency.scan.completed", "scan", res, "EV-7Y");
        return res;
    }

    public List<Map<String, Object>> list(String cell, String status) {
        List<Map<String, Object>> l = new ArrayList<>(violations.values());
        l.removeIf(v -> (cell != null && !cell.isBlank() && !cell.equalsIgnoreCase(String.valueOf(v.get("cell"))))
                || (status != null && !status.isBlank() && !status.equals(v.get("status"))));
        l.sort(Comparator.comparing(v -> String.valueOf(v.get("assetId"))));
        return l;
    }

    public Optional<Map<String, Object>> get(String idOrAsset) {
        for (Map<String, Object> v : violations.values())
            if (idOrAsset.equals(v.get("id")) || idOrAsset.equals(v.get("assetId"))) return Optional.of(v);
        return Optional.empty();
    }

    public Map<String, Object> lastScan() { return lastScan; }

    /** Simulated cloud inventory record for an asset (in-cell only). */
    public Map<String, Object> cloudInventory(String assetId) {
        DataAsset a = store.assets.get(assetId);
        if (a == null) throw new NoSuchElementException(assetId);
        String region = a.location.contains(":") ? a.location.split(":")[1] : a.location;
        String kmsRegion = store.controls.values().stream()
                .filter(c -> c.resource.equals(assetId) && c.control.toLowerCase().contains("key") && "failed".equals(c.status))
                .findFirst().map(c -> "out-of-cell (" + c.detail + ")").orElse("in-region");
        return Json.map("assetId", a.id, "provider", store.region(a.location).get("cloud"), "region", region, "resourceUri", a.resourceUri,
                "createdBy", a.createdBy, "replicationOf", a.replicationOf, "copyPurpose", a.copyPurpose, "kmsKey", kmsRegion,
                "tagsOnResource", Json.map("rcp:asset", a.id, "rcp:owner", a.owner), "source", "simulated-inventory");
    }

    /** Read-only IaC view (Terraform state excerpt, synthetic). */
    public Map<String, Object> iac(String assetId) {
        DataAsset a = store.assets.get(assetId);
        if (a == null) throw new NoSuchElementException(assetId);
        String region = a.location.contains(":") ? a.location.split(":")[1] : a.location;
        String snippet;
        if (a.iacRef == null || a.iacRef.startsWith("(")) snippet = "# resource not managed by Terraform: " + a.createdBy;
        else if (a.createdBy != null && a.createdBy.startsWith("replication-rule"))
            snippet = "resource \"aws_s3_bucket_replication_configuration\" \"archive\" {\n  rule {\n    status = \"Enabled\"\n    destination { bucket = \"arn:aws:s3:::" + a.resourceUri.replace("s3://", "").split("/")[0] + "\"  # region " + region + "\n    }\n  }\n}";
        else if (a.createdBy != null && a.createdBy.startsWith("backup-policy"))
            snippet = "resource \"aws_backup_plan\" \"dr\" {\n  rule { copy_action { destination_vault_arn = \"...:" + region + ":...\" } }\n}";
        else snippet = "resource \"storage\" \"" + a.name.replaceAll("\\W+", "_").toLowerCase() + "\" {\n  region = \"" + region + "\"\n  # created by " + a.createdBy + "\n}";
        return Json.map("assetId", a.id, "iacRef", a.iacRef, "excerpt", snippet, "source", "terraform-state (read-only, synthetic)");
    }
}

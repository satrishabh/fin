package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.PolicyPack;
import com.bank.rcp.model.Models.PolicyRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * policy-pack-service: versioning, signing and distribution of jurisdiction packs, audit to
 * enforce promotion, and merging of approved policy PRs. Only humans reach the mutating
 * methods (via case approvals or the policy UI); agents only get read tools and a sandbox PR tool.
 */
@Service
public class PolicyPackService {
    private final DataStore store;
    private final EventBus bus;
    private final EvidenceService evidence;
    private final byte[] signingKey;

    public PolicyPackService(DataStore store, EventBus bus, EvidenceService evidence,
                             @Value("${rcp.policy.signing-key:rcp-poc-bundle-signing-key-change-me}") String key) {
        this.store = store;
        this.bus = bus;
        this.evidence = evidence;
        this.signingKey = key.getBytes(StandardCharsets.UTF_8);
        signAll();
    }

    public void signAll() {
        for (PolicyPack p : store.packs.values()) {
            if (p.publishedAt == null) p.publishedAt = Ids.now();
            p.signature = sign(p);
        }
    }

    String sign(PolicyPack p) {
        String sig = p.signature;
        p.signature = null;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return "hmac-sha256:" + HexFormat.of().formatHex(mac.doFinal(Json.canonical(p).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        } finally {
            p.signature = sig;
        }
    }

    /** PDPs refuse unsigned or tampered bundles. */
    public boolean verified(PolicyPack p) {
        return p != null && p.signature != null && p.signature.equals(sign(p));
    }

    public List<PolicyPack> list() {
        return DataStore.sorted(store.packs, Comparator.comparing(p -> p.id));
    }

    public Optional<PolicyPack> get(String id) { return Optional.ofNullable(store.packs.get(id)); }

    public List<Map<String, Object>> listRules(String jurisdiction, String pkg) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (PolicyPack p : list()) {
            if (jurisdiction != null && !jurisdiction.isBlank() && !p.jurisdiction.equalsIgnoreCase(jurisdiction) && !p.id.equalsIgnoreCase(jurisdiction)) continue;
            for (PolicyRule r : p.rules) {
                if (pkg != null && !pkg.isBlank() && !pkg.equalsIgnoreCase(r.pkg)) continue;
                out.add(Json.map("pack", p.id, "bundleVersion", p.version, "ruleId", r.ruleId, "pkg", r.pkg,
                        "mode", effectiveMode(p, r), "description", r.description, "requirementRef", r.requirementRef,
                        "sourceRef", r.sourceRef, "builtin", r.builtin));
            }
        }
        return out;
    }

    public String effectiveMode(PolicyPack p, PolicyRule r) {
        return r.mode != null && !r.mode.isBlank() ? r.mode : p.mode;
    }

    public PolicyPack changeMode(String packId, String mode, String user) {
        if (!Set.of("audit", "enforce").contains(mode)) throw new IllegalArgumentException("mode must be audit|enforce");
        PolicyPack p = store.packs.get(packId);
        if (p == null) throw new NoSuchElementException("pack " + packId);
        String old = p.mode;
        p.mode = mode;
        bump(p);
        evidence.record("GLOBAL", "policy.mode.changed", "pack:" + p.id,
                Json.map("pack", p.id, "from", old, "to", mode, "by", user, "bundleVersion", p.version), "EV-7Y");
        bus.publish("platform.events", "policy.mode.changed", null, "policy-pack-service", "pack:" + p.id,
                Json.map("pack", p.id, "from", old, "to", mode, "by", user, "bundleVersion", p.version));
        return p;
    }

    public PolicyPack setRuleMode(String packId, String ruleId, String mode, String user) {
        PolicyPack p = store.packs.get(packId);
        if (p == null) throw new NoSuchElementException("pack " + packId);
        PolicyRule r = p.rules.stream().filter(x -> x.ruleId.equals(ruleId)).findFirst().orElseThrow();
        r.mode = mode;
        bump(p);
        evidence.record("GLOBAL", "policy.rule.mode.changed", "rule:" + ruleId, Json.map("pack", packId, "rule", ruleId, "mode", mode, "by", user, "bundleVersion", p.version), "EV-7Y");
        return p;
    }

    /** Merge of an approved policy PR. New rules always ship in audit mode (principle P8). */
    public PolicyPack mergeRule(String packId, PolicyRule rule, String mergedBy) {
        PolicyPack p = store.packs.get(packId);
        if (p == null) throw new NoSuchElementException("pack " + packId);
        if (rule.requirementRef == null || rule.sourceRef == null) throw new IllegalArgumentException("Provenance gate: requirementRef and sourceRef are mandatory");
        rule.builtin = false;
        rule.mode = "audit";
        p.rules.removeIf(r -> r.ruleId.equals(rule.ruleId));
        p.rules.add(rule);
        bump(p);
        evidence.record("GLOBAL", "policy.bundle.published", "pack:" + p.id,
                Json.map("pack", p.id, "bundleVersion", p.version, "ruleAdded", rule.ruleId, "mergedBy", mergedBy, "signature", p.signature), "EV-7Y");
        bus.publish("platform.events", "policy.bundle.published", null, "policy-pack-service", "pack:" + p.id,
                Json.map("pack", p.id, "bundleVersion", p.version, "ruleAdded", rule.ruleId, "mode", "audit"));
        return p;
    }

    private void bump(PolicyPack p) {
        String v = p.version;
        int dot = v.lastIndexOf('.');
        try {
            p.version = v.substring(0, dot + 1) + (Integer.parseInt(v.substring(dot + 1)) + 1);
        } catch (Exception e) {
            p.version = v + ".1";
        }
        p.publishedAt = Ids.now();
        p.signature = sign(p);
    }
}

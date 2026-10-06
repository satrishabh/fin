package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

/** retention-service + disclosure-workflow. */
@Service
public class PrivacyOpsService {
    private final DataStore store;
    private final PolicyEngine pdp;
    private final EventBus bus;
    private final EvidenceService evidence;
    private final CaseService cases;

    public PrivacyOpsService(DataStore store, PolicyEngine pdp, EventBus bus, EvidenceService evidence, CaseService cases) {
        this.store = store;
        this.pdp = pdp;
        this.bus = bus;
        this.evidence = evidence;
        this.cases = cases;
        cases.onApproved("disclosure_request", this::onDisclosureApproved);
    }

    // ------------------------------------------------------------ erasure vs retention

    public ErasureRequest erasure(Map<String, Object> in, String user) {
        ErasureRequest r = new ErasureRequest();
        r.id = Ids.next("era");
        r.subjectRef = Json.str(in, "subjectRef", "subject-" + Ids.uuid().substring(0, 8));
        r.assetIds = Json.strList(in.get("assetIds"));
        long age = Json.num(in, "recordAgeYears", 1);
        r.requestedBy = user;
        r.ts = Ids.now();
        boolean anyDeferred = false;
        for (String id : r.assetIds) {
            DataAsset a = store.assets.get(id);
            if (a == null) { r.perAsset.add(Json.map("assetId", id, "result", "unknown asset")); continue; }
            r.cell = a.cell;
            Decision d = pdp.evaluateErasure(a, age);
            String result = d.allow ? "erase_scheduled" : "deferred";
            if (!d.allow) anyDeferred = true;
            r.perAsset.add(Json.map("assetId", id, "result", result, "decisionId", d.decisionId, "reasons", d.reasons, "retentionClass", a.tags.retentionClass));
            if (!d.allow) bus.publish("platform.events", "erasure.deferred", a.cell, "retention-service", a.id, Json.map("erasureId", r.id, "assetId", id, "ruleIds", d.ruleIds));
        }
        r.status = anyDeferred ? "partially_deferred" : "scheduled";
        store.erasures.put(r.id, r);
        evidence.record(r.cell, "erasure.evaluated", r.id, Json.map("erasureId", r.id, "subjectRef", r.subjectRef, "status", r.status, "perAsset", r.perAsset), "PD-MIN");
        if (anyDeferred) bus.publish("platform.events", "retention.conflict", r.cell, "retention-service", r.id, Json.map("erasureId", r.id, "status", r.status));
        return r;
    }

    // ------------------------------------------------------------ disclosure (DSL Art. 36 gate)

    public DisclosureRequest disclosure(Map<String, Object> in, String user) {
        DisclosureRequest r = new DisclosureRequest();
        r.id = Ids.next("dsc");
        r.requestingAuthority = Json.str(in, "requestingAuthority", "Foreign authority");
        r.authorityCountry = Json.str(in, "authorityCountry", "US");
        r.dataCell = Json.str(in, "dataCell", "CN");
        r.assetIds = Json.strList(in.get("assetIds"));
        r.purpose = Json.str(in, "purpose", "regulatory inquiry");
        r.requestedBy = user;
        r.ts = Ids.now();
        Decision d = pdp.evaluateDisclosure(r, false);
        r.gates = new ArrayList<>(d.reasons);
        if (d.allow) {
            r.status = "awaiting_legal_review";
        } else {
            r.status = "awaiting_competent_authority";
        }
        CaseItem c = cases.create("disclosure_request", "Disclosure to " + r.requestingAuthority + " (" + r.dataCell + " data)", user, user, r.dataCell,
                Json.map("disclosureId", r.id, "gates", r.gates, "decisionId", d.decisionId, "workaroundPattern", d.allow ? null : "WP-07"), null, false);
        r.caseId = c.id;
        store.disclosures.put(r.id, r);
        evidence.record(r.dataCell, "disclosure.requested", r.id, Json.map("disclosureId", r.id, "authority", r.requestingAuthority, "status", r.status, "decisionId", d.decisionId), "EV-7Y");
        bus.publish("platform.events", "disclosure.requested", r.dataCell, "disclosure-workflow", r.id, Json.map("disclosureId", r.id, "status", r.status));
        return r;
    }

    /** Legal records the competent-authority approval; only then does the PDP allow export. */
    private void onDisclosureApproved(CaseItem c) {
        DisclosureRequest r = store.disclosures.get(Json.str(c.payload, "disclosureId", ""));
        if (r == null) return;
        Decision d = pdp.evaluateDisclosure(r, true);
        r.gates.add("Legal approval recorded in " + c.id + "; re-evaluated: " + d.reasons);
        r.status = d.allow ? "approved" : "refused";
        evidence.record(r.dataCell, "disclosure." + r.status, r.id, Json.map("disclosureId", r.id, "decisionId", d.decisionId, "caseId", c.id), "EV-7Y");
        c.resolution = "Disclosure " + r.status + " after competent-authority approval was recorded";
    }
}

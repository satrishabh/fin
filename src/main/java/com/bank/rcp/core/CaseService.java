package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * case-service: human task and approval workflows with four-eyes and segregation of duties.
 * Deterministic side effects (apply re-tag, record mechanism, merge policy PR, ...) are
 * registered by the owning services and run only after the final human approval.
 */
@Service
public class CaseService {

    /** Default approver roles per case type. */
    public static final Map<String, List<String>> ROLES = Map.ofEntries(
            Map.entry("retag_request", List.of("data_owner")),
            Map.entry("quarantine_request", List.of("data_owner")),
            Map.entry("investigation_report", List.of("compliance")),
            Map.entry("impact_assessment", List.of("compliance")),
            Map.entry("policy_pr", List.of("policy_engineer", "compliance")),
            Map.entry("transfer_assessment", List.of("privacy")),
            Map.entry("workaround_proposal", List.of("legal", "business_owner")),
            Map.entry("register_update", List.of("vendor_risk")),
            Map.entry("incident_classification", List.of("incident_manager", "dpo")),
            Map.entry("notice_draft", List.of("dpo")),
            Map.entry("evidence_pack_release", List.of("compliance")),
            Map.entry("mechanism_record", List.of("legal")),
            Map.entry("erasure_conflict", List.of("dpo")),
            Map.entry("disclosure_request", List.of("legal")),
            Map.entry("assurance_finding", List.of("compliance")),
            Map.entry("report_narrative", List.of("compliance")));

    private final DataStore store;
    private final EventBus bus;
    private final EvidenceService evidence;
    private final Map<String, Consumer<CaseItem>> onApproved = new ConcurrentHashMap<>();

    public CaseService(DataStore store, EventBus bus, EvidenceService evidence) {
        this.store = store;
        this.bus = bus;
        this.evidence = evidence;
    }

    public void onApproved(String type, Consumer<CaseItem> handler) { onApproved.put(type, handler); }

    public CaseItem create(String type, String title, String createdBy, String triggeredBy, String cell,
                           Map<String, Object> payload, String agentRunId, boolean draft) {
        CaseItem c = new CaseItem();
        c.id = Ids.next("case");
        c.type = type;
        c.title = title;
        c.createdBy = createdBy;
        c.triggeredBy = triggeredBy;
        c.cell = cell;
        c.payload = payload == null ? new LinkedHashMap<>() : payload;
        c.requiredRoles = new ArrayList<>(ROLES.getOrDefault(type, List.of("compliance")));
        c.requiredApprovals = c.requiredRoles.size();
        c.agentRunId = agentRunId;
        c.status = draft ? "draft" : "open";
        c.createdAt = Ids.now();
        store.cases.put(c.id, c);
        bus.publish("case.events", "case.created", cell, "case-service", c.id,
                Json.map("caseId", c.id, "type", type, "title", title, "createdBy", createdBy, "status", c.status));
        return c;
    }

    /** Agents create drafts; a human submits them into the approval queue. */
    public CaseItem submit(String caseId, String user) {
        CaseItem c = require(caseId);
        if (!"draft".equals(c.status)) throw new IllegalStateException("Only drafts can be submitted");
        c.status = "open";
        bus.publish("case.events", "case.submitted", c.cell, "case-service", c.id, Json.map("caseId", c.id, "by", user));
        return c;
    }

    public synchronized CaseItem decide(String caseId, String userId, String decision, String comment) {
        CaseItem c = require(caseId);
        User u = store.users.get(userId);
        if (u == null) throw new SecurityException("Unknown user " + userId);
        if (!Set.of("open", "draft").contains(c.status)) throw new IllegalStateException("Case is " + c.status);
        if (userId.equals(c.triggeredBy) || userId.equals(c.createdBy))
            throw new SecurityException("Segregation of duties: " + userId + " triggered or created this case and cannot approve it");
        if (c.approvals.stream().anyMatch(a -> a.by.equals(userId)))
            throw new SecurityException("Four-eyes: " + userId + " has already decided on this case");
        Set<String> satisfied = new HashSet<>();
        for (Approval a : c.approvals) if ("approve".equals(a.decision)) satisfied.add(a.role);
        String role = c.requiredRoles.stream().filter(r -> !satisfied.contains(r) && u.roles.contains(r)).findFirst()
                .orElseThrow(() -> new SecurityException("User " + userId + " holds none of the outstanding roles " + outstanding(c, satisfied)));

        Approval a = new Approval();
        a.by = userId;
        a.role = role;
        a.decision = "deny".equals(decision) ? "deny" : "approve";
        a.comment = comment;
        a.ts = Ids.now();
        c.approvals.add(a);

        if ("deny".equals(a.decision)) {
            c.status = "denied";
            c.resolution = "Denied by " + userId + " (" + role + "): " + (comment == null ? "" : comment);
        } else {
            satisfied.add(role);
            if (satisfied.containsAll(c.requiredRoles)) {
                c.status = "approved";
                Consumer<CaseItem> h = onApproved.get(c.type);
                if (h != null) {
                    try {
                        h.accept(c);
                        if (c.resolution == null) c.resolution = "Approved and executed by deterministic service";
                        c.status = "executed";
                    } catch (Exception ex) {
                        c.resolution = "Approved, but execution failed: " + ex.getMessage();
                    }
                } else c.resolution = "Approved";
            }
        }
        EvidenceRecord ev = evidence.record(c.cell, "approval." + a.decision, c.id,
                Json.map("caseId", c.id, "type", c.type, "by", userId, "role", role, "comment", comment, "status", c.status, "payloadHash", Json.hashOf(c.payload)), "EV-7Y");
        c.evidenceIds.add(ev.id);
        bus.publish("case.events", "approval." + (a.decision.equals("approve") ? "granted" : "denied"), c.cell, "case-service", c.id,
                Json.map("caseId", c.id, "by", userId, "role", role, "status", c.status));
        return c;
    }

    private List<String> outstanding(CaseItem c, Set<String> satisfied) {
        List<String> r = new ArrayList<>(c.requiredRoles);
        r.removeAll(satisfied);
        return r;
    }

    public CaseItem require(String id) {
        CaseItem c = store.cases.get(id);
        if (c == null) throw new NoSuchElementException("case " + id);
        return c;
    }

    public List<CaseItem> list(String status, String type) {
        List<CaseItem> l = new ArrayList<>(store.cases.values());
        l.removeIf(c -> (status != null && !status.isBlank() && !status.equals(c.status)) || (type != null && !type.isBlank() && !type.equals(c.type)));
        l.sort(Comparator.comparing((CaseItem c) -> c.createdAt).reversed());
        return l;
    }
}

package com.bank.rcp.model;

import java.util.*;

/**
 * Core data model (spec Section 11). Plain public-field classes keep the POC terse and
 * serialise identically with Jackson 2 (hashing, seed files) and Jackson 3 (Spring MVC).
 */
public final class Models {
    private Models() {}

    // ---------------------------------------------------------------- data & classification

    public static class DataAsset {
        public String id;
        public String name;
        public String cell;              // EU | IN | SG | CN
        public String location;          // cloud:region, e.g. aws:ap-south-1
        public String kind;              // bucket | table | topic | dataset
        public String resourceUri;
        public String owner;
        public String legalEntity;
        public String segment;           // markets | wealth | payments | insurance
        public Tags tags = new Tags();
        public List<Column> columns = new ArrayList<>();
        public String copyPurpose;       // storage | analytics | backup | foreign_processing | dr
        public String returnBy;          // for foreign-processed copies
        public String replicationOf;
        public String createdBy;         // pipeline / IaC module that created it
        public String iacRef;
        public long recordCount;
        public long individuals;
        public boolean quarantined;
        public String registeredAt;
    }

    public static class Tags {
        public String dataClass;          // public | internal | personal | sensitive_personal | payment | trade | health | important_data_candidate
        public String originJurisdiction; // EU | IN | SG | CN
        public String sensitivity;        // low | medium | high
        public boolean importantDataFlag;
        public String retentionClass;     // BR-6Y | EV-7Y | OPS-1Y | PD-MIN | TRADE-7Y | PAY-10Y
        public double confidence;
        public String source;             // rule | ml | human | agent-l3
    }

    public static class Column {
        public String name;
        public String type;
        public String sample;             // always masked
        public String detectedClass;
    }

    public static class LineageEdge {
        public String from;
        public String to;
        public String job;
    }

    public static class ClassificationResult {
        public String id;
        public String assetId;
        public String cell;
        public String proposedClass;
        public double confidence;
        public String status;             // auto_applied | low_confidence | resolved | pending_approval
        public List<String> detectors = new ArrayList<>();
        public String ts;
    }

    // ---------------------------------------------------------------- policy

    public static class PolicyPack {
        public String id;                 // eu | in | sg | cn | common
        public String jurisdiction;
        public String version;
        public String mode;               // audit | enforce
        public String signature;
        public String publishedAt;
        public List<PolicyRule> rules = new ArrayList<>();
    }

    public static class PolicyRule {
        public String ruleId;
        public String pkg;                // residency | transfer | retention | disclosure
        public String description;
        public String requirementRef;
        public String sourceRef;
        public String severity = "high";
        public String mode;               // optional per-rule override (new rules ship in audit)
        public boolean builtin = true;
        /** Declarative condition for agent-authored rules: {"dataClassIn":[..],"origin":"IN","locationNotIn":"in.payment_storage_allowed", "copyPurpose":"..", "missingField":"returnBy"} */
        public Map<String, Object> condition;
        public String message;
        public String rego;               // Rego v1 text kept with the rule for OPA portability
    }

    public static class Decision {
        public String decisionId;
        public String pkg;
        public boolean allow;             // policy result
        public boolean effectiveAllow;    // after audit/enforce mode
        public String mode;
        public List<String> reasons = new ArrayList<>();
        public List<String> ruleIds = new ArrayList<>();
        public String bundleVersion;
        public String inputHash;
        public String timestamp;
        public Map<String, Object> extra = new LinkedHashMap<>();
    }

    public static class Mechanism {
        public String id;
        public String type;               // SCC | adequacy | IGA | BCR | CN-SC-filing | CN-certification | CN-assessment | MAS-outsourcing-assessment | exemption
        public String exporter;
        public String originJurisdiction;
        public List<String> destinations = new ArrayList<>(); // ISO country codes
        public List<String> purposes = new ArrayList<>();
        public List<String> dataClasses = new ArrayList<>();
        public String route;              // CN routes
        public String validFrom;
        public String validTo;
        public String filingRef;
        public String status = "active";  // active | revoked
        public String recordedBy;
    }

    // ---------------------------------------------------------------- transfer

    public static class Transfer {
        public String id;
        public String originCell;
        public String destinationCountry;
        public String destinationCell;
        public String exporter;
        public String purpose;
        public List<String> dataClasses = new ArrayList<>();
        public long recordCount;
        public long individualsNonsensitive;
        public long individualsSensitive;
        public boolean containsImportantData;
        public String transform;          // WP-xx or null
        public Integer kMin;
        public Integer rowCap;
        public String returnBy;
        public String mechanismId;
        public String status;             // requested | approved | blocked | completed
        public String decisionId;
        public List<String> reasons = new ArrayList<>();
        public String requestedBy;
        public String ts;
        public String evidenceId;
    }

    public static class Counter {
        public String exporter;
        public int year;
        public long nonsensitive;
        public long sensitive;
        public boolean reconciledExact;
        public String lastReconciled;
    }

    // ---------------------------------------------------------------- third party

    public static class ThirdParty {
        public String id;
        public String name;
        public String lei;
        public String country;
        public String type;               // cloud | saas | model_provider | data | network
        public List<String> regulatorFlags = new ArrayList<>(); // CTPP | MSP | material_outsourcing
        public List<ServiceArrangement> arrangements = new ArrayList<>();
    }

    public static class ServiceArrangement {
        public String id;
        public String service;
        public String ictServiceType;
        public String criticalFunction;   // name of supported critical/important function or null
        public boolean critical;
        public List<String> dataLocations = new ArrayList<>();
        public List<String> processingLocations = new ArrayList<>();
        public List<Subcontractor> subcontractors = new ArrayList<>();
        public String exitPlanRef;
        public String contractId;
        public Map<String, String> clauses = new LinkedHashMap<>(); // clause -> section ref | "missing"
        public String startDate;
        public String endDate;
        public long annualCostEur;
        public List<String> legalEntities = new ArrayList<>();
    }

    public static class Subcontractor {
        public String name;
        public String country;
        public String service;
        public int rank = 1;
    }

    public static class Contract {
        public String id;
        public String providerId;
        public String title;
        public List<ContractSection> sections = new ArrayList<>();
    }

    public static class ContractSection {
        public String ref;
        public String heading;
        public String text;
    }

    // ---------------------------------------------------------------- incidents

    public static class Incident {
        public String id;
        public String title;
        public String description;
        public String severity;           // low | medium | high | critical
        public String detectedAt;
        public List<String> assetIds = new ArrayList<>();
        public List<String> jurisdictions = new ArrayList<>();
        public boolean personalDataAffected;
        public long individualsAffected;
        public String status = "open";
        public List<Clock> clocks = new ArrayList<>();
        public Map<String, Object> classification = new LinkedHashMap<>();
        public String openedBy;
    }

    public static class Clock {
        public String id;
        public String regime;
        public String jurisdiction;
        public String authority;
        public String basis;
        public String startedAt;
        public String deadlineAt;
        public long hours;
        public String status = "running"; // running | warning | breached | closed
        public String template;
        public String closedEvidenceId;
    }

    // ---------------------------------------------------------------- regulatory change & requirements

    public static class RegChangeItem {
        public String id;
        public String source;
        public String title;
        public String jurisdiction;
        public String publishedAt;
        public String url;
        public String text;
        public String status = "ingested"; // ingested | assessed | accepted | rejected | policy_drafted
        public String contentHash;
        public Map<String, Object> impact;
        public String ingestedAt;
    }

    public static class Requirement {
        public String id;
        public String jurisdiction;
        public String family;
        public String title;
        public String regulation;
        public List<String> ruleRefs = new ArrayList<>();
        public String confidence;
    }

    // ---------------------------------------------------------------- cases & approvals

    public static class CaseItem {
        public String id;
        public String type;
        public String title;
        public String status = "open";    // draft | open | approved | denied | executed
        public String createdBy;          // agent id or user id
        public String triggeredBy;        // human who triggered (for segregation of duties)
        public String cell;
        public Map<String, Object> payload = new LinkedHashMap<>();
        public int requiredApprovals = 1;
        public List<String> requiredRoles = new ArrayList<>();
        public List<Approval> approvals = new ArrayList<>();
        public String agentRunId;
        public List<String> evidenceIds = new ArrayList<>();
        public String createdAt;
        public String resolution;
    }

    public static class Approval {
        public String by;
        public String role;
        public String decision;           // approve | deny
        public String comment;
        public String ts;
    }

    // ---------------------------------------------------------------- evidence

    public static class EvidenceRecord {
        public String id;
        public String cell;
        public long seq;
        public String type;
        public String subject;
        public Map<String, Object> payload;
        public String payloadHash;
        public String prevHash;
        public String recordHash;
        public String retentionClass;
        public String ts;
    }

    // ---------------------------------------------------------------- workarounds, agents

    public static class WorkaroundPattern {
        public String id;
        public String name;
        public String solves;
        public String preconditions;
        public String residualRisk;
        public String transform;          // aggregate | pseudonymise | in_place_scores | federated | in_region_replica | retention_vault | approval_gated | volume_managed | synthetic
        public String costImpact;
        public String latencyImpact;
        public String owner = "Legal";
        public String version;
        public List<String> conflictTypes = new ArrayList<>();
    }

    public static class AgentDef {
        public String id;
        public String code;               // A1..A12
        public String name;
        public String purpose;
        public String autonomy;           // L0..L3
        public String cell;               // GLOBAL | EU | IN | SG | CN
        public List<String> tools = new ArrayList<>();
        public List<String> delegates = new ArrayList<>();
        public String modelProfile;
        public String owner;
        public String inventoryId;
        public String version;
        public String riskTier;
        public String euAiActClass = "not Annex III (internal compliance support) - confirm";
        public boolean killed;
        public Map<String, Object> evalScores = new LinkedHashMap<>();
        public String signature;
    }

    public static class AgentRun {
        public String id;
        public String agentId;
        public String agentVersion;
        public String inventoryId;
        public String cell;
        public String onBehalfOf;
        public String parentRunId;
        public int depth;
        public Map<String, Object> input = new LinkedHashMap<>();
        public Map<String, Object> output = new LinkedHashMap<>();
        public String status = "running"; // running | completed | failed | refused | blocked
        public List<Map<String, Object>> steps = new ArrayList<>();
        public String traceId;
        public String llmProvider;
        public String modelProfile;
        public long inputTokens;
        public long outputTokens;
        public String startedAt;
        public String endedAt;
        public long durationMs;
        public List<String> evidenceIds = new ArrayList<>();
        public List<String> caseIds = new ArrayList<>();
        public String error;
    }

    // ---------------------------------------------------------------- misc ops

    public static class ControlTest {
        public String id;
        public String cell;
        public String cloud;
        public String control;
        public String family;
        public String resource;
        public String status;             // passed | failed
        public String detail;
        public String lastRun;
    }

    public static class LegalHold {
        public String id;
        public String assetId;
        public String reason;
        public String until;
    }

    public static class ErasureRequest {
        public String id;
        public String subjectRef;         // pseudonymous reference only
        public String cell;
        public List<String> assetIds = new ArrayList<>();
        public String status;
        public List<Map<String, Object>> perAsset = new ArrayList<>();
        public String requestedBy;
        public String ts;
    }

    public static class DisclosureRequest {
        public String id;
        public String requestingAuthority;
        public String authorityCountry;
        public String dataCell;
        public List<String> assetIds = new ArrayList<>();
        public String purpose;
        public String status;             // requested | awaiting_competent_authority | approved | refused | released
        public List<String> gates = new ArrayList<>();
        public String caseId;
        public String requestedBy;
        public String ts;
    }

    public static class User {
        public String id;
        public String name;
        public List<String> roles = new ArrayList<>();
    }

    public static class ReportRun {
        public String id;
        public String reportId;
        public String title;
        public String generatedAt;
        public String generatedBy;
        public String inputsHash;
        public Map<String, Object> inputsSnapshot = new LinkedHashMap<>();
        public Map<String, Object> figures = new LinkedHashMap<>();
        public String figuresHash;
        public String evidenceId;
        public String narrative;
        public String narrativeStatus;
    }
}

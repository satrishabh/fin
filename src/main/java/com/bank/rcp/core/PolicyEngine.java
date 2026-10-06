package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;

/**
 * policy-decision-service (PDP). Same input/output contract as the OPA packages in the spec
 * ({@code POST /v1/data/{pkg}} returning allow + reasons + bundle version), implemented natively
 * so the POC runs without an OPA sidecar. Thresholds, region lists and adequacy lists are read
 * from the signed bundle data, never hard-coded (principle P6). Agent-authored rules are
 * declarative conditions evaluated by {@link #evalCondition}.
 */
@Service
public class PolicyEngine {
    private static final Set<String> PERSONAL = Set.of("personal", "sensitive_personal", "payment", "health");

    private final DataStore store;
    private final PolicyPackService packs;
    private final EventBus bus;
    private final EvidenceService evidence;

    public PolicyEngine(DataStore store, PolicyPackService packs, EventBus bus, EvidenceService evidence) {
        this.store = store;
        this.packs = packs;
        this.bus = bus;
        this.evidence = evidence;
    }

    /** Internal accumulator for one evaluation. */
    private final class Eval {
        final Decision d = new Decision();
        final Set<String> packsUsed = new TreeSet<>();
        boolean enforceViolation = false;
        boolean anyViolation = false;
        final List<String> notes = new ArrayList<>();

        Eval(String pkg, Object input) {
            d.decisionId = Ids.next("dec");
            d.pkg = pkg;
            d.inputHash = Json.hashOf(input);
            d.timestamp = Ids.now();
        }

        void violation(String packId, String ruleId, String msg) {
            PolicyPack p = store.packs.get(packId);
            PolicyRule r = p == null ? null : p.rules.stream().filter(x -> x.ruleId.equals(ruleId)).findFirst().orElse(null);
            if (p == null || r == null) {
                d.reasons.add(ruleId + ": rule not present in loaded bundle (skipped)");
                return;
            }
            if (!packs.verified(p)) {
                d.reasons.add(ruleId + ": bundle " + p.version + " signature invalid - PDP refuses unsigned bundle; failing closed");
                enforceViolation = true; anyViolation = true;
                return;
            }
            packsUsed.add(p.version);
            String mode = packs.effectiveMode(p, r);
            anyViolation = true;
            if ("enforce".equals(mode)) enforceViolation = true;
            d.ruleIds.add(ruleId);
            d.reasons.add(("audit".equals(mode) ? "[audit] " : "") + msg);
        }

        void allowNote(String packId, String msg) {
            PolicyPack p = store.packs.get(packId);
            if (p != null) packsUsed.add(p.version);
            notes.add(msg);
        }

        Decision finish(String subjectCell, String subject, boolean simulated) {
            d.allow = !anyViolation;
            d.effectiveAllow = !enforceViolation;
            d.mode = !anyViolation ? "n/a" : (enforceViolation ? "enforce" : "audit");
            if (d.reasons.isEmpty()) d.reasons.addAll(notes);
            else d.extra.put("notes", notes);
            d.bundleVersion = packsUsed.isEmpty() ? "none" : String.join("+", packsUsed);
            d.extra.put("simulated", simulated);
            d.extra.put("subject", subject);
            if (!simulated) {
                store.decisions.put(d.decisionId, d);
                bus.publish("policy.decisions", "decision.made", subjectCell, "policy-decision-service", subject,
                        Json.map("decisionId", d.decisionId, "pkg", d.pkg, "allow", d.allow, "effectiveAllow", d.effectiveAllow,
                                "mode", d.mode, "ruleIds", d.ruleIds, "bundleVersion", d.bundleVersion));
            }
            return d;
        }
    }

    // =============================================================== residency

    public Decision evaluateResidency(DataAsset a, boolean simulated) {
        Eval e = new Eval("residency", a);
        String origin = a.tags.originJurisdiction != null ? a.tags.originJurisdiction : a.cell;
        String cls = a.tags.dataClass;
        String loc = a.location;
        String country = store.countryOf(loc);
        String locJur = store.jurisdictionOf(loc);
        String homeJur = String.valueOf(store.pdata("common.home_jurisdiction_of_cell." + a.cell));
        String purpose = a.copyPurpose == null ? "storage" : a.copyPurpose;

        if (cls == null || cls.isBlank()) {
            if (!locJur.equals(homeJur))
                e.violation("common", "COMMON-UNK-001", "COMMON-UNK-001: unclassified asset (treated as restricted) located in " + loc + " outside home cell " + a.cell);
            else e.allowNote("common", "unclassified asset inside home cell; treated as restricted pending classification");
        }

        // India - RBI payment data storage
        if ("IN".equals(origin) && "payment".equals(cls)) {
            List<String> allowed = store.pdataList("in.payment_storage_allowed");
            if (!allowed.contains(loc))
                e.violation("in", "IN-RBI-PAYDATA-001", "IN-RBI-PAYDATA-001: Indian payment data stored in " + loc + "; must be stored in India");
            else e.allowNote("in", "IN payment data stored in permitted region " + loc);
            if ("foreign_processing".equals(purpose) && (a.returnBy == null || a.returnBy.isBlank()))
                e.violation("in", "IN-RBI-PAYDATA-002", "IN-RBI-PAYDATA-002: foreign-processed copy lacks return-to-India deadline");
        }

        // EU - GDPR Chapter V for data hosted outside the EEA
        if ("EU".equals(origin) && cls != null && PERSONAL.contains(cls)) {
            List<String> eea = store.pdataList("common.eea_countries");
            if (eea.contains(country)) e.allowNote("eu", "EU data hosted in EEA (" + country + ")");
            else if (store.pdataList("eu.adequacy_countries").contains(country)) e.allowNote("eu", "adequacy decision covers " + country);
            else {
                Optional<Mechanism> m = findMechanism(a.legalEntity, "EU", country, purpose, cls, Set.of("SCC", "IGA", "BCR"));
                if (m.isPresent()) {
                    e.allowNote("eu", "transfer tool " + m.get().id + " (" + m.get().type + ") covers " + country + " for " + purpose);
                    e.d.extra.put("mechanismId", m.get().id);
                } else
                    e.violation("eu", "EU-GDPR-TRF-001", "EU-GDPR-TRF-001: EU " + cls + " data hosted in " + loc + " (" + country + ") with no adequacy or valid transfer tool for purpose '" + purpose + "'");
            }
        }

        // Singapore - MAS outsourcing for customer information abroad
        if ("SG".equals(origin) && cls != null && PERSONAL.contains(cls) && !"SG".equals(country)) {
            Optional<Mechanism> m = findMechanism(a.legalEntity, "SG", country, purpose, cls, new HashSet<>(store.pdataList("sg.outsourcing_mechanism_types")));
            if (m.isPresent()) e.allowNote("sg", "MAS outsourcing assessment " + m.get().id + " covers " + country);
            else e.violation("sg", "SG-MAS-OUT-001", "SG-MAS-OUT-001: SG customer information hosted in " + loc + " (" + country + ") without a MAS outsourcing/cloud assessment covering that location");
        }

        // China (simulated) - localisation of important data and sensitive PI
        if ("CN".equals(origin) && (a.tags.importantDataFlag || "sensitive_personal".equals(cls) || "important_data_candidate".equals(cls))) {
            if (!store.pdataList("cn.mainland_regions").contains(loc))
                e.violation("cn", "CN-PIPL-LOC-001", "CN-PIPL-LOC-001: " + (a.tags.importantDataFlag ? "important data" : cls) + " of the China entity stored in " + loc + " outside mainland China");
            else e.allowNote("cn", "CN data localised in " + loc);
        }

        // Declarative (agent-authored, human-merged) rules
        for (PolicyPack p : store.packs.values()) {
            for (PolicyRule r : p.rules) {
                if (r.builtin || r.condition == null || !"residency".equals(r.pkg)) continue;
                String msg = evalCondition(r, assetInput(a));
                if (msg != null) e.violation(p.id, r.ruleId, msg);
            }
        }

        if (e.notes.isEmpty() && !e.anyViolation) e.allowNote("common", "no residency rule applies to class " + cls + " in " + loc);
        Decision d = e.finish(a.cell, a.id, simulated);
        if (!simulated) evidence.record(a.cell, "decision.residency", a.id,
                Json.map("decisionId", d.decisionId, "allow", d.allow, "effectiveAllow", d.effectiveAllow, "ruleIds", d.ruleIds, "bundleVersion", d.bundleVersion, "inputHash", d.inputHash), "EV-7Y");
        return d;
    }

    public Map<String, Object> assetInput(DataAsset a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", a.id);
        m.put("cell", a.cell);
        m.put("location", a.location);
        m.put("locationJurisdiction", store.jurisdictionOf(a.location));
        m.put("dataClass", a.tags.dataClass);
        m.put("origin", a.tags.originJurisdiction);
        m.put("segment", a.segment);
        m.put("copyPurpose", a.copyPurpose);
        m.put("returnBy", a.returnBy);
        m.put("importantData", a.tags.importantDataFlag);
        m.put("individuals", a.individuals);
        m.put("iacRef", a.iacRef);
        m.put("owner", a.owner);
        return m;
    }

    /** Evaluates a declarative rule condition; returns the violation message or null. */
    public String evalCondition(PolicyRule r, Map<String, Object> in) {
        Map<String, Object> c = r.condition;
        if (c == null || c.isEmpty()) return null;
        if (c.containsKey("dataClassIn") && !Json.strList(c.get("dataClassIn")).contains(String.valueOf(in.get("dataClass")))) return null;
        if (c.containsKey("origin") && !String.valueOf(c.get("origin")).equals(String.valueOf(in.get("origin")))) return null;
        if (c.containsKey("segmentIn") && !Json.strList(c.get("segmentIn")).contains(String.valueOf(in.get("segment")))) return null;
        if (c.containsKey("copyPurpose") && !String.valueOf(c.get("copyPurpose")).equals(String.valueOf(in.get("copyPurpose")))) return null;
        if (c.containsKey("importantData") && Json.bool(c, "importantData", false) != Boolean.TRUE.equals(in.get("importantData"))) return null;
        if (c.containsKey("minIndividuals") && Json.num(in, "individuals", 0) < Json.num(c, "minIndividuals", 0)) return null;
        if (c.containsKey("locationNotIn")) {
            Object listOrPath = c.get("locationNotIn");
            List<String> allowed = listOrPath instanceof Collection<?> ? Json.strList(listOrPath) : store.pdataList(String.valueOf(listOrPath));
            if (allowed.contains(String.valueOf(in.get("location")))) return null;
        }
        if (c.containsKey("locationJurisdictionNot") && String.valueOf(c.get("locationJurisdictionNot")).equals(String.valueOf(in.get("locationJurisdiction")))) return null;
        if (c.containsKey("missingField")) {
            Object v = in.get(String.valueOf(c.get("missingField")));
            if (v != null && !String.valueOf(v).isBlank()) return null;
        }
        String msg = r.message == null ? r.description : r.message;
        return r.ruleId + ": " + msg.replace("{location}", String.valueOf(in.get("location"))).replace("{id}", String.valueOf(in.get("id")));
    }

    // =============================================================== transfer

    public Decision evaluateTransfer(Map<String, Object> t, boolean simulated) {
        Eval e = new Eval("transfer", t);
        String originCell = Json.str(t, "originCell", "EU");
        String origin = String.valueOf(store.pdata("common.home_jurisdiction_of_cell." + originCell));
        String dest = Json.str(t, "destinationCountry", "").toUpperCase();
        if (dest.isBlank()) {
            String destCell = Json.str(t, "destinationCell", "");
            dest = String.valueOf(store.pdata("common.home_country_of_cell." + destCell));
        }
        String purpose = Json.str(t, "purpose", "unspecified");
        String exporter = Json.str(t, "exporter", defaultExporter(originCell));
        List<String> classes = Json.strList(t.get("dataClasses"));
        boolean important = Json.bool(t, "containsImportantData", false);
        long nonSens = Json.num(t, "individualsNonsensitive", 0);
        long sens = Json.num(t, "individualsSensitive", 0);
        String transformId = Json.str(t, "transform", null);
        WorkaroundPattern wp = transformId == null ? null : store.workarounds.get(transformId);

        // ---- transform semantics (approved workaround patterns)
        List<String> effective = new ArrayList<>(classes);
        if (wp != null) {
            e.d.extra.put("transform", wp.id + " " + wp.name);
            switch (wp.transform) {
                case "aggregate" -> {
                    long k = Json.num(t, "kMin", 0);
                    long min = Json.num(Json.toMap(store.pdata("common")), "min_cell_size", 10);
                    if (k >= min) { effective = List.of("aggregate"); e.allowNote("common", "WP-01 aggregates with minimum cell size " + k + " >= " + min + " contain no personal data"); }
                    else e.violation("common", "COMMON-TRF-001", "COMMON-TRF-001: aggregate minimum cell size " + k + " below threshold " + min);
                }
                case "in_place_scores" -> { effective = List.of("derived_scores"); e.allowNote("common", "WP-03 only model scores leave the cell (output schema must be reviewed)"); }
                case "federated" -> {
                    long cap = Json.num(Json.toMap(store.pdata("common")), "federated_row_cap", 500);
                    long rows = Json.num(t, "rowCap", cap + 1);
                    if (rows <= cap) { effective = List.of("aggregate"); e.allowNote("common", "WP-04 federated result capped at " + rows + " rows"); }
                    else e.violation("common", "COMMON-TRF-001", "COMMON-TRF-001: federated query row cap " + rows + " exceeds " + cap);
                }
                case "synthetic" -> { effective = List.of("synthetic"); important = false; e.allowNote("common", "WP-09 synthetic data substitution"); }
                case "pseudonymise" -> e.allowNote("common", "WP-02 pseudonymised data remains personal data; mechanism still required");
                case "volume_managed" -> e.allowNote("common", "WP-08 volume-managed: evaluated against projected counters");
                default -> e.d.reasons.add("pattern " + wp.id + " is not a transfer transform; ignored");
            }
            if ("in_place_scores".equals(wp.transform)) important = false;
        }
        e.d.extra.put("effectiveDataClasses", effective);

        boolean anyPersonal = effective.stream().anyMatch(PERSONAL::contains);
        String destJur = dest;
        if (store.pdataList("common.eea_countries").contains(dest)) destJur = "EU";

        if (!anyPersonal && !important) {
            e.allowNote("common", "COMMON-TRF-000: no personal or regulated data leaves the cell (" + effective + ")");
        } else if (origin.equals(destJur)) {
            e.allowNote(origin.toLowerCase(), "intra-jurisdiction movement (" + origin + " -> " + dest + ")");
        } else {
            switch (origin) {
                case "EU" -> transferEU(e, exporter, dest, purpose, effective);
                case "IN" -> transferIN(e, t, dest, purpose, effective);
                case "SG" -> transferSG(e, exporter, dest, purpose, effective);
                case "CN" -> transferCN(e, exporter, dest, purpose, effective, important, nonSens, sens, Json.str(t, "now", LocalDate.now().toString()));
                default -> e.d.reasons.add("no transfer package for origin " + origin);
            }
        }
        Decision d = e.finish(originCell, "transfer:" + originCell + "->" + dest + ":" + purpose, simulated);
        return d;
    }

    private void transferEU(Eval e, String exporter, String dest, String purpose, List<String> classes) {
        if (store.pdataList("eu.adequacy_countries").contains(dest)) { e.allowNote("eu", "adequacy decision covers " + dest); return; }
        for (String cls : classes) {
            if (!PERSONAL.contains(cls)) continue;
            Optional<Mechanism> m = findMechanism(exporter, "EU", dest, purpose, cls, Set.of("SCC", "IGA", "BCR"));
            if (m.isEmpty()) {
                e.violation("eu", "EU-GDPR-TRF-002", "EU-GDPR-TRF-002: no adequacy or valid SCC/IGA/BCR for " + cls + " to " + dest + " for purpose '" + purpose + "'");
                return;
            }
            e.d.extra.put("mechanismId", m.get().id);
            e.allowNote("eu", "mechanism " + m.get().id + " (" + m.get().type + ", valid to " + m.get().validTo + ") covers " + cls + " to " + dest);
        }
    }

    private void transferIN(Eval e, Map<String, Object> t, String dest, String purpose, List<String> classes) {
        if (classes.contains("payment")) {
            String returnBy = Json.str(t, "returnBy", "");
            if ("foreign_processing".equals(purpose) && !returnBy.isBlank())
                e.allowNote("in", "IN payment data exported for processing with return-by " + returnBy + " (" + store.pdata("in.foreign_processing_return_hours") + "h rule)");
            else {
                e.violation("in", "IN-RBI-PAYDATA-003", "IN-RBI-PAYDATA-003: raw Indian payment data may not leave India (only foreign processing with return deadline)");
                return;
            }
        }
        if (classes.stream().anyMatch(c -> PERSONAL.contains(c) && !"payment".equals(c))) {
            if (store.pdataList("in.dpdp_restricted_countries").contains(dest))
                e.violation("in", "IN-DPDP-TRF-001", "IN-DPDP-TRF-001: destination " + dest + " is restricted under DPDP s.16");
            else e.allowNote("in", "DPDP s.16: " + dest + " not on restricted list (sectoral rules still apply)");
        }
    }

    private void transferSG(Eval e, String exporter, String dest, String purpose, List<String> classes) {
        for (String cls : classes) {
            if (!PERSONAL.contains(cls)) continue;
            Optional<Mechanism> m = findMechanism(exporter, "SG", dest, purpose, cls, new HashSet<>(store.pdataList("sg.transfer_mechanism_types")));
            if (m.isEmpty()) {
                e.violation("sg", "SG-PDPA-TRF-001", "SG-PDPA-TRF-001: no comparable-protection instrument for " + cls + " to " + dest + " (purpose '" + purpose + "')");
                return;
            }
            e.d.extra.put("mechanismId", m.get().id);
            e.allowNote("sg", "PDPA s.26 satisfied by " + m.get().id);
        }
    }

    private void transferCN(Eval e, String exporter, String dest, String purpose, List<String> classes, boolean important,
                            long nonSens, long sens, String now) {
        Map<String, Object> th = Json.toMap(store.pdata("cn.thresholds"));
        Counter c = store.counters.get(DataStore.counterKey(exporter, LocalDate.now().getYear()));
        long curN = c == null ? 0 : c.nonsensitive;
        long curS = c == null ? 0 : c.sensitive;
        long projN = curN + nonSens;
        long projS = curS + sens;
        boolean ciio = store.pdataList("cn.ciio_exporters").contains(exporter);
        String route;
        String why;
        if (important) { route = "cac_security_assessment"; why = "contains important data"; }
        else if (ciio) { route = "cac_security_assessment"; why = "exporter is a CIIO"; }
        else if (projN >= Json.num(th, "assessment_nonsensitive", 1_000_000)) { route = "cac_security_assessment"; why = "projected non-sensitive individuals " + projN + " >= " + th.get("assessment_nonsensitive"); }
        else if (projS >= Json.num(th, "assessment_sensitive", 10_000)) { route = "cac_security_assessment"; why = "projected sensitive individuals " + projS + " >= " + th.get("assessment_sensitive"); }
        else if (projN >= Json.num(th, "exempt_nonsensitive_below", 100_000)) { route = "standard_contract_or_certification"; why = "projected non-sensitive individuals " + projN + " >= " + th.get("exempt_nonsensitive_below"); }
        else if (projS > 0) { route = "standard_contract_or_certification"; why = "sensitive personal information included"; }
        else { route = "exempt_with_pipl_duties"; why = "below thresholds"; }

        e.d.extra.put("requiredRoute", route);
        e.d.extra.put("routeReason", why);
        e.d.extra.put("projected", Json.map("nonsensitive", projN, "sensitive", projS, "currentNonsensitive", curN, "currentSensitive", curS));

        if ("exempt_with_pipl_duties".equals(route)) {
            e.allowNote("cn", "CN route: exempt (" + why + "); PIPL notice/consent/PIPIA duties still apply");
            return;
        }
        LocalDate today = LocalDate.parse(now.substring(0, 10));
        for (Mechanism m : store.mechanisms.values()) {
            if (!"active".equals(m.status) || !route.equals(m.route)) continue;
            if (m.exporter != null && !m.exporter.equals(exporter)) continue;
            if (!m.destinations.contains(dest) || !m.purposes.contains(purpose)) continue;
            if (m.validTo != null && LocalDate.parse(m.validTo).isBefore(today)) continue;
            e.d.extra.put("mechanismId", m.id);
            e.allowNote("cn", "CN route " + route + " satisfied by " + m.id + " (" + m.filingRef + ")");
            return;
        }
        e.violation("cn", "CN-PIPL-TRF-001", "CN-PIPL-TRF-001: route '" + route + "' required (" + why + "); no recorded mechanism covers exporter/destination " + dest + "/purpose '" + purpose + "'"
                + (classes.contains("sensitive_personal") ? "; sensitive PI also needs separate consent" : ""));
    }

    // =============================================================== disclosure & retention

    public Decision evaluateDisclosure(DisclosureRequest r, boolean competentAuthorityApproved) {
        Eval e = new Eval("disclosure", Json.map("req", r.id, "cell", r.dataCell, "authorityCountry", r.authorityCountry, "approved", competentAuthorityApproved));
        if ("CN".equals(r.dataCell) && !"CN".equalsIgnoreCase(r.authorityCountry)) {
            if (competentAuthorityApproved) e.allowNote("cn", "DSL Art. 36 gate satisfied: competent authority approval recorded");
            else e.violation("cn", "CN-DSL-36-001", "CN-DSL-36-001: provision to foreign authority (" + r.requestingAuthority + ") requires approval of the competent Chinese authority before export");
        } else {
            e.allowNote("common", "no statutory approval gate for " + r.dataCell + " data to " + r.authorityCountry + " in loaded packs; legal review still required");
        }
        return e.finish(r.dataCell, "disclosure:" + r.id, false);
    }

    public Decision evaluateErasure(DataAsset a, long recordAgeYears) {
        Eval e = new Eval("retention", Json.map("asset", a.id, "ageYears", recordAgeYears));
        String today = LocalDate.now().toString();
        for (LegalHold h : store.legalHolds.values()) {
            if (h.assetId.equals(a.id) && (h.until == null || h.until.compareTo(today) >= 0))
                e.violation("common", "COMMON-RET-001", "COMMON-RET-001: legal hold " + h.id + " (" + h.reason + ") until " + h.until);
        }
        long min = Json.num(Json.toMap(store.pdata("retention.minimum_years")), String.valueOf(a.tags.retentionClass), 0);
        if (min > 0 && recordAgeYears < min)
            e.violation("common", "COMMON-RET-001", "COMMON-RET-001: statutory retention " + a.tags.retentionClass + " (" + min + "y) not yet elapsed (record age " + recordAgeYears + "y)");
        if (!e.anyViolation) e.allowNote("common", "no hold or unexpired retention; erasure may proceed");
        // Retention conflicts are enforced regardless of pack mode: erasing would be irreversible.
        Decision d = e.finish(a.cell, a.id, false);
        d.effectiveAllow = d.allow;
        return d;
    }

    // =============================================================== helpers

    public Optional<Mechanism> findMechanism(String exporter, String origin, String destCountry, String purpose, String cls, Set<String> types) {
        LocalDate today = LocalDate.now();
        return store.mechanisms.values().stream()
                .filter(m -> "active".equals(m.status))
                .filter(m -> types.contains(m.type))
                .filter(m -> origin.equals(m.originJurisdiction))
                .filter(m -> exporter == null || m.exporter == null || m.exporter.equals(exporter))
                .filter(m -> m.destinations.contains(destCountry))
                .filter(m -> purpose == null || m.purposes.contains(purpose))
                .filter(m -> cls == null || m.dataClasses.contains(cls))
                .filter(m -> m.validFrom == null || !LocalDate.parse(m.validFrom).isAfter(today))
                .filter(m -> m.validTo == null || !LocalDate.parse(m.validTo).isBefore(today))
                .sorted(Comparator.comparing(m -> m.id))
                .findFirst();
    }

    public String defaultExporter(String cell) {
        return switch (cell) {
            case "EU" -> "Bank Europe SE";
            case "IN" -> "Bank India Ltd";
            case "SG" -> "Bank Singapore Pte Ltd";
            case "CN" -> "Bank (China) Co Ltd [SIM]";
            default -> cell;
        };
    }
}

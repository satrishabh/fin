package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * third-party-register-service: one master register exported to DORA RoI (ITS-style subset),
 * APRA CPS 230 material service provider register, and MAS outsourcing register formats.
 */
@Service
public class ThirdPartyService {
    /** Required clause sets per regime (indicative mapping; confirm with legal). */
    public static final Map<String, List<String>> REQUIRED_CLAUSES = new LinkedHashMap<>();
    static {
        REQUIRED_CLAUSES.put("DORA Art. 30", List.of("service_levels", "data_location", "security", "incident_notification", "audit_rights", "regulator_access", "termination_exit", "subcontracting"));
        REQUIRED_CLAUSES.put("APRA CPS 230", List.of("service_levels", "audit_rights", "regulator_access", "termination_exit", "subcontracting", "incident_notification"));
        REQUIRED_CLAUSES.put("MAS Outsourcing", List.of("audit_rights", "regulator_access", "data_location", "security", "termination_exit", "subcontracting", "incident_notification"));
    }

    private final DataStore store;
    private final EventBus bus;
    private final EvidenceService evidence;

    public ThirdPartyService(DataStore store, EventBus bus, EvidenceService evidence, CaseService cases) {
        this.store = store;
        this.bus = bus;
        this.evidence = evidence;
        cases.onApproved("register_update", this::applyUpdate);
    }

    public List<ThirdParty> list() { return DataStore.sorted(store.thirdParties, Comparator.comparing(t -> t.id)); }

    public ThirdParty require(String id) {
        ThirdParty t = store.thirdParties.get(id);
        if (t == null) throw new NoSuchElementException("third party " + id);
        return t;
    }

    public List<Map<String, Object>> gaps() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ThirdParty tp : list()) for (ServiceArrangement a : tp.arrangements) {
            Map<String, Object> byRegime = new LinkedHashMap<>();
            for (var e : REQUIRED_CLAUSES.entrySet()) {
                List<String> missing = new ArrayList<>();
                for (String c : e.getValue()) if (a.clauses.get(c) == null || "missing".equals(a.clauses.get(c))) missing.add(c);
                byRegime.put(e.getKey(), missing);
            }
            out.add(Json.map("providerId", tp.id, "provider", tp.name, "arrangementId", a.id, "critical", a.critical,
                    "hasExitPlan", a.exitPlanRef != null, "hasSubcontractorChain", !a.subcontractors.isEmpty() || !a.critical, "missingByRegime", byRegime));
        }
        return out;
    }

    public Map<String, Object> metrics() {
        int crit = 0, complete = 0;
        for (ThirdParty tp : list()) for (ServiceArrangement a : tp.arrangements) {
            if (!a.critical) continue;
            crit++;
            if (a.exitPlanRef != null && !a.subcontractors.isEmpty()) complete++;
        }
        return Json.map("criticalArrangements", crit, "withChainAndExitPlan", complete, "pct", crit == 0 ? 100.0 : Math.round(1000.0 * complete / crit) / 10.0);
    }

    // ------------------------------------------------------------ exports

    public Map<String, Object> export(String format, String user) {
        List<String> header;
        List<List<String>> rows = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        switch (format) {
            case "dora_roi" -> {
                header = List.of("B_02.01.0010 contractual_arrangement_ref", "B_05.01.0010 provider_identification_code(LEI)", "B_05.01.0020 provider_name",
                        "B_05.01.0080 country_of_headquarters", "B_02.02.0060 ict_service_type", "B_06.01 function_name", "critical_or_important",
                        "B_02.02.0130 data_storage_locations", "B_02.02.0150 processing_locations", "B_05.02 subcontractor_chain", "exit_plan_exists",
                        "B_02.01.0050 annual_expense_eur", "start_date", "end_date", "entities_using_service");
                for (ThirdParty tp : list()) for (ServiceArrangement a : tp.arrangements) {
                    rows.add(List.of(a.id, nz(tp.lei), tp.name, tp.country, a.ictServiceType, nz(a.criticalFunction), a.critical ? "Y" : "N",
                            String.join("|", a.dataLocations), String.join("|", a.processingLocations),
                            String.join("|", a.subcontractors.stream().map(s -> "r" + s.rank + ":" + s.name + "(" + s.country + ")").toList()),
                            a.exitPlanRef != null ? "Y" : "N", String.valueOf(a.annualCostEur), nz(a.startDate), nz(a.endDate), String.join("|", a.legalEntities)));
                    if (tp.lei == null || tp.lei.isBlank()) errors.add(a.id + ": missing provider LEI");
                    if (a.critical && a.exitPlanRef == null) errors.add(a.id + ": critical arrangement without exit plan");
                    if (a.critical && a.subcontractors.isEmpty()) errors.add(a.id + ": critical arrangement without subcontractor chain (confirm none)");
                    if (a.dataLocations.isEmpty()) errors.add(a.id + ": data storage location missing");
                }
            }
            case "cps230" -> {
                header = List.of("service_provider", "abn_or_lei", "material_service", "critical_operation_supported", "location", "fourth_parties", "notification_to_apra_required", "termination_arrangements");
                for (ThirdParty tp : list()) for (ServiceArrangement a : tp.arrangements) {
                    if (!a.critical) continue;
                    rows.add(List.of(tp.name, nz(tp.lei), a.service, nz(a.criticalFunction), String.join("|", a.dataLocations),
                            String.join("|", a.subcontractors.stream().map(s -> s.name).toList()), tp.country.equals("AU") ? "N" : "Y (offshore)",
                            a.clauses.getOrDefault("termination_exit", "missing")));
                    if ("missing".equals(a.clauses.getOrDefault("termination_exit", "missing"))) errors.add(a.id + ": termination arrangements missing");
                }
            }
            case "mas" -> {
                header = List.of("service_provider", "arrangement", "materiality", "customer_information_involved", "locations", "subcontracting", "audit_access_rights", "exit_plan");
                for (ThirdParty tp : list()) for (ServiceArrangement a : tp.arrangements) {
                    if (!a.legalEntities.contains("Bank Singapore Pte Ltd")) continue;
                    rows.add(List.of(tp.name, a.service, a.critical ? "material" : "non-material", a.criticalFunction != null ? "Y" : "N",
                            String.join("|", a.dataLocations), a.subcontractors.isEmpty() ? "none" : String.valueOf(a.subcontractors.size()),
                            a.clauses.getOrDefault("audit_rights", "missing"), nz(a.exitPlanRef)));
                    if ("missing".equals(a.clauses.getOrDefault("audit_rights", "missing"))) errors.add(a.id + ": MAS requires audit/access rights");
                    if ("missing".equals(a.clauses.getOrDefault("regulator_access", "missing"))) errors.add(a.id + ": MAS access rights for the Authority missing");
                }
            }
            default -> throw new IllegalArgumentException("format must be dora_roi | cps230 | mas");
        }
        StringBuilder csv = new StringBuilder(String.join(",", header.stream().map(ThirdPartyService::csv).toList())).append('\n');
        for (List<String> r : rows) csv.append(String.join(",", r.stream().map(ThirdPartyService::csv).toList())).append('\n');
        Map<String, Object> res = Json.map("format", format, "rows", rows.size(), "validation", errors.isEmpty() ? "pass" : "fail", "errors", errors,
                "csv", csv.toString(), "generatedAt", Ids.now(), "by", user,
                "note", "Field codes are an indicative subset of the ESA ITS RoI data model; validate against the official taxonomy before submission.");
        EvidenceRecord ev = evidence.record("GLOBAL", "register.export.generated", format, Json.map("format", format, "rows", rows.size(), "validation", res.get("validation"), "csvHash", Json.sha256(csv.toString())), "EV-7Y");
        res.put("evidenceId", ev.id);
        bus.publish("platform.events", "register.export.generated", null, "third-party-register-service", format, Json.map("format", format, "rows", rows.size(), "validation", res.get("validation")));
        return res;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String csv(String s) {
        if (s == null) return "";
        return s.contains(",") || s.contains("\"") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }

    @SuppressWarnings("unchecked")
    private void applyUpdate(CaseItem c) {
        String arrId = Json.str(c.payload, "arrangementId", null);
        Map<String, Object> clauses = Json.toMap(c.payload.get("clauses"));
        for (ThirdParty tp : list()) for (ServiceArrangement a : tp.arrangements) {
            if (!a.id.equals(arrId)) continue;
            for (var e : clauses.entrySet()) {
                Object v = e.getValue();
                String ref = v instanceof Map<?, ?> m ? String.valueOf(((Map<String, Object>) m).getOrDefault("section", "missing")) : String.valueOf(v);
                a.clauses.put(e.getKey(), ref);
            }
            evidence.record("GLOBAL", "register.updated", a.id, Json.map("arrangementId", a.id, "clauses", a.clauses, "caseId", c.id), "EV-7Y");
            c.resolution = "Register arrangement " + a.id + " clause references updated";
            return;
        }
        throw new NoSuchElementException("arrangement " + arrId);
    }

    public Contract contract(String id) {
        Contract c = store.contracts.get(id);
        if (c == null) throw new NoSuchElementException("contract " + id);
        return c;
    }

    public void addContract(Contract c) {
        if (c.id == null) c.id = Ids.next("CTR");
        store.contracts.put(c.id, c);
    }
}

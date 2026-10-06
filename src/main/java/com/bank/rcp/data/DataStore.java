package com.bank.rcp.data;

import com.bank.rcp.common.Json;
import com.bank.rcp.model.Models.*;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory stores for every service. Each map stands in for a service-owned database.
 *
 * <p>Data source: the bundled synthetic seed ({@code classpath:seed/*.json}). To run with
 * original data, set {@code RCP_DATA_DIR} to a folder holding files with the same names and
 * shapes; any file present there overrides the seed. Datasets can also be replaced at runtime
 * through {@code POST /api/admin/import/{dataset}}.
 */
@Component
public class DataStore {
    private static final Logger log = LoggerFactory.getLogger(DataStore.class);

    public static final List<String> DATASETS = List.of(
            "assets", "lineage", "mechanisms", "workarounds", "users", "agents",
            "third-parties", "contracts", "reg-changes", "requirements", "policy-packs", "misc");

    public final Map<String, DataAsset> assets = new ConcurrentHashMap<>();
    public final List<LineageEdge> lineage = new CopyOnWriteArrayList<>();
    public final Map<String, ClassificationResult> classifications = new ConcurrentHashMap<>();
    public final Map<String, PolicyPack> packs = new ConcurrentHashMap<>();
    public volatile Map<String, Object> policyData = new LinkedHashMap<>();
    public final Map<String, Decision> decisions = new ConcurrentHashMap<>();
    public final Map<String, Mechanism> mechanisms = new ConcurrentHashMap<>();
    public final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    public final Map<String, Counter> counters = new ConcurrentHashMap<>();
    public final Map<String, ThirdParty> thirdParties = new ConcurrentHashMap<>();
    public final Map<String, Contract> contracts = new ConcurrentHashMap<>();
    public final Map<String, Incident> incidents = new ConcurrentHashMap<>();
    public final Map<String, RegChangeItem> regChanges = new ConcurrentHashMap<>();
    public final Map<String, Requirement> requirements = new ConcurrentHashMap<>();
    public final Map<String, CaseItem> cases = new ConcurrentHashMap<>();
    public final Map<String, WorkaroundPattern> workarounds = new ConcurrentHashMap<>();
    public final Map<String, AgentDef> agents = new ConcurrentHashMap<>();
    public final Map<String, AgentRun> runs = new ConcurrentHashMap<>();
    public final Map<String, ControlTest> controls = new ConcurrentHashMap<>();
    public final Map<String, LegalHold> legalHolds = new ConcurrentHashMap<>();
    public final Map<String, ErasureRequest> erasures = new ConcurrentHashMap<>();
    public final Map<String, DisclosureRequest> disclosures = new ConcurrentHashMap<>();
    public final Map<String, User> users = new ConcurrentHashMap<>();
    public final Map<String, ReportRun> reportRuns = new ConcurrentHashMap<>();
    public volatile List<Map<String, Object>> incidentRegimes = new ArrayList<>();
    public volatile Map<String, String> templates = new LinkedHashMap<>();
    public final Map<String, String> datasetSource = new ConcurrentHashMap<>();

    private final String dataDir;

    public DataStore(@Value("${rcp.data.dir:}") String dataDir) {
        this.dataDir = dataDir == null ? "" : dataDir.trim();
        reloadAll();
    }

    public String dataDir() { return dataDir; }

    public synchronized void reloadAll() {
        for (String ds : DATASETS) load(ds, readSource(ds));
        log.info("RCP data loaded: {} assets, {} mechanisms, {} third parties, {} agents (sources: {})",
                assets.size(), mechanisms.size(), thirdParties.size(), agents.size(), datasetSource);
    }

    private String readSource(String ds) {
        if (!dataDir.isEmpty()) {
            Path p = Path.of(dataDir, ds + ".json");
            if (Files.exists(p)) {
                try {
                    datasetSource.put(ds, "original:" + p);
                    return Files.readString(p, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    log.warn("Could not read {}: {} - falling back to synthetic seed", p, e.getMessage());
                }
            }
        }
        try (InputStream in = new ClassPathResource("seed/" + ds + ".json").getInputStream()) {
            datasetSource.put(ds, "synthetic-seed");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Missing seed dataset " + ds, e);
        }
    }

    /** Replace one dataset from JSON (used by import API). */
    public synchronized void importDataset(String ds, String json, String source) {
        if (!DATASETS.contains(ds)) throw new IllegalArgumentException("Unknown dataset " + ds + "; expected one of " + DATASETS);
        load(ds, json);
        datasetSource.put(ds, source);
    }

    @SuppressWarnings("unchecked")
    private void load(String ds, String raw) {
        String json = raw != null && raw.startsWith("﻿") ? raw.substring(1) : raw;
        switch (ds) {
            case "assets" -> replace(assets, Json.read(json, new TypeReference<List<DataAsset>>() {}), a -> a.id);
            case "lineage" -> { lineage.clear(); lineage.addAll(Json.read(json, new TypeReference<List<LineageEdge>>() {})); }
            case "mechanisms" -> replace(mechanisms, Json.read(json, new TypeReference<List<Mechanism>>() {}), m -> m.id);
            case "workarounds" -> replace(workarounds, Json.read(json, new TypeReference<List<WorkaroundPattern>>() {}), w -> w.id);
            case "users" -> replace(users, Json.read(json, new TypeReference<List<User>>() {}), u -> u.id);
            case "agents" -> replace(agents, Json.read(json, new TypeReference<List<AgentDef>>() {}), a -> a.id);
            case "third-parties" -> replace(thirdParties, Json.read(json, new TypeReference<List<ThirdParty>>() {}), t -> t.id);
            case "contracts" -> replace(contracts, Json.read(json, new TypeReference<List<Contract>>() {}), c -> c.id);
            case "reg-changes" -> {
                List<RegChangeItem> items = Json.read(json, new TypeReference<List<RegChangeItem>>() {});
                for (RegChangeItem i : items) { if (i.contentHash == null) i.contentHash = Json.sha256(String.valueOf(i.text)); if (i.status == null) i.status = "ingested"; }
                replace(regChanges, items, r -> r.id);
            }
            case "requirements" -> replace(requirements, Json.read(json, new TypeReference<List<Requirement>>() {}), r -> r.id);
            case "policy-packs" -> {
                Map<String, Object> root = Json.read(json, new TypeReference<LinkedHashMap<String, Object>>() {});
                policyData = (Map<String, Object>) root.getOrDefault("data", new LinkedHashMap<>());
                List<PolicyPack> list = Json.convert(root.getOrDefault("packs", List.of()), new TypeReference<List<PolicyPack>>() {});
                replace(packs, list, p -> p.id);
            }
            case "misc" -> {
                Map<String, Object> root = Json.read(json, new TypeReference<LinkedHashMap<String, Object>>() {});
                List<Counter> cs = Json.convert(root.getOrDefault("counters", List.of()), new TypeReference<List<Counter>>() {});
                counters.clear();
                for (Counter c : cs) counters.put(counterKey(c.exporter, c.year), c);
                replace(legalHolds, Json.convert(root.getOrDefault("legalHolds", List.of()), new TypeReference<List<LegalHold>>() {}), h -> h.id);
                replace(controls, Json.convert(root.getOrDefault("controls", List.of()), new TypeReference<List<ControlTest>>() {}), c -> c.id);
                incidentRegimes = Json.convert(root.getOrDefault("incidentRegimes", List.of()), new TypeReference<List<Map<String, Object>>>() {});
                templates = Json.convert(root.getOrDefault("templates", Map.of()), new TypeReference<LinkedHashMap<String, String>>() {});
            }
            default -> throw new IllegalArgumentException(ds);
        }
    }

    private static <T> void replace(Map<String, T> target, List<T> items, java.util.function.Function<T, String> id) {
        target.clear();
        for (T t : items) target.put(id.apply(t), t);
    }

    public static String counterKey(String exporter, int year) { return exporter + "|" + year; }

    // ------------------------------------------------------------ policy data helpers

    @SuppressWarnings("unchecked")
    public Object pdata(String dottedPath) {
        Object cur = policyData;
        for (String part : dottedPath.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = ((Map<String, Object>) m).get(part);
        }
        return cur;
    }

    public List<String> pdataList(String dottedPath) { return Json.strList(pdata(dottedPath)); }

    @SuppressWarnings("unchecked")
    public Map<String, Object> region(String location) {
        Object r = pdata("common.regions");
        if (r instanceof Map<?, ?> m && m.get(location) instanceof Map<?, ?> x) return (Map<String, Object>) x;
        return Map.of("country", "UNKNOWN", "jurisdiction", "UNKNOWN", "cloud", location == null ? "?" : location.split(":")[0]);
    }

    public String countryOf(String location) { return String.valueOf(region(location).get("country")); }

    public String jurisdictionOf(String location) { return String.valueOf(region(location).get("jurisdiction")); }

    public static <T> List<T> sorted(Map<String, T> m, Comparator<T> c) {
        List<T> l = new ArrayList<>(m.values());
        l.sort(c);
        return l;
    }
}

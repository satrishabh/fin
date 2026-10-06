package com.bank.rcp.web;

import com.bank.rcp.agents.A2ARegistry;
import com.bank.rcp.agents.AgentRuntime;
import com.bank.rcp.common.Json;
import com.bank.rcp.core.EvidenceService;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import com.bank.rcp.platform.ModelGateway;
import com.bank.rcp.platform.ToolGateway;
import com.bank.rcp.platform.Tracing;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Agent layer, platform status, data administration and evaluation APIs. */
@RestController
@RequestMapping("/api")
public class AgentApiController {
    private static final String U = "X-User";
    private static final String DEF_USER = "analyst.k.rao";

    private final DataStore store;
    private final AgentRuntime runtime;
    private final A2ARegistry a2a;
    private final ModelGateway models;
    private final ToolGateway tools;
    private final Tracing tracing;
    private final EvidenceService evidence;
    private final EventBus bus;
    private final EvalService eval;

    public AgentApiController(DataStore store, AgentRuntime runtime, A2ARegistry a2a, ModelGateway models, ToolGateway tools, Tracing tracing,
                              EvidenceService evidence, EventBus bus, EvalService eval) {
        this.store = store; this.runtime = runtime; this.a2a = a2a; this.models = models; this.tools = tools; this.tracing = tracing;
        this.evidence = evidence; this.bus = bus; this.eval = eval;
    }

    @GetMapping("/agents")
    public List<AgentDef> agents() { return DataStore.sorted(store.agents, Comparator.comparingInt((AgentDef a) -> Integer.parseInt(a.code.substring(1)))); }

    @GetMapping("/agents/cards")
    public List<Map<String, Object>> cards() { return a2a.allCards(); }

    @PostMapping("/agents/{id}/run")
    public AgentRun run(@PathVariable String id, @RequestBody(required = false) Map<String, Object> input, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user);
        AgentRun r = runtime.run(id, input == null ? Map.of() : input, user);
        // Workflow hook: reg-change-service tracks the item through impact assessment.
        if ("reg-change-analyst".equals(id) && "completed".equals(r.status)) {
            RegChangeItem item = store.regChanges.get(Json.str(r.input, "itemId", ""));
            if (item != null) { item.status = "assessed"; item.impact = r.output; }
            bus.publish("regchange.items", "regchange.impact.assessed", null, "reg-change-service", Json.str(r.input, "itemId", ""), Json.map("runId", r.id, "caseIds", r.caseIds));
        }
        if ("policy-authoring".equals(id) && "completed".equals(r.status)) {
            store.cases.values().stream().filter(c -> c.id.equals(Json.str(r.input, "impactCaseId", ""))).findFirst().ifPresent(c -> {
                RegChangeItem item = store.regChanges.get(Json.str(c.payload, "itemId", ""));
                if (item != null) item.status = "policy_drafted";
            });
        }
        return r;
    }

    @PostMapping("/agents/{id}/kill")
    public AgentDef kill(@PathVariable String id, @RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "compliance");
        AgentDef a = store.agents.get(id);
        if (a == null) throw new NoSuchElementException(id);
        a.killed = Json.bool(body, "killed", true);
        evidence.record("GLOBAL", "agent.kill_switch", id, Json.map("agentId", id, "killed", a.killed, "by", user), "EV-7Y");
        bus.publish("agent.runs", "agent.kill_switch", null, "mcp-gateway", id, Json.map("agentId", id, "killed", a.killed));
        return a;
    }

    @PostMapping("/agents/kill-all")
    public Map<String, Object> killAll(@RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "compliance");
        models.setGlobalKill(Json.bool(body, "killed", true));
        evidence.record("GLOBAL", "agent.global_kill_switch", "all", Json.map("killed", models.globalKill(), "by", user), "EV-7Y");
        return Json.map("globalKill", models.globalKill());
    }

    @GetMapping("/agents/runs")
    public List<AgentRun> runs(@RequestParam(required = false) String agentId, @RequestParam(defaultValue = "50") int limit) { return runtime.runs(agentId, limit); }

    @GetMapping("/agents/runs/{id}")
    public Map<String, Object> runDetail(@PathVariable String id) {
        AgentRun r = store.runs.get(id);
        if (r == null) throw new NoSuchElementException(id);
        List<AgentRun> children = store.runs.values().stream().filter(c -> id.equals(c.parentRunId)).toList();
        return Json.map("run", r, "children", children, "trace", tracing.trace(r.traceId));
    }

    // ------------------------------------------------------------ platform

    @GetMapping("/platform/status")
    public Map<String, Object> status() {
        return Json.map("models", models.status(), "tools", tools.stats(), "toolCount", tools.tools().size(), "dataSources", store.datasetSource,
                "dataDir", store.dataDir(), "evidence", evidence.globalIndex());
    }

    @PostMapping("/platform/llm")
    public Map<String, Object> setLlm(@RequestBody Map<String, Object> body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        String model = Json.str(body, "model", null);
        if (model != null) models.setModel(model);
        String p = Json.str(body, "provider", null);
        if (p != null) models.setActiveProvider(p);
        evidence.record("GLOBAL", "model.gateway.changed", "model-gateway", Json.map("provider", models.activeProvider(), "model", model, "by", user), "EV-7Y");
        return models.status();
    }

    @GetMapping("/mcp/tools")
    public List<Map<String, Object>> mcpTools(@RequestParam(required = false) String agentId) { return tools.describe(agentId); }

    // ------------------------------------------------------------ data administration (switch mock -> original data)

    @GetMapping("/admin/datasets")
    public Map<String, Object> datasets() { return Json.map("datasets", DataStore.DATASETS, "sources", store.datasetSource, "dataDir", store.dataDir()); }

    @PostMapping(path = "/admin/import/{dataset}", consumes = "*/*")
    public Map<String, Object> importDataset(@PathVariable String dataset, @RequestBody byte[] body, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "compliance", "policy_engineer", "data_owner");
        String json = new String(body, StandardCharsets.UTF_8);
        store.importDataset(dataset, json, "imported-by:" + user);
        evidence.record("GLOBAL", "data.imported", dataset, Json.map("dataset", dataset, "sha256", Json.sha256(json), "by", user), "EV-7Y");
        return Json.map("imported", dataset, "sources", store.datasetSource);
    }

    @PostMapping("/admin/reload")
    public Map<String, Object> reload(@RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        ApiSupport.requireRole(store, user, "compliance", "policy_engineer");
        store.reloadAll();
        return Json.map("reloaded", true, "sources", store.datasetSource);
    }

    @GetMapping("/admin/sample/{dataset}")
    public Object sample(@PathVariable String dataset) {
        return switch (dataset) {
            case "assets" -> DataStore.sorted(store.assets, Comparator.comparing(a -> a.id)).stream().limit(2).toList();
            case "mechanisms" -> store.mechanisms.values().stream().limit(2).toList();
            case "third-parties" -> store.thirdParties.values().stream().limit(1).toList();
            case "lineage" -> store.lineage.stream().limit(3).toList();
            case "reg-changes" -> store.regChanges.values().stream().limit(1).toList();
            case "contracts" -> store.contracts.values().stream().limit(1).toList();
            default -> Json.map("note", "see src/main/resources/seed/" + dataset + ".json for the full shape");
        };
    }

    // ------------------------------------------------------------ evaluation

    @PostMapping("/eval/run")
    public Map<String, Object> runEval(@RequestParam(defaultValue = "true") boolean agents, @RequestHeader(value = U, defaultValue = DEF_USER) String user) {
        return eval.run(user, agents);
    }
}

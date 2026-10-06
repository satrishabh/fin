package com.bank.rcp.agents;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.model.Models.AgentDef;
import com.bank.rcp.model.Models.AgentRun;
import com.bank.rcp.platform.ModelGateway;
import com.bank.rcp.platform.ModelGateway.Block;
import com.bank.rcp.platform.ToolGateway;
import com.bank.rcp.platform.Tracing;

import java.util.*;
import java.util.function.Supplier;

/** Per-run handle given to an agent: tools via the MCP gateway, models via the model gateway, A2A delegation. */
public class AgentContext {
    public final AgentDef def;
    public final AgentRun run;
    public final Tracing.RcpSpan span;
    private final AgentRuntime runtime;
    private final ToolGateway tools;
    private final ModelGateway models;
    private boolean untrustedRead = false;
    private final Set<String> plannedTools = new LinkedHashSet<>();

    AgentContext(AgentDef def, AgentRun run, Tracing.RcpSpan span, AgentRuntime runtime, ToolGateway tools, ModelGateway models) {
        this.def = def;
        this.run = run;
        this.span = span;
        this.runtime = runtime;
        this.tools = tools;
        this.models = models;
    }

    public Map<String, Object> input() { return run.input; }

    public String in(String k, String def) { return Json.str(run.input, k, def); }

    public String cell() { return run.cell; }

    public void step(String type, String name, String status, Object detail) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("ts", Ids.now());
        s.put("type", type);
        s.put("name", name);
        s.put("status", status);
        if (detail != null) {
            String d = detail instanceof String str ? str : Json.write(detail);
            s.put("detail", d.length() > 1500 ? d.substring(0, 1500) + "..." : d);
        }
        run.steps.add(s);
    }

    /**
     * ASI01 control: the tool plan is fixed before untrusted content is read. Tool calls not in
     * the declared plan are refused once untrusted content has entered the context.
     */
    public void plan(String... toolNames) {
        plannedTools.addAll(List.of(toolNames));
        step("plan", "fixed-plan", "ok", plannedTools);
    }

    public void markUntrustedRead(String label) {
        untrustedRead = true;
        step("guardrail", "untrusted-content", "ok", label + " wrapped as data; plan frozen");
    }

    public ToolGateway.Outcome tryTool(String name, Map<String, Object> args) {
        if (untrustedRead && !plannedTools.isEmpty() && !plannedTools.contains(name)) {
            String msg = "re-validation failed: '" + name + "' was not in the plan fixed before untrusted content was read";
            step("tool", name, "denied", msg);
            return new ToolGateway.Outcome(false, null, msg);
        }
        ToolGateway.Outcome o = tools.call(new ToolGateway.Call(def.id, run.cell, run.id, run.onBehalfOf, args == null ? Map.of() : args), name, span);
        step("tool", name, o.ok() ? "ok" : "denied", o.ok() ? Json.map("args", args) : o.error());
        return o;
    }

    /** Calls a tool and throws if the gateway refuses it. */
    public Object tool(String name, Map<String, Object> args) {
        ToolGateway.Outcome o = tryTool(name, args);
        if (!o.ok()) throw new ToolDeniedException(name + ": " + o.error());
        return o.result();
    }

    /** Tool result as a plain (deep-copied) JSON map: agents never hold live service objects. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> toolMap(String name, Map<String, Object> args) {
        Object o = Json.plain(tool(name, args));
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> toolList(String name, Map<String, Object> args) {
        Object o = Json.plain(tool(name, args));
        List<Map<String, Object>> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(x instanceof Map<?, ?> m ? (Map<String, Object>) m : Json.map("value", x));
        return out;
    }

    /**
     * Ask the model for a JSON object. The deterministic fallback is computed from tool data and
     * used when no provider is configured, the call fails, or output is not valid JSON. Model
     * keys override fallback keys only where present; guardrails run after merge.
     */
    public Map<String, Object> reason(String system, List<Block> blocks, Supplier<Map<String, Object>> fallback) {
        Map<String, Object> base = fallback.get();
        ModelGateway.Result r = models.call(def, run.cell, system, blocks, true, span);
        run.modelProfile = r.profile();
        if (!r.dropped().isEmpty()) step("guardrail", "model-gateway", "ok", "blocks withheld from endpoint by routing profile " + r.profile() + ": " + r.dropped());
        if (r.text() == null) {
            run.llmProvider = r.provider();
            step("llm", "mock".equals(r.provider()) ? "deterministic-reasoning" : "chat", r.error() == null ? "fallback" : "error",
                    r.error() == null ? "no model provider configured - deterministic reasoning from tool data" : r.error() + " - using deterministic fallback");
            base.put("_reasoning", "deterministic");
            return base;
        }
        run.llmProvider = r.provider() + ":" + r.model();
        run.inputTokens += r.inputTokens();
        run.outputTokens += r.outputTokens();
        Optional<Map<String, Object>> parsed = Json.extractObject(r.text());
        if (parsed.isEmpty()) {
            step("llm", "chat", "invalid-output", "model output was not a JSON object; schema validation failed - using deterministic fallback");
            base.put("_reasoning", "deterministic (model output rejected)");
            return base;
        }
        step("llm", "chat " + r.model(), "ok", Json.map("provider", r.provider(), "inputTokens", r.inputTokens(), "outputTokens", r.outputTokens()));
        Map<String, Object> merged = new LinkedHashMap<>(base);
        for (var e : parsed.get().entrySet()) if (e.getValue() != null) merged.put(e.getKey(), e.getValue());
        merged.put("_reasoning", r.provider() + ":" + r.model());
        return merged;
    }

    /** Plain-text model call (narratives). Returns fallback text when no model. */
    public String narrate(String system, List<Block> blocks, Supplier<String> fallback) {
        ModelGateway.Result r = models.call(def, run.cell, system, blocks, false, span);
        run.modelProfile = r.profile();
        if (r.text() == null || r.text().isBlank()) {
            run.llmProvider = r.provider();
            step("llm", "deterministic-narrative", r.error() == null ? "fallback" : "error", r.error());
            return fallback.get();
        }
        run.llmProvider = r.provider() + ":" + r.model();
        run.inputTokens += r.inputTokens();
        run.outputTokens += r.outputTokens();
        step("llm", "chat " + r.model(), "ok", Json.map("inputTokens", r.inputTokens(), "outputTokens", r.outputTokens()));
        return r.text().trim();
    }

    /** A2A delegation: allowlisted delegates only, signed cards only, depth <= 3. */
    public AgentRun delegate(String agentId, Map<String, Object> input) {
        if (!def.tools.contains("a2a.delegate") || !def.delegates.contains(agentId)) {
            step("a2a", agentId, "denied", "delegate not on allowlist of " + def.id);
            throw new ToolDeniedException("A2A delegation to " + agentId + " not allowed for " + def.id);
        }
        step("a2a", "delegate -> " + agentId, "started", null);
        AgentRun child = runtime.runChild(agentId, input, this);
        step("a2a", "delegate -> " + agentId, child.status, Json.map("runId", child.id, "status", child.status));
        run.caseIds.addAll(child.caseIds);
        return child;
    }

    public void cite(String evidenceOrDecisionId) {
        if (evidenceOrDecisionId != null && !run.evidenceIds.contains(evidenceOrDecisionId)) run.evidenceIds.add(evidenceOrDecisionId);
    }

    public void caseCreated(Object caseResult) {
        String id = Json.str(Json.toMap(caseResult), "caseId", null);
        if (id != null) run.caseIds.add(id);
    }

    public static class ToolDeniedException extends RuntimeException {
        public ToolDeniedException(String m) { super(m); }
    }
}

package com.bank.rcp.platform;

import com.bank.rcp.common.Json;
import com.bank.rcp.core.EvidenceService;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.AgentDef;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * mcp-gateway: single ingress for agent tool calls. Enforces registration (no shadow AI),
 * per-agent allowlists, kill switches, cell scoping, argument schemas and rate limits, and
 * records every call (hashes only) as evidence and as an {@code execute_tool} span.
 * The tool surface is deliberately narrower than service APIs: reads plus request-style writes.
 */
@Component
public class ToolGateway {

    public record Call(String agentId, String cell, String runId, String onBehalfOf, Map<String, Object> args) {}

    public record Tool(String name, String description, boolean write, boolean cellScoped, Map<String, Object> inputSchema, Function<Call, Object> handler) {}

    public record Outcome(boolean ok, Object result, String error) {}

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final DataStore store;
    private final EvidenceService evidence;
    private final EventBus bus;
    private final Tracing tracing;
    private final ModelGateway models;
    private final Map<String, Deque<Long>> rate = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private final List<Map<String, Object>> denials = Collections.synchronizedList(new ArrayList<>());

    public ToolGateway(DataStore store, EvidenceService evidence, EventBus bus, Tracing tracing, ModelGateway models) {
        this.store = store;
        this.evidence = evidence;
        this.bus = bus;
        this.tracing = tracing;
        this.models = models;
    }

    public void register(Tool t) { tools.put(t.name(), t); }

    public Collection<Tool> tools() { return tools.values(); }

    public Optional<Tool> tool(String name) { return Optional.ofNullable(tools.get(name)); }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        counters.forEach((k, v) -> m.put(k, v.get()));
        List<Map<String, Object>> recent;
        synchronized (denials) { recent = new ArrayList<>(denials.subList(Math.max(0, denials.size() - 50), denials.size())); }
        Collections.reverse(recent);
        return Json.map("counters", m, "recentDenials", recent);
    }

    public long deniedCount() { return counters.getOrDefault("denied", new AtomicLong()).get(); }

    public Outcome call(Call c, String toolName, Tracing.RcpSpan parent) {
        Tracing.RcpSpan span = tracing.start("execute_tool " + toolName, SpanKind.INTERNAL, parent)
                .set("gen_ai.operation.name", "execute_tool").set("gen_ai.tool.name", toolName)
                .set("gen_ai.agent.id", c.agentId()).set("rcp.agent.cell", c.cell()).set("rcp.agent.run_id", c.runId())
                .set("langsmith.span.kind", "tool");
        String denial = authorise(c, toolName);
        if (denial != null) {
            deny(c, toolName, denial);
            span.set("rcp.tool.denied", true).end("denied", denial);
            return new Outcome(false, null, denial);
        }
        Tool t = tools.get(toolName);
        try {
            Object result = t.handler().apply(c);
            counters.computeIfAbsent("ok", k -> new AtomicLong()).incrementAndGet();
            counters.computeIfAbsent("tool." + toolName, k -> new AtomicLong()).incrementAndGet();
            evidence.record(c.cell(), "rcp.agent.action", c.runId(), Json.map("record_type", "rcp.agent.action", "agent_id", c.agentId(),
                    "on_behalf_of", c.onBehalfOf(), "tool_call", Json.map("name", toolName, "args_hash", Json.hashOf(c.args()), "result_hash", Json.hashOf(result == null ? "null" : result), "status", "ok"),
                    "write", t.write()), retentionFor(c));
            span.set("rcp.tool.args_hash", Json.hashOf(c.args())).set("rcp.tool.write", t.write()).end("ok", null);
            return new Outcome(true, result, null);
        } catch (Exception e) {
            counters.computeIfAbsent("error", k -> new AtomicLong()).incrementAndGet();
            String msg = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (e instanceof SecurityException) deny(c, toolName, msg);
            span.end("error", msg);
            return new Outcome(false, null, msg);
        }
    }

    private String retentionFor(Call c) {
        // Agent runs in regulated workflows are books-and-records (confirm per record type with counsel).
        return "GLOBAL".equals(c.cell()) ? "EV-7Y" : "BR-6Y";
    }

    private String authorise(Call c, String toolName) {
        AgentDef a = store.agents.get(c.agentId());
        if (a == null) return "shadow AI: agent '" + c.agentId() + "' is not registered in the model inventory";
        if (a.killed) return "kill switch: agent " + a.id + " tools revoked";
        Tool t = tools.get(toolName);
        if (t == null) return "tool '" + toolName + "' does not exist on the MCP surface (regulatory decisions are not exposed to agents)";
        if (!a.tools.contains(toolName)) return "tool '" + toolName + "' is not on the allowlist of " + a.id;
        if (t.write() && models.globalKill()) return "global kill switch: agent write-requests disabled";
        String schemaErr = validate(t.inputSchema(), c.args());
        if (schemaErr != null) return "argument validation failed: " + schemaErr;
        if (t.cellScoped() && !"GLOBAL".equals(c.cell())) {
            for (Object v : c.args().values()) {
                String s = String.valueOf(v);
                if (s.startsWith("asset:") && !s.startsWith("asset:" + c.cell().toLowerCase() + ":"))
                    return "cell scoping: " + a.id + " running in " + c.cell() + " cannot access " + s;
            }
        }
        int limit = t.write() ? 10 : 60;
        Deque<Long> q = rate.computeIfAbsent(a.id + "|" + toolName, k -> new ArrayDeque<>());
        long now = System.currentTimeMillis();
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= limit) return "rate limit: " + limit + " calls/min for " + toolName;
            q.addLast(now);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    static String validate(Map<String, Object> schema, Map<String, Object> args) {
        if (schema == null) return null;
        for (String r : Json.strList(schema.get("required")))
            if (args.get(r) == null || String.valueOf(args.get(r)).isBlank()) return "missing required '" + r + "'";
        Map<String, Object> props = (Map<String, Object>) schema.getOrDefault("properties", Map.of());
        for (var e : props.entrySet()) {
            Object v = args.get(e.getKey());
            if (v == null) continue;
            Map<String, Object> p = (Map<String, Object>) e.getValue();
            if (p.containsKey("enum") && !Json.strList(p.get("enum")).contains(String.valueOf(v))) return e.getKey() + " must be one of " + p.get("enum");
            if (p.containsKey("pattern") && !String.valueOf(v).matches(String.valueOf(p.get("pattern")) + ".*")) return e.getKey() + " must match " + p.get("pattern");
            if (p.containsKey("maxLength") && String.valueOf(v).length() > ((Number) p.get("maxLength")).intValue()) return e.getKey() + " too long";
        }
        return null;
    }

    private void deny(Call c, String tool, String reason) {
        counters.computeIfAbsent("denied", k -> new AtomicLong()).incrementAndGet();
        Map<String, Object> d = Json.map("ts", com.bank.rcp.common.Ids.now(), "agentId", c.agentId(), "tool", tool, "runId", c.runId(), "reason", reason);
        denials.add(d);
        evidence.record(c.cell(), "rcp.agent.tool_denied", c.runId(), d, "EV-7Y");
        bus.publish("agent.runs", "agent.tool.denied", c.cell(), "mcp-gateway", c.runId(), d);
    }

    /** MCP-style schema description for tools/list. */
    public List<Map<String, Object>> describe(String agentId) {
        AgentDef a = agentId == null ? null : store.agents.get(agentId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : tools.values()) {
            if (a != null && !a.tools.contains(t.name())) continue;
            out.add(Json.map("name", t.name(), "description", t.description() + (t.write() ? " (request-style: creates an approval case; does NOT execute)" : ""),
                    "inputSchema", t.inputSchema() == null ? Json.map("type", "object") : t.inputSchema(),
                    "annotations", Json.map("readOnlyHint", !t.write(), "cellScoped", t.cellScoped())));
        }
        return out;
    }
}

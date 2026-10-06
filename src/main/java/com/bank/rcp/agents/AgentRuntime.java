package com.bank.rcp.agents;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.core.EvidenceService;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.AgentDef;
import com.bank.rcp.model.Models.AgentRun;
import com.bank.rcp.platform.ModelGateway;
import com.bank.rcp.platform.ToolGateway;
import com.bank.rcp.platform.Tracing;
import io.opentelemetry.api.trace.SpanKind;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent runtime: registration and kill-switch checks, A2A depth caps, boundary guard,
 * {@code invoke_agent} spans, run records (hash-chained evidence, BR-6Y in regulated cells)
 * and {@code agent.runs} events. Framework-agnostic: agents may use LangGraph4j inside.
 */
@Component
public class AgentRuntime {
    public static final int MAX_DEPTH = 3;
    public static final int MAX_FANOUT = 6;

    private final DataStore store;
    private final ToolGateway tools;
    private final ModelGateway models;
    private final Tracing tracing;
    private final EvidenceService evidence;
    private final EventBus bus;
    private final A2ARegistry a2a;
    private final Map<String, Agent> impls = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();

    public AgentRuntime(DataStore store, ToolGateway tools, ModelGateway models, Tracing tracing, EvidenceService evidence, EventBus bus,
                        A2ARegistry a2a, @Lazy List<Agent> agents) {
        this.store = store;
        this.tools = tools;
        this.models = models;
        this.tracing = tracing;
        this.evidence = evidence;
        this.bus = bus;
        this.a2a = a2a;
        this.agentsList = agents;
    }

    private final List<Agent> agentsList;

    private Agent impl(String id) {
        if (impls.isEmpty()) for (Agent a : agentsList) impls.put(a.id(), a);
        return impls.get(id);
    }

    public AgentRun run(String agentId, Map<String, Object> input, String onBehalfOf) {
        return execute(agentId, input, onBehalfOf, null);
    }

    AgentRun runChild(String agentId, Map<String, Object> input, AgentContext parent) {
        long children = store.runs.values().stream().filter(r -> parent.run.id.equals(r.parentRunId)).count();
        if (children >= MAX_FANOUT) throw new AgentContext.ToolDeniedException("fan-out cap " + MAX_FANOUT + " reached");
        if (!a2a.verify(agentId)) throw new AgentContext.ToolDeniedException("A2A: agent card for " + agentId + " failed signature verification");
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.putIfAbsent("cell", parent.run.cell);
        return execute(agentId, in, parent.run.onBehalfOf, parent);
    }

    private AgentRun execute(String agentId, Map<String, Object> input, String onBehalfOf, AgentContext parent) {
        AgentDef def = store.agents.get(agentId);
        AgentRun run = new AgentRun();
        run.id = Ids.next("run");
        run.agentId = agentId;
        run.onBehalfOf = onBehalfOf;
        run.input = new LinkedHashMap<>(input == null ? Map.of() : input);
        run.startedAt = Ids.now();
        run.parentRunId = parent == null ? null : parent.run.id;
        run.depth = parent == null ? 0 : parent.run.depth + 1;
        run.cell = resolveCell(def, run.input);
        store.runs.put(run.id, run);

        Tracing.RcpSpan span = tracing.start("invoke_agent " + agentId, SpanKind.INTERNAL, parent == null ? null : parent.span)
                .set("gen_ai.operation.name", "invoke_agent").set("gen_ai.agent.id", agentId).set("gen_ai.agent.name", def == null ? agentId : def.name)
                .set("rcp.agent.version", def == null ? null : def.version).set("rcp.agent.inventory_id", def == null ? null : def.inventoryId)
                .set("rcp.agent.cell", run.cell).set("rcp.agent.autonomy", def == null ? null : def.autonomy).set("rcp.agent.on_behalf_of", onBehalfOf)
                .set("rcp.agent.depth", run.depth).set("langsmith.span.kind", "chain").set("langfuse.trace.name", agentId);
        span.content("langfuse.observation.input", Json.write(run.input), null, null);
        run.traceId = span.traceId();
        long t0 = System.currentTimeMillis();
        try {
            if (def == null) throw new SecurityException("shadow AI: agent " + agentId + " not registered in model inventory");
            run.agentVersion = def.version;
            run.inventoryId = def.inventoryId;
            if (def.killed || models.globalKill()) throw new SecurityException("kill switch active for " + agentId);
            if (run.depth > MAX_DEPTH) throw new SecurityException("delegation depth " + run.depth + " exceeds cap " + MAX_DEPTH);
            if (consecutiveFailures.getOrDefault(agentId, 0) >= 5) throw new SecurityException("circuit breaker open for " + agentId + " after repeated failures");
            Agent a = impl(agentId);
            if (a == null) throw new IllegalStateException("no implementation for " + agentId);

            Optional<String> prohibited = Guards.prohibitedAction(Json.str(run.input, "question", "") + " " + Json.str(run.input, "request", ""));
            AgentContext ctx = new AgentContext(def, run, span, this, tools, models);
            if (prohibited.isPresent()) {
                run.status = "refused";
                run.output = Json.map("refused", true, "answer", Guards.refusal(prohibited.get()), "boundary", "P1 / Section 7.2 hard rule");
                ctx.step("guardrail", "boundary-adherence", "refused", prohibited.get());
            } else {
                run.output = a.execute(ctx);
                run.status = Boolean.TRUE.equals(run.output.get("refused")) ? "refused" : "completed";
            }
            consecutiveFailures.put(agentId, 0);
        } catch (AgentContext.ToolDeniedException e) {
            run.status = "blocked";
            run.error = e.getMessage();
        } catch (SecurityException e) {
            run.status = "blocked";
            run.error = e.getMessage();
        } catch (Exception e) {
            run.status = "failed";
            run.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            consecutiveFailures.merge(agentId, 1, Integer::sum);
        }
        run.endedAt = Ids.now();
        run.durationMs = System.currentTimeMillis() - t0;
        span.set("rcp.agent.status", run.status).set("gen_ai.usage.input_tokens", run.inputTokens).set("gen_ai.usage.output_tokens", run.outputTokens);
        span.content(null, null, "langfuse.observation.output", Json.write(run.output));
        span.end(run.status, run.error);

        String cellForRecord = run.cell == null ? "GLOBAL" : run.cell;
        evidence.record(cellForRecord, "rcp.agent.run", run.id, Json.map("record_type", "rcp.agent.run", "agent_id", agentId, "agent_version", run.agentVersion,
                "inventory_id", run.inventoryId, "cell", run.cell, "task_id", run.id, "trace_id", run.traceId, "on_behalf_of", onBehalfOf,
                "model", Json.map("profile", run.modelProfile, "provider", run.llmProvider), "status", run.status,
                "input_hash", Json.hashOf(run.input), "output_hash", Json.hashOf(run.output), "parent_run", run.parentRunId, "cases", run.caseIds),
                "GLOBAL".equals(cellForRecord) ? "EV-7Y" : "BR-6Y");
        bus.publish("agent.runs", "agent.run." + run.status, run.cell, "agent-runtime", run.id,
                Json.map("runId", run.id, "agentId", agentId, "status", run.status, "durationMs", run.durationMs, "cases", run.caseIds, "provider", run.llmProvider));
        return run;
    }

    private String resolveCell(AgentDef def, Map<String, Object> input) {
        if (def != null && !"CELL".equals(def.cell)) return def.cell;
        String c = Json.str(input, "cell", null);
        if (c == null) {
            String asset = Json.str(input, "assetId", Json.str(input, "violationId", ""));
            if (asset.startsWith("asset:")) c = asset.split(":")[1].toUpperCase();
        }
        return c == null ? "EU" : c.toUpperCase();
    }

    public List<AgentRun> runs(String agentId, int limit) {
        List<AgentRun> l = new ArrayList<>(store.runs.values());
        l.removeIf(r -> agentId != null && !agentId.isBlank() && !agentId.equals(r.agentId));
        l.sort(Comparator.comparing((AgentRun r) -> r.startedAt).reversed());
        return l.subList(0, Math.min(limit, l.size()));
    }
}

package com.bank.rcp.web;

import com.bank.rcp.agents.A2ARegistry;
import com.bank.rcp.agents.AgentRuntime;
import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.model.Models.AgentRun;
import com.bank.rcp.platform.ToolGateway;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Protocol endpoints:
 * <ul>
 *   <li>{@code POST /mcp} - MCP (JSON-RPC 2.0, stateless streamable-HTTP style): initialize, tools/list, tools/call.
 *       Callers identify with {@code X-Agent-Id} (+ {@code X-RCP-Cell}); every call passes the gateway controls.</li>
 *   <li>{@code GET /.well-known/agent-card.json}, {@code GET /a2a/cards}, {@code POST /a2a/{agentId}} - A2A message/send.</li>
 * </ul>
 */
@RestController
public class ProtocolController {
    public static final String MCP_PROTOCOL_VERSION = "2025-06-18";

    private final ToolGateway tools;
    private final A2ARegistry a2a;
    private final AgentRuntime runtime;
    private final DataStore store;

    public ProtocolController(ToolGateway tools, A2ARegistry a2a, AgentRuntime runtime, DataStore store) {
        this.tools = tools; this.a2a = a2a; this.runtime = runtime; this.store = store;
    }

    @PostMapping("/mcp")
    public Map<String, Object> mcp(@RequestBody Map<String, Object> req, @RequestHeader(value = "X-Agent-Id", required = false) String agentId,
                                   @RequestHeader(value = "X-RCP-Cell", defaultValue = "GLOBAL") String cell) {
        Object id = req.get("id");
        String method = Json.str(req, "method", "");
        Map<String, Object> params = Json.toMap(req.get("params"));
        try {
            Object result = switch (method) {
                case "initialize" -> Json.map("protocolVersion", MCP_PROTOCOL_VERSION, "serverInfo", Json.map("name", "rcp-mcp-gateway", "version", "0.9.0"),
                        "capabilities", Json.map("tools", Json.map("listChanged", false)),
                        "instructions", "Curated RCP tool surface. Write tools are request-style and create approval cases.");
                case "tools/list" -> Json.map("tools", tools.describe(agentId));
                case "tools/call" -> {
                    if (agentId == null) throw new SecurityException("X-Agent-Id header required (agents must be registered)");
                    String name = Json.str(params, "name", "");
                    ToolGateway.Outcome o = tools.call(new ToolGateway.Call(agentId, cell, "mcp-" + Ids.uuid().substring(0, 8), "mcp-client", Json.toMap(params.get("arguments"))), name, null);
                    yield Json.map("content", List.of(Json.map("type", "text", "text", o.ok() ? Json.write(o.result()) : o.error())), "isError", !o.ok());
                }
                case "ping" -> Map.of();
                default -> throw new IllegalArgumentException("method not found: " + method);
            };
            return Json.map("jsonrpc", "2.0", "id", id, "result", result);
        } catch (Exception e) {
            return Json.map("jsonrpc", "2.0", "id", id, "error", Json.map("code", e instanceof IllegalArgumentException ? -32601 : -32000, "message", e.getMessage()));
        }
    }

    @GetMapping("/.well-known/agent-card.json")
    public Map<String, Object> wellKnown() { return a2a.signedCard("supervisor", null); }

    @GetMapping("/a2a/cards")
    public List<Map<String, Object>> cards() { return a2a.allCards(); }

    /** A2A JSON-RPC message/send. The data part becomes the agent input; text part becomes "question". */
    @PostMapping("/a2a/{agentId}")
    public Map<String, Object> a2a(@PathVariable String agentId, @RequestParam(required = false) String cell, @RequestBody Map<String, Object> req,
                                   @RequestHeader(value = "X-User", defaultValue = "analyst.k.rao") String user) {
        Object id = req.get("id");
        if (!"message/send".equals(Json.str(req, "method", ""))) return Json.map("jsonrpc", "2.0", "id", id, "error", Json.map("code", -32601, "message", "only message/send is supported"));
        if (!a2a.verify(agentId)) return Json.map("jsonrpc", "2.0", "id", id, "error", Json.map("code", -32001, "message", "agent card not registered/signed"));
        Map<String, Object> msg = Json.toMap(Json.toMap(req.get("params")).get("message"));
        Map<String, Object> input = new LinkedHashMap<>();
        for (Object p : Json.convert(msg.getOrDefault("parts", List.of()), List.class)) {
            Map<String, Object> part = Json.toMap(p);
            if ("data".equals(part.get("kind"))) input.putAll(Json.toMap(part.get("data")));
            if ("text".equals(part.get("kind"))) input.put("question", part.get("text"));
        }
        if (cell != null) input.put("cell", cell);
        AgentRun r = runtime.run(agentId, input, user);
        String state = switch (r.status) { case "completed" -> "completed"; case "refused" -> "rejected"; default -> "failed"; };
        return Json.map("jsonrpc", "2.0", "id", id, "result", Json.map("kind", "task", "id", r.id, "contextId", r.traceId,
                "status", Json.map("state", state, "timestamp", r.endedAt),
                "artifacts", List.of(Json.map("artifactId", r.id + "-out", "parts", List.of(Json.map("kind", "data", "data", r.output)))),
                "metadata", Json.map("rcp.error", r.error, "rcp.cases", r.caseIds)));
    }
}

package com.bank.rcp.agents;

import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.model.Models.AgentDef;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * a2a-registry: signed Agent Cards (A2A v1.0 core fields + our {@code x-rcp} extension).
 * Only agents with a valid card signature are discoverable or callable. Cell agents get one
 * card per cell (e.g. residency-investigator-in).
 */
@Component
public class A2ARegistry {
    public static final List<String> CELLS = List.of("EU", "IN", "SG", "CN");

    private final DataStore store;
    private final byte[] key;
    private final String baseUrl;

    public A2ARegistry(DataStore store, @Value("${rcp.a2a.signing-key:rcp-poc-a2a-card-key-change-me}") String key,
                       @Value("${rcp.a2a.base-url:http://localhost:8080}") String baseUrl) {
        this.store = store;
        this.key = key.getBytes(StandardCharsets.UTF_8);
        this.baseUrl = baseUrl;
        for (AgentDef a : store.agents.values()) a.signature = sign(cardBody(a, null));
    }

    public Map<String, Object> cardBody(AgentDef a, String cell) {
        boolean cellAgent = "CELL".equals(a.cell);
        String name = cellAgent && cell != null ? a.id + "-" + cell.toLowerCase() : a.id;
        String host = cellAgent && cell != null ? "agents." + cell.toLowerCase() + "-cell.rcp.internal" : "agents.global.rcp.internal";
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("protocolVersion", "1.0");
        card.put("name", name);
        card.put("description", a.purpose);
        card.put("version", a.version);
        card.put("url", baseUrl + "/a2a/" + a.id + (cell != null ? "?cell=" + cell : ""));
        card.put("preferredTransport", "JSONRPC");
        card.put("provider", Json.map("organization", "Bank RCP Platform Team", "url", "https://" + host));
        card.put("capabilities", Json.map("streaming", false, "pushNotifications", false));
        card.put("defaultInputModes", List.of("application/json", "text/plain"));
        card.put("defaultOutputModes", List.of("application/json", "text/markdown"));
        card.put("skills", List.of(Json.map("id", a.id.replace('-', '_'), "name", a.name, "description", a.purpose, "tags", List.of(a.code, a.autonomy))));
        card.put("x-rcp", Json.map("code", a.code, "cell", cellAgent ? (cell == null ? "per-cell" : cell) : a.cell, "autonomy_level", a.autonomy,
                "tool_allowlist", a.tools, "delegates", a.delegates, "model_profile", a.modelProfile, "owner", a.owner, "inventory_id", a.inventoryId, "risk_tier", a.riskTier));
        return card;
    }

    public Map<String, Object> signedCard(String agentId, String cell) {
        AgentDef a = store.agents.get(agentId);
        if (a == null) throw new NoSuchElementException("agent " + agentId);
        Map<String, Object> body = cardBody(a, cell);
        Map<String, Object> card = new LinkedHashMap<>(body);
        card.put("signatures", List.of(Json.map("protected", "{\"alg\":\"HS256\",\"kid\":\"rcp-a2a-poc\"}", "signature", sign(body))));
        return card;
    }

    public List<Map<String, Object>> allCards() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentDef a : DataStore.sorted(store.agents, Comparator.comparingInt((AgentDef x) -> Integer.parseInt(x.code.substring(1))))) {
            if ("CELL".equals(a.cell)) for (String c : CELLS) out.add(signedCard(a.id, c));
            else out.add(signedCard(a.id, null));
        }
        return out;
    }

    /** Receiver-side check before accepting a delegated task (ASI04/ASI07). */
    public boolean verify(String agentId) {
        AgentDef a = store.agents.get(agentId);
        return a != null && a.signature != null && a.signature.equals(sign(cardBody(a, null)));
    }

    String sign(Map<String, Object> body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(Json.canonical(body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}

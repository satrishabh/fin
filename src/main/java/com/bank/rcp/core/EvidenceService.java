package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.EvidenceRecord;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * evidence-service: append-only, hash-chained evidence per cell (WORM semantics: there is no
 * update or delete API). Only chain heads (hashes) are visible in the global index.
 */
@Service
public class EvidenceService {
    public static final String GENESIS = "sha256:genesis";

    private final Map<String, List<EvidenceRecord>> chains = new ConcurrentHashMap<>();
    private final Map<String, EvidenceRecord> byId = new ConcurrentHashMap<>();
    private final EventBus bus;

    public EvidenceService(EventBus bus) { this.bus = bus; }

    public synchronized EvidenceRecord record(String cell, String type, String subject, Map<String, Object> payload, String retentionClass) {
        String c = cell == null || cell.isBlank() ? "GLOBAL" : cell;
        List<EvidenceRecord> chain = chains.computeIfAbsent(c, k -> new CopyOnWriteArrayList<>());
        EvidenceRecord r = new EvidenceRecord();
        r.id = "ev-" + c.toLowerCase() + "-" + String.format("%06d", chain.size() + 1);
        r.cell = c;
        r.seq = chain.size() + 1;
        r.type = type;
        r.subject = subject;
        r.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        r.payloadHash = Json.hashOf(r.payload);
        r.prevHash = chain.isEmpty() ? GENESIS : chain.get(chain.size() - 1).recordHash;
        r.retentionClass = retentionClass == null ? "EV-7Y" : retentionClass;
        r.ts = Ids.now();
        r.recordHash = computeHash(r);
        chain.add(r);
        byId.put(r.id, r);
        bus.publish("platform.events", "evidence.recorded", c, "evidence-service", r.id,
                Json.map("evidenceId", r.id, "type", type, "subject", subject, "recordHash", r.recordHash));
        return r;
    }

    private static String computeHash(EvidenceRecord r) {
        return Json.sha256(r.prevHash + "|" + r.seq + "|" + r.cell + "|" + r.type + "|" + r.subject + "|" + r.payloadHash + "|" + r.retentionClass + "|" + r.ts);
    }

    public Optional<EvidenceRecord> get(String id) { return Optional.ofNullable(byId.get(id)); }

    public boolean exists(String id) { return byId.containsKey(id); }

    public List<EvidenceRecord> search(String cell, String type, String q, int limit) {
        List<EvidenceRecord> out = new ArrayList<>();
        List<String> cells = new ArrayList<>(chains.keySet());
        Collections.sort(cells);
        for (String c : cells) {
            if (cell != null && !cell.isBlank() && !cell.equalsIgnoreCase(c)) continue;
            for (EvidenceRecord r : chains.get(c)) {
                if (type != null && !type.isBlank() && !r.type.contains(type)) continue;
                if (q != null && !q.isBlank()) {
                    String hay = (r.subject + " " + r.type + " " + Json.write(r.payload)).toLowerCase();
                    boolean all = true;
                    for (String tok : q.toLowerCase().split("\\s+")) if (!tok.isBlank() && !hay.contains(tok)) { all = false; break; }
                    if (!all) continue;
                }
                out.add(r);
            }
        }
        out.sort(Comparator.comparing((EvidenceRecord r) -> r.ts).reversed());
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Re-computes every hash in the chain; any tampering breaks verification. */
    public Map<String, Object> verify(String cell) {
        Map<String, Object> res = new LinkedHashMap<>();
        for (String c : new TreeSet<>(chains.keySet())) {
            if (cell != null && !cell.isBlank() && !cell.equalsIgnoreCase(c)) continue;
            List<EvidenceRecord> chain = chains.get(c);
            String prev = GENESIS;
            boolean ok = true;
            String brokenAt = null;
            for (EvidenceRecord r : chain) {
                if (!r.prevHash.equals(prev) || !r.payloadHash.equals(Json.hashOf(r.payload)) || !r.recordHash.equals(computeHash(r))) {
                    ok = false; brokenAt = r.id; break;
                }
                prev = r.recordHash;
            }
            res.put(c, Json.map("records", chain.size(), "valid", ok, "brokenAt", brokenAt, "head", prev));
        }
        return res;
    }

    /** Global hash index: heads only (no payloads leave the cell). */
    public Map<String, Object> globalIndex() {
        Map<String, Object> idx = new LinkedHashMap<>();
        for (String c : new TreeSet<>(chains.keySet())) {
            List<EvidenceRecord> ch = chains.get(c);
            idx.put(c, Json.map("count", ch.size(), "head", ch.isEmpty() ? GENESIS : ch.get(ch.size() - 1).recordHash));
        }
        return idx;
    }

    public int count() { return byId.size(); }
}

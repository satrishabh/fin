package com.bank.rcp.events;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process stand-in for the per-cell Kafka backbone (spec Section 6). Every event is a
 * CloudEvents 1.0 envelope. Topics carry an export class; only metadata/aggregate/public
 * topics would replicate to the global cluster.
 */
@Component
public class EventBus {

    public static final Map<String, String> TOPIC_EXPORT_CLASS = Map.of(
            "asset.lifecycle", "metadata",
            "classification.results", "metadata",
            "policy.decisions", "metadata",
            "residency.violations", "metadata",
            "transfer.ledger", "metadata",
            "incident.events", "metadata",
            "regchange.items", "public",
            "agent.runs", "metadata",
            "case.events", "metadata",
            "platform.events", "metadata");

    private final Deque<Map<String, Object>> log = new ConcurrentLinkedDeque<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final Map<String, List<Consumer<Map<String, Object>>>> subscribers = new HashMap<>();

    public synchronized void subscribe(String topic, Consumer<Map<String, Object>> consumer) {
        subscribers.computeIfAbsent(topic, k -> new CopyOnWriteArrayList<>()).add(consumer);
    }

    public Map<String, Object> publish(String topic, String type, String cell, String service, String subject, Map<String, Object> data) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("specversion", "1.0");
        ev.put("type", "com.bank.rcp." + type);
        ev.put("source", "rcp/" + (cell == null ? "global" : cell.toLowerCase() + "-cell") + "/" + service);
        ev.put("id", Ids.uuid());
        ev.put("time", Ids.now());
        ev.put("subject", subject);
        ev.put("datacontenttype", "application/json");
        ev.put("topic", topic);
        ev.put("exportclass", TOPIC_EXPORT_CLASS.getOrDefault(topic, "metadata"));
        ev.put("data", data);
        log.addFirst(ev);
        while (log.size() > 1000) log.pollLast();

        String payload = Json.write(ev);
        for (SseEmitter em : emitters) {
            try { em.send(SseEmitter.event().name("event").data(payload)); }
            catch (Exception e) { emitters.remove(em); }
        }
        List<Consumer<Map<String, Object>>> subs;
        synchronized (this) { subs = subscribers.getOrDefault(topic, List.of()); }
        for (Consumer<Map<String, Object>> c : subs) {
            try { c.accept(ev); } catch (Exception ignored) { }
        }
        return ev;
    }

    public List<Map<String, Object>> recent(int limit, String topic) {
        List<Map<String, Object>> r = new ArrayList<>();
        for (Map<String, Object> e : log) {
            if (topic != null && !topic.isBlank() && !topic.equals(e.get("topic"))) continue;
            r.add(e);
            if (r.size() >= limit) break;
        }
        return r;
    }

    public SseEmitter register() {
        SseEmitter em = new SseEmitter(0L);
        em.onCompletion(() -> emitters.remove(em));
        em.onTimeout(() -> emitters.remove(em));
        em.onError(e -> emitters.remove(em));
        emitters.add(em);
        try { em.send(SseEmitter.event().name("hello").data("{\"ok\":true}")); } catch (Exception ignored) { }
        return em;
    }
}

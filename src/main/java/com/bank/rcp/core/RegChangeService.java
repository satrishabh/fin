package com.bank.rcp.core;

import com.bank.rcp.common.Ids;
import com.bank.rcp.common.Json;
import com.bank.rcp.data.DataStore;
import com.bank.rcp.events.EventBus;
import com.bank.rcp.model.Models.*;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * reg-change-service: ingests regulatory publications (pasted text, or fetched from a URL for
 * real data), deduplicates by content hash and tracks items through impact assessment.
 */
@Service
public class RegChangeService {
    private final DataStore store;
    private final EventBus bus;
    private final EvidenceService evidence;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NORMAL).build();

    public RegChangeService(DataStore store, EventBus bus, EvidenceService evidence, CaseService cases) {
        this.store = store;
        this.bus = bus;
        this.evidence = evidence;
        cases.onApproved("impact_assessment", c -> {
            RegChangeItem i = store.regChanges.get(Json.str(c.payload, "itemId", ""));
            if (i != null) { i.status = "accepted"; c.resolution = "Impact accepted by compliance; policy authoring may start for " + i.id; }
        });
    }

    public List<RegChangeItem> list() {
        List<RegChangeItem> l = new ArrayList<>(store.regChanges.values());
        l.sort(Comparator.comparing((RegChangeItem r) -> String.valueOf(r.publishedAt)).reversed());
        return l;
    }

    public RegChangeItem require(String id) {
        RegChangeItem r = store.regChanges.get(id);
        if (r == null) throw new NoSuchElementException("reg change item " + id);
        return r;
    }

    public Map<String, Object> ingest(Map<String, Object> in, String user) {
        String text = Json.str(in, "text", "");
        String url = Json.str(in, "url", null);
        String fetchNote = null;
        if (text.isBlank() && url != null) {
            try {
                HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30))
                        .header("User-Agent", "RCP-RegChange/0.9").GET().build(), HttpResponse.BodyHandlers.ofString());
                text = htmlToText(resp.body());
                fetchNote = "fetched " + url + " (HTTP " + resp.statusCode() + ", " + text.length() + " chars)";
            } catch (Exception e) {
                throw new IllegalArgumentException("Could not fetch " + url + ": " + e.getMessage());
            }
        }
        if (text.isBlank()) throw new IllegalArgumentException("Provide 'text' or a fetchable 'url'");
        if (text.length() > 20000) text = text.substring(0, 20000);
        String hash = Json.sha256(text);
        for (RegChangeItem existing : store.regChanges.values())
            if (hash.equals(existing.contentHash)) return Json.map("duplicate", true, "item", existing);
        RegChangeItem i = new RegChangeItem();
        i.id = Ids.next("RC");
        i.title = Json.str(in, "title", url != null ? url : text.substring(0, Math.min(80, text.length())));
        i.source = Json.str(in, "source", url != null ? URI.create(url).getHost() : "manual");
        i.jurisdiction = Json.str(in, "jurisdiction", "UNKNOWN");
        i.publishedAt = Json.str(in, "publishedAt", Ids.now().substring(0, 10));
        i.url = url;
        i.text = text;
        i.contentHash = hash;
        i.ingestedAt = Ids.now();
        store.regChanges.put(i.id, i);
        evidence.record("GLOBAL", "regchange.item.ingested", i.id, Json.map("itemId", i.id, "title", i.title, "contentHash", hash, "by", user, "fetch", fetchNote), "EV-7Y");
        bus.publish("regchange.items", "regchange.item.ingested", null, "reg-change-service", i.id, Json.map("itemId", i.id, "title", i.title, "jurisdiction", i.jurisdiction));
        return Json.map("duplicate", false, "item", i, "note", fetchNote);
    }

    static String htmlToText(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?is)<(script|style|noscript)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?is)<br\\s*/?>|</p>|</h\\d>|</li>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
        return s.replaceAll("[ \\t\\x0B\\f\\r]+", " ").replaceAll("\\n\\s*\\n+", "\n").trim();
    }

    public List<Requirement> searchRequirements(String jurisdiction, String q) {
        List<Requirement> l = new ArrayList<>(store.requirements.values());
        l.removeIf(r -> jurisdiction != null && !jurisdiction.isBlank() && !jurisdiction.equalsIgnoreCase(r.jurisdiction) && !"GLOBAL".equals(r.jurisdiction));
        if (q != null && !q.isBlank()) {
            String[] toks = q.toLowerCase().split("\\W+");
            l.removeIf(r -> {
                String hay = (r.title + " " + r.family + " " + r.regulation).toLowerCase();
                for (String t : toks) if (t.length() > 3 && hay.contains(t)) return false;
                return true;
            });
        }
        l.sort(Comparator.comparing(r -> r.id));
        return l;
    }
}

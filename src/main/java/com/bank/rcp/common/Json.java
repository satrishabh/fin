package com.bank.rcp.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** JSON helpers: canonical serialisation for hashing, and lenient parsing of model output. */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonParser.Feature.ALLOW_COMMENTS, true)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    private static final ObjectMapper CANONICAL = MAPPER.copy()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)
            .configure(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY, true);

    private Json() {}

    public static String write(Object o) {
        try { return MAPPER.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String pretty(Object o) {
        try { return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String canonical(Object o) {
        try { return CANONICAL.writeValueAsString(o); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static <T> T read(String s, Class<T> type) {
        try { return MAPPER.readValue(s, type); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e); }
    }

    public static <T> T read(String s, TypeReference<T> type) {
        try { return MAPPER.readValue(s, type); } catch (Exception e) { throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e); }
    }

    /** Deep copy into plain JSON structures (LinkedHashMap / ArrayList / scalars) - safe to serialise or clone. */
    public static Object plain(Object o) {
        if (o == null) return null;
        try { return MAPPER.readValue(MAPPER.writeValueAsString(o), Object.class); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static <T> T convert(Object o, Class<T> type) { return MAPPER.convertValue(o, type); }

    public static <T> T convert(Object o, TypeReference<T> type) { return MAPPER.convertValue(o, type); }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(Object o) {
        if (o == null) return new LinkedHashMap<>();
        if (o instanceof Map<?, ?> m) return new LinkedHashMap<>((Map<String, Object>) m);
        return MAPPER.convertValue(o, new TypeReference<LinkedHashMap<String, Object>>() {});
    }

    /** Extracts the first JSON object from model text (handles ```json fences and chatter). */
    public static Optional<Map<String, Object>> extractObject(String text) {
        if (text == null) return Optional.empty();
        String t = text.trim();
        int fence = t.indexOf("```");
        if (fence >= 0) {
            int start = t.indexOf('\n', fence);
            int end = t.indexOf("```", start + 1);
            if (start > 0 && end > start) t = t.substring(start + 1, end).trim();
        }
        int b = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (b < 0 || e <= b) return Optional.empty();
        try {
            return Optional.of(MAPPER.readValue(t.substring(b, e + 1), new TypeReference<LinkedHashMap<String, Object>>() {}));
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return "sha256:" + HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static String hashOf(Object o) { return sha256(canonical(o)); }

    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static List<String> strList(Object o) {
        if (o == null) return new ArrayList<>();
        if (o instanceof Collection<?> c) { List<String> r = new ArrayList<>(); for (Object x : c) r.add(String.valueOf(x)); return r; }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) return new ArrayList<>();
        List<String> r = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isBlank()) r.add(p.trim());
        return r;
    }

    public static String str(Map<String, ?> m, String k, String def) {
        Object v = m == null ? null : m.get(k);
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }

    public static long num(Map<String, ?> m, String k, long def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).replace(",", "").trim()); } catch (Exception e) { return def; }
    }

    public static double dbl(Map<String, ?> m, String k, double def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v).trim()); } catch (Exception e) { return def; }
    }

    public static boolean bool(Map<String, ?> m, String k, boolean def) {
        Object v = m == null ? null : m.get(k);
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(v));
    }
}

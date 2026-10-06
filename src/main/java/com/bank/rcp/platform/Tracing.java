package com.bank.rcp.platform;

import com.bank.rcp.common.Json;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * OpenTelemetry tracing for agents (spec Section 10). Spans follow the GenAI semantic
 * conventions ({@code invoke_agent} -> {@code chat} -> {@code execute_tool}) with the schema
 * version pinned, plus our stable internal {@code rcp.agent.*} attributes.
 *
 * <p>Exporters (OTLP/HTTP), each enabled only when its keys are configured:
 * <ul>
 *   <li>Langfuse: {@code LANGFUSE_PUBLIC_KEY}, {@code LANGFUSE_SECRET_KEY}, {@code LANGFUSE_HOST}</li>
 *   <li>LangSmith: {@code LANGSMITH_API_KEY}, {@code LANGSMITH_PROJECT}, {@code LANGSMITH_ENDPOINT}</li>
 * </ul>
 * Prompt/response content is OFF by default ({@code RCP_TRACE_CAPTURE_CONTENT=true} to enable
 * for non-regulated, synthetic workflows only). Spans are also kept in memory for the UI.
 */
@Component
public class Tracing {
    private static final Logger log = LoggerFactory.getLogger(Tracing.class);
    public static final String GENAI_SCHEMA_PIN = "otel-genai-semconv@1.42.0 (Development; pinned)";

    private final OpenTelemetrySdk sdk;
    private final Tracer tracer;
    private final boolean captureContent;
    private final List<String> exporters = new ArrayList<>();
    private final Map<String, List<Map<String, Object>>> spansByTrace = new ConcurrentHashMap<>();
    private final Deque<String> traceOrder = new ConcurrentLinkedDeque<>();

    public Tracing(@Value("${rcp.tracing.langfuse.public-key:}") String lfPk,
                   @Value("${rcp.tracing.langfuse.secret-key:}") String lfSk,
                   @Value("${rcp.tracing.langfuse.host:https://cloud.langfuse.com}") String lfHost,
                   @Value("${rcp.tracing.langsmith.api-key:}") String lsKey,
                   @Value("${rcp.tracing.langsmith.project:rcp-poc}") String lsProject,
                   @Value("${rcp.tracing.langsmith.endpoint:https://api.smith.langchain.com/otel/v1/traces}") String lsEndpoint,
                   @Value("${rcp.tracing.capture-content:false}") boolean captureContent) {
        this.captureContent = captureContent;
        SdkTracerProviderBuilder tp = SdkTracerProvider.builder()
                .setResource(Resource.getDefault().merge(Resource.create(Attributes.of(
                        AttributeKey.stringKey("service.name"), "rcp-agent-runtime",
                        AttributeKey.stringKey("service.version"), "0.9.0-POC",
                        AttributeKey.stringKey("rcp.schema"), GENAI_SCHEMA_PIN))));
        if (!lfPk.isBlank() && !lfSk.isBlank()) {
            String auth = Base64.getEncoder().encodeToString((lfPk + ":" + lfSk).getBytes(StandardCharsets.UTF_8));
            String endpoint = lfHost.replaceAll("/+$", "") + "/api/public/otel/v1/traces";
            tp.addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                    .setEndpoint(endpoint).addHeader("Authorization", "Basic " + auth).setTimeout(Duration.ofSeconds(10)).build()).build());
            exporters.add("langfuse(" + endpoint + ")");
        }
        if (!lsKey.isBlank()) {
            tp.addSpanProcessor(BatchSpanProcessor.builder(OtlpHttpSpanExporter.builder()
                    .setEndpoint(lsEndpoint).addHeader("x-api-key", lsKey).addHeader("Langsmith-Project", lsProject).setTimeout(Duration.ofSeconds(10)).build()).build());
            exporters.add("langsmith(" + lsProject + ")");
        }
        sdk = OpenTelemetrySdk.builder().setTracerProvider(tp.build()).build();
        tracer = sdk.getTracer("com.bank.rcp.agents", "0.9.0");
        log.info("Tracing exporters: {} (content capture {})", exporters.isEmpty() ? "in-memory only" : exporters, captureContent ? "ON" : "OFF");
    }

    @PreDestroy
    public void shutdown() { sdk.getSdkTracerProvider().shutdown(); }

    public List<String> exporters() { return exporters; }

    public boolean captureContent() { return captureContent; }

    /** Thin span wrapper that mirrors attributes into the in-memory store. */
    public final class RcpSpan {
        final Span span;
        final Context ctx;
        final Map<String, Object> rec = new LinkedHashMap<>();
        final Map<String, Object> attrs = new LinkedHashMap<>();
        final long t0 = System.currentTimeMillis();

        RcpSpan(Span span, Context ctx, String name, String parentId) {
            this.span = span;
            this.ctx = ctx;
            rec.put("traceId", span.getSpanContext().getTraceId());
            rec.put("spanId", span.getSpanContext().getSpanId());
            rec.put("parentId", parentId);
            rec.put("name", name);
            rec.put("start", Instant.now().toString());
            rec.put("attributes", attrs);
            String tid = span.getSpanContext().getTraceId();
            spansByTrace.computeIfAbsent(tid, k -> { traceOrder.addFirst(k); trim(); return Collections.synchronizedList(new ArrayList<>()); }).add(rec);
        }

        public String traceId() { return span.getSpanContext().getTraceId(); }

        public RcpSpan set(String k, Object v) {
            if (v == null) return this;
            if (v instanceof Number n) {
                if (v instanceof Double || v instanceof Float) span.setAttribute(k, n.doubleValue());
                else span.setAttribute(k, n.longValue());
            } else if (v instanceof Boolean b) span.setAttribute(k, b);
            else {
                String s = v instanceof String str ? str : Json.write(v);
                span.setAttribute(k, s.length() > 8000 ? s.substring(0, 8000) : s);
            }
            attrs.put(k, v instanceof String s && s.length() > 600 ? s.substring(0, 600) + "..." : v);
            return this;
        }

        /** Content attributes are only exported when capture is enabled (privacy default). */
        public RcpSpan content(String inputKey, String input, String outputKey, String output) {
            if (!captureContent) return this;
            if (input != null) set(inputKey, input);
            if (output != null) set(outputKey, output);
            return this;
        }

        public RcpSpan child(String name, SpanKind kind) { return start(name, kind, this); }

        public void end(String status, String error) {
            if (error != null) { span.setStatus(StatusCode.ERROR, error); rec.put("error", error); }
            else span.setStatus(StatusCode.OK);
            rec.put("status", status);
            rec.put("durationMs", System.currentTimeMillis() - t0);
            span.end();
        }
    }

    public RcpSpan start(String name, SpanKind kind, RcpSpan parent) {
        Context parentCtx = parent == null ? Context.root() : parent.ctx;
        Span s = tracer.spanBuilder(name).setSpanKind(kind).setParent(parentCtx).startSpan();
        return new RcpSpan(s, parentCtx.with(s), name, parent == null ? null : parent.span.getSpanContext().getSpanId());
    }

    private void trim() {
        while (traceOrder.size() > 300) {
            String old = traceOrder.pollLast();
            if (old != null) spansByTrace.remove(old);
        }
    }

    public List<Map<String, Object>> trace(String traceId) {
        List<Map<String, Object>> l = spansByTrace.get(traceId);
        return l == null ? List.of() : new ArrayList<>(l);
    }
}

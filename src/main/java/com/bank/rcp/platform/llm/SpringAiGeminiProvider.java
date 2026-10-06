package com.bank.rcp.platform.llm;

import com.google.genai.Client;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Gemini via Spring AI ({@code spring-ai-google-genai}). Built programmatically rather than via
 * auto-configuration so the application starts (in mock mode) when no API key is configured.
 */
@Component
public class SpringAiGeminiProvider implements LlmProvider {
    private final String apiKey;
    private volatile String modelName;
    private volatile GoogleGenAiChatModel model;

    public SpringAiGeminiProvider(@Value("${rcp.llm.gemini.api-key:}") String apiKey,
                                  @Value("${rcp.llm.gemini.model:gemini-2.5-flash}") String modelName) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = modelName;
    }

    @Override public String id() { return "springai"; }

    @Override public String description() { return "Gemini via Spring AI GoogleGenAiChatModel"; }

    @Override public boolean available() { return !apiKey.isEmpty(); }

    @Override public String model() { return modelName; }

    public synchronized void setModel(String m) { this.modelName = m; this.model = null; }

    private GoogleGenAiChatModel model() {
        GoogleGenAiChatModel m = model;
        if (m == null) {
            synchronized (this) {
                if (model == null) {
                    GoogleGenAiChatOptions.Builder ob = GoogleGenAiChatOptions.builder();
                    ob.model(modelName);
                    ob.temperature(0.1);
                    model = GoogleGenAiChatModel.builder()
                            .genAiClient(Client.builder().apiKey(apiKey).build())
                            .options(ob.build())
                            .toolCallingManager(ToolCallingManager.builder().build())
                            .retryTemplate(new RetryTemplate())
                            .observationRegistry(ObservationRegistry.NOOP)
                            .build();
                }
                m = model;
            }
        }
        return m;
    }

    @Override
    public LlmResult chat(String system, String user, boolean jsonOutput) {
        Prompt prompt;
        if (jsonOutput) {
            GoogleGenAiChatOptions.Builder ob = GoogleGenAiChatOptions.builder();
            ob.model(modelName);
            ob.temperature(0.1);
            ob.responseMimeType("application/json");
            prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), ob.build());
        } else {
            prompt = new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
        }
        ChatResponse r = model().call(prompt);
        long in = 0, out = 0;
        if (r.getMetadata() != null && r.getMetadata().getUsage() != null) {
            Integer p = r.getMetadata().getUsage().getPromptTokens();
            Integer c = r.getMetadata().getUsage().getCompletionTokens();
            in = p == null ? 0 : p;
            out = c == null ? 0 : c;
        }
        return new LlmResult(r.getResult().getOutput().getText(), in, out, modelName, id());
    }
}

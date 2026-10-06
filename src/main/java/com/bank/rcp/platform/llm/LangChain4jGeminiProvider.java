package com.bank.rcp.platform.llm;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;

/** Gemini via LangChain4j ({@code langchain4j-google-ai-gemini}). */
@Component
public class LangChain4jGeminiProvider implements LlmProvider {
    private final String apiKey;
    private volatile String modelName;
    private volatile ChatModel model;

    public LangChain4jGeminiProvider(@Value("${rcp.llm.gemini.api-key:}") String apiKey,
                                     @Value("${rcp.llm.gemini.model:gemini-2.5-flash}") String modelName) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.modelName = modelName;
    }

    @Override public String id() { return "langchain4j"; }

    @Override public String description() { return "Gemini via LangChain4j GoogleAiGeminiChatModel"; }

    @Override public boolean available() { return !apiKey.isEmpty(); }

    @Override public String model() { return modelName; }

    public synchronized void setModel(String m) { this.modelName = m; this.model = null; }

    private ChatModel model() {
        ChatModel m = model;
        if (m == null) {
            synchronized (this) {
                if (model == null) {
                    model = GoogleAiGeminiChatModel.builder()
                            .apiKey(apiKey)
                            .modelName(modelName)
                            .temperature(0.1)
                            .timeout(Duration.ofSeconds(90))
                            .maxRetries(2)
                            .build();
                }
                m = model;
            }
        }
        return m;
    }

    @Override
    public LlmResult chat(String system, String user, boolean jsonOutput) {
        ChatRequest.Builder b = ChatRequest.builder().messages(SystemMessage.from(system), UserMessage.from(user));
        if (jsonOutput) b.responseFormat(ResponseFormat.JSON);
        ChatResponse r = model().chat(b.build());
        long in = 0, out = 0;
        if (r.tokenUsage() != null) {
            in = r.tokenUsage().inputTokenCount() == null ? 0 : r.tokenUsage().inputTokenCount();
            out = r.tokenUsage().outputTokenCount() == null ? 0 : r.tokenUsage().outputTokenCount();
        }
        return new LlmResult(r.aiMessage().text(), in, out, modelName, id());
    }
}

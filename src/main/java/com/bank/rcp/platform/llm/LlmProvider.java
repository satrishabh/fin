package com.bank.rcp.platform.llm;

/** Internal model interface: the agent framework and vendor SDK sit behind it (swap cost stays low). */
public interface LlmProvider {

    record LlmResult(String text, long inputTokens, long outputTokens, String model, String provider) {}

    String id();

    String description();

    boolean available();

    String model();

    LlmResult chat(String system, String user, boolean jsonOutput);
}

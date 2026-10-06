package com.bank.rcp.agents;

import java.util.Map;

/** An agent implementation. Identity, allowlist and autonomy come from its registered AgentDef. */
public interface Agent {
    String id();

    Map<String, Object> execute(AgentContext ctx) throws Exception;
}

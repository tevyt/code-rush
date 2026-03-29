package dev.coderush.common.dto;

import dev.coderush.common.model.AgentState;

public record HeartbeatRequest(
    String agentName,
    AgentState state,
    Long currentBuildId
) {}

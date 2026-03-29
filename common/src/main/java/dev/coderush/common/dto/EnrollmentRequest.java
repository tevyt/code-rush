package dev.coderush.common.dto;

public record EnrollmentRequest(
    String agentName,
    String agentAddress,
    String nonce
) {}

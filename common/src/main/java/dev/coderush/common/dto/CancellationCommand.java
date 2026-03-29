package dev.coderush.common.dto;

public record CancellationCommand(
    long buildId,
    String signature
) {}

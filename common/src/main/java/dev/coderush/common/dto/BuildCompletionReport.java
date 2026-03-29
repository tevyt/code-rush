package dev.coderush.common.dto;

import dev.coderush.common.model.BuildStatus;

public record BuildCompletionReport(
    long buildId,
    BuildStatus status,
    String errorMessage
) {}

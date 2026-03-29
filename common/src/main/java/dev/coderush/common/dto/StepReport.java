package dev.coderush.common.dto;

import dev.coderush.common.model.StepStatus;

public record StepReport(
    int stepNumber,
    String name,
    String script,
    StepStatus status,
    String log
) {}

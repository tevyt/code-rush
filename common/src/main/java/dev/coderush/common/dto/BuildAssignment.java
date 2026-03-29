package dev.coderush.common.dto;

public record BuildAssignment(
    long buildId,
    String repositoryUrl,
    String branch,
    String commitSha,
    String sshKey,
    String signature
) {}

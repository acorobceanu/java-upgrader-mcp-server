package com.javaupgrader.dto;

public record UpgradeAcceptedResponse(String message, String githubUrl, int targetJavaVersion, String jobId) {}

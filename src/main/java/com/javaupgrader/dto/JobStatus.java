package com.javaupgrader.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record JobStatus(
    String jobId,
    String state,            // "pending" | "succeeded" | "no_changes" | "failed"
    String githubUrl,
    int targetJavaVersion,
    String prUrl,            // set on "succeeded"
    String issueUrl,         // set on "failed" when a GitHub issue was opened
    String error             // set on "failed"
) {
    public static JobStatus pending(String jobId, String githubUrl, int targetVersion) {
        return new JobStatus(jobId, "pending", githubUrl, targetVersion, null, null, null);
    }

    public static JobStatus succeeded(String jobId, String githubUrl, int targetVersion, String prUrl) {
        return new JobStatus(jobId, "succeeded", githubUrl, targetVersion, prUrl, null, null);
    }

    public static JobStatus noChanges(String jobId, String githubUrl, int targetVersion) {
        return new JobStatus(jobId, "no_changes", githubUrl, targetVersion, null, null, null);
    }

    public static JobStatus failed(String jobId, String githubUrl, int targetVersion, String error, String issueUrl) {
        return new JobStatus(jobId, "failed", githubUrl, targetVersion, null, issueUrl, error);
    }
}

package com.javaupgrader.mcp;

import com.javaupgrader.service.JobStore;
import com.javaupgrader.service.UpgradeJobService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

@Component
public class JavaUpgraderTools {

    private final UpgradeJobService jobService;
    private final JobStore jobStore;

    public JavaUpgraderTools(UpgradeJobService jobService, JobStore jobStore) {
        this.jobService = jobService;
        this.jobStore = jobStore;
    }

    @McpTool(
        description = """
            Start an asynchronous upgrade of a GitHub Java project to a newer Java version. \
            Clones the repository, applies Java modernization changes using an AI agent \
            (build config, var, text blocks, records, switch expressions, pattern matching, etc.), \
            commits the result to a new branch, and opens a pull request. \
            Returns a job ID immediately — poll the result with get_upgrade_status.\
            """,
        annotations = @McpTool.McpAnnotations(idempotentHint = false)
    )
    public String upgradeJava(
        @McpToolParam(description = "GitHub repository URL (HTTPS or SSH), e.g. https://github.com/owner/repo")
        String githubUrl,
        @McpToolParam(description = "Target Java version to upgrade to (e.g. 17, 21). Defaults to 21 if omitted.", required = false)
        Integer targetJavaVersion
    ) {
        String jobId = jobService.submit(githubUrl, targetJavaVersion != null ? targetJavaVersion : 21);
        return "Job started. ID: " + jobId + "\nStatus: PENDING\nPoll with: get_upgrade_status(\"" + jobId + "\")";
    }

    @McpTool(
        description = """
            Check the status of an upgrade job started with upgrade_java. \
            Returns PENDING (queued), RUNNING (in progress), \
            COMPLETE (with the PR URL or no-changes message), or FAILED (with the error detail).\
            """,
        annotations = @McpTool.McpAnnotations(idempotentHint = true)
    )
    public String getUpgradeStatus(
        @McpToolParam(description = "Job ID returned by upgrade_java")
        String jobId
    ) {
        return jobStore.get(jobId)
            .map(job -> switch (job.status()) {
                case PENDING  -> "Status: PENDING\nThe job is queued and will start shortly.";
                case RUNNING  -> "Status: RUNNING\nUpgrade is in progress — check again in a minute.";
                case COMPLETE -> "Status: COMPLETE\n" + job.result();
                case FAILED   -> "Status: FAILED\n" + job.error();
            })
            .orElse("No job found with ID: " + jobId);
    }
}

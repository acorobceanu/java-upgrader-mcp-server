package com.javaupgrader.mcp;

import com.javaupgrader.service.JobStore;
import com.javaupgrader.service.JobStore.Status;
import com.javaupgrader.service.UpgradeJobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JavaUpgraderToolsTest {

    private static final String GITHUB_URL = "https://github.com/owner/repo";

    private UpgradeJobService jobService;
    private JobStore jobStore;
    private JavaUpgraderTools tools;

    @BeforeEach
    void setup() {
        jobService = mock(UpgradeJobService.class);
        jobStore = new JobStore();
        tools = new JavaUpgraderTools(jobService, jobStore);
    }

    // --- upgradeJava ---

    @Test
    void upgradeJava_nullVersion_submitsWithDefault21() {
        when(jobService.submit(GITHUB_URL, 21)).thenReturn("abc-123");

        tools.upgradeJava(GITHUB_URL, null);

        verify(jobService).submit(GITHUB_URL, 21);
    }

    @Test
    void upgradeJava_explicitVersion_passedThrough() {
        when(jobService.submit(GITHUB_URL, 17)).thenReturn("abc-456");

        tools.upgradeJava(GITHUB_URL, 17);

        verify(jobService).submit(GITHUB_URL, 17);
    }

    @Test
    void upgradeJava_responseContainsJobId() {
        when(jobService.submit(GITHUB_URL, 21)).thenReturn("my-job-id");

        String result = tools.upgradeJava(GITHUB_URL, null);

        assertTrue(result.contains("my-job-id"));
    }

    @Test
    void upgradeJava_responseContainsPendingStatus() {
        when(jobService.submit(GITHUB_URL, 21)).thenReturn("my-job-id");

        String result = tools.upgradeJava(GITHUB_URL, null);

        assertTrue(result.contains("PENDING"));
    }

    // --- getUpgradeStatus ---

    @Test
    void getUpgradeStatus_unknownJobId_returnsNotFound() {
        String result = tools.getUpgradeStatus("no-such-id");

        assertTrue(result.contains("No job found"));
        assertTrue(result.contains("no-such-id"));
    }

    @Test
    void getUpgradeStatus_pendingJob_returnsPending() {
        String id = jobStore.create(); // initial status is PENDING

        String result = tools.getUpgradeStatus(id);

        assertTrue(result.contains("PENDING"));
    }

    @Test
    void getUpgradeStatus_runningJob_returnsRunning() {
        String id = jobStore.create();
        jobStore.markRunning(id);

        String result = tools.getUpgradeStatus(id);

        assertTrue(result.contains("RUNNING"));
    }

    @Test
    void getUpgradeStatus_completeJob_returnsResult() {
        String id = jobStore.create();
        jobStore.markComplete(id, "PR: https://github.com/owner/repo/pull/1");

        String result = tools.getUpgradeStatus(id);

        assertTrue(result.contains("COMPLETE"));
        assertTrue(result.contains("PR:"));
    }

    @Test
    void getUpgradeStatus_failedJob_returnsError() {
        String id = jobStore.create();
        jobStore.markFailed(id, "Clone failed: 401 Unauthorized");

        String result = tools.getUpgradeStatus(id);

        assertTrue(result.contains("FAILED"));
        assertTrue(result.contains("Clone failed"));
    }
}

package com.javaupgrader.service;

import com.javaupgrader.service.JobStore.Status;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UpgradeJobServiceTest {

    private static final String GITHUB_URL = "https://github.com/owner/repo";

    @Test
    void submit_returnsJobIdImmediately() {
        var store = new JobStore();
        var orchestration = mock(UpgradeOrchestrationService.class);
        var service = new UpgradeJobService(store, orchestration);

        String jobId = service.submit(GITHUB_URL, 21);

        assertFalse(jobId.isBlank());
        assertTrue(store.get(jobId).isPresent());
        service.shutdown();
    }

    @Test
    void submit_jobReachesCompleteOnSuccess() throws InterruptedException {
        var store = new JobStore();
        var orchestration = mock(UpgradeOrchestrationService.class);
        when(orchestration.upgrade(GITHUB_URL, 21)).thenReturn("PR: https://github.com/owner/repo/pull/1");
        var service = new UpgradeJobService(store, orchestration);

        String jobId = service.submit(GITHUB_URL, 21);
        awaitTerminal(store, jobId, Duration.ofSeconds(5));

        var job = store.get(jobId).orElseThrow();
        assertEquals(Status.COMPLETE, job.status());
        assertTrue(job.result().contains("PR:"));
        service.shutdown();
    }

    @Test
    void submit_jobReachesFailedOnException() throws InterruptedException {
        var store = new JobStore();
        var orchestration = mock(UpgradeOrchestrationService.class);
        when(orchestration.upgrade(GITHUB_URL, 21)).thenThrow(new RuntimeException("Clone failed"));
        var service = new UpgradeJobService(store, orchestration);

        String jobId = service.submit(GITHUB_URL, 21);
        awaitTerminal(store, jobId, Duration.ofSeconds(5));

        var job = store.get(jobId).orElseThrow();
        assertEquals(Status.FAILED, job.status());
        assertTrue(job.error().contains("Clone failed"));
        service.shutdown();
    }

    @Test
    void submit_passesVersionToOrchestration() throws InterruptedException {
        var store = new JobStore();
        var orchestration = mock(UpgradeOrchestrationService.class);
        when(orchestration.upgrade(GITHUB_URL, 17)).thenReturn("done");
        var service = new UpgradeJobService(store, orchestration);

        String jobId = service.submit(GITHUB_URL, 17);
        awaitTerminal(store, jobId, Duration.ofSeconds(5));

        verify(orchestration).upgrade(GITHUB_URL, 17);
        service.shutdown();
    }

    @Test
    void submit_marksFailedWhenExecutorIsShutDown() {
        var store = new JobStore();
        var orchestration = mock(UpgradeOrchestrationService.class);
        var service = new UpgradeJobService(store, orchestration);

        // Shut down the executor before submitting so it rejects the task.
        service.shutdown();

        String jobId = service.submit(GITHUB_URL, 21);

        var job = store.get(jobId).orElseThrow();
        assertEquals(Status.FAILED, job.status(),
                "Job should be FAILED when the executor rejects it");
        assertNotNull(job.error());
        assertTrue(job.error().contains("shut") || job.error().contains("queue"),
                "Error message should mention shutdown/queue, got: " + job.error());
    }

    private static void awaitTerminal(JobStore store, String jobId, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            var status = store.get(jobId).orElseThrow().status();
            if (status == Status.COMPLETE || status == Status.FAILED) return;
            Thread.sleep(50);
        }
        fail("Job " + jobId + " did not reach a terminal status within " + timeout);
    }
}

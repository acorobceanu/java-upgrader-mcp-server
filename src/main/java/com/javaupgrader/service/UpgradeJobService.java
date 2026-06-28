package com.javaupgrader.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

@Service
public class UpgradeJobService {

    private static final Logger log = LoggerFactory.getLogger(UpgradeJobService.class);

    private final JobStore jobStore;
    private final UpgradeOrchestrationService orchestrationService;

    // Single worker so concurrent upgrades don't exhaust disk space or Anthropic API quota.
    // Increase the pool size here if parallel capacity is needed.
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        var t = new Thread(r, "upgrade-worker");
        t.setDaemon(true);
        return t;
    });

    public UpgradeJobService(JobStore jobStore, UpgradeOrchestrationService orchestrationService) {
        this.jobStore = jobStore;
        this.orchestrationService = orchestrationService;
    }

    /**
     * Enqueues an upgrade job and returns its ID immediately.
     * The caller should poll {@link JobStore#get(String)} (via the MCP tool) for the outcome.
     */
    public String submit(String githubUrl, int targetJavaVersion) {
        String jobId = jobStore.create();
        try {
            executor.submit(() -> {
                jobStore.markRunning(jobId);
                try {
                    String result = orchestrationService.upgrade(githubUrl, targetJavaVersion);
                    jobStore.markComplete(jobId, result);
                } catch (Exception e) {
                    String error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.error("Job {} failed: {}", jobId, error, e);
                    jobStore.markFailed(jobId, error);
                }
            });
        } catch (RejectedExecutionException e) {
            log.warn("Job {} rejected — executor is shut down", jobId);
            jobStore.markFailed(jobId, "Server is shutting down; job could not be queued.");
        }
        return jobId;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

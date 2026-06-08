package com.javaupgrader.controller;

import com.javaupgrader.dto.JobStatus;
import com.javaupgrader.dto.UpgradeAcceptedResponse;
import com.javaupgrader.dto.UpgradeRequest;
import com.javaupgrader.service.JobStore;
import com.javaupgrader.service.UpgradeOrchestrationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UpgradeController {

    private final UpgradeOrchestrationService upgradeService;
    private final JobStore jobStore;

    public UpgradeController(UpgradeOrchestrationService upgradeService, JobStore jobStore) {
        this.upgradeService = upgradeService;
        this.jobStore = jobStore;
    }

    /**
     * POST /api/upgrade
     *
     * Body: { "githubUrl": "https://github.com/owner/repo", "targetJavaVersion": 21 }
     *
     * Returns 202 immediately. The upgrade runs in the background.
     * Poll GET /api/upgrade/{jobId} for status, or watch GitHub for a new pull request.
     */
    @PostMapping("/upgrade")
    public ResponseEntity<UpgradeAcceptedResponse> upgrade(@RequestBody UpgradeRequest request) {
        if (request.githubUrl() == null || request.githubUrl().isBlank()) {
            return ResponseEntity.badRequest()
                .body(new UpgradeAcceptedResponse("githubUrl is required", null, 0, null));
        }

        String jobId = UUID.randomUUID().toString();
        jobStore.put(jobId, JobStatus.pending(jobId, request.githubUrl(), request.effectiveTargetVersion()));
        upgradeService.upgrade(request, jobId);

        String message = ("Upgrade started for %s (Java %d). " +
            "Monitor the repository for a new pull request, " +
            "or poll GET /api/upgrade/%s for status.")
            .formatted(request.githubUrl(), request.effectiveTargetVersion(), jobId);

        return ResponseEntity.accepted()
            .body(new UpgradeAcceptedResponse(message, request.githubUrl(), request.effectiveTargetVersion(), jobId));
    }

    /**
     * GET /api/upgrade/{jobId}
     *
     * Returns current job status. State is one of:
     *   "pending"    — running
     *   "succeeded"  — PR created (see prUrl)
     *   "no_changes" — project already up to date
     *   "failed"     — error (see error + issueUrl if a GitHub issue was opened)
     */
    @GetMapping("/upgrade/{jobId}")
    public ResponseEntity<JobStatus> getStatus(@PathVariable String jobId) {
        return jobStore.get(jobId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}

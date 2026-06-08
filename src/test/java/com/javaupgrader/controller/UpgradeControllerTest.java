package com.javaupgrader.controller;

import com.javaupgrader.dto.JobStatus;
import com.javaupgrader.service.JobStore;
import com.javaupgrader.service.UpgradeOrchestrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UpgradeController.class)
class UpgradeControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    UpgradeOrchestrationService upgradeService;

    @MockBean
    JobStore jobStore;

    @Test
    void upgrade_returnsAcceptedWithJobId() throws Exception {
        mockMvc.perform(post("/api/upgrade")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubUrl\":\"https://github.com/owner/repo\"}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").exists())
            .andExpect(jsonPath("$.githubUrl").value("https://github.com/owner/repo"))
            .andExpect(jsonPath("$.targetJavaVersion").value(21))
            .andExpect(jsonPath("$.jobId").isNotEmpty());

        verify(upgradeService).upgrade(any(), anyString());
    }

    @Test
    void upgrade_withExplicitVersion_reflectsVersionInResponse() throws Exception {
        mockMvc.perform(post("/api/upgrade")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubUrl\":\"https://github.com/owner/repo\",\"targetJavaVersion\":17}"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.targetJavaVersion").value(17));
    }

    @Test
    void upgrade_missingUrl_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/upgrade")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void upgrade_blankUrl_returnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/upgrade")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"githubUrl\":\"   \"}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void getStatus_pendingJob_returnsStatus() throws Exception {
        var status = JobStatus.pending("job-1", "https://github.com/owner/repo", 21);
        when(jobStore.get("job-1")).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/upgrade/job-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.jobId").value("job-1"))
            .andExpect(jsonPath("$.state").value("pending"))
            .andExpect(jsonPath("$.prUrl").doesNotExist())
            .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void getStatus_succeededJob_includesPrUrl() throws Exception {
        var status = JobStatus.succeeded("job-2", "https://github.com/owner/repo", 21,
            "https://github.com/owner/repo/pull/5");
        when(jobStore.get("job-2")).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/upgrade/job-2"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("succeeded"))
            .andExpect(jsonPath("$.prUrl").value("https://github.com/owner/repo/pull/5"));
    }

    @Test
    void getStatus_failedJob_includesErrorAndIssueUrl() throws Exception {
        var status = JobStatus.failed("job-3", "https://github.com/owner/repo", 21,
            "Push failed", "https://github.com/owner/repo/issues/10");
        when(jobStore.get("job-3")).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/upgrade/job-3"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("failed"))
            .andExpect(jsonPath("$.error").value("Push failed"))
            .andExpect(jsonPath("$.issueUrl").value("https://github.com/owner/repo/issues/10"));
    }

    @Test
    void getStatus_failedJobWithNoIssue_omitsIssueUrl() throws Exception {
        var status = JobStatus.failed("job-4", "https://github.com/owner/repo", 21,
            "Token missing", null);
        when(jobStore.get("job-4")).thenReturn(Optional.of(status));

        mockMvc.perform(get("/api/upgrade/job-4"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("failed"))
            .andExpect(jsonPath("$.issueUrl").doesNotExist());
    }

    @Test
    void getStatus_unknownJob_returnsNotFound() throws Exception {
        when(jobStore.get("unknown")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/upgrade/unknown"))
            .andExpect(status().isNotFound());
    }
}

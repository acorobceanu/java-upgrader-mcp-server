package com.javaupgrader.service;

import com.javaupgrader.dto.JobStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class JobStoreTest {

    private final JobStore store = new JobStore();

    @Test
    void get_unknownJobId_returnsEmpty() {
        assertTrue(store.get("nonexistent").isEmpty());
    }

    @Test
    void put_thenGet_returnsStatus() {
        var status = JobStatus.pending("job-1", "https://github.com/o/r", 21);
        store.put("job-1", status);
        assertEquals(Optional.of(status), store.get("job-1"));
    }

    @Test
    void put_overwritesExistingEntry() {
        store.put("job-1", JobStatus.pending("job-1", "https://github.com/o/r", 21));
        var succeeded = JobStatus.succeeded("job-1", "https://github.com/o/r", 21, "https://github.com/o/r/pull/1");
        store.put("job-1", succeeded);
        assertEquals("succeeded", store.get("job-1").get().state());
    }

    @Test
    void failedJobWithIssue_issueUrlPresent() {
        var status = JobStatus.failed("job-2", "https://github.com/o/r", 21,
            "push failed", "https://github.com/o/r/issues/3");
        store.put("job-2", status);
        assertEquals("https://github.com/o/r/issues/3", store.get("job-2").get().issueUrl());
    }

    @Test
    void failedJobWithoutIssue_issueUrlIsNull() {
        var status = JobStatus.failed("job-3", "https://github.com/o/r", 21, "token missing", null);
        store.put("job-3", status);
        assertNull(store.get("job-3").get().issueUrl());
    }
}

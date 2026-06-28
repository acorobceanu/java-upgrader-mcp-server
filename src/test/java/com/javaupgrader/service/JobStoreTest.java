package com.javaupgrader.service;

import com.javaupgrader.service.JobStore.Status;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JobStoreTest {

    private final JobStore store = new JobStore();

    @Test
    void create_returnsNonBlankId() {
        assertFalse(store.create().isBlank());
    }

    @Test
    void create_returnsUniqueIds() {
        assertNotEquals(store.create(), store.create());
    }

    @Test
    void create_initialStatusIsPending() {
        var job = store.get(store.create()).orElseThrow();
        assertEquals(Status.PENDING, job.status());
        assertNull(job.result());
        assertNull(job.error());
    }

    @Test
    void markRunning_updatesStatus() {
        String id = store.create();
        store.markRunning(id);
        assertEquals(Status.RUNNING, store.get(id).orElseThrow().status());
    }

    @Test
    void markComplete_updatesStatusAndResult() {
        String id = store.create();
        store.markComplete(id, "PR: https://github.com/owner/repo/pull/1");
        var job = store.get(id).orElseThrow();
        assertEquals(Status.COMPLETE, job.status());
        assertEquals("PR: https://github.com/owner/repo/pull/1", job.result());
        assertNull(job.error());
    }

    @Test
    void markFailed_updatesStatusAndError() {
        String id = store.create();
        store.markFailed(id, "Clone failed: authentication error");
        var job = store.get(id).orElseThrow();
        assertEquals(Status.FAILED, job.status());
        assertEquals("Clone failed: authentication error", job.error());
        assertNull(job.result());
    }

    @Test
    void get_unknownId_returnsEmpty() {
        assertTrue(store.get("no-such-id").isEmpty());
    }

    @Test
    void markRunning_unknownId_doesNothing() {
        store.markRunning("no-such-id"); // must not throw
        assertTrue(store.get("no-such-id").isEmpty());
    }
}

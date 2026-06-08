package com.javaupgrader.service;

import com.javaupgrader.dto.JobStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class JobStore {

    private final ConcurrentHashMap<String, JobStatus> store = new ConcurrentHashMap<>();

    public void put(String jobId, JobStatus status) {
        store.put(jobId, status);
    }

    public Optional<JobStatus> get(String jobId) {
        return Optional.ofNullable(store.get(jobId));
    }
}

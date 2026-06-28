package com.javaupgrader.service;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

@Component
public class JobStore {

    public enum Status { PENDING, RUNNING, COMPLETE, FAILED }

    public record Job(
        String id,
        Status status,
        String result,
        String error,
        Instant createdAt,
        Instant updatedAt
    ) {}

    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();

    public String create() {
        String id = UUID.randomUUID().toString();
        jobs.put(id, new Job(id, Status.PENDING, null, null, Instant.now(), Instant.now()));
        return id;
    }

    public void markRunning(String id) {
        update(id, j -> new Job(j.id(), Status.RUNNING, null, null, j.createdAt(), Instant.now()));
    }

    public void markComplete(String id, String result) {
        update(id, j -> new Job(j.id(), Status.COMPLETE, result, null, j.createdAt(), Instant.now()));
    }

    public void markFailed(String id, String error) {
        update(id, j -> new Job(j.id(), Status.FAILED, null, error, j.createdAt(), Instant.now()));
    }

    public Optional<Job> get(String id) {
        return Optional.ofNullable(jobs.get(id));
    }

    private void update(String id, UnaryOperator<Job> fn) {
        jobs.computeIfPresent(id, (k, j) -> fn.apply(j));
    }
}

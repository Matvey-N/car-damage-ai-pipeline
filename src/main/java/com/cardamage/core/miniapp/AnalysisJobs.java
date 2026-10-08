package com.cardamage.core.miniapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/**
 * Background analyses of the Mini App.
 *
 * Why: analyzing a set of photos takes from ten seconds to several minutes. As
 * one long HTTP request it broke whenever the phone locked, the user switched
 * apps or the tunnel dropped the connection ("context canceled" in cloudflared),
 * and the work was lost. Now the request only starts a job and returns at once;
 * the page asks for the progress, and the result is stored as an inspection
 * even if nobody is waiting for it any more.
 *
 * Jobs live in memory: after a restart of the service unfinished jobs are gone
 * (finished ones are already stored). Finished jobs are forgotten after keepSeconds.
 */
public class AnalysisJobs {

    public enum State { QUEUED, RUNNING, DONE, FAILED }

    /** The work of a job; reports each finished photo, returns the id of the stored inspection. */
    @FunctionalInterface
    public interface Work {
        String run(Runnable photoDone) throws Exception;
    }

    /** Called once when a job ends (done or failed), e.g. to notify the user in the chat. */
    @FunctionalInterface
    public interface Listener {
        void finished(Job job);
    }

    public static final class Job {
        private final String id;
        private final long userId;
        private final long createdAt;
        private final int total;
        private final AtomicInteger done = new AtomicInteger();
        private volatile State state = State.QUEUED;
        private volatile String inspectionId;
        private volatile String error;
        private volatile long finishedAt;
        private volatile long lastSeenAt;

        Job(String id, long userId, long createdAt, int total) {
            this.id = id;
            this.userId = userId;
            this.createdAt = createdAt;
            this.total = total;
            this.lastSeenAt = createdAt;
        }

        public String id() { return id; }
        public long userId() { return userId; }
        public long createdAt() { return createdAt; }
        public int total() { return total; }
        public int done() { return done.get(); }
        public State state() { return state; }
        public String inspectionId() { return inspectionId; }
        public String error() { return error; }
        /** Last time the page asked for this job: tells whether somebody is still waiting. */
        public long lastSeenAt() { return lastSeenAt; }
        public boolean active() { return state == State.QUEUED || state == State.RUNNING; }
    }

    /** Thrown when a user already has the maximum number of unfinished jobs. */
    public static class TooManyJobs extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public TooManyJobs(String message) {
            super(message);
        }
    }

    private final ExecutorService runner;
    private final int maxActivePerUser;
    private final long keepSeconds;
    private final LongSupplier clock;
    private final Listener listener;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();

    public AnalysisJobs(ExecutorService runner, int maxActivePerUser, long keepSeconds, LongSupplier clock,
                        Listener listener) {
        this.runner = runner;
        this.maxActivePerUser = maxActivePerUser;
        this.keepSeconds = keepSeconds;
        this.clock = clock;
        this.listener = listener == null ? j -> { } : listener;
    }

    public synchronized Job submit(long userId, int total, Work work) {
        forgetOld();
        long active = jobs.values().stream().filter(j -> j.userId == userId && j.active()).count();
        if (active >= maxActivePerUser) {
            throw new TooManyJobs("Wait until your current analysis is finished");
        }
        Job job = new Job(UUID.randomUUID().toString(), userId, clock.getAsLong(), total);
        jobs.put(job.id, job);
        try {
            runner.execute(() -> run(job, work));
        } catch (RejectedExecutionException e) {
            jobs.remove(job.id);
            throw new TooManyJobs("The service is busy, try again in a minute");
        }
        return job;
    }

    private void run(Job job, Work work) {
        job.state = State.RUNNING;
        try {
            job.inspectionId = work.run(job.done::incrementAndGet);
            job.state = State.DONE;
        } catch (Exception | Error e) {
            job.error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            job.state = State.FAILED;
        } finally {
            job.finishedAt = clock.getAsLong();
            try {
                listener.finished(job);
            } catch (RuntimeException ignored) {
                // a failed notification must not change the job
            }
        }
    }

    /** The job, only for its owner (others get empty, as for a missing id). Marks it as seen. */
    public Optional<Job> get(String id, long userId) {
        Job job = id == null ? null : jobs.get(id);
        if (job == null || job.userId != userId) {
            return Optional.empty();
        }
        job.lastSeenAt = clock.getAsLong();
        return Optional.of(job);
    }

    /** Unfinished jobs of the user (to show them after the page was reopened). */
    public List<Job> active(long userId) {
        List<Job> out = new ArrayList<>();
        for (Job j : jobs.values()) {
            if (j.userId == userId && j.active()) {
                out.add(j);
            }
        }
        out.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        return out;
    }

    private void forgetOld() {
        long now = clock.getAsLong();
        jobs.values().removeIf(j -> !j.active() && now - j.finishedAt > keepSeconds);
    }
}

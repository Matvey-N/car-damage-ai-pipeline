package com.cardamage.core.miniapp;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AnalysisJobsTest {

    private final AtomicLong clock = new AtomicLong(1000);
    private final List<AnalysisJobs.Job> finished = Collections.synchronizedList(new ArrayList<>());

    private AnalysisJobs jobs(ExecutorService pool) {
        return new AnalysisJobs(pool, 1, 600, clock::get, finished::add);
    }

    private static void waitFor(AnalysisJobs.Job job) throws InterruptedException {
        for (int i = 0; i < 200 && job.active(); i++) {
            Thread.sleep(10);
        }
        assertFalse(job.active(), "job did not finish");
    }

    @Test
    void jobReportsProgressAndResult() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            CountDownLatch go = new CountDownLatch(1);
            AnalysisJobs jobs = jobs(pool);
            AnalysisJobs.Job job = jobs.submit(7, 3, photoDone -> {
                photoDone.run();
                go.await(5, TimeUnit.SECONDS);
                photoDone.run();
                photoDone.run();
                return "inspection-1";
            });
            assertTrue(job.active());
            assertEquals(List.of(job), jobs.active(7));
            go.countDown();
            waitFor(job);
            assertEquals(AnalysisJobs.State.DONE, job.state());
            assertEquals(3, job.done());
            assertEquals("inspection-1", job.inspectionId());
            assertTrue(jobs.active(7).isEmpty());
            for (int i = 0; i < 100 && finished.isEmpty(); i++) {
                Thread.sleep(10);
            }
            assertEquals(List.of(job), finished, "listener called once");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failureIsReported() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AnalysisJobs.Job job = jobs(pool).submit(7, 1, photoDone -> {
                throw new IllegalStateException("database error");
            });
            waitFor(job);
            assertEquals(AnalysisJobs.State.FAILED, job.state());
            assertEquals("database error", job.error());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void onlyTheOwnerSeesTheJobAndOneActiveJobPerUser() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch go = new CountDownLatch(1);
            AnalysisJobs jobs = jobs(pool);
            AnalysisJobs.Job job = jobs.submit(7, 1, photoDone -> {
                go.await(5, TimeUnit.SECONDS);
                return "x";
            });
            assertTrue(jobs.get(job.id(), 7).isPresent());
            assertTrue(jobs.get(job.id(), 8).isEmpty());
            assertTrue(jobs.get("nope", 7).isEmpty());
            assertThrows(AnalysisJobs.TooManyJobs.class, () -> jobs.submit(7, 1, photoDone -> "y"));
            AnalysisJobs.Job other = jobs.submit(8, 1, photoDone -> "z");   // another user may start
            go.countDown();
            waitFor(job);
            waitFor(other);
            assertNotNull(jobs.submit(7, 1, photoDone -> "again"), "after the end a new job is allowed");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void finishedJobsAreForgottenAfterAWhile() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AnalysisJobs jobs = jobs(pool);
            AnalysisJobs.Job job = jobs.submit(7, 1, photoDone -> "x");
            waitFor(job);
            clock.addAndGet(601);
            jobs.submit(9, 1, photoDone -> "y");   // cleaning happens on submit
            assertTrue(jobs.get(job.id(), 7).isEmpty());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void pollingMarksTheJobAsSeen() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AnalysisJobs jobs = jobs(pool);
            AnalysisJobs.Job job = jobs.submit(7, 1, photoDone -> "x");
            clock.set(1050);
            jobs.get(job.id(), 7);
            assertEquals(1050, job.lastSeenAt());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rateLimiterGivesBackUnits() {
        RateLimiter limiter = new RateLimiter(4, 3600);
        assertTrue(limiter.tryAcquire(1, 4, 100));
        assertFalse(limiter.tryAcquire(1, 1, 100));
        limiter.release(1, 2);
        assertTrue(limiter.tryAcquire(1, 2, 100));
    }
}

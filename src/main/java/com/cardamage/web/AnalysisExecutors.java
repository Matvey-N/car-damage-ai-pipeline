package com.cardamage.web;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pools of the service. They are separate on purpose: a job waits for its
 * photos and a tiled photo waits for its tiles, so a task must never wait for a
 * task of its own pool (that could block all threads).
 *
 *   jobs   - background Mini App analyses (one thread per running job)
 *   photos - photos of one or several jobs analyzed in parallel
 *   tiles  - calls of the tiled mode (whole image + tiles) in parallel
 *
 * The sizes also bound the number of simultaneous model calls, which keeps the
 * service below the API rate limit of a small account.
 */
public class AnalysisExecutors implements AutoCloseable {

    private final ExecutorService jobs;
    private final ExecutorService photos;
    private final ExecutorService tiles;

    public AnalysisExecutors(int jobThreads, int jobQueue, int photoThreads, int tileThreads) {
        this.jobs = new ThreadPoolExecutor(jobThreads, jobThreads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(jobQueue), named("analysis-job"));
        this.photos = new ThreadPoolExecutor(photoThreads, photoThreads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("analysis-photo"));
        this.tiles = new ThreadPoolExecutor(tileThreads, tileThreads, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), named("analysis-tile"));
    }

    public ExecutorService jobs() {
        return jobs;
    }

    public ExecutorService photos() {
        return photos;
    }

    public ExecutorService tiles() {
        return tiles;
    }

    @Override
    public void close() {
        jobs.shutdownNow();
        photos.shutdownNow();
        tiles.shutdownNow();
    }

    private static ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}

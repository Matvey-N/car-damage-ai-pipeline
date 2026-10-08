package com.cardamage.core.miniapp;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * At most `limit` units per user in a sliding window. Protects the API key
 * when the Mini App is reachable from the internet through a tunnel.
 * In memory only: it resets when the service restarts.
 */
public class RateLimiter {

    private final int limit;
    private final long windowSeconds;
    private final Map<Long, Deque<Long>> used = new HashMap<>();

    public RateLimiter(int limit, long windowSeconds) {
        this.limit = limit;
        this.windowSeconds = windowSeconds;
    }

    /** Takes `units` for the user if they fit into the window; returns false (taking nothing) otherwise. */
    public synchronized boolean tryAcquire(long userId, int units, long nowEpochSeconds) {
        Deque<Long> times = used.computeIfAbsent(userId, k -> new ArrayDeque<>());
        while (!times.isEmpty() && times.peekFirst() <= nowEpochSeconds - windowSeconds) {
            times.pollFirst();
        }
        if (times.size() + units > limit) {
            return false;
        }
        for (int i = 0; i < units; i++) {
            times.addLast(nowEpochSeconds);
        }
        return true;
    }

    public int limit() {
        return limit;
    }

    public long windowSeconds() {
        return windowSeconds;
    }
}

package com.jreinhal.mercenary.util;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

public class SimpleCircuitBreaker {
    public enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final int halfOpenMaxCalls;
    private final Duration openDuration;
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);
    private volatile long openUntilEpochMs = 0L;
    private volatile State state = State.CLOSED;

    public SimpleCircuitBreaker(int failureThreshold, Duration openDuration, int halfOpenMaxCalls) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDuration = openDuration == null ? Duration.ofSeconds(30) : openDuration;
        this.halfOpenMaxCalls = Math.max(1, halfOpenMaxCalls);
    }

    public boolean allowRequest() {
        if (this.state == State.CLOSED) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (this.state == State.OPEN) {
            if (now < this.openUntilEpochMs) {
                return false;
            }
            synchronized (this) {
                if (this.state == State.OPEN && now >= this.openUntilEpochMs) {
                    this.state = State.HALF_OPEN;
                    this.halfOpenCalls.set(0);
                }
            }
        }
        return this.halfOpenCalls.incrementAndGet() <= this.halfOpenMaxCalls;
    }

    public void recordSuccess() {
        if (this.state == State.CLOSED) {
            this.failureCount.set(0);
            return;
        }
        synchronized (this) {
            this.state = State.CLOSED;
            this.failureCount.set(0);
            this.halfOpenCalls.set(0);
            this.openUntilEpochMs = 0L;
        }
    }

    public void recordFailure(Throwable error) {
        if (this.state == State.HALF_OPEN) {
            openCircuit();
            return;
        }
        int failures = this.failureCount.incrementAndGet();
        if (failures >= this.failureThreshold) {
            openCircuit();
        }
    }

    public State getState() {
        return this.state;
    }

    private void openCircuit() {
        synchronized (this) {
            this.state = State.OPEN;
            this.openUntilEpochMs = System.currentTimeMillis() + this.openDuration.toMillis();
            this.failureCount.set(0);
            this.halfOpenCalls.set(0);
        }
    }
}

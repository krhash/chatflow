package cs6650.chatflow.consumer.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit Breaker implementation with CLOSED, OPEN, and HALF_OPEN states.
 * Protects against cascading failures by temporarily stopping operations
 * when failure rate exceeds threshold.
 */
public class CircuitBreaker {
    private static final Logger logger = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State {
        CLOSED,      // Normal operation
        OPEN,        // Failure mode - reject calls
        HALF_OPEN    // Testing recovery - allow limited calls
    }

    private final String name;
    private final int failureThreshold;
    private final int successThreshold;
    private final long recoveryTimeoutMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicInteger consecutiveSuccesses = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);

    public CircuitBreaker(String name, int failureThreshold, int successThreshold, long recoveryTimeoutMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.successThreshold = successThreshold;
        this.recoveryTimeoutMs = recoveryTimeoutMs;
    }

    public CircuitBreaker(String name) {
        this(name, 5, 3, 60000); // Default: 5 failures, 3 successes, 60s timeout
    }

    /**
     * Executes an operation protected by the circuit breaker.
     * Returns true if the operation was executed (successful or failed),
     * false if the circuit breaker prevented execution.
     */
    public boolean execute(Operation operation) throws Exception {
        if (canExecute()) {
            try {
                operation.run();
                onSuccess();
                return true;
            } catch (Exception e) {
                onFailure();
                throw e;
            }
        } else {
            logger.warn("Circuit breaker '{}' is {}, operation not executed", name, state.get());
            return false;
        }
    }

    /**
     * Checks if an operation can be executed based on current circuit breaker state.
     */
    public boolean canExecute() {
        State currentState = state.get();
        long currentTime = System.currentTimeMillis();

        switch (currentState) {
            case CLOSED:
                return true;

            case OPEN:
                // Check if recovery timeout has elapsed
                if (currentTime - lastFailureTime.get() >= recoveryTimeoutMs) {
                    if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                        consecutiveSuccesses.set(0); // Reset success counter
                        logger.info("Circuit breaker '{}' transitioning from OPEN to HALF_OPEN", name);
                    }
                    return true;
                }
                return false;

            case HALF_OPEN:
                return true;

            default:
                return false;
        }
    }

    /**
     * Records a successful operation.
     */
    public void onSuccess() {
        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                consecutiveFailures.set(0); // Reset failure counter
                break;

            case HALF_OPEN:
                int successes = consecutiveSuccesses.incrementAndGet();
                if (successes >= successThreshold) {
                    if (state.compareAndSet(State.HALF_OPEN, State.CLOSED)) {
                        consecutiveFailures.set(0);
                        consecutiveSuccesses.set(0);
                        logger.info("Circuit breaker '{}' transitioning from HALF_OPEN to CLOSED after {} successes",
                            name, successes);
                    }
                }
                break;
        }
    }

    /**
     * Records a failed operation.
     */
    public void onFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        lastFailureTime.set(System.currentTimeMillis());

        State currentState = state.get();
        if (currentState == State.HALF_OPEN) {
            // Single failure in HALF_OPEN causes immediate transition back to OPEN
            if (state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
                consecutiveSuccesses.set(0);
                logger.warn("Circuit breaker '{}' failed in HALF_OPEN, returning to OPEN", name);
            }
        } else if (currentState == State.CLOSED && failures >= failureThreshold) {
            if (state.compareAndSet(State.CLOSED, State.OPEN)) {
                logger.warn("Circuit breaker '{}' transitioning from CLOSED to OPEN after {} consecutive failures",
                    name, failures);
            }
        }
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }

    public int getConsecutiveSuccesses() {
        return consecutiveSuccesses.get();
    }

    public String getName() {
        return name;
    }

    @FunctionalInterface
    public interface Operation {
        void run() throws Exception;
    }
}

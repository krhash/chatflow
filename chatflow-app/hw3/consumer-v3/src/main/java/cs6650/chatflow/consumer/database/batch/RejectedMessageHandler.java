package cs6650.chatflow.consumer.database.batch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Custom handler for rejected database write tasks.
 * Logs rejected messages and tracks metrics when queue is full.
 */
public class RejectedMessageHandler implements RejectedExecutionHandler {
    private static final Logger logger = LoggerFactory.getLogger(RejectedMessageHandler.class);

    private final AtomicLong rejectedCount = new AtomicLong(0);
    private final AtomicLong lastRejectionTime = new AtomicLong(0);
    private final long alertThrottleMs;

    public RejectedMessageHandler() {
        this(5000); // Default: throttle alerts to once per 5 seconds
    }

    public RejectedMessageHandler(long alertThrottleMs) {
        this.alertThrottleMs = alertThrottleMs;
    }

    @Override
    public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        long count = rejectedCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long lastAlert = lastRejectionTime.get();

        // Throttled logging to prevent log spam
        if (now - lastAlert > alertThrottleMs) {
            if (lastRejectionTime.compareAndSet(lastAlert, now)) {
                logger.error("⚠️  DATABASE WRITE QUEUE FULL! Rejected {} tasks total. " +
                                "Queue size: {}, Active threads: {}, Pool size: {}",
                        count,
                        executor.getQueue().size(),
                        executor.getActiveCount(),
                        executor.getPoolSize());

                // Log pool stats
                logger.warn("Thread Pool Stats - Core: {}, Max: {}, Largest: {}, Completed: {}",
                        executor.getCorePoolSize(),
                        executor.getMaximumPoolSize(),
                        executor.getLargestPoolSize(),
                        executor.getCompletedTaskCount());
            }
        }

        // Extract task info if available
        if (task instanceof BatchWriteTask) {
            BatchWriteTask writeTask = (BatchWriteTask) task;
            logger.debug("Dropped batch with {} messages", writeTask.getBatchSize());
        }

        // Task is dropped (not retried)
        // In production, you might want to:
        // - Write to a dead letter queue
        // - Store in a backup location
        // - Trigger alerts
    }

    /**
     * Get total number of rejected tasks
     */
    public long getRejectedCount() {
        return rejectedCount.get();
    }

    /**
     * Reset rejection counter
     */
    public void resetCounter() {
        rejectedCount.set(0);
    }
}

package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dead Letter Queue for messages that failed to write to the database.
 * Handles retry with exponential backoff and eventual persistence to disk/logging.
 *
 * Features:
 * - Automatic retry with exponential backoff (2s, 4s, 8s, 16s, 32s)
 * - Configurable max retries (default: 5)
 * - Bounded queue with overflow protection
 * - Logs abandoned messages for manual recovery
 * - Thread-safe concurrent operations
 * - Graceful shutdown with flush
 *
 * Usage:
 * <pre>
 * DeadLetterQueue dlq = new DeadLetterQueue(batchWriter, 1000, 5, 2000);
 * dlq.start();
 *
 * // Add failed message
 * dlq.add(failedMessage);
 *
 * // Shutdown (processes remaining messages)
 * dlq.stop();
 * </pre>
 */
public class DeadLetterQueue {
    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueue.class);

    // Configuration
    private final int maxRetries;
    private final long initialRetryDelayMs;
    private final int maxQueueSize;

    // Storage
    private final BlockingQueue<FailedMessage> dlq;
    private final ScheduledExecutorService retryScheduler;
    private final DatabaseBatchWriter batchWriter;

    // Metrics
    private final AtomicLong totalEnqueued = new AtomicLong(0);
    private final AtomicLong totalRecovered = new AtomicLong(0);
    private final AtomicLong totalAbandoned = new AtomicLong(0);
    private final AtomicLong totalRetryAttempts = new AtomicLong(0);

    // State
    private volatile boolean running = false;

    /**
     * Create a new Dead Letter Queue
     *
     * @param batchWriter Writer to use for retry attempts
     * @param maxQueueSize Maximum queue size (typically 10% of main queue)
     * @param maxRetries Maximum retry attempts per message
     * @param initialRetryDelayMs Initial retry delay in milliseconds
     */
    public DeadLetterQueue(DatabaseBatchWriter batchWriter,
                           int maxQueueSize,
                           int maxRetries,
                           long initialRetryDelayMs) {
        this.batchWriter = batchWriter;
        this.maxQueueSize = maxQueueSize;
        this.maxRetries = maxRetries;
        this.initialRetryDelayMs = initialRetryDelayMs;

        this.dlq = new LinkedBlockingQueue<>(maxQueueSize);
        this.retryScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DLQ-RetryProcessor");
            t.setDaemon(true);
            return t;
        });

        logger.info("DeadLetterQueue initialized: maxSize={}, maxRetries={}, initialRetryDelay={}ms",
                maxQueueSize, maxRetries, initialRetryDelayMs);
    }

    /**
     * Start the DLQ processor
     */
    public void start() {
        if (running) {
            logger.warn("DeadLetterQueue already running");
            return;
        }

        running = true;

        // Schedule periodic retry processing
        retryScheduler.scheduleWithFixedDelay(
                this::processRetries,
                initialRetryDelayMs,
                initialRetryDelayMs / 2,  // Check twice per retry interval
                TimeUnit.MILLISECONDS
        );

        logger.info("DeadLetterQueue started");
    }

    /**
     * Stop the DLQ processor and flush remaining messages
     */
    public void stop() {
        if (!running) {
            return;
        }

        logger.info("Stopping DeadLetterQueue (queue size: {})...", dlq.size());
        running = false;

        // Process remaining messages one final time
        int remaining = dlq.size();
        if (remaining > 0) {
            logger.info("Processing {} remaining messages in DLQ...", remaining);
            processRetries();
        }

        // Shutdown scheduler
        retryScheduler.shutdown();
        try {
            if (!retryScheduler.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("DLQ retry scheduler did not terminate in time, forcing shutdown");
                retryScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            retryScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Log any remaining messages
        if (!dlq.isEmpty()) {
            logger.warn("DLQ has {} unprocessed messages on shutdown", dlq.size());
            List<FailedMessage> remaining_messages = new ArrayList<>();
            dlq.drainTo(remaining_messages);
            for (FailedMessage msg : remaining_messages) {
                logAbandonedMessage(msg.getMessage(), "shutdown");
            }
        }

        // Log final statistics
        logFinalStats();

        logger.info("DeadLetterQueue stopped");
    }

    /**
     * Add a failed message to the DLQ
     *
     * @param message Message that failed to write
     * @return true if added successfully, false if queue is full
     */
    public boolean add(DatabaseMessage message) {
        if (!running) {
            logger.warn("DLQ not running, cannot enqueue message: {}", message.getMessageId());
            logAbandonedMessage(message, "dlq_not_running");
            totalAbandoned.incrementAndGet();
            return false;
        }

        FailedMessage failedMessage = new FailedMessage(message);
        boolean added = dlq.offer(failedMessage);

        if (added) {
            totalEnqueued.incrementAndGet();
            logger.debug("Message added to DLQ: {} (queue size: {}/{})",
                    message.getMessageId(), dlq.size(), maxQueueSize);
        } else {
            logger.error("⚠️ DLQ queue full! Cannot add message: {} (size: {}/{})",
                    message.getMessageId(), dlq.size(), maxQueueSize);
            totalAbandoned.incrementAndGet();

            // Log to file as last resort
            logAbandonedMessage(message, "dlq_full");
        }

        return added;
    }

    /**
     * Add multiple failed messages to DLQ
     *
     * @param messages List of messages that failed to write
     */
    public void addAll(List<DatabaseMessage> messages) {
        for (DatabaseMessage message : messages) {
            add(message);
        }
    }

    /**
     * Process retry attempts for messages in DLQ
     */
    private void processRetries() {
        if (!running || dlq.isEmpty()) {
            return;
        }

        // Process up to 25 messages at a time
        List<FailedMessage> batch = new ArrayList<>();
        dlq.drainTo(batch, 25);

        if (batch.isEmpty()) {
            return;
        }

        logger.debug("Processing {} messages from DLQ (queue size: {})", batch.size(), dlq.size());

        for (FailedMessage failedMessage : batch) {
            if (!running) {
                // Re-add to queue if shutting down
                dlq.offer(failedMessage);
                break;
            }

            processFailedMessage(failedMessage);
        }
    }

    /**
     * Process a single failed message with retry logic
     */
    private void processFailedMessage(FailedMessage failedMessage) {
        DatabaseMessage message = failedMessage.getMessage();
        int attempt = failedMessage.getAttemptCount();

        // Check if max retries exceeded
        if (attempt >= maxRetries) {
            logger.error("❌ Message {} exceeded max retries ({}), abandoning",
                    message.getMessageId(), maxRetries);
            totalAbandoned.incrementAndGet();
            logAbandonedMessage(message, "max_retries_exceeded");
            return;
        }

        // Calculate backoff delay
        long requiredDelay = calculateBackoffDelay(attempt);
        long timeSinceLastAttempt = System.currentTimeMillis() - failedMessage.getLastAttemptTime();

        // Wait if not enough time has passed
        if (timeSinceLastAttempt < requiredDelay) {
            // Re-queue for later processing
            dlq.offer(failedMessage);
            return;
        }

        // Attempt retry
        failedMessage.incrementAttempt();
        totalRetryAttempts.incrementAndGet();

        logger.debug("DLQ: Retrying message {} (attempt {}/{})",
                message.getMessageId(), attempt + 1, maxRetries);

        try {
            List<DatabaseMessage> singleMessage = new ArrayList<>();
            singleMessage.add(message);

            BatchWriteResult result = batchWriter.writeBatch(singleMessage);

            if (result.getSuccessful() > 0) {
                // Success - message written!
                totalRecovered.incrementAndGet();
                logger.info("✅ DLQ: Successfully recovered message {} on attempt {}/{}",
                        message.getMessageId(), attempt + 1, maxRetries);

            } else if (result.getDuplicates() > 0) {
                // Duplicate - message already exists in database (also success!)
                totalRecovered.incrementAndGet();
                logger.debug("DLQ: Message {} already exists in database (duplicate), removing from DLQ",
                        message.getMessageId());

            } else if (result.getFailed() > 0) {
                // Failed again - re-queue for next retry
                logger.warn("DLQ: Retry attempt {}/{} failed for message {}, re-queuing",
                        attempt + 1, maxRetries, message.getMessageId());
                dlq.offer(failedMessage);

            } else {
                // Unexpected result
                logger.error("DLQ: Unexpected result for message {}: {}",
                        message.getMessageId(), result);
                dlq.offer(failedMessage);
            }

        } catch (Exception e) {
            logger.error("DLQ: Error retrying message {} (attempt {}/{}): {}",
                    message.getMessageId(), attempt + 1, maxRetries, e.getMessage());

            // Re-queue for next retry
            dlq.offer(failedMessage);
        }
    }

    /**
     * Calculate exponential backoff delay
     * Returns: 2s, 4s, 8s, 16s, 32s, ...
     */
    private long calculateBackoffDelay(int attempt) {
        return initialRetryDelayMs * (long) Math.pow(2, attempt);
    }

    /**
     * Log abandoned message to file for manual recovery
     */
    private void logAbandonedMessage(DatabaseMessage message, String reason) {
        // In production, this could write to:
        // - Separate log file (via Log4j/Logback appender)
        // - S3 bucket
        // - Dead letter database table
        // - Monitoring/alerting system

        logger.error("ABANDONED_MESSAGE|reason={}|messageId={}|roomId={}|userId={}|username={}|timestamp={}|messageType={}|message={}",
                reason,
                message.getMessageId(),
                message.getRoomId(),
                message.getUserId(),
                message.getUsername(),
                message.getTimestamp(),
                message.getMessageType(),
                message.getMessage());
    }

    /**
     * Log final statistics on shutdown
     */
    private void logFinalStats() {
        long total = totalEnqueued.get();
        long recovered = totalRecovered.get();
        long abandoned = totalAbandoned.get();
        double recoveryRate = total > 0 ? (recovered * 100.0 / total) : 100.0;

        logger.info("╔════════════════════════════════════════════════════════╗");
        logger.info("║     Dead Letter Queue Final Statistics                ║");
        logger.info("╠════════════════════════════════════════════════════════╣");
        logger.info("║  Total Enqueued:      {:>8}                        ║", total);
        logger.info("║  Total Recovered:     {:>8} (✅)                    ║", recovered);
        logger.info("║  Total Abandoned:     {:>8} (❌)                    ║", abandoned);
        logger.info("║  Still in Queue:      {:>8}                        ║", dlq.size());
        logger.info("║  Total Retry Attempts:{:>8}                        ║", totalRetryAttempts.get());
        logger.info("║  Recovery Rate:       {:>7.2f}%                      ║", recoveryRate);
        logger.info("╚════════════════════════════════════════════════════════╝");

        if (abandoned > 0) {
            logger.error("⚠️  {} messages were abandoned! Check logs for ABANDONED_MESSAGE entries", abandoned);
        }
    }

    // ========== Getters for Metrics ==========

    /**
     * Get current DLQ size
     */
    public int getQueueSize() {
        return dlq.size();
    }

    /**
     * Get total number of messages added to DLQ
     */
    public long getTotalEnqueued() {
        return totalEnqueued.get();
    }

    /**
     * Get total number of messages successfully recovered
     */
    public long getTotalRecovered() {
        return totalRecovered.get();
    }

    /**
     * Get total number of messages abandoned (max retries exceeded)
     */
    public long getTotalAbandoned() {
        return totalAbandoned.get();
    }

    /**
     * Get total number of retry attempts made
     */
    public long getTotalRetryAttempts() {
        return totalRetryAttempts.get();
    }

    /**
     * Check if DLQ is running
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Get recovery rate percentage
     */
    public double getRecoveryRate() {
        long total = totalEnqueued.get();
        return total > 0 ? (totalRecovered.get() * 100.0 / total) : 100.0;
    }

    /**
     * Get current utilization percentage
     */
    public double getUtilization() {
        return (dlq.size() * 100.0) / maxQueueSize;
    }

    // ========== Inner Class: FailedMessage ==========

    /**
     * Wrapper class to track failed messages with retry metadata
     */
    private static class FailedMessage {
        private final DatabaseMessage message;
        private int attemptCount = 0;
        private long lastAttemptTime;
        private final long firstFailureTime;

        public FailedMessage(DatabaseMessage message) {
            this.message = message;
            this.firstFailureTime = System.currentTimeMillis();
            this.lastAttemptTime = firstFailureTime;
        }

        public DatabaseMessage getMessage() {
            return message;
        }

        public int getAttemptCount() {
            return attemptCount;
        }

        public long getLastAttemptTime() {
            return lastAttemptTime;
        }

        public long getFirstFailureTime() {
            return firstFailureTime;
        }

        public long getTimeInDLQ() {
            return System.currentTimeMillis() - firstFailureTime;
        }

        public void incrementAttempt() {
            attemptCount++;
            lastAttemptTime = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("FailedMessage{messageId='%s', attempts=%d, timeInDLQ=%dms}",
                    message.getMessageId(), attemptCount, getTimeInDLQ());
        }
    }
}

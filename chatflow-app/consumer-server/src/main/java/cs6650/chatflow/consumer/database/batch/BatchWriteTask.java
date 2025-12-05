package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import cs6650.chatflow.consumer.database.util.ThroughputTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runnable task for writing a batch of messages to the database.
 * Properly distinguishes between duplicates and genuine failures.
 */
public class BatchWriteTask implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(BatchWriteTask.class);

    private final List<DatabaseMessage> batch;
    private final DatabaseBatchWriter writer;
    private final AtomicLong successCounter;
    private final AtomicLong duplicateCounter;
    private final AtomicLong failureCounter;
    private final List<Long> latencyTracker;
    private final ThroughputTracker throughputTracker;
    private final DeadLetterQueue deadLetterQueue;

    /**
     * Create a new batch write task
     *
     * @param batch List of messages to write
     * @param writer Database batch writer
     * @param successCounter Counter for successful writes
     * @param duplicateCounter Counter for duplicate writes (NOT failures)
     * @param failureCounter Counter for failed writes
     * @param latencyTracker List to track write latencies
     * @param throughputTracker Throughput tracker for metrics
     * @param deadLetterQueue DLQ for failed messages
     */
    public BatchWriteTask(List<DatabaseMessage> batch,
                          DatabaseBatchWriter writer,
                          AtomicLong successCounter,
                          AtomicLong duplicateCounter,
                          AtomicLong failureCounter,
                          List<Long> latencyTracker,
                          ThroughputTracker throughputTracker,
                          DeadLetterQueue deadLetterQueue) {
        this.batch = batch;
        this.writer = writer;
        this.successCounter = successCounter;
        this.duplicateCounter = duplicateCounter;
        this.failureCounter = failureCounter;
        this.latencyTracker = latencyTracker;
        this.throughputTracker = throughputTracker;
        this.deadLetterQueue = deadLetterQueue;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        try {
            // Write batch and get detailed results
            BatchWriteResult result = writer.writeBatch(batch);

            // Update metrics based on results
            long duration = System.currentTimeMillis() - startTime;

            // Track successful writes
            if (result.getSuccessful() > 0) {
                successCounter.addAndGet(result.getSuccessful());

                if (throughputTracker != null) {
                    throughputTracker.recordWrite(result.getSuccessful());
                }
            }

            // Track duplicates (NOT failures!)
            if (result.getDuplicates() > 0) {
                duplicateCounter.addAndGet(result.getDuplicates());
                logger.debug("Prevented {} duplicate writes in batch", result.getDuplicates());
            }

            // Track genuine failures and send to DLQ
            if (result.getFailed() > 0) {
                failureCounter.addAndGet(result.getFailed());

                // Send failed messages to DLQ for retry
                if (deadLetterQueue != null && !result.getFailedMessages().isEmpty()) {
                    deadLetterQueue.addAll(result.getFailedMessages());
                    logger.warn("Sent {} failed messages to DLQ", result.getFailedMessages().size());
                }
            }

            // Record latency
            synchronized (latencyTracker) {
                latencyTracker.add(duration);

                // Keep only last 1000 measurements
                if (latencyTracker.size() > 1000) {
                    latencyTracker.remove(0);
                }
            }

            // Log summary
            if (result.getFailed() > 0) {
                logger.warn("Batch write completed in {}ms: {}", duration, result);
            } else if (result.getDuplicates() > 0) {
                logger.debug("Batch write completed in {}ms: {}", duration, result);
            } else {
                logger.trace("Batch write completed in {}ms: {} messages",
                        duration, result.getSuccessful());
            }

        } catch (Exception e) {
            // Catastrophic failure - entire batch failed
            logger.error("Catastrophic batch write failure for {} messages: {}",
                    batch.size(), e.getMessage(), e);

            failureCounter.addAndGet(batch.size());

            // Send entire batch to DLQ
            if (deadLetterQueue != null) {
                deadLetterQueue.addAll(batch);
                logger.error("Sent entire failed batch ({} messages) to DLQ", batch.size());
            }
        }
    }

    public int getBatchSize() {
        return batch.size();
    }
}

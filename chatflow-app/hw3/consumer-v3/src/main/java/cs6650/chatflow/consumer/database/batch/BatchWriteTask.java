package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import cs6650.chatflow.consumer.database.util.ThroughputTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runnable task for writing a batch of messages to the database.
 */
public class BatchWriteTask implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(BatchWriteTask.class);

    private final List<DatabaseMessage> batch;
    private final DatabaseBatchWriter writer;
    private final AtomicLong successCounter;
    private final AtomicLong failureCounter;
    private final List<Long> latencyTracker;
    private final ThroughputTracker throughputTracker;

    public BatchWriteTask(List<DatabaseMessage> batch,
                          DatabaseBatchWriter writer,
                          AtomicLong successCounter,
                          AtomicLong failureCounter,
                          List<Long> latencyTracker,
                          ThroughputTracker throughputTracker) {
        this.batch = batch;
        this.writer = writer;
        this.successCounter = successCounter;
        this.failureCounter = failureCounter;
        this.latencyTracker = latencyTracker;
        this.throughputTracker = throughputTracker;
    }

    @Override
    public void run() {
        long startTime = System.currentTimeMillis();

        try {
            // Delegate to writer
            writer.writeBatch(batch);

            // Update metrics
            long duration = System.currentTimeMillis() - startTime;
            successCounter.addAndGet(batch.size());

            // ========== TRACK THROUGHPUT ==========
            if (throughputTracker != null) {
                throughputTracker.recordWrite(batch.size());
            }

            synchronized (latencyTracker) {
                latencyTracker.add(duration);

                // Keep only last 1000 measurements
                if (latencyTracker.size() > 1000) {
                    latencyTracker.remove(0);
                }
            }

            logger.debug("Wrote batch of {} messages in {}ms", batch.size(), duration);

        } catch (Exception e) {
            logger.error("Batch write failed for {} messages: {}", batch.size(), e.getMessage(), e);
            failureCounter.addAndGet(batch.size());
        }
    }

    public int getBatchSize() {
        return batch.size();
    }
}

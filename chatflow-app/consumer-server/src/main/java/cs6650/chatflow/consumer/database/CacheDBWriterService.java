package cs6650.chatflow.consumer.database;

import cs6650.chatflow.consumer.cache.ValkeyCacheService;
import cs6650.chatflow.consumer.database.batch.BatchWriteResult;
import cs6650.chatflow.consumer.database.batch.DatabaseBatchWriter;
import cs6650.chatflow.consumer.database.batch.DeadLetterQueue;
import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import cs6650.chatflow.consumer.database.util.ThroughputTracker;
import cs6650.chatflow.consumer.model.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Background service that reads messages from Valkey cache and writes to database.
 * This provides clean separation between message consumption and database writes.
 *
 * Architecture:
 * - Polls Valkey cache for pending messages
 * - Batches messages for efficient database writes
 * - Uses deduplication to handle multiple consumer instances
 * - Failed messages go to DLQ for retry
 * - Successfully written messages are removed from cache
 *
 * This service can be extracted as a separate microservice in the future.
 */
public class CacheDBWriterService {
    private static final Logger logger = LoggerFactory.getLogger(CacheDBWriterService.class);

    // Dependencies
    private final ValkeyCacheService cacheService;
    private final DatabaseBatchWriter batchWriter;
    private final DeadLetterQueue deadLetterQueue;
    private final ThroughputTracker throughputTracker;

    // Configuration
    private final int batchSize;
    private final long pollIntervalMs;
    private final int workerThreads;

    // Thread pool
    private ScheduledExecutorService scheduledExecutor;
    private ExecutorService workerExecutor;

    // Metrics
    private final AtomicLong totalProcessed = new AtomicLong(0);
    private final AtomicLong totalSuccessful = new AtomicLong(0);
    private final AtomicLong totalDuplicates = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);
    private final AtomicLong totalRemoved = new AtomicLong(0);

    // State
    private volatile boolean running = false;

    /**
     * Create cache-to-database writer service
     *
     * @param cacheService Valkey cache service
     * @param batchWriter Database batch writer
     * @param deadLetterQueue DLQ for failed writes
     * @param throughputTracker Throughput tracker
     * @param batchSize Number of messages to batch
     * @param pollIntervalMs How often to poll cache (milliseconds)
     * @param workerThreads Number of worker threads
     */
    public CacheDBWriterService(ValkeyCacheService cacheService,
                                DatabaseBatchWriter batchWriter,
                                DeadLetterQueue deadLetterQueue,
                                ThroughputTracker throughputTracker,
                                int batchSize,
                                long pollIntervalMs,
                                int workerThreads) {
        this.cacheService = cacheService;
        this.batchWriter = batchWriter;
        this.deadLetterQueue = deadLetterQueue;
        this.throughputTracker = throughputTracker;
        this.batchSize = batchSize;
        this.pollIntervalMs = pollIntervalMs;
        this.workerThreads = workerThreads;

        logger.info("CacheDBWriterService initialized: batchSize={}, pollInterval={}ms, workers={}",
                batchSize, pollIntervalMs, workerThreads);
    }

    /**
     * Start the background writer service
     */
    public void start() {
        if (running) {
            logger.warn("CacheDBWriterService already running");
            return;
        }

        running = true;

        // Create scheduler for polling cache
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "CacheDBWriter-Scheduler");
            t.setDaemon(true);
            return t;
        });

        // Create worker thread pool for processing batches
        workerExecutor = Executors.newFixedThreadPool(workerThreads, new ThreadFactory() {
            private final AtomicLong threadCounter = new AtomicLong(0);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "CacheDBWriter-Worker-" + threadCounter.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });

        // Schedule periodic cache polling
        scheduledExecutor.scheduleWithFixedDelay(
                this::pollAndWriteFromCache,
                0, // Start immediately
                pollIntervalMs,
                TimeUnit.MILLISECONDS
        );

        logger.info("✅ CacheDBWriterService started");
    }

    /**
     * Stop the background writer service
     */
    public void stop() {
        if (!running) {
            return;
        }

        logger.info("Stopping CacheDBWriterService...");
        running = false;

        // Process remaining messages one final time
        pollAndWriteFromCache();

        // Shutdown executors
        if (scheduledExecutor != null) {
            scheduledExecutor.shutdown();
            try {
                if (!scheduledExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduledExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduledExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        logFinalStatistics();
        logger.info("✅ CacheDBWriterService stopped");
    }

    /**
     * Poll cache and write messages to database
     */
    private void pollAndWriteFromCache() {
        if (!running) {
            return;
        }

        try {
            // Get messages from cache
            List<CachedMessage> cachedMessages = pollMessagesFromCache(batchSize);

            if (cachedMessages.isEmpty()) {
                logger.trace("No messages in cache to process");
                return;
            }

            logger.debug("Polled {} messages from cache", cachedMessages.size());

            // Submit to worker pool for processing
            workerExecutor.submit(() -> processMessageBatch(cachedMessages));

        } catch (Exception e) {
            logger.error("Error polling cache: {}", e.getMessage(), e);
        }
    }

    /**
     * Poll messages from cache using SCAN and MGET for efficiency.
     */
    private List<CachedMessage> pollMessagesFromCache(int limit) {
        List<CachedMessage> messages = new ArrayList<>();
        try {
            String pattern = "chatflow:msg:*";
            List<String> keys = cacheService.scanKeys(pattern, limit);

            if (keys.isEmpty()) {
                return messages;
            }

            List<String> messageIds = keys.stream()
                    .map(k -> k.replace("chatflow:msg:", ""))
                    .collect(Collectors.toList());

            List<ChatEvent> events = cacheService.getMessages(messageIds);

            for (int i = 0; i < keys.size(); i++) {
                if (i < events.size() && events.get(i) != null) {
                    messages.add(new CachedMessage(keys.get(i), messageIds.get(i), events.get(i)));
                }
            }
        } catch (Exception e) {
            logger.error("Error scanning cache: {}", e.getMessage(), e);
        }
        return messages;
    }

    /**
     * Process a batch of messages
     */
    private void processMessageBatch(List<CachedMessage> cachedMessages) {
        if (cachedMessages.isEmpty()) {
            return;
        }

        try {
            // Convert to DatabaseMessage, filtering out ACK messages
            List<DatabaseMessage> dbMessages = cachedMessages.stream()
                    .filter(cm -> !"ACK".equals(cm.event.getMessageType()))
                    .map(cm -> DatabaseMessage.fromChatEvent(cm.event, extractRoomId(cm.event)))
                    .collect(Collectors.toList());

            if (dbMessages.isEmpty()) {
                logger.debug("Batch contained only ACK messages, nothing to write to DB.");
                // Still need to remove the ACK messages from the cache
                List<String> keysToRemove = cachedMessages.stream().map(cm -> cm.cacheKey).collect(Collectors.toList());
                removeFromCache(keysToRemove);
                return;
            }

            totalProcessed.addAndGet(dbMessages.size());

            // Write to database
            BatchWriteResult result = batchWriter.writeBatch(dbMessages);

            // Update metrics
            totalSuccessful.addAndGet(result.getSuccessful());
            totalDuplicates.addAndGet(result.getDuplicates());
            totalFailed.addAndGet(result.getFailed());

            // Track throughput
            if (throughputTracker != null) {
                throughputTracker.recordWrite(result.getSuccessful());
            }

            // Remove successfully written messages from cache
            List<String> keysToRemove = new ArrayList<>();
            for (int i = 0; i < cachedMessages.size(); i++) {
                if (i < result.getSuccessful() + result.getDuplicates()) {
                    keysToRemove.add(cachedMessages.get(i).cacheKey);
                }
            }

            removeFromCache(keysToRemove);

            // Send failed messages to DLQ
            if (result.hasFailures() && deadLetterQueue != null) {
                deadLetterQueue.addAll(result.getFailedMessages());
                logger.warn("Sent {} failed messages to DLQ", result.getFailedMessages().size());
            }

            logger.debug("Processed batch: {}", result);

        } catch (Exception e) {
            logger.error("Error processing message batch: {}", e.getMessage(), e);

            // Send entire batch to DLQ on catastrophic failure
            if (deadLetterQueue != null) {
                List<DatabaseMessage> dbMessages = cachedMessages.stream()
                        .map(cm -> DatabaseMessage.fromChatEvent(cm.event, extractRoomId(cm.event)))
                        .collect(Collectors.toList());
                deadLetterQueue.addAll(dbMessages);
            }
        }
    }

    /**
     * Remove messages from cache using a single batch operation.
     */
    private void removeFromCache(List<String> keys) {
        if (keys == null || keys.isEmpty()) {
            return;
        }
        try {
            List<String> messageIds = keys.stream()
                    .map(k -> k.replace("chatflow:msg:", ""))
                    .collect(Collectors.toList());
            long deletedCount = cacheService.deleteMessages(messageIds);
            totalRemoved.addAndGet(deletedCount);
        } catch (Exception e) {
            logger.warn("Failed to remove keys from cache: {}", e.getMessage());
        }
    }

    /**
     * Extract room ID from event (helper method)
     */
    private String extractRoomId(ChatEvent event) {
        // This is a placeholder - you might need to add roomId to ChatEvent
        // or derive it from another field
        return "1"; // Default for now
    }

    /**
     * Log final statistics
     */
    private void logFinalStatistics() {
        logger.info("╔════════════════════════════════════════════════════════╗");
        logger.info("║     CacheDBWriter Final Statistics                    ║");
        logger.info("╠════════════════════════════════════════════════════════╣");
        logger.info("║  Total Processed:     {:>8}                        ║", totalProcessed.get());
        logger.info("║  Successful Writes:   {:>8}                        ║", totalSuccessful.get());
        logger.info("║  Duplicates Prevented:{:>8}                        ║", totalDuplicates.get());
        logger.info("║  Failed Writes:       {:>8}                        ║", totalFailed.get());
        logger.info("║  Removed from Cache:  {:>8}                        ║", totalRemoved.get());
        logger.info("╚════════════════════════════════════════════════════════╝");
    }

    // ========== Getters for Metrics ==========

    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    public long getTotalSuccessful() {
        return totalSuccessful.get();
    }

    public long getTotalDuplicates() {
        return totalDuplicates.get();
    }

    public long getTotalFailed() {
        return totalFailed.get();
    }

    public long getTotalRemoved() {
        return totalRemoved.get();
    }

    public boolean isRunning() {
        return running;
    }

    // ========== Inner Class: CachedMessage ==========

    /**
     * Wrapper for cached messages with metadata
     */
    private static class CachedMessage {
        final String cacheKey;
        final String messageId;
        final ChatEvent event;

        CachedMessage(String cacheKey, String messageId, ChatEvent event) {
            this.cacheKey = cacheKey;
            this.messageId = messageId;
            this.event = event;
        }
    }
}

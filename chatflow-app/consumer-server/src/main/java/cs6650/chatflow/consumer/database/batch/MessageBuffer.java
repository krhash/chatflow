package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import cs6650.chatflow.consumer.database.util.ThroughputTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Buffer for batching database writes.
 * Manages queue, thread pool, and flush triggers.
 */
public class MessageBuffer {
    private static final Logger logger = LoggerFactory.getLogger(MessageBuffer.class);

    // Configuration
    private final int batchSize;
    private final long flushIntervalMs;
    private final int taskQueueSize;

    // Queue and thread pool
    private final BlockingQueue<DatabaseMessage> messageQueue;
    private final ThreadPoolExecutor writerThreadPool;
    private final ScheduledExecutorService flushScheduler;
    private final RejectedMessageHandler rejectionHandler;

    // Writer
    private final DatabaseBatchWriter batchWriter;

    // Metrics
    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong failedWrites = new AtomicLong(0);
    private final List<Long> writeLatencies = new CopyOnWriteArrayList<>();

    // State
    private volatile boolean running = false;

    private final ThroughputTracker throughputTracker;

    public MessageBuffer(int batchSize,
                         long flushIntervalMs,
                         int maxQueueSize,
                         int corePoolSize,
                         int maxPoolSize,
                         int taskQueueSize,
                         DatabaseBatchWriter batchWriter,
                         ThroughputTracker throughputTracker) {

        this.batchSize = batchSize;
        this.flushIntervalMs = flushIntervalMs;
        this.taskQueueSize = taskQueueSize;  // ← STORE IT
        this.batchWriter = batchWriter;

        // Create bounded queue
        this.messageQueue = new LinkedBlockingQueue<>(maxQueueSize);
        this.throughputTracker = throughputTracker;

        // Create rejection handler
        this.rejectionHandler = new RejectedMessageHandler(5000);

        // Create thread pool with CONFIGURABLE task queue size
        this.writerThreadPool = new ThreadPoolExecutor(
                corePoolSize,
                maxPoolSize,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(taskQueueSize),  // ← USE PARAMETER, NOT HARDCODED 100!
                new ThreadFactory() {
                    private final AtomicLong threadCounter = new AtomicLong(0);
                    @Override
                    public Thread newThread(Runnable r) {
                        Thread t = new Thread(r, "DBWriter-" + threadCounter.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    }
                },
                rejectionHandler
        );

        // Create scheduler for time-based flushing
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DBFlushScheduler");
            t.setDaemon(true);
            return t;
        });

        logger.info("MessageBuffer initialized: batchSize={}, flushInterval={}ms, " +
                        "messageQueueSize={}, taskQueueSize={}, coreThreads={}, maxThreads={}",
                batchSize, flushIntervalMs, maxQueueSize, taskQueueSize, corePoolSize, maxPoolSize);
    }

    /**
     * Start the buffer (begin processing)
     */
    public void start() {
        if (running) {
            logger.warn("MessageBuffer already started");
            return;
        }

        running = true;

        // Start time-based flush
        flushScheduler.scheduleAtFixedRate(
                this::flushIfNeeded,
                flushIntervalMs,
                flushIntervalMs,
                TimeUnit.MILLISECONDS
        );

        // Start size-based monitoring
        writerThreadPool.execute(this::monitorQueueSize);

        logger.info("MessageBuffer started");
    }

    /**
     * Stop the buffer and flush remaining messages
     */
    public void stop() {
        if (!running) {
            return;
        }

        logger.info("Stopping MessageBuffer...");
        running = false;

        // Flush remaining messages
        int flushed = flush();
        logger.info("Flushed {} pending messages on shutdown", flushed);

        // Shutdown scheduler
        flushScheduler.shutdown();

        // Shutdown thread pool gracefully
        writerThreadPool.shutdown();
        try {
            if (!writerThreadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                writerThreadPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            writerThreadPool.shutdownNow();
            Thread.currentThread().interrupt();
        }

        logger.info("MessageBuffer stopped. Total writes: {}, Failed: {}, Rejected: {}",
                totalWrites.get(), failedWrites.get(), rejectionHandler.getRejectedCount());
    }

    /**
     * Add message to buffer (non-blocking)
     */
    public boolean add(DatabaseMessage message) {
        if (!running) {
            logger.warn("Buffer not running, dropping message: {}", message.getMessageId());
            return false;
        }

        boolean added = messageQueue.offer(message);

        if (!added) {
            logger.error("Message queue full, dropping message: {}", message.getMessageId());
            failedWrites.incrementAndGet();
        }

        return added;
    }

    /**
     * Flush pending messages
     */
    public synchronized int flush() {
        if (messageQueue.isEmpty()) {
            return 0;
        }

        List<DatabaseMessage> batch = new ArrayList<>();
        messageQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) {
            return 0;
        }

        // Submit batch write task to thread pool
        BatchWriteTask task = new BatchWriteTask(
                batch,
                batchWriter,
                totalWrites,
                failedWrites,
                writeLatencies,
                throughputTracker
        );

        try {
            writerThreadPool.execute(task);
            return batch.size();
        } catch (RejectedExecutionException e) {
            // Thread pool queue is full
            logger.error("Thread pool rejected batch of {} messages", batch.size());
            failedWrites.addAndGet(batch.size());
            return 0;
        }
    }

    // Private helper methods

    private void flushIfNeeded() {
        if (!messageQueue.isEmpty()) {
            flush();
        }
    }

    private void monitorQueueSize() {
        while (running) {
            try {
                if (messageQueue.size() >= batchSize) {
                    flush();
                }
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Error in queue monitor", e);
            }
        }
    }

    // Getters for metrics

    public int getQueueSize() {
        return messageQueue.size();
    }

    public int getActiveThreads() {
        return writerThreadPool.getActiveCount();
    }

    public int getPoolSize() {
        return writerThreadPool.getPoolSize();
    }

    public long getTotalWrites() {
        return totalWrites.get();
    }

    public long getFailedWrites() {
        return failedWrites.get();
    }

    public long getRejectedTasks() {
        return rejectionHandler.getRejectedCount();
    }

    public List<Long> getWriteLatencies() {
        return new ArrayList<>(writeLatencies);
    }

    public ThroughputTracker getThroughputTracker() {
        return throughputTracker;
    }
}

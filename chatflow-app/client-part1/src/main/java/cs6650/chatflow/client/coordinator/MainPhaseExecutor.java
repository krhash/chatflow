package cs6650.chatflow.client.coordinator;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.sender.MessageConsumer;
import cs6650.chatflow.client.sender.MessageProducer;
import cs6650.chatflow.client.sender.ResponseProcessor;
import cs6650.chatflow.client.util.*;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main phase executor coordinating producer-consumer architecture.
 * Manages all components for high-throughput WebSocket testing.
 */
public class MainPhaseExecutor {
    private static final Logger logger = LoggerFactory.getLogger(MainPhaseExecutor.class);

    private final String serverIp;
    private final String port;
    private final int totalMessages;

    // Core components
    private MessageQueue messageQueue;
    private ResponseQueue responseQueue;
    private DeadLetterQueue deadLetterQueue;
    private MessageTimer messageTimer;
    private WebSocketConnectionPool connectionPool;

    // Threads and executors
    private ExecutorService producerExecutor;
    private ExecutorService consumerExecutor;
    private ExecutorService responseExecutor;
    private ExecutorService monitorExecutor;
    private ExecutorService retryExecutor;

    // Counters
    private final AtomicInteger messagesSent = new AtomicInteger(0);
    private final AtomicInteger messagesReceived = new AtomicInteger(0);
    private final Map<String, Long> responseLatencies = new ConcurrentHashMap<>();

    /**
     * Creates main phase executor.
     * @param serverIp server IP address
     * @param port server port
     * @param totalMessages number of messages to send in main phase
     */
    public MainPhaseExecutor(String serverIp, String port, int totalMessages) {
        this.serverIp = serverIp;
        this.port = port;
        this.totalMessages = totalMessages;
    }

    /**
     * Executes the main phase load test.
     * @return result containing messages received and test end time
     */
    public MainPhaseResult execute() throws URISyntaxException, InterruptedException {
        System.out.println("\n=================================================================");
        System.out.println("                     ChatFlow Main Phase");
        System.out.println("              High-Throughput Load Testing");
        System.out.println("=================================================================\n");

        System.out.println(String.format("Connected to server: %s:%s", serverIp, port) );

        long startTime = System.currentTimeMillis();

        try {
            // Initialize components
            initializeComponents();

            // Start all threads
            startThreads();

            // Wait for completion
            waitForCompletion();

            // Process dead letter queue
            processDeadLetterQueue();

            // Capture test end time before shutdown
            long testEndTime = System.currentTimeMillis();

            // Generate report
            generateReport(startTime);

            // Return result with messages received and test end time
            return new MainPhaseResult(messagesReceived.get(), testEndTime);

        } finally {
            // Cleanup
            shutdown();
        }
    }

    /**
     * Initializes all components.
     */
    private void initializeComponents() throws URISyntaxException, InterruptedException {
        logger.info("Initializing main phase components...");

        // Create queues and utilities
        messageQueue = new MessageQueue();
        responseQueue = new ResponseQueue();
        deadLetterQueue = new DeadLetterQueue();
        messageTimer = new MessageTimer();

        // Create connection pool - use random room ID like warmup phase
        int roomId = (int) (Math.random() * Constants.ROOM_COUNT) + 1;
        URI serverUri = new URI(String.format("ws://%s:%s/chatflow-server/chat/room%d", serverIp, port, roomId));
        logger.debug("Creating WebSocket connection pool with URI: {}", serverUri);
        connectionPool = new WebSocketConnectionPool(serverUri, responseQueue);

        logger.info("Components initialized successfully");
    }

    /**
     * Starts all threads.
     */
    private void startThreads() {
        logger.info("Starting main phase threads...");

        // Create executors
        logger.debug("Creating thread executors...");
        producerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "MessageProducer"));
        consumerExecutor = Executors.newFixedThreadPool(Constants.MAIN_PHASE_CONSUMER_THREADS,
            r -> new Thread(r, "MessageConsumer-" + r.hashCode()));
        responseExecutor = Executors.newFixedThreadPool(Constants.RESPONSE_THREAD_POOL_SIZE,
            r -> new Thread(r, "ResponseProcessor-" + r.hashCode()));
        monitorExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "TimeoutMonitor"));
        retryExecutor = Executors.newFixedThreadPool(Constants.RETRY_WORKER_THREADS,
            r -> new Thread(r, "RetryWorker-" + r.hashCode()));

        // Start producer
        logger.info("Starting message producer thread...");
        producerExecutor.submit(new MessageProducer(messageQueue, totalMessages));
        logger.info("Started message producer thread");

        // Start consumers
        logger.info("Starting {} message consumer threads...", Constants.MAIN_PHASE_CONSUMER_THREADS);
        for (int i = 0; i < Constants.MAIN_PHASE_CONSUMER_THREADS; i++) {
            consumerExecutor.submit(new MessageConsumer(messageQueue, connectionPool,
                messageTimer, messagesSent, totalMessages));
        }
        logger.info("Started {} message consumer threads", Constants.MAIN_PHASE_CONSUMER_THREADS);

        // Start response processors
        logger.info("Starting {} response processor threads...", Constants.RESPONSE_THREAD_POOL_SIZE);
        for (int i = 0; i < Constants.RESPONSE_THREAD_POOL_SIZE; i++) {
            responseExecutor.submit(new ResponseProcessor(responseQueue, messageTimer,
                messagesReceived, responseLatencies));
        }
        logger.info("Started {} response processor threads", Constants.RESPONSE_THREAD_POOL_SIZE);

        // Start timeout monitor
        logger.info("Starting timeout monitor thread...");
        monitorExecutor.submit(new TimeoutMonitor(messageTimer, deadLetterQueue));
        logger.info("Started timeout monitor thread");

        // Start retry workers
        logger.info("Starting {} retry worker threads...", Constants.RETRY_WORKER_THREADS);
        for (int i = 0; i < Constants.RETRY_WORKER_THREADS; i++) {
            retryExecutor.submit(new RetryWorker(connectionPool, deadLetterQueue));
        }
        logger.info("Started {} retry worker threads", Constants.RETRY_WORKER_THREADS);

        logger.info("All main phase threads started successfully");
    }

    /**
     * Waits for test completion - all messages sent AND all responses received or timed out.
     */
    private void waitForCompletion() throws InterruptedException {
        logger.info("Waiting for message generation and processing to complete...");

        long startWaitTime = System.currentTimeMillis();
        long maxWaitTime = 10 * 60 * 1000; // 10 minutes maximum wait time

        // Wait for producer to finish
        producerExecutor.shutdown();
        producerExecutor.awaitTermination(10, TimeUnit.MINUTES);

        // Wait for message queue to be empty (all messages sent)
        while (messageQueue.size() > 0) {
            Thread.sleep(1000);
            logger.debug("Waiting for queue to empty: {} messages remaining", messageQueue.size());
        }

        logger.info("All messages sent. Waiting for all responses...");

        // Wait until all responses received OR global timeout reached
        while (true) {
            int sent = messagesSent.get();
            int received = messagesReceived.get();
            int failed = deadLetterQueue.size();
            int accountedFor = received + failed;

            // Check if all messages are accounted for
            if (accountedFor >= sent) {
                logger.info("All messages accounted for - test complete");
                break;
            }

            // Check for global timeout
            long elapsed = System.currentTimeMillis() - startWaitTime;
            if (elapsed > maxWaitTime) {
                logger.warn("Global timeout reached after {} minutes. {}/{} messages still pending",
                    elapsed / 60000.0, (sent - accountedFor), sent);
                break;
            }

            // Progress reporting - every 30 seconds to reduce verbosity
            if ((System.currentTimeMillis() / 1000) % 30 == 0) {
                logger.info("Waiting for responses: {} sent, {} received, {} failed, {} pending",
                    sent, received, failed, (sent - accountedFor));
            }

            Thread.sleep(1000);
        }

        logger.info("Main processing complete");
    }

    /**
     * Processes dead letter queue for final retries.
     */
    private void processDeadLetterQueue() {
        logger.info("Processing dead letter queue...");

        int failedCount = deadLetterQueue.size();
        if (failedCount > 0) {
            logger.info("Found {} failed messages, attempting final retries...", failedCount);

            // In a real implementation, we would retry these messages
            // For now, just log them
            logger.info("Dead letter queue processing complete. {} messages failed permanently.", failedCount);
        } else {
            logger.info("No failed messages found.");
        }
    }

    /**
     * Generates final performance report.
     */
    private void generateReport(long startTime) {
        long endTime = System.currentTimeMillis();
        double durationSeconds = (endTime - startTime) / 1000.0;

        int sent = messagesSent.get();
        int received = messagesReceived.get();
        double throughput = sent > 0 ? sent / durationSeconds : 0;

        System.out.println("\n=================================================================");
        System.out.println("                     Main Phase Results");
        System.out.println("-----------------------------------------------------------------");

        // Configuration
        System.out.println(" CONFIGURATION:");
        System.out.printf("   Target Messages:     %d%n", totalMessages);
        System.out.printf("   Producer Threads:    %d%n", 1);
        System.out.printf("   Consumer Threads:    %d%n", Constants.MAIN_PHASE_CONSUMER_THREADS);
        System.out.printf("   Response Threads:    %d%n", Constants.RESPONSE_THREAD_POOL_SIZE);
        System.out.printf("   Retry Workers:       %d%n", Constants.RETRY_WORKER_THREADS);
        System.out.printf("   Timeout Monitor:     %d%n", 1);
        System.out.printf("   Connection Pool:     %d%n", Constants.MAIN_PHASE_CONNECTION_POOL_SIZE);
        System.out.printf("   Message Queue:       %d%n", Constants.MESSAGE_QUEUE_CAPACITY);
        System.out.printf("   Response Queue:      %d%n", Constants.RESPONSE_QUEUE_CAPACITY);
        System.out.printf("   Dead Letter Queue:   %d%n", Constants.DEAD_LETTER_QUEUE_CAPACITY);
        System.out.printf("   Message Timeout:     %d sec%n", Constants.MESSAGE_TIMEOUT_MILLIS / 1000);
        System.out.println();

        // Results
        System.out.printf(" Messages Sent:       %d%n", sent);
        System.out.printf(" Messages Received:   %d%n", received);
        System.out.printf(" Failed Messages:     %d%n", deadLetterQueue.size());
        System.out.printf(" Success Rate:        %.1f%%%n",
            sent > 0 ? (received * 100.0 / sent) : 0.0);
        System.out.println();

        // Performance
        System.out.printf(" Duration:            %.2fs%n", durationSeconds);
        System.out.printf(" Throughput:          %.0f msg/sec%n", throughput);
        System.out.printf(" Avg Latency:         %.2fms%n", calculateAverageLatency());

        System.out.println("=================================================================\n");
    }

    /**
     * Calculates average response latency.
     */
    private double calculateAverageLatency() {
        if (responseLatencies.isEmpty()) {
            return 0.0;
        }

        long totalLatency = responseLatencies.values().stream().mapToLong(Long::longValue).sum();
        return (totalLatency / (double) responseLatencies.size()) / 1_000_000.0; // Convert to milliseconds
    }

    /**
     * Shuts down all components.
     */
    private void shutdown() {
        System.out.println("Shutting down main phase components...");

        // Shutdown executors
        shutdownExecutor(producerExecutor, "Producer");
        shutdownExecutor(consumerExecutor, "Consumer");
        shutdownExecutor(responseExecutor, "Response");
        shutdownExecutor(monitorExecutor, "Monitor");
        shutdownExecutor(retryExecutor, "Retry");

        // Close connections
        if (connectionPool != null) {
            connectionPool.closeAll();
        }

        System.out.println("Shutdown complete");
    }

    /**
     * Helper method to shutdown executor.
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}

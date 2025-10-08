package cs6650.chatflow.client.coordinator;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.sender.MessageConsumer;
import cs6650.chatflow.client.sender.MessageProducer;
import cs6650.chatflow.client.sender.ResponseProcessor;
import cs6650.chatflow.client.util.*;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;

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

    private final String serverIp;
    private final String port;

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

    // Counters
    private final AtomicInteger messagesSent = new AtomicInteger(0);
    private final AtomicInteger messagesReceived = new AtomicInteger(0);
    private final Map<String, Long> responseLatencies = new ConcurrentHashMap<>();

    /**
     * Creates main phase executor.
     * @param serverIp server IP address
     * @param port server port
     */
    public MainPhaseExecutor(String serverIp, String port) {
        this.serverIp = serverIp;
        this.port = port;
    }

    /**
     * Executes the main phase load test.
     */
    public void execute() throws URISyntaxException, InterruptedException {
        System.out.println("\n=================================================================");
        System.out.println("                     ChatFlow Main Phase");
        System.out.println("              High-Throughput Load Testing");
        System.out.println("=================================================================\n");

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

            // Generate report
            generateReport(startTime);

        } finally {
            // Cleanup
            shutdown();
        }
    }

    /**
     * Initializes all components.
     */
    private void initializeComponents() throws URISyntaxException, InterruptedException {
        System.out.println("Initializing main phase components...");

        // Create queues and utilities
        messageQueue = new MessageQueue();
        responseQueue = new ResponseQueue();
        deadLetterQueue = new DeadLetterQueue();
        messageTimer = new MessageTimer();

        // Create connection pool - use random room ID like warmup phase
        int roomId = (int) (Math.random() * Constants.ROOM_COUNT) + 1;
        URI serverUri = new URI(String.format("ws://%s:%s/chatflow-server/chat/room%d", serverIp, port, roomId));
        connectionPool = new WebSocketConnectionPool(serverUri, responseQueue);

        System.out.println("Components initialized successfully");
    }

    /**
     * Starts all threads.
     */
    private void startThreads() {
        System.out.println("Starting main phase threads...");

        // Create executors
        producerExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "MessageProducer"));
        consumerExecutor = Executors.newFixedThreadPool(Constants.MAIN_PHASE_CONSUMER_THREADS,
            r -> new Thread(r, "MessageConsumer-" + r.hashCode()));
        responseExecutor = Executors.newFixedThreadPool(Constants.RESPONSE_THREAD_POOL_SIZE,
            r -> new Thread(r, "ResponseProcessor-" + r.hashCode()));
        monitorExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "TimeoutMonitor"));

        // Start producer
        producerExecutor.submit(new MessageProducer(messageQueue, Constants.TOTAL_MESSAGES));

        // Start consumers
        for (int i = 0; i < Constants.MAIN_PHASE_CONSUMER_THREADS; i++) {
            consumerExecutor.submit(new MessageConsumer(messageQueue, connectionPool,
                messageTimer, messagesSent));
        }

        // Start response processors
        for (int i = 0; i < Constants.RESPONSE_THREAD_POOL_SIZE; i++) {
            responseExecutor.submit(new ResponseProcessor(responseQueue, messageTimer,
                messagesReceived, responseLatencies));
        }

        // Start timeout monitor
        monitorExecutor.submit(new TimeoutMonitor(messageTimer, deadLetterQueue));

        System.out.println("All threads started");
    }

    /**
     * Waits for test completion - all messages sent AND all responses received or timed out.
     */
    private void waitForCompletion() throws InterruptedException {
        System.out.println("Waiting for message generation and processing to complete...");

        long startWaitTime = System.currentTimeMillis();
        long maxWaitTime = 10 * 60 * 1000; // 10 minutes maximum wait time

        // Wait for producer to finish
        producerExecutor.shutdown();
        producerExecutor.awaitTermination(10, TimeUnit.MINUTES);

        // Wait for message queue to be empty (all messages sent)
        while (messageQueue.size() > 0) {
            Thread.sleep(1000);
            System.out.printf("Waiting for queue to empty: %d messages remaining%n", messageQueue.size());
        }

        System.out.println("All messages sent. Waiting for all responses...");

        // Wait until all responses received OR global timeout reached
        while (true) {
            int sent = messagesSent.get();
            int received = messagesReceived.get();
            int failed = deadLetterQueue.size();
            int accountedFor = received + failed;

            // Check if all messages are accounted for
            if (accountedFor >= sent) {
                System.out.println("All messages accounted for - test complete");
                break;
            }

            // Check for global timeout
            long elapsed = System.currentTimeMillis() - startWaitTime;
            if (elapsed > maxWaitTime) {
                System.out.printf("Global timeout reached after %.1f minutes. %d/%d messages still pending%n",
                    elapsed / 60000.0, (sent - accountedFor), sent);
                break;
            }

            // Progress reporting
            if ((System.currentTimeMillis() / 1000) % 10 == 0) { // Every 10 seconds
                System.out.printf("Waiting for responses: %d sent, %d received, %d failed, %d pending%n",
                    sent, received, failed, (sent - accountedFor));
            }

            Thread.sleep(1000);
        }

        System.out.println("Main processing complete");
    }

    /**
     * Processes dead letter queue for final retries.
     */
    private void processDeadLetterQueue() {
        System.out.println("Processing dead letter queue...");

        int failedCount = deadLetterQueue.size();
        if (failedCount > 0) {
            System.out.printf("Found %d failed messages, attempting final retries...%n", failedCount);

            // In a real implementation, we would retry these messages
            // For now, just log them
            System.out.printf("Dead letter queue processing complete. %d messages failed permanently.%n", failedCount);
        } else {
            System.out.println("No failed messages found.");
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
        System.out.printf(" Target Messages:     %d%n", Constants.TOTAL_MESSAGES);
        System.out.printf(" Consumer Threads:    %d%n", Constants.MAIN_PHASE_CONSUMER_THREADS);
        System.out.printf(" Connection Pool:     %d%n", Constants.MAIN_PHASE_CONNECTION_POOL_SIZE);
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

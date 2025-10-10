package cs6650.chatflow.client.coordinator;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.workers.WarmupWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Coordinator for warmup phase in client-part1.
 * Manages warmup threads that send messages and wait for responses.
 */
public class WarmupPhaseExecutor {
    private static final Logger logger = LoggerFactory.getLogger(WarmupPhaseExecutor.class);

    private final String wsBaseUri;
    private final AtomicInteger totalMessagesSent;
    private final AtomicInteger totalMessagesReceived;
    private final AtomicInteger warmupReconnections;
    private final AtomicInteger warmupConnections;

    /**
     * Creates warmup coordinator.
     * @param wsBaseUri WebSocket base URI (ws://server:port/servlet-context)
     * @param totalMessagesSent global sent counter
     * @param totalMessagesReceived global received counter
     * @param warmupReconnections global reconnection counter for warmup phase
     * @param warmupConnections global connection counter for warmup phase
     */
    public WarmupPhaseExecutor(String wsBaseUri, AtomicInteger totalMessagesSent, AtomicInteger totalMessagesReceived, AtomicInteger warmupReconnections, AtomicInteger warmupConnections) {
        this.wsBaseUri = wsBaseUri;
        this.totalMessagesSent = totalMessagesSent;
        this.totalMessagesReceived = totalMessagesReceived;
        this.warmupReconnections = warmupReconnections;
        this.warmupConnections = warmupConnections;
    }

    /**
     * Executes the warmup phase.
     */
    public void execute() throws InterruptedException {
        logger.info("Starting warmup phase with {} threads", Constants.WARMUP_THREADS);

        long startTime = System.currentTimeMillis();

        ExecutorService executorService = Executors.newFixedThreadPool(Constants.WARMUP_THREADS);
        CountDownLatch sendCompletionLatch = new CountDownLatch(Constants.WARMUP_THREADS);
        CountDownLatch responseCompletionLatch = new CountDownLatch(Constants.WARMUP_THREADS);

        logger.info("Initializing warmup threads...");

        for (int i = 0; i < Constants.WARMUP_THREADS; i++) {
            String roomId = "room" + ((i % Constants.ROOM_COUNT) + 1);
            try {
                logger.debug("Creating warmup thread {} with room {}", i + 1, roomId);
                executorService.submit(new WarmupWorker(wsBaseUri, roomId,
                        Constants.MESSAGES_PER_THREAD, sendCompletionLatch, responseCompletionLatch,
                        totalMessagesSent, totalMessagesReceived, warmupReconnections, warmupConnections));
            } catch (Exception e) {
                logger.error("Failed to create warmup thread: {}", e.getMessage());
                System.exit(1);
            }
        }

        logger.info("Waiting for all warmup threads to complete sending...");
        sendCompletionLatch.await();
        long sendCompletionTime = System.currentTimeMillis();
        double sendDurationSeconds = (sendCompletionTime - startTime) / 1000.0;
        int actualMessagesSent = totalMessagesSent.get();
        double sendThroughput = actualMessagesSent > 0 ? actualMessagesSent / sendDurationSeconds : 0;

        logger.info("Waiting for all warmup responses...");
        responseCompletionLatch.await();
        executorService.shutdown();

        long endTime = System.currentTimeMillis();
        double totalDurationSeconds = (endTime - startTime) / 1000.0;
        int actualMessagesReceived = totalMessagesReceived.get();
        double overallThroughput = actualMessagesReceived > 0 ? actualMessagesReceived / totalDurationSeconds : 0;

        printWarmupResults(Constants.WARMUP_THREADS, actualMessagesSent, actualMessagesReceived,
                          sendDurationSeconds, totalDurationSeconds, sendThroughput, overallThroughput);
    }

    private static void printWarmupResults(int warmupThreads, int messagesSent, int messagesReceived,
                                          double sendTime, double totalTime, double sendThroughput, double overallThroughput) {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                     Warmup Phase Results");
        System.out.println("-----------------------------------------------------------------");

        // Configuration
        System.out.println(" CONFIGURATION:");
        System.out.printf("   Threads:           %d%n", warmupThreads);
        System.out.printf("   Messages/Thread:   %d%n", Constants.MESSAGES_PER_THREAD);
        System.out.printf("   Expected Messages: %d%n", warmupThreads * Constants.MESSAGES_PER_THREAD);
        System.out.printf("   Room Count:        %d%n", Constants.ROOM_COUNT);
        System.out.println();

        // Results
        System.out.println(" RESULTS:");
        System.out.printf("   Messages Sent:     %d%n", messagesSent);
        System.out.printf("   Messages Received: %d%n", messagesReceived);
        System.out.printf("   Success Rate:      %.1f%%%n",
            messagesSent > 0 ? (messagesReceived * 100.0 / messagesSent) : 0.0);
        System.out.println();

        // Performance
        System.out.println(" PERFORMANCE:");
        System.out.printf("   Send Duration:     %.2fs%n", sendTime);
        System.out.printf("   Total Duration:    %.2fs%n", totalTime);
        System.out.printf("   Send Throughput:   %.0f msg/sec%n", sendThroughput);
        System.out.printf("   Overall Throughput: %.0f msg/sec%n", overallThroughput);

        System.out.println("=================================================================");
        System.out.println();
    }
}

package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.coordinator.MainPhaseExecutor;
import cs6650.chatflow.client.coordinator.MainPhaseResult;
import cs6650.chatflow.client.sender.WarmupSenderThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main class for ChatFlow client warmup phase.
 * Executes WebSocket performance tests using multiple threads.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        if (args.length < 2) {
            printUsage();
            System.exit(1);
        }
        String serverIp = args[0];
        String port = args[1];

        printHeader();

        ExecutorService executorService = Executors.newFixedThreadPool(Constants.WARMUP_THREADS);
        CountDownLatch sendCompletionLatch = new CountDownLatch(Constants.WARMUP_THREADS);
        CountDownLatch responseCompletionLatch = new CountDownLatch(Constants.WARMUP_THREADS);
        AtomicInteger totalMessagesSent = new AtomicInteger(0);
        AtomicInteger totalMessagesReceived = new AtomicInteger(0);

        logger.info("Initializing warmup threads...");
        long warmupStartTime = System.currentTimeMillis();

        for (int i = 0; i < Constants.WARMUP_THREADS; i++) {
            String roomId = "room" + ((i % Constants.ROOM_COUNT) + 1);
            try {
                logger.debug("Creating warmup thread {} with room {}", i + 1, roomId);
                executorService.submit(new WarmupSenderThread(serverIp, port, roomId,
                        Constants.MESSAGES_PER_THREAD, sendCompletionLatch, responseCompletionLatch,
                        totalMessagesSent, totalMessagesReceived));
            } catch (Exception e) {
                logger.error("Failed to create Java-WebSocket thread: {}", e.getMessage());
                System.exit(1);
            }
        }

        logger.info("Waiting for all warmup threads to complete sending...");
        sendCompletionLatch.await();
        long sendCompletionTime = System.currentTimeMillis();
        double sendDurationSeconds = (sendCompletionTime - warmupStartTime) / 1000.0;
        int actualMessagesSent = totalMessagesSent.get();
        double sendThroughput = actualMessagesSent > 0 ? actualMessagesSent / sendDurationSeconds : 0;

        logger.info("Waiting for all warmup responses...");
        responseCompletionLatch.await();
        executorService.shutdown();

        long warmupEndTime = System.currentTimeMillis();
        double warmupDurationSeconds = (warmupEndTime - warmupStartTime) / 1000.0;
        int actualMessagesReceived = totalMessagesReceived.get();
        double warmupThroughput = actualMessagesReceived > 0 ? actualMessagesReceived / warmupDurationSeconds : 0;

        printWarmupResults(Constants.WARMUP_THREADS, actualMessagesSent, actualMessagesReceived,
                          sendDurationSeconds, warmupDurationSeconds, sendThroughput, warmupThroughput);

        // Execute main phase - send remaining messages after warmup
        long mainPhaseStartTime = System.currentTimeMillis();
        MainPhaseResult mainPhaseResult = null;
        try {
            int mainPhaseMessages = Constants.TOTAL_MESSAGES - Constants.WARMUP_TOTAL_MESSAGES;
            MainPhaseExecutor mainExecutor = new MainPhaseExecutor(serverIp, port, mainPhaseMessages);
            mainPhaseResult = mainExecutor.execute();
        } catch (Exception e) {
            System.err.println("Error during main phase: " + e.getMessage());
            e.printStackTrace();
        }

        // Generate final overall summary
        long overallStartTime = warmupStartTime;
        long overallEndTime = mainPhaseResult != null ? mainPhaseResult.getTestEndTime() : System.currentTimeMillis();
        int totalSent = Constants.TOTAL_MESSAGES;
        int mainPhaseReceived = mainPhaseResult != null ? mainPhaseResult.getMessagesReceived() : 0;
        int totalReceived = actualMessagesReceived + mainPhaseReceived;
        generateFinalSummary(overallStartTime, overallEndTime, totalSent, totalReceived);
    }

    private static void printHeader() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                     ChatFlow Warmup");
        System.out.println("                  WebSocket Performance Test");
        System.out.println("=================================================================");
        System.out.println();
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                          Usage");
        System.out.println("-----------------------------------------------------------------");
        System.out.println(" java -jar client-part1.jar <server-ip> <port>");
        System.out.println();
        System.out.println(" Examples:");
        System.out.println("   java -jar client-part1.jar localhost 8080");
        System.out.println("   java -jar client-part1.jar 192.168.1.100 8081");
        System.out.println("=================================================================");
        System.out.println();
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

    /**
     * Generates final overall test summary combining warmup and main phases.
     */
    private static void generateFinalSummary(long startTime, long endTime, int totalSent, int totalReceived) {
        double durationSeconds = (endTime - startTime) / 1000.0;
        double throughput = totalSent > 0 ? totalSent / durationSeconds : 0;
        double successRate = totalSent > 0 ? (totalReceived * 100.0) / totalSent : 0;

        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                   ChatFlow Test Complete");
        System.out.println("-----------------------------------------------------------------");

        // Overall Results
        System.out.println(" OVERALL RESULTS:");
        System.out.printf("   Total Messages:     %d%n", totalSent);
        System.out.printf("   Messages Received:  %d%n", totalReceived);
        System.out.printf("   Success Rate:       %.1f%%%n", successRate);
        System.out.println();

        // Overall Performance
        System.out.println(" OVERALL PERFORMANCE:");
        System.out.printf("   Total Duration:     %.2fs%n", durationSeconds);
        System.out.printf("   Overall Throughput: %.0f msg/sec%n", throughput);

        System.out.println("=================================================================");
        System.out.println();
    }
}

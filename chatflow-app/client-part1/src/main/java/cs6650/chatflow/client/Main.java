package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.coordinator.MainPhaseCoordinator;
import cs6650.chatflow.client.model.MainPhaseResult;
import cs6650.chatflow.client.coordinator.WarmupPhaseCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main class for ChatFlow client.
 * Orchestrates warmup and main phases using coordinators.
 */
public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws InterruptedException {
        if (args.length != 3) {
            printUsage();
            System.exit(1);
        }
        String serverIp = args[0];
        String port = args[1];
        String servletContext = args[2];

        // Build WebSocket base URI
        String wsBaseUri = "ws://" + serverIp + ":" + port + "/" + servletContext;
        logger.info("WebSocket base URI: {}", wsBaseUri);

        printHeader();

        // Global counters shared between phases
        AtomicInteger totalMessagesSent = new AtomicInteger(0);
        AtomicInteger totalMessagesReceived = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        try {
            // Phase 1: Warmup
            logger.info("Starting Warmup Phase ...");
            WarmupPhaseCoordinator warmupPhaseCoordinator = new WarmupPhaseCoordinator(
                wsBaseUri, totalMessagesSent, totalMessagesReceived);
            warmupPhaseCoordinator.execute();

            // Phase 2: Main Load Test
            logger.info("Starting Main Phase ...");
            MainPhaseCoordinator mainPhaseCoordinator = new MainPhaseCoordinator(
                wsBaseUri, totalMessagesSent, totalMessagesReceived);
            MainPhaseResult mainPhaseResult = mainPhaseCoordinator.execute();

            // Final Summary
            generateFinalSummary(startTime, Constants.TOTAL_MESSAGES, totalMessagesReceived.get(),
                               mainPhaseResult);

        } catch (Exception e) {
            logger.error("Fatal error during execution: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printHeader() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                     ChatFlow Client");
        System.out.println("                  WebSocket Performance Test");
        System.out.println("=================================================================");
        System.out.println();
    }

    private static void printUsage() {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                          Usage");
        System.out.println("-----------------------------------------------------------------");
        System.out.println(" java -jar client-part1.jar <server> <port> <servlet-context>");
        System.out.println();
        System.out.println(" Examples:");
        System.out.println("   java -jar client-part1.jar localhost 8080 chatflow-server");
        System.out.println("   java -jar client-part1.jar 192.168.1.100 8081 chatflow-server");
        System.out.println("=================================================================");
        System.out.println();
    }

    /**
     * Generates final overall test summary combining warmup and main phases.
     */
    private static void generateFinalSummary(long startTime, int totalSent, int warmupReceived,
                                           MainPhaseResult mainPhaseResult) {
        long endTime = mainPhaseResult != null ? mainPhaseResult.getTestEndTime() : System.currentTimeMillis();
        int mainPhaseReceived = mainPhaseResult != null ? mainPhaseResult.getMessagesReceived() : 0;
        int totalReceived = warmupReceived + mainPhaseReceived;

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

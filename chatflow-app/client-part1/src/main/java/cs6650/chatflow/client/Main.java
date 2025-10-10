package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.coordinator.MainPhaseExecutor;
import cs6650.chatflow.client.model.MainPhaseResult;
import cs6650.chatflow.client.coordinator.WarmupPhaseExecutor;
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
        AtomicInteger warmupReconnections = new AtomicInteger(0);
        AtomicInteger warmupConnections = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        try {
            // Phase 1: Warmup
            logger.info("Starting Warmup Phase ...");
            WarmupPhaseExecutor warmupPhaseExecutor = new WarmupPhaseExecutor(
                wsBaseUri, totalMessagesSent, totalMessagesReceived, warmupReconnections, warmupConnections);
            warmupPhaseExecutor.execute();

            // Phase 2: Main Load Test
            logger.info("Starting Main Phase ...");

            MainPhaseExecutor mainPhaseExecutor = new MainPhaseExecutor(wsBaseUri, Constants.TOTAL_MESSAGES - Constants.WARMUP_TOTAL_MESSAGES);
            MainPhaseResult mainPhaseResult = mainPhaseExecutor.execute();

            logger.info("Main phase completed successfully");

            // Final Summary
            generateFinalSummary(startTime, Constants.TOTAL_MESSAGES, totalMessagesReceived.get(),
                               mainPhaseResult, warmupReconnections.get(), warmupConnections.get(), wsBaseUri);

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
                                           MainPhaseResult mainPhaseResult, int warmupReconnections, int warmupConnections, String wsBaseUri) {
        long endTime = mainPhaseResult != null ? mainPhaseResult.getTestEndTime() : System.currentTimeMillis();
        int mainPhaseReceived = mainPhaseResult != null ? mainPhaseResult.getMessagesReceived() : 0;
        int mainPhaseFailed = mainPhaseResult != null ? mainPhaseResult.getMessagesFailed() : 0;
        int totalReceived = warmupReceived + mainPhaseReceived;
        int totalFailed = mainPhaseFailed; // Only main phase has failures tracked

        double durationSeconds = (endTime - startTime) / 1000.0;
        double throughput = totalReceived > 0 ? totalReceived / durationSeconds : 0;
        double successRate = totalSent > 0 ? (totalReceived * 100.0) / totalSent : 0;

        // Connection statistics
        int mainPhaseConnections = mainPhaseResult != null ? mainPhaseResult.getTotalConnections() : 0;
        int mainPhaseReconnections = mainPhaseResult != null ? mainPhaseResult.getReconnections() : 0;

        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                   ChatFlow Part 1 Test Complete");
        System.out.println("=================================================================");
        System.out.printf("Server base URI: %s%n", wsBaseUri);
        System.out.println();

        System.out.printf("Warmup websocket connections: %d total connections, %d reconnections%n", warmupConnections, warmupReconnections);
        System.out.printf("Main phase websocket connections: %d total connections, %d reconnections%n",
                mainPhaseConnections, mainPhaseReconnections);
        System.out.printf("Number of successful messages sent: %d%n", totalReceived);
        System.out.printf("Number of failed messages: %d%n", totalFailed);
        System.out.printf("Total runtime (wall time): %.2f seconds%n", durationSeconds);
        System.out.printf("Overall throughput: %.0f messages/second%n", throughput);
        System.out.printf("Success rate: %.1f%%%n", successRate);


        System.out.println("=================================================================");
        System.out.println();
    }
}

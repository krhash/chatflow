package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.sender.WarmupSenderThread;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main class for ChatFlow client warmup phase.
 * Executes WebSocket performance tests using multiple threads.
 */
public class Main {

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

        System.out.println("Initializing warmup threads...");
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < Constants.WARMUP_THREADS; i++) {
            String roomId = "room" + ((i % Constants.ROOM_COUNT) + 1);
            try {
                executorService.submit(new WarmupSenderThread(serverIp, port, roomId,
                        Constants.MESSAGES_PER_THREAD, sendCompletionLatch, responseCompletionLatch,
                        totalMessagesSent, totalMessagesReceived));
            } catch (Exception e) {
                System.err.println("Failed to create Java-WebSocket thread: " + e.getMessage());
                System.exit(1);
            }
        }

        System.out.println("Waiting for all threads to complete sending...");
        sendCompletionLatch.await();
        long sendCompletionTime = System.currentTimeMillis();
        double sendDurationSeconds = (sendCompletionTime - startTime) / 1000.0;
        int actualMessagesSent = totalMessagesSent.get();
        double sendThroughput = actualMessagesSent > 0 ? actualMessagesSent / sendDurationSeconds : 0;

        System.out.println("Waiting for all responses...");
        responseCompletionLatch.await();
        executorService.shutdown();

        long responseCompletionTime = System.currentTimeMillis();
        double responseDurationSeconds = (responseCompletionTime - startTime) / 1000.0;
        int actualMessagesReceived = totalMessagesReceived.get();
        double overallThroughput = actualMessagesReceived > 0 ? actualMessagesReceived / responseDurationSeconds : 0;

        printResults(Constants.WARMUP_THREADS, actualMessagesSent, actualMessagesReceived,
                    sendDurationSeconds, responseDurationSeconds, sendThroughput, overallThroughput);
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

    private static void printResults(int warmupThreads, int messagesSent, int messagesReceived,
                                   double sendTime, double totalTime, double sendThroughput, double overallThroughput) {
        System.out.println();
        System.out.println("=================================================================");
        System.out.println("                     Warmup Results");
        System.out.println("-----------------------------------------------------------------");

        // Configuration
        System.out.printf(" Threads:           %d%n", warmupThreads);
        System.out.printf(" Expected Messages: %d%n", warmupThreads * Constants.MESSAGES_PER_THREAD);
        System.out.println();

        // Results
        System.out.printf(" Messages Sent:     %d%n", messagesSent);
        System.out.printf(" Messages Received: %d%n", messagesReceived);
        System.out.printf(" Success Rate:      %.1f%%%n",
            messagesSent > 0 ? (messagesReceived * 100.0 / messagesSent) : 0.0);
        System.out.println();

        // Performance
        System.out.printf(" Send Time:         %.2fs%n", sendTime);
        System.out.printf(" Total Time:        %.2fs%n", totalTime);
        System.out.printf(" Send Throughput:   %.0f msg/sec%n", sendThroughput);
        System.out.printf(" Overall Throughput: %.0f msg/sec%n", overallThroughput);

        System.out.println("=================================================================");
        System.out.println();
    }
}

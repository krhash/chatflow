package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.connection.ProducerConnectionPool;
import cs6650.chatflow.client.connection.ReceiverConnectionPool;
import cs6650.chatflow.client.connection.SimpleWebSocketClient;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sustained Load Test Client
 * Sends messages at a controlled, sustained rate to test system endurance
 * Uses existing connection pool infrastructure from DistributedClient
 */
public class SustainedLoadClient {
    private static final Logger logger = LoggerFactory.getLogger(SustainedLoadClient.class);
    private static final Gson gson = new Gson();

    // Test Configuration
    private final int targetThroughput;      // Messages per second
    private final int durationSeconds;       // Test duration
    private final String producerHost;
    private final int producerPort;
    private final String consumerHost;
    private final int consumerPort;
    private final int totalRooms;

    // Connection Pools (reuse from DistributedClient)
    private ProducerConnectionPool producerConnectionPool;
    private ReceiverConnectionPool consumerConnectionPool;

    // Rate Limiting
    private final Semaphore rateLimiter;
    private final ScheduledExecutorService rateLimiterRefill;

    // Message Generation
    private final ExecutorService generatorExecutor;
    private volatile boolean running = true;

    // Metrics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);
    private final long testStartTime = System.currentTimeMillis();

    // User Management
    private final Set<String> activeUsers = ConcurrentHashMap.newKeySet();
    private final AtomicLong userIdCounter = new AtomicLong(0);

    /**
     * Constructor
     */
    public SustainedLoadClient(String producerHost, int producerPort,
                               String consumerHost, int consumerPort,
                               int targetThroughput, int durationSeconds, int totalRooms) {
        this.producerHost = producerHost;
        this.producerPort = producerPort;
        this.consumerHost = consumerHost;
        this.consumerPort = consumerPort;
        this.targetThroughput = targetThroughput;
        this.durationSeconds = durationSeconds;
        this.totalRooms = totalRooms;

        this.rateLimiter = new Semaphore(targetThroughput);
        this.rateLimiterRefill = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "RateLimiterRefill");
            t.setDaemon(true);
            return t;
        });
        this.generatorExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "MessageGenerator");
            t.setDaemon(false);
            return t;
        });
    }

    /**
     * Main execution method
     */
    public void run() {
        try {
            System.out.println("╔════════════════════════════════════════════════════════╗");
            System.out.println("║         SUSTAINED LOAD TEST CLIENT                    ║");
            System.out.println("╚════════════════════════════════════════════════════════╝");
            System.out.println();
            System.out.println("Configuration:");
            System.out.println("  Target Throughput: " + targetThroughput + " msg/sec");
            System.out.println("  Test Duration: " + durationSeconds + " seconds (" + (durationSeconds/60) + " minutes)");
            System.out.println("  Total Messages: " + ((long)targetThroughput * durationSeconds));
            System.out.println("  Producer: " + producerHost + ":" + producerPort);
            System.out.println("  Consumer: " + consumerHost + ":" + consumerPort);
            System.out.println("  Rooms: " + totalRooms);
            System.out.println();

            // Initialize connection pools
            initializeConnections();

            // Start rate limiter
            startRateLimiter();

            // Start progress monitor
            startProgressMonitor();

            // Run sustained load
            runSustainedLoad();

            // Wait a bit for final messages to send
            System.out.println("Waiting for final messages to send...");
            Thread.sleep(5000);

            // Shutdown
            shutdown();
            printFinalReport();

        } catch (Exception e) {
            logger.error("Test failed", e);
            e.printStackTrace();
            shutdown();
        }
    }

    /**
     * Initialize connection pools
     */
    private void initializeConnections() {
        System.out.println("Initializing connection pools...");

        // Producer connection pool
        producerConnectionPool = new ProducerConnectionPool(
                producerHost,
                producerPort,
                Constants.PRODUCER_SERVER_PATH
        );
        System.out.println("✅ Producer connection pool initialized");

        // Consumer connection pool
        consumerConnectionPool = new ReceiverConnectionPool(
                consumerHost,
                consumerPort,
                Constants.CONSUMER_SERVER_PATH
        );
        System.out.println("✅ Consumer connection pool initialized");

        // Wait for connections to establish
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Start rate limiter that refills permits every second
     */
    private void startRateLimiter() {
        rateLimiterRefill.scheduleAtFixedRate(() -> {
            rateLimiter.drainPermits();
            rateLimiter.release(targetThroughput);
        }, 0, 1, TimeUnit.SECONDS);

        System.out.println("✅ Rate limiter started: " + targetThroughput + " permits/sec");
    }

    /**
     * Start progress monitoring
     */
    private void startProgressMonitor() {
        ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor();

        monitor.scheduleAtFixedRate(() -> {
            long sent = messagesSent.get();
            long failed = messagesFailed.get();
            long elapsed = (System.currentTimeMillis() - testStartTime) / 1000;

            if (elapsed > 0) {
                double actualRate = (double) sent / elapsed;
                double successRate = sent > 0 ? ((sent - failed) * 100.0 / sent) : 100.0;
                long totalTarget = (long)targetThroughput * durationSeconds;
                long remaining = totalTarget - sent;
                long etaSeconds = remaining > 0 && actualRate > 0 ? (long)(remaining / actualRate) : 0;

                System.out.printf("[%02d:%02d] Sent: %,d | Failed: %,d | Rate: %.1f msg/sec | Success: %.2f%% | ETA: %02d:%02d%n",
                        elapsed / 60, elapsed % 60,
                        sent, failed, actualRate, successRate,
                        etaSeconds / 60, etaSeconds % 60);
            }
        }, 10, 10, TimeUnit.SECONDS);

        System.out.println("✅ Progress monitor started");
        System.out.println();
    }

    /**
     * Run sustained load test
     */
    private void runSustainedLoad() {
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println("Starting sustained load generation...");
        System.out.println("════════════════════════════════════════════════════════");
        System.out.println();

        generatorExecutor.submit(() -> {
            long endTime = testStartTime + (durationSeconds * 1000L);
            Random random = new Random();

            // Message type distribution
            long totalMessages = (long) targetThroughput * durationSeconds;
            long joinPhase = totalMessages / 20;        // First 5%
            long textPhaseEnd = joinPhase + (totalMessages * 9 / 10); // Next 90%

            long messagesGenerated = 0;

            while (System.currentTimeMillis() < endTime && running) {
                try {
                    // Acquire permit (blocks if rate limit reached)
                    rateLimiter.acquire();

                    // Determine message type
                    String messageType;
                    String userId;
                    String messageText;

                    if (messagesGenerated < joinPhase) {
                        // JOIN phase
                        messageType = Constants.MESSAGE_TYPE_JOIN;
                        userId = String.valueOf(userIdCounter.incrementAndGet());
                        activeUsers.add(userId);
                        messageText = "User " + userId + " joined";

                    } else if (messagesGenerated < textPhaseEnd) {
                        // TEXT phase
                        List<String> users = new ArrayList<>(activeUsers);
                        if (users.isEmpty()) {
                            Thread.sleep(10);
                            continue;
                        }
                        userId = users.get(random.nextInt(users.size()));
                        messageType = Constants.MESSAGE_TYPE_TEXT;
                        messageText = "Message " + messagesGenerated;

                    } else {
                        // LEAVE phase
                        List<String> users = new ArrayList<>(activeUsers);
                        if (users.isEmpty()) {
                            Thread.sleep(10);
                            continue;
                        }
                        userId = users.get(random.nextInt(users.size()));
                        activeUsers.remove(userId);
                        messageType = Constants.MESSAGE_TYPE_LEAVE;
                        messageText = "User " + userId + " left";
                    }

                    // Create message
                    ChatMessage msg = createMessage(userId, messageType, messageText);

                    // Pick random room
                    String roomId = "room" + (random.nextInt(totalRooms) + 1);

                    // Send message
                    sendMessage(msg, roomId);
                    messagesGenerated++;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error generating message", e);
                }
            }

            long actualDuration = (System.currentTimeMillis() - testStartTime) / 1000;
            double actualThroughput = (double) messagesGenerated / actualDuration;

            System.out.println();
            System.out.println("════════════════════════════════════════════════════════");
            System.out.println("Message generation completed:");
            System.out.println("  Generated: " + messagesGenerated + " messages");
            System.out.println("  Duration: " + actualDuration + " seconds");
            System.out.println("  Actual throughput: " + String.format("%.2f", actualThroughput) + " msg/sec");
            System.out.println("════════════════════════════════════════════════════════");
        });

        // Wait for generator to complete
        try {
            generatorExecutor.shutdown();
            generatorExecutor.awaitTermination(durationSeconds + 60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Create a chat message
     */
    private ChatMessage createMessage(String userId, String messageType, String messageText) {
        ChatMessage msg = new ChatMessage();
        msg.setMessageId(UUID.randomUUID().toString());
        msg.setUserId(userId);
        msg.setUsername("User" + userId);
        msg.setMessage(messageText);
        msg.setMessageType(messageType);
        msg.setTimestamp(String.valueOf(System.currentTimeMillis()));
        return msg;
    }

    /**
     * Send message via producer connection pool
     */
    private void sendMessage(ChatMessage message, String roomId) {
        try {
            SimpleWebSocketClient connection = producerConnectionPool.getConnection(roomId);

            if (connection != null && connection.isOpen()) {
                String json = gson.toJson(message);
                connection.send(json);
                messagesSent.incrementAndGet();
            } else {
                messagesFailed.incrementAndGet();
                logger.warn("No connection available for room: {}", roomId);
            }

        } catch (Exception e) {
            messagesFailed.incrementAndGet();
            logger.error("Failed to send message", e);
        }
    }

    /**
     * Shutdown gracefully
     */
    private void shutdown() {
        System.out.println();
        System.out.println("Shutting down...");

        running = false;

        // Close connection pools
        if (producerConnectionPool != null) {
            producerConnectionPool.closeAll();
            System.out.println("✅ Producer connections closed");
        }

        if (consumerConnectionPool != null) {
            consumerConnectionPool.closeAll();
            System.out.println("✅ Consumer connections closed");
        }

        // Shutdown executors
        if (rateLimiterRefill != null) {
            rateLimiterRefill.shutdown();
        }

        if (generatorExecutor != null && !generatorExecutor.isShutdown()) {
            generatorExecutor.shutdownNow();
        }

        System.out.println("✅ Shutdown complete");
    }

    /**
     * Print final report
     */
    private void printFinalReport() {
        long totalTime = System.currentTimeMillis() - testStartTime;
        long sent = messagesSent.get();
        long failed = messagesFailed.get();
        double seconds = totalTime / 1000.0;

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║              SUSTAINED LOAD TEST RESULTS               ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Test Summary:");
        System.out.println("  Duration: " + String.format("%.1f", seconds) + " seconds (" + String.format("%.1f", seconds/60) + " minutes)");
        System.out.println("  Messages Sent: " + String.format("%,d", sent));
        System.out.println("  Messages Failed: " + String.format("%,d", failed));

        if (sent > 0) {
            System.out.println("  Success Rate: " + String.format("%.2f%%", (sent - failed) * 100.0 / sent));
        }

        System.out.println();
        System.out.println("Throughput:");
        System.out.println("  Target: " + String.format("%,d", targetThroughput) + " msg/sec");

        if (seconds > 0) {
            double actualThroughput = sent / seconds;
            System.out.println("  Actual: " + String.format("%.2f", actualThroughput) + " msg/sec");

            double percentOfTarget = (actualThroughput / targetThroughput) * 100;
            System.out.println("  Performance: " + String.format("%.1f%%", percentOfTarget) + " of target");
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("Next Steps:");
        System.out.println("  1. Wait 2-3 minutes for consumer to flush all batches");
        System.out.println("  2. Call consumer metrics API:");
        System.out.println("     curl http://" + consumerHost + ":" + consumerPort + "/consumer-server/api/metrics");
        System.out.println("  3. Check consumer logs for throughput milestones");
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }

    /**
     * Simple ChatMessage class (matches your existing model)
     */
    private static class ChatMessage {
        private String messageId;
        private String userId;
        private String username;
        private String message;
        private String messageType;
        private String timestamp;

        public void setMessageId(String messageId) { this.messageId = messageId; }
        public void setUserId(String userId) { this.userId = userId; }
        public void setUsername(String username) { this.username = username; }
        public void setMessage(String message) { this.message = message; }
        public void setMessageType(String messageType) { this.messageType = messageType; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

        public String getMessageId() { return messageId; }
        public String getUserId() { return userId; }
    }

    /**
     * Main method
     */
    public static void main(String[] args) {
        // Parse arguments (same format as DistributedClient)
        String producerHost = "localhost";
        int producerPort = 8080;
        String consumerHost = "localhost";
        int consumerPort = 8080;
        int throughput = 2500;
        int duration = 1800; // 30 minutes default
        int rooms = 20;

        if (args.length >= 4) {
            producerHost = args[0];
            producerPort = Integer.parseInt(args[1]);
            consumerHost = args[2];
            consumerPort = Integer.parseInt(args[3]);
        }
        if (args.length >= 5) {
            throughput = Integer.parseInt(args[4]);
        }
        if (args.length >= 6) {
            duration = Integer.parseInt(args[5]);
        }
        if (args.length >= 7) {
            rooms = Integer.parseInt(args[6]);
        }

        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║    SUSTAINED LOAD TEST - CONFIGURATION                ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println("  Producer: " + producerHost + ":" + producerPort);
        System.out.println("  Consumer: " + consumerHost + ":" + consumerPort);
        System.out.println("  Throughput: " + throughput + " msg/sec");
        System.out.println("  Duration: " + duration + " seconds (" + (duration/60) + " minutes)");
        System.out.println("  Total Messages: " + String.format("%,d", (long)throughput * duration));
        System.out.println("  Rooms: " + rooms);
        System.out.println();

        // Confirmation prompt for long tests
        if (duration > 600) {
            System.out.println("⚠️  This is a " + (duration/60) + " minute test sending " +
                    String.format("%,d", (long)throughput * duration) + " messages");
            System.out.print("Continue? (yes/no): ");
            Scanner scanner = new Scanner(System.in);
            String response = scanner.nextLine();
            if (!response.equalsIgnoreCase("yes") && !response.equalsIgnoreCase("y")) {
                System.out.println("Test cancelled.");
                return;
            }
        }

        SustainedLoadClient client = new SustainedLoadClient(
                producerHost, producerPort, consumerHost, consumerPort,
                throughput, duration, rooms
        );

        client.run();
    }
}

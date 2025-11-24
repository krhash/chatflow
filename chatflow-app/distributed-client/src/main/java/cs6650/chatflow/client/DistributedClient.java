package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.connection.ProducerConnectionPool;
import cs6650.chatflow.client.connection.ReceiverConnectionPool;
import cs6650.chatflow.client.connection.SenderConnectionPool;
import cs6650.chatflow.client.connection.SimpleWebSocketClient;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageQueueEntry;
import cs6650.chatflow.client.queues.MessageQueue;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistributedClient {

    private static final Logger logger = LoggerFactory.getLogger(DistributedClient.class);

    // ========== UPDATED: Fixed user pool configuration ==========
    private static final int TOTAL_MESSAGES = 10000000;
    private static final int MAX_USERS = 25000;           // Fixed user pool size
    private static final boolean SEND_LEAVE_MESSAGES = false; // Optional LEAVE messages
    // ============================================================

    private static final int THREAD_POOL_SIZE = 100;

    // Server configuration
    private final String producerHost;
    private final int producerPort;
    private final String consumerHost;
    private final int consumerPort;

    // Message queue - shared by all sender threads
    private final MessageQueue messageQueue = new MessageQueue();

    // Statistics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final long startTime = System.currentTimeMillis();
    private final AtomicInteger roomIdCounter = new AtomicInteger(0);

    // Concurrent data structure to track sent message IDs
    private final Set<String> sentMessageIds = ConcurrentHashMap.newKeySet();

    // Client metrics
    private final ClientMetrics metrics = new ClientMetrics();

    // Executor services
    private ExecutorService messageGeneratorExecutor;
    private ExecutorService senderExecutor;
    private ExecutorService receiverExecutor;
    private ScheduledExecutorService monitorExecutor;

    // WebSocket connection pools
    private ReceiverConnectionPool consumerConnectionPool;
    private SenderConnectionPool ackConnectionPool;
    private ProducerConnectionPool producerConnectionPool;

    // ========== UPDATED: Fixed user pool ==========
    private final List<String> userPool = new ArrayList<>(); // Fixed list of users
    // =============================================

    // JSON serializer
    private final Gson gson = new GsonBuilder().create();

    /**
     * Constructor with server configuration.
     */
    public DistributedClient(String producerHost, int producerPort, String consumerHost, int consumerPort) {
        this.producerHost = producerHost;
        this.producerPort = producerPort;
        this.consumerHost = consumerHost;
        this.consumerPort = consumerPort;

        // ========== NEW: Pre-create fixed user pool ==========
        for (int i = 1; i <= MAX_USERS; i++) {
            userPool.add(String.valueOf(i));
        }
        System.out.println("Created fixed user pool: " + MAX_USERS + " users");
        // ====================================================
    }

    public static void main(String[] args) {
        // Parse command line arguments
        String producerHost = "localhost";
        int producerPort = 8080;
        String consumerHost = "localhost";
        int consumerPort = 8081;

        if (args.length >= 4) {
            producerHost = args[0];
            producerPort = Integer.parseInt(args[1]);
            consumerHost = args[2];
            consumerPort = Integer.parseInt(args[3]);
        } else if (args.length > 0) {
            System.err.println("Usage: java -jar distributed-client.jar [producerHost] [producerPort] [consumerHost] [consumerPort]");
            System.err.println("Example: java -jar distributed-client.jar localhost 8080 localhost 8081");
            System.err.println("Using default values: localhost 8080 localhost 8081");
        }

        DistributedClient client = new DistributedClient(producerHost, producerPort, consumerHost, consumerPort);
        client.start();
    }

    public void start() {
        try {
            logger.info("Starting Distributed Client...");
            logger.info("Target: {} messages | Users: {} (fixed pool) | LEAVE messages: {}",
                    TOTAL_MESSAGES, MAX_USERS, SEND_LEAVE_MESSAGES);

            initializeExecutors();
            startMessageGeneration();
            startSenders();
            startMonitoring();

            // Wait for completion
            waitForCompletion();
            shutdown();
            printFinalReport();

        } catch (Exception e) {
            logger.error("Error running distributed client: {}", e.getMessage(), e);
            shutdown();
        }
    }

    /**
     * Initialize executor services and consumer connection pool.
     */
    private void initializeExecutors() {
        messageGeneratorExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "MessageGenerator"));
        senderExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE,
                r -> new Thread(r, "Sender-" + r.hashCode()));
        receiverExecutor = Executors.newFixedThreadPool(THREAD_POOL_SIZE,
                r -> new Thread(r, "Receiver-" + r.hashCode()));
        monitorExecutor = Executors.newScheduledThreadPool(1, r -> new Thread(r, "Monitor"));

        // Initialize both connection pools
        initializeConsumerConnectionPool();
        initializeProducerConnectionPool();
    }

    /**
     * Start message generation in a separate thread.
     */
    private void startMessageGeneration() {
        messageGeneratorExecutor.submit(this::generateMessages);
        System.out.println("Started message generator thread");
    }

    /**
     * Start sender threads.
     */
    private void startSenders() {
        for (int i = 0; i < THREAD_POOL_SIZE; i++) {
            senderExecutor.submit(new SenderWorker(messageQueue));
        }
        System.out.println("Started " + THREAD_POOL_SIZE + " sender threads");
    }

    /**
     * Start monitoring thread.
     */
    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::printProgress, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Generate all messages with FIXED user pool
     */
    private void generateMessages() {
        try {
            System.out.println("Starting message generation with fixed user pool...");
            Random random = new Random();

            // ========== PHASE 1: JOIN messages (all users) ==========
            int joinCount = MAX_USERS;
            System.out.println("Phase 1: Generating " + joinCount + " JOIN messages...");

            for (String userId : userPool) {
                ChatMessage message = createMessage(userId, Constants.MESSAGE_TYPE_JOIN, "joined");
                message.setUsername("User" + userId);

                String randomRoomId = "room" + (random.nextInt(Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID);

                try {
                    messageQueue.put(new MessageQueueEntry(message, randomRoomId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            System.out.println("✅ Generated " + joinCount + " JOIN messages");

            // ========== PHASE 2: TEXT messages (reuse users) ==========
            int textCount;
            if (SEND_LEAVE_MESSAGES) {
                textCount = (TOTAL_MESSAGES * 9) / 10; // 90% if we have LEAVE
            } else {
                textCount = TOTAL_MESSAGES - joinCount; // Rest are TEXT
            }

            System.out.println("Phase 2: Generating " + textCount + " TEXT messages (reusing " + MAX_USERS + " users)...");

            String[] messagePool = Arrays.copyOfRange(Constants.MESSAGE_POOL,
                    0, Math.min(10, Constants.MESSAGE_POOL.length));

            for (int i = 0; i < textCount; i++) {
                // Pick random user from fixed pool
                String userId = userPool.get(random.nextInt(userPool.size()));
                String randomMessage = messagePool[random.nextInt(messagePool.length)];

                ChatMessage message = createMessage(userId, Constants.MESSAGE_TYPE_TEXT, randomMessage);

                String randomRoomId = "room" + (random.nextInt(Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID);

                try {
                    messageQueue.put(new MessageQueueEntry(message, randomRoomId));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // Progress update
                if ((i + 1) % 100000 == 0) {
                    System.out.println("  Generated " + (i + 1) + " / " + textCount + " TEXT messages");
                }
            }

            System.out.println("✅ Generated " + textCount + " TEXT messages");

            // ========== PHASE 3: LEAVE messages (optional) ==========
            if (SEND_LEAVE_MESSAGES) {
                int leaveCount = TOTAL_MESSAGES / 20; // 5%
                System.out.println("Phase 3: Generating " + leaveCount + " LEAVE messages...");

                List<String> usersToLeave = new ArrayList<>(userPool);
                Collections.shuffle(usersToLeave);

                for (int i = 0; i < Math.min(leaveCount, usersToLeave.size()); i++) {
                    String userId = usersToLeave.get(i);
                    ChatMessage message = createMessage(userId, Constants.MESSAGE_TYPE_LEAVE, "left");

                    String randomRoomId = "room" + (random.nextInt(Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID);

                    try {
                        messageQueue.put(new MessageQueueEntry(message, randomRoomId));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                System.out.println("✅ Generated LEAVE messages");
            }

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("Message generation completed!");
            System.out.println("  Total queued: " + messageQueue.size() + " messages");
            System.out.println("  User pool: " + MAX_USERS + " users (reused across messages)");
            System.out.println("═══════════════════════════════════════════════════════");

        } catch (Exception e) {
            System.err.println("Error generating messages: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Create a chat message with the given parameters.
     */
    private ChatMessage createMessage(String userId, String messageType, String messageText) {
        String messageId = java.util.UUID.randomUUID().toString();
        String timestamp = java.time.Instant.now().toString();

        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setUserId(userId);
        message.setUsername("User" + userId);
        message.setMessage(messageText);
        message.setTimestamp(timestamp);
        message.setMessageType(messageType);

        return message;
    }

    /**
     * Print progress every 5 seconds.
     */
    private void printProgress() {
        long sent = messagesSent.get();
        long elapsed = System.currentTimeMillis() - startTime;
        double seconds = elapsed / 1000.0;

        if (seconds > 0) {
            double rate = sent / seconds;
            double percentage = (sent * 100.0) / TOTAL_MESSAGES;

            System.out.printf("[%02d:%02d] Sent: %,d/%,d (%.1f%%) | Rate: %.1f msg/sec | Failed: %d%n",
                    (int)(seconds / 60), (int)(seconds % 60),
                    sent, TOTAL_MESSAGES, percentage, rate, metrics.getConnectionFailures());
        }
    }

    /**
     * Wait for all messages to be sent.
     */
    private void waitForCompletion() throws InterruptedException {
        System.out.println();
        System.out.println("Waiting for message processing to complete...");

        // Wait until all expected messages are sent
        while (messagesSent.get() < TOTAL_MESSAGES) {
            Thread.sleep(1000);

            // Safety check
            if ((System.currentTimeMillis() - startTime) > 3600000) { // 1 hour timeout
                System.out.println("Timeout reached. Proceeding with partial results.");
                break;
            }
        }

        // Shut down sender executor
        System.out.println("Shutting down sender executor...");
        senderExecutor.shutdown();
        senderExecutor.awaitTermination(30, TimeUnit.SECONDS);

        System.out.println("All messages sent!");
    }

    /**
     * Shutdown all executors gracefully.
     */
    private void shutdown() {
        System.out.println("Shutting down client...");

        if (consumerConnectionPool != null) {
            consumerConnectionPool.closeAll();
        }

        shutdownExecutor(messageGeneratorExecutor, "MessageGenerator");
        shutdownExecutor(senderExecutor, "Sender");
        shutdownExecutor(receiverExecutor, "Receiver");
        shutdownExecutor(monitorExecutor, "Monitor");

        System.out.println("Client shutdown complete");
    }

    /**
     * Initialize consumer connection pool.
     */
    private void initializeConsumerConnectionPool() {
        consumerConnectionPool = new ReceiverConnectionPool(consumerHost, consumerPort, Constants.CONSUMER_SERVER_PATH);
        metrics.markStartTime();
        System.out.println("Consumer connection pool initialized at " + consumerHost + ":" + consumerPort);
        startReceivers();
    }

    /**
     * Initialize producer connection pool.
     */
    private void initializeProducerConnectionPool() {
        producerConnectionPool = new ProducerConnectionPool(producerHost, producerPort, Constants.PRODUCER_SERVER_PATH);
        System.out.println("Producer connection pool initialized at " + producerHost + ":" + producerPort);

        ackConnectionPool = new SenderConnectionPool(producerHost, producerPort, Constants.PRODUCER_SERVER_PATH);
        System.out.println("ACK connection pool initialized");
    }

    /**
     * Start receiver threads.
     */
    private void startReceivers() {
        for (int i = 0; i < THREAD_POOL_SIZE; i++) {
            int roomNumber = (roomIdCounter.incrementAndGet() - 1) % Constants.TOTAL_ROOMS + Constants.MIN_ROOM_ID;
            String roomId = "room" + roomNumber;
            receiverExecutor.submit(new SimpleReceiverWorker(roomId));
        }
        System.out.println("Started " + THREAD_POOL_SIZE + " receiver threads");
    }

    /**
     * Shutdown executor.
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Print final report.
     */
    private void printFinalReport() {
        long totalTime = System.currentTimeMillis() - startTime;
        long sent = messagesSent.get();
        double throughput = (totalTime > 0) ? (sent * 1000.0) / totalTime : 0;

        System.out.println();
        System.out.println("╔════════════════════════════════════════════════════════╗");
        System.out.println("║                  TEST RESULTS                          ║");
        System.out.println("╚════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Total Messages: " + String.format("%,d", TOTAL_MESSAGES));
        System.out.println("  User Pool: " + MAX_USERS + " users (fixed)");
        System.out.println("  Sender Threads: " + THREAD_POOL_SIZE);
        System.out.println();
        System.out.println("Results:");
        System.out.println("  Duration: " + (totalTime/1000) + " seconds");
        System.out.println("  Messages Sent: " + String.format("%,d", sent));
        System.out.println("  Throughput: " + String.format("%.2f", throughput) + " msg/sec");
        System.out.println("  Failed: " + metrics.getConnectionFailures());
        System.out.println();
        System.out.println("Message Distribution:");
        System.out.println("  JOIN: " + String.format("%,d", MAX_USERS) + " (" + String.format("%.1f%%", MAX_USERS * 100.0 / TOTAL_MESSAGES) + ")");

        if (SEND_LEAVE_MESSAGES) {
            int textCount = (TOTAL_MESSAGES * 9) / 10;
            int leaveCount = TOTAL_MESSAGES / 20;
            System.out.println("  TEXT: " + String.format("%,d", textCount) + " (" + String.format("%.1f%%", textCount * 100.0 / TOTAL_MESSAGES) + ")");
            System.out.println("  LEAVE: " + String.format("%,d", leaveCount) + " (" + String.format("%.1f%%", leaveCount * 100.0 / TOTAL_MESSAGES) + ")");
        } else {
            int textCount = TOTAL_MESSAGES - MAX_USERS;
            System.out.println("  TEXT: " + String.format("%,d", textCount) + " (" + String.format("%.1f%%", textCount * 100.0 / TOTAL_MESSAGES) + ")");
            System.out.println("  LEAVE: 0 (disabled)");
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("Next Steps:");
        System.out.println("  1. Wait 2-3 minutes for consumer to flush all batches");
        System.out.println("  2. Call metrics API:");
        System.out.println("     curl http://" + consumerHost + ":" + consumerPort + "/consumer-server/api/metrics");
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("╚════════════════════════════════════════════════════════╝");
    }

    /**
     * Worker class for sending messages.
     */
    private class SenderWorker implements Runnable {
        private final MessageQueue messageQueue;

        public SenderWorker(MessageQueue messageQueue) {
            this.messageQueue = messageQueue;
        }

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    MessageQueueEntry entry = messageQueue.take();
                    ChatMessage message = entry.getMessage();
                    String roomId = entry.getRoomId();

                    sentMessageIds.add(message.getMessageId());
                    sendMessage(message, roomId);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void sendMessage(ChatMessage message, String roomId) {
            SimpleWebSocketClient connection = producerConnectionPool.getConnection(roomId);

            if (connection != null && connection.isOpen()) {
                try {
                    String jsonMessage = gson.toJson(message);
                    connection.send(jsonMessage);
                    messagesSent.incrementAndGet();
                    metrics.recordMessageSent();

                } catch (Exception e) {
                    metrics.recordConnectionFailure();
                    logger.error("Failed to send message to room {}", roomId, e);
                }
            } else {
                metrics.recordConnectionFailure();
            }
        }
    }

    private class SimpleReceiverWorker implements Runnable {
        private final String roomId;

        public SimpleReceiverWorker(String roomId) {
            this.roomId = roomId;
        }

        @Override
        public void run() {
            try {
                consumerConnectionPool.addMessageListener(roomId, this::handleMessage);

                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                }

            } catch (Exception e) {
                logger.error("Error in receiver for room {}", roomId, e);
            } finally {
                consumerConnectionPool.removeMessageListener(roomId, this::handleMessage);
            }
        }

        private void handleMessage(ChatMessage message) {
            try {
                metrics.recordMessageReceived();

                if (sentMessageIds.contains(message.getMessageId())) {
                    sendDeliveryAcknowledgment(message);
                    sentMessageIds.remove(message.getMessageId());
                    metrics.recordMessageAcked();
                }

            } catch (Exception e) {
                logger.error("Error handling message in room {}", roomId, e);
            }
        }

        private void sendDeliveryAcknowledgment(ChatMessage receivedMessage) {
            try {
                SenderConnectionPool.SimpleChatMessage ackMessage = new SenderConnectionPool.SimpleChatMessage();
                ackMessage.setMessageId(receivedMessage.getMessageId() + "-DELIVERY_ACK");
                ackMessage.setUserId(receivedMessage.getUserId());
                ackMessage.setUsername(receivedMessage.getUsername());
                ackMessage.setMessage("DELIVERY_ACK:" + receivedMessage.getMessageId());
                ackMessage.setRoomId(receivedMessage.getRoomId());
                ackMessage.setMessageType(Constants.MESSAGE_TYPE_ACK);
                ackMessage.setTimestamp(java.time.Instant.now().toString());

                if (ackConnectionPool != null) {
                    ackConnectionPool.sendAckMessage(ackMessage);
                }

            } catch (Exception e) {
                logger.error("Error sending ACK for message {}", receivedMessage.getMessageId(), e);
            }
        }
    }
}

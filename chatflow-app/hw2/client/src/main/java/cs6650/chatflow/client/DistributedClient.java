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
import java.net.URI;

import org.java_websocket.client.WebSocketClient;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DistributedClient {

    private static final Logger logger = LoggerFactory.getLogger(DistributedClient.class);

    private static final int TOTAL_MESSAGES = 500_000; // Exactly 500K messages
    private static final int THREAD_POOL_SIZE = 100; // Number of sender threads - increased for concurrency

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
    private final AtomicInteger roomIdCounter = new AtomicInteger(0); // For round-robin room assignment

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

    // User management - now that userIds are unique, set ensures no duplicates
    private final Set<String> activeUserIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger userIdCounter = new AtomicInteger(0);

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
            logger.info("Starting Simple Distributed Client...");
            logger.info("Target: Send exactly {} messages using {} threads", TOTAL_MESSAGES, THREAD_POOL_SIZE);

            initializeExecutors();
            startMessageGeneration();
            startSenders();
            startMonitoring();

            // Wait for completion
            waitForCompletion();
            shutdown();
            printFinalReport();

        } catch (Exception e) {
            logger.error("Error running simple distributed client: {}", e.getMessage(), e);
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
        System.out.println("Started " + THREAD_POOL_SIZE + " sender threads (competing for messages from queue)");
    }

    /**
     * Start monitoring thread.
     */
    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::printProgress, 5, 5, TimeUnit.SECONDS);
    }

    /**
     * Generate all messages according to the specified pattern.
     */
    private void generateMessages() {
        try {
            System.out.println("Starting message generation...");

            // Phase 1: Generate JOIN messages (5% of total)
            int joinMessageCount = TOTAL_MESSAGES / 20; // 5%
            generateJoinMessages(joinMessageCount);

            // Phase 2: Generate TEXT messages (90% of total)
            int textMessageCount = (TOTAL_MESSAGES * 9) / 10; // 90%
            generateTextMessages(textMessageCount);

            // Phase 3: Generate LEAVE messages (5% of total)
            int leaveMessageCount = TOTAL_MESSAGES / 20; // 5%
            generateLeaveMessages(leaveMessageCount);

            System.out.println("Message generation completed. Generated " + messageQueue.size() + " messages");

        } catch (Exception e) {
            System.err.println("Error generating messages: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Generate JOIN messages and track user IDs.
     */
    private void generateJoinMessages(int count) {
        System.out.println("Generating " + count + " JOIN messages...");

        for (int i = 0; i < count; i++) {
            String userId = generateUniqueUserId();
            synchronized (activeUserIds) {
                activeUserIds.add(userId);
            }

            ChatMessage message = createMessage(userId, Constants.MESSAGE_TYPE_JOIN, "User " + userId + " joined");
            message.setUsername(userId); // User ID and username are the same

            // Assign random room ID and put entry in queue (blocking if queue is full)
            try {
                String randomRoomId = "room" + (new Random().nextInt(Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID);
                messageQueue.put(new MessageQueueEntry(message, randomRoomId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Generated " + count + " JOIN messages for " + activeUserIds.size() + " users");
    }

    /**
     * Generate TEXT messages using existing user IDs.
     */
    private void generateTextMessages(int count) {
        System.out.println("Generating " + count + " TEXT messages...");

        Random random = new Random();
        String[] messagePool = Arrays.copyOfRange(Constants.MESSAGE_POOL,
            0, Math.min(10, Constants.MESSAGE_POOL.length)); // Use first 10 messages for variety

        for (int i = 0; i < count; i++) {
            List<String> userList = new ArrayList<>(activeUserIds);
            if (userList.isEmpty()) {
                System.out.println("No active users for TEXT message generation, skipping...");
                continue;
            }

            String userId = userList.get(random.nextInt(userList.size()));
            String randomMessage = messagePool[random.nextInt(messagePool.length)];

            ChatMessage message = createMessage(userId, Constants.MESSAGE_TYPE_TEXT, randomMessage);

            // Assign random room ID and put entry in queue (blocking if queue is full)
            try {
                String randomRoomId = "room" + (new Random().nextInt(Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID);
                messageQueue.put(new MessageQueueEntry(message, randomRoomId));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Generated " + count + " TEXT messages");
    }

    /**
     * Generate LEAVE messages for all active users.
     */
    private void generateLeaveMessages(int count) {
        System.out.println("Generating " + count + " LEAVE messages...");

        List<String> usersToLeave = new ArrayList<>(activeUserIds);
        Collections.shuffle(usersToLeave);

        int messagesGenerated = 0;
        for (String userId : usersToLeave) {
            if (messagesGenerated >= count) break;

            ChatMessage message = createMessage(userId, Constants.MESSAGE_TYPE_LEAVE, "User " + userId + " left");

            synchronized (activeUserIds) {
                activeUserIds.remove(userId); // Remove user when creating LEAVE message
            }

            // Assign random room ID and put entry in queue (blocking if queue is full)
            try {
                String randomRoomId = "room" + (new Random().nextInt(Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID);
                messageQueue.put(new MessageQueueEntry(message, randomRoomId));
                messagesGenerated++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        System.out.println("Generated " + messagesGenerated + " LEAVE messages");
    }

    /**
     * Create a chat message with the given parameters.
     * Uses the distributed client's ChatMessage constructor rather than client-part1's.
     */
    private ChatMessage createMessage(String userId, String messageType, String messageText) {
        // Generate unique message ID following client-part1 pattern
        String messageId = java.util.UUID.randomUUID().toString();

        // Extract numeric user ID from userId string (e.g., "distributed-user-1" -> 1)
        int numericUserId = extractNumericUserId(userId);

        // Set username same as userId for consistency
        String username = userId;

        // Set current timestamp
        String timestamp = java.time.Instant.now().toString();

        // Create message using distributed client ChatMessage constructor
        ChatMessage message = new ChatMessage();
        message.setMessageId(messageId);
        message.setUserId(String.valueOf(numericUserId)); // Convert to string as expected by server
        message.setUsername(username);
        message.setMessage(messageText);
        message.setTimestamp(timestamp);
        message.setMessageType(messageType);

        return message;
    }

    /**
     * Generate a unique user ID following client-part1 pattern.
     */
    private String generateUniqueUserId() {
        // Use counter to ensure unique IDs
        int numericUserId = userIdCounter.incrementAndGet();
        return "user" + numericUserId;
    }

    /**
     * Extract numeric user ID from user ID string.
     */
    private int extractNumericUserId(String userId) {
        try {
            // If it's "distributed-user-N", extract N
            if (userId.startsWith("distributed-user-")) {
                return Integer.parseInt(userId.substring("distributed-user-".length()));
            }
            // If it's "userN", extract N
            else if (userId.startsWith("user")) {
                return Integer.parseInt(userId.substring("user".length()));
            }
            // Otherwise, try to parse the whole string
            else {
                return Integer.parseInt(userId.replaceAll("[^0-9]", ""));
            }
        } catch (NumberFormatException e) {
            // If parsing fails, use a random number like client-part1
            return new java.util.Random().nextInt(100_000) + 1;
        }
    }

    /**
     * Extract original message ID from ACK message content.
     * @param ackMessageContent The message content (e.g., "DELIVERY_ACK:uuid-here")
     * @return The original message ID, or null if not found
     */
    private String extractOriginalMessageId(String ackMessageContent) {
        if (ackMessageContent != null && ackMessageContent.startsWith("DELIVERY_ACK:")) {
            return ackMessageContent.substring("DELIVERY_ACK:".length());
        }
        return null;
    }

    /**
     * Get a random room ID.
     */
    private String getRandomRoomId() {
        // Distribute messages across rooms, but keep it simple for now
        int roomId = (int)(Math.random() * Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID;
        return String.valueOf(roomId);
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
            long pendingAcks = metrics.getMessagesSent() - metrics.getMessagesAcked();
            double ackRate = (metrics.getMessagesSent() > 0) ?
                           (metrics.getMessagesAcked() * 1000.0) / metrics.getTotalRuntime() : 0;

            System.out.println("Progress: " + sent + "/" + TOTAL_MESSAGES + " sent (" +
                             String.format("%.1f", rate) + " msg/sec, " +
                             String.format("%.1f", percentage) + "% complete) | " +
                             "ACK'd: " + metrics.getMessagesAcked() + " | " +
                             "Pending ACKs: " + pendingAcks + " | " +
                             "ACK rate: " + String.format("%.1f", ackRate) + "/sec | " +
                             "Failed: " + metrics.getConnectionFailures());
        }
    }

    /**
     * Wait for all messages to be sent.
     */
    private void waitForCompletion() throws InterruptedException {
        System.out.println("Waiting for message processing to complete...");

        // Wait until all expected messages are sent
        while (messagesSent.get() < TOTAL_MESSAGES) {
            Thread.sleep(1000);

            // Safety check - don't wait forever
            if ((System.currentTimeMillis() - startTime) > 600000) { // 10 minutes timeout
                System.out.println("Timeout reached. Proceeding with partial results.");
                break;
            }
        }

        // Shut down sender executor to stop processing
        System.out.println("Shutting down sender executor...");
        senderExecutor.shutdown();
        try {
            if (!senderExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                senderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            senderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        System.out.println("All messages sent. Shutting down workers...");
    }

    /**
     * Shutdown all executors gracefully.
     */
    private void shutdown() {
        System.out.println("Shutting down Simple Distributed Client...");

        // Shutdown consumer connection pool first
        if (consumerConnectionPool != null) {
            consumerConnectionPool.closeAll();
            System.out.println("Consumer connection pool shutdown complete");
        }

        // Shutdown executors
        shutdownExecutor(messageGeneratorExecutor, "MessageGenerator");
        shutdownExecutor(senderExecutor, "Sender");
        shutdownExecutor(receiverExecutor, "Receiver");
        shutdownExecutor(monitorExecutor, "Monitor");

        System.out.println("Client shutdown complete");
    }

    /**
     * Initialize consumer connection pool for message reception.
     */
    private void initializeConsumerConnectionPool() {
        consumerConnectionPool = new ReceiverConnectionPool(consumerHost, consumerPort, Constants.CONSUMER_SERVER_PATH);

        try {
            // Mark start time when connections begin
            metrics.markStartTime();

            System.out.println("Consumer connection pool initialized for message reception at " + consumerHost + ":" + consumerPort);

            // Start receiver threads to listen for messages from each room
            startReceivers();

        } catch (Exception e) {
            metrics.recordConnectionFailure();
            System.err.println("Failed to initialize consumer connection pool: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Initialize producer connection pool for ChatMessages and ACK connection pool.
     */
    private void initializeProducerConnectionPool() {
        producerConnectionPool = new ProducerConnectionPool(producerHost, producerPort, Constants.PRODUCER_SERVER_PATH);
        System.out.println("Producer connection pool initialized with 100 connections (5 per room) for " + producerHost + ":" + producerPort);

        ackConnectionPool = new SenderConnectionPool(producerHost, producerPort, Constants.PRODUCER_SERVER_PATH);
        System.out.println("ACK connection pool initialized with 20 dedicated WebSocket connections for " + producerHost + ":" + producerPort);
    }

    /**
     * Start receiver threads to listen for messages from consumer server.
     */
    private void startReceivers() {
        // Start only one receiver thread per room
        for (int i = 0; i < THREAD_POOL_SIZE; i++) {
            // Assign room ID in round-robin fashion (room1-room20)
            int roomNumber = (roomIdCounter.incrementAndGet() - 1) % Constants.TOTAL_ROOMS + Constants.MIN_ROOM_ID;
            String roomId = "room" + roomNumber;

            // Create receiver worker with room ID
            SimpleReceiverWorker receiver = new SimpleReceiverWorker(roomId);
            receiverExecutor.submit(receiver);
        }
        System.out.println("Started " + THREAD_POOL_SIZE + " receiver threads (one per room)");
    }

    /**
     * Helper method to shutdown executor gracefully.
     */
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    System.out.println(name + " executor force shutdown after timeout");
                } else {
                    System.out.println(name + " executor shutdown gracefully");
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
        // Print the detailed metrics from ClientMetrics
        System.out.println(metrics.generateReport("SIMPLE DISTRIBUTED CLIENT"));

        // Additional information specific to this client
        long totalTime = System.currentTimeMillis() - startTime;
        long sent = messagesSent.get();
        double throughput = (totalTime > 0) ? (sent * 1000.0) / totalTime : 0;

        System.out.println("CONFIGURATION:");
        System.out.println("  Total Target Messages: " + TOTAL_MESSAGES);
        System.out.println("  Sender Threads: " + THREAD_POOL_SIZE);



        System.out.println("\nEXECUTOR SERVICE INFO:");
        System.out.println("  Message Generator: Single thread");
        System.out.println("  Sender Executors: Fixed thread pool (" + THREAD_POOL_SIZE + " threads)");
        System.out.println("  Receiver Executors: Fixed thread pool (" + THREAD_POOL_SIZE + " threads)");
        System.out.println("  Monitor: Scheduled executor");

        System.out.println("\nMESSAGE DISTRIBUTION:");
        System.out.println("  JOIN: 5% (" + (TOTAL_MESSAGES / 20) + " messages)");
        System.out.println("  TEXT: 90% (" + (TOTAL_MESSAGES * 9 / 10) + " messages)");
        System.out.println("  LEAVE: 5% (" + (TOTAL_MESSAGES / 20) + " messages)");

        System.out.println("\n" + "=".repeat(80));
    }

    /**
     * Worker class for sending messages.
     * Processes messages from the shared queue until generation completes.
     */
    private class SenderWorker implements Runnable {
        private final MessageQueue messageQueue;

        public SenderWorker(MessageQueue messageQueue) {
            this.messageQueue = messageQueue;
        }

        @Override
        public void run() {
            System.out.println("SenderWorker started for message processing from queue");

            try {
                while (!Thread.currentThread().isInterrupted()) {
                    // Get message entry from queue (blocking) - will throw when generation completes
                    MessageQueueEntry entry = messageQueue.take();
                    ChatMessage message = entry.getMessage();
                    String roomId = entry.getRoomId();

                    // Track sent message ID
                    sentMessageIds.add(message.getMessageId());

                    // Send message via connection pool
                    sendMessage(message, roomId);
                }
            } catch (InterruptedException e) {
                // Normal shutdown
                Thread.currentThread().interrupt();
            }

            System.out.println("SenderWorker completed processing messages");
        }

        /**
         * Send a message via the producer connection pool.
         */
        private void sendMessage(ChatMessage message, String roomId) {
            SimpleWebSocketClient connection = producerConnectionPool.getConnection(roomId);

            if (connection != null && connection.isOpen()) {
                try {
                    String jsonMessage = gson.toJson(message);
                    connection.send(jsonMessage);
                    messagesSent.incrementAndGet();

                    // Record message sent in metrics
                    metrics.recordMessageSent();

                    // Log progress periodically
                    long currentSent = messagesSent.get();
                    if (currentSent % 10000 == 0) {
                        System.out.println("Total messages sent: " + currentSent + "/" + TOTAL_MESSAGES);
                    }

                } catch (Exception e) {
                    metrics.recordConnectionFailure();
                    System.err.println("Failed to send message " + message.getMessageId() + " to room " + roomId + ": " + e.getMessage());
                }
            } else {
                metrics.recordConnectionFailure();
                System.err.println("No connection available for room " + roomId + " to send message " + message.getMessageId());
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
            System.out.println("SimpleReceiverWorker started for room: " + roomId);

            try {
                // Register this worker as a message listener for the specific room
                consumerConnectionPool.addMessageListener(roomId, this::handleMessage);

                // Keep the thread alive to listen for messages
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(100); // Prevent busy waiting
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

            } catch (Exception e) {
                System.err.println("Error in SimpleReceiverWorker for room " + roomId + ": " + e.getMessage());
                e.printStackTrace();
            } finally {
                // Remove message listener when done
                consumerConnectionPool.removeMessageListener(roomId, this::handleMessage);
                System.out.println("SimpleReceiverWorker stopped for room: " + roomId);
            }
        }

        /**
         * Handle incoming messages from consumer server.
         */
        private void handleMessage(ChatMessage message) {
            try {
                // Record message received in metrics
                metrics.recordMessageReceived();

                String messageId = message.getMessageId();

                // Check if this message ID was sent by us and we haven't removed it yet (not ACK'd)
                if (sentMessageIds.contains(messageId)) {
                    // This is a message we sent that came back to us
                // Send DELIVERY_ACK back to producer server and remove from tracking set

                // Get producer connection pool and send ACK
                sendDeliveryAcknowledgment(message);
                // Successfully sent ACK, remove from tracking set
                sentMessageIds.remove(messageId);
                metrics.recordMessageAcked();
                logger.debug("Removed message {} from tracking set after sending ACK", messageId);
                }

                // Log the received message (moved to debug level as requested)
                String messageType = message.getMessageType();

                // Log different message types appropriately at debug level
                if ("JOIN".equals(messageType) || "LEAVE".equals(messageType)) {
                    logger.debug("Room {} | {} {} ({})", roomId, message.getUsername(),
                                messageType.toLowerCase(), message.getMessageType());
                } else if ("TEXT".equals(messageType)) {
                    logger.debug("Received text in room {}: '{}' from {}", roomId,
                                message.getMessage(), message.getUsername());
                } else if ("ACK".equals(messageType)) {
                    logger.debug("Received ACK confirmation in room {}: '{}' from {} ({})",
                                roomId, message.getMessage(), message.getUsername(), messageType);

                    // Extract original message ID from ACK message content
                    String originalMessageId = extractOriginalMessageId(message.getMessage());
                    if (originalMessageId != null && sentMessageIds.contains(originalMessageId)) {
                        // This ACK confirms our DELIVERY_ACK was received - complete lifecycle
                        sentMessageIds.remove(originalMessageId);
                        metrics.recordMessageAcked();
                        logger.debug("Removed message {} from tracking after receiving ACK confirmation", originalMessageId);
                    }
                } else {
                    logger.warn("Received unknown message type in room {}: '{}' from {} ({})",
                               roomId, message.getMessage(), message.getUsername(), messageType);
                }



            } catch (Exception e) {
                logger.error("Error handling message in room {}: {}", roomId, e.getMessage(), e);
            }
        }

        /**
         * Send DELIVERY_ACK message back to producer server when we receive a message we sent.
         */
        private void sendDeliveryAcknowledgment(ChatMessage receivedMessage) {
            try {
                // Create simple DELIVERY_ACK message using our custom message class
                SenderConnectionPool.SimpleChatMessage ackMessage = new SenderConnectionPool.SimpleChatMessage();
                ackMessage.setMessageId(receivedMessage.getMessageId() + "-DELIVERY_ACK");
                ackMessage.setUserId(receivedMessage.getUserId());
                ackMessage.setUsername(receivedMessage.getUsername());
                ackMessage.setMessage("DELIVERY_ACK:" + receivedMessage.getMessageId());
                ackMessage.setRoomId(receivedMessage.getRoomId());
                ackMessage.setMessageType(Constants.MESSAGE_TYPE_ACK);
                // Use same timestamp format as original messages (ISO 8601)
                ackMessage.setTimestamp(java.time.Instant.now().toString());

                // Send via ACK connection pool - uses dedicated connection for the room
                if (ackConnectionPool != null) {
                    boolean sent = ackConnectionPool.sendAckMessage(ackMessage);
                    logger.debug("Sent DELIVERY_ACK for message: {}", receivedMessage.getMessageId());
                } else {
                    System.err.println("No ACK connection pool available for DELIVERY_ACK");
                }

            } catch (Exception e) {
                System.err.println("Error sending DELIVERY_ACK for message " + receivedMessage.getMessageId() + ": " + e.getMessage());
            }
        }
    }
}

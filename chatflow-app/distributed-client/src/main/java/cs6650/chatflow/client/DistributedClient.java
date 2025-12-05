package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.connection.ProducerConnectionPool;
import cs6650.chatflow.client.connection.ReceiverConnectionPool;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageQueueEntry;
import cs6650.chatflow.client.queues.MessageQueue;
import cs6650.chatflow.client.workers.AckSenderWorker;
import cs6650.chatflow.client.workers.MessageReceiverWorker;
import cs6650.chatflow.client.workers.MessageSenderWorker;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Distributed Client with Direct ACK Confirmation Handling
 *
 * Architecture:
 * 1. Message Generation → MessageQueue → 100 Sender Threads → Producer Server (40 connections)
 * 2. Consumer Server (40 connections) → ReceiverQueue → 100 Receiver Threads → Process & Queue ACK
 * 3. AckQueue → 20 ACK Sender Threads → Producer Server (reuses connection pool)
 * 4. Producer sends ACK_CONFIRMATION directly back → Message marked as COMPLETED (optimized!)
 *
 * Throughput Measurement:
 * - End-to-End Throughput: messagesCompleted / time (full lifecycle with ACK confirmation)
 */
public class DistributedClient {

    private static final Logger logger = LoggerFactory.getLogger(DistributedClient.class);

    // ========== Configuration ==========
    private static final int TOTAL_MESSAGES = 10000000;
    private static final int MAX_USERS = 25000;
    private static final boolean SEND_LEAVE_MESSAGES = false;

    // Thread pool sizes
    private static final int SENDER_THREAD_POOL_SIZE = 100;
    private static final int RECEIVER_THREAD_POOL_SIZE = 100;
    private static final int ACK_SENDER_THREAD_POOL_SIZE = 20;
    // ===================================

    // Server configuration
    private final String producerHost;
    private final int producerPort;
    private final String consumerHost;
    private final int consumerPort;

    // Message queue
    private final MessageQueue messageQueue = new MessageQueue();

    // ========== Queues ==========
    private final BlockingQueue<ReceivedMessageEntry> receiverQueue =
            new LinkedBlockingQueue<>(100_000);

    private final BlockingQueue<AckQueueEntry> ackQueue =
            new LinkedBlockingQueue<>(100_000);
    // ============================

    // Statistics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong acksQueued = new AtomicLong(0);
    private final AtomicLong acksSent = new AtomicLong(0);
    private final AtomicLong acksFailed = new AtomicLong(0);
    private final AtomicLong receiverQueueDropped = new AtomicLong(0);
    private final AtomicLong messagesCompleted = new AtomicLong(0);

    private final long startTime = System.currentTimeMillis();
    private final AtomicInteger roomIdCounter = new AtomicInteger(0);

    // Track sent message IDs (for original messages only, not ACKs)
    private final Set<String> sentMessageIds = ConcurrentHashMap.newKeySet();

    // ========== NEW: Direct ACK confirmation tracking ==========
    // Maps original message ID → timestamp when sent
    private final ConcurrentHashMap<String, Long> pendingMessages = new ConcurrentHashMap<>();
    // ===========================================================

    // Client metrics
    private final ClientMetrics metrics = new ClientMetrics();

    // Executor services
    private ExecutorService messageGeneratorExecutor;
    private ExecutorService senderExecutor;
    private ExecutorService receiverExecutor;
    private ExecutorService ackSenderExecutor;
    private ScheduledExecutorService monitorExecutor;

    // WebSocket connection pools
    private ReceiverConnectionPool consumerConnectionPool;
    private ProducerConnectionPool producerConnectionPool;

    // Fixed user pool
    private final List<String> userPool = new ArrayList<>();

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

        // Pre-create fixed user pool
        for (int i = 1; i <= MAX_USERS; i++) {
            userPool.add(String.valueOf(i));
        }
        System.out.println("Created fixed user pool: " + MAX_USERS + " users");
    }

    public static void main(String[] args) {
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
            logger.info("Starting Distributed Client with Direct ACK Confirmations...");
            logger.info("Target: {} messages | Users: {} (fixed pool) | LEAVE messages: {}",
                    TOTAL_MESSAGES, MAX_USERS, SEND_LEAVE_MESSAGES);
            logger.info("Thread Pools: {} senders, {} receivers, {} ACK senders",
                    SENDER_THREAD_POOL_SIZE, RECEIVER_THREAD_POOL_SIZE, ACK_SENDER_THREAD_POOL_SIZE);
            logger.info("Connections: 40 producer (2/room), 40 consumer (2/room)");
            logger.info("ACK Flow: Producer → Client (direct confirmation, optimized!)");

            initializeExecutors();
            startMessageGeneration();
            startSenders();
            startReceivers();
            startAckSenders();
            startMonitoring();

            waitForCompletion();
            shutdown();
            printFinalReport();

        } catch (Exception e) {
            logger.error("Error running distributed client: {}", e.getMessage(), e);
            shutdown();
        }
    }

    /**
     * Initialize executor services and connection pools.
     */
    private void initializeExecutors() {
        messageGeneratorExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "MessageGenerator"));

        senderExecutor = Executors.newFixedThreadPool(SENDER_THREAD_POOL_SIZE,
                r -> new Thread(r, "Sender-" + r.hashCode()));

        receiverExecutor = Executors.newFixedThreadPool(RECEIVER_THREAD_POOL_SIZE,
                r -> new Thread(r, "Receiver-" + r.hashCode()));

        ackSenderExecutor = Executors.newFixedThreadPool(ACK_SENDER_THREAD_POOL_SIZE,
                r -> new Thread(r, "AckSender-" + r.hashCode()));

        monitorExecutor = Executors.newScheduledThreadPool(1, r -> new Thread(r, "Monitor"));

        initializeConsumerConnectionPool();
        initializeProducerConnectionPool();
    }

    /**
     * Initialize consumer connection pool.
     */
    private void initializeConsumerConnectionPool() {
        consumerConnectionPool = new ReceiverConnectionPool(
                consumerHost,
                consumerPort,
                Constants.CONSUMER_SERVER_PATH,
                2
        );
        metrics.markStartTime();
        System.out.println("Consumer connection pool initialized: 40 connections (2 per room) at " +
                consumerHost + ":" + consumerPort);
    }

    /**
     * Initialize producer connection pool with ACK confirmation handler.
     */
    private void initializeProducerConnectionPool() {
        producerConnectionPool = new ProducerConnectionPool(
                producerHost,
                producerPort,
                Constants.PRODUCER_SERVER_PATH,
                this::handleAckConfirmation  // NEW: Pass ACK confirmation handler
        );
        System.out.println("Producer connection pool initialized: 40 connections (2 per room) at " +
                producerHost + ":" + producerPort);
        System.out.println("✓ Producer connections handle direct ACK confirmations");
    }

    /**
     * NEW: Handle ACK confirmation received from producer.
     * Called when producer sends ACK_CONFIRMATION message directly back.
     */
    private void handleAckConfirmation(ChatMessage confirmation) {
        try {
            // Extract original message ID from confirmation
            String originalMessageId = confirmation.getOriginalMessageId();

            if (originalMessageId != null && pendingMessages.remove(originalMessageId) != null) {
                // Message lifecycle complete!
                messagesCompleted.incrementAndGet();
                logger.debug("Message {} completed (ACK confirmation received)", originalMessageId);
            } else {
                logger.debug("Received ACK confirmation for unknown message: {}", originalMessageId);
            }

        } catch (Exception e) {
            logger.error("Error handling ACK confirmation: {}", e.getMessage(), e);
        }
    }

    private void startMessageGeneration() {
        messageGeneratorExecutor.submit(this::generateMessages);
        System.out.println("Started message generator thread");
    }

    private void startSenders() {
        for (int i = 0; i < SENDER_THREAD_POOL_SIZE; i++) {
            senderExecutor.submit(new MessageSenderWorker(
                    messageQueue,
                    producerConnectionPool,
                    messagesSent,
                    sentMessageIds,
                    pendingMessages,  // NEW: Pass pending messages tracker
                    metrics,
                    gson
            ));
        }
        System.out.println("Started " + SENDER_THREAD_POOL_SIZE + " sender threads");
    }

    private void startReceivers() {
        for (int i = 0; i < RECEIVER_THREAD_POOL_SIZE; i++) {
            int roomNumber = (i % Constants.TOTAL_ROOMS) + Constants.MIN_ROOM_ID;
            String roomId = "room" + roomNumber;

            receiverExecutor.submit(new MessageReceiverWorker(
                    roomId,
                    consumerConnectionPool,
                    receiverQueue,
                    ackQueue,
                    sentMessageIds,
                    messagesReceived,
                    acksQueued,
                    receiverQueueDropped,
                    metrics
            ));
        }
        System.out.println("Started " + RECEIVER_THREAD_POOL_SIZE + " receiver threads (5 per room)");
    }

    /**
     * Start ACK sender threads.
     */
    private void startAckSenders() {
        for (int i = 0; i < ACK_SENDER_THREAD_POOL_SIZE; i++) {
            ackSenderExecutor.submit(new AckSenderWorker(
                    ackQueue,
                    producerConnectionPool,
                    acksSent,
                    acksFailed,
                    // REMOVED: sentMessageIds parameter (no longer needed)
                    metrics,
                    gson
            ));
        }
        System.out.println("Started " + ACK_SENDER_THREAD_POOL_SIZE + " ACK sender threads");
    }

    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::printProgress, 5, 5, TimeUnit.SECONDS);
    }

    private void generateMessages() {
        try {
            System.out.println("Starting message generation with fixed user pool...");
            Random random = new Random();

            // JOIN messages
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

            // TEXT messages
            int textCount = SEND_LEAVE_MESSAGES ? (TOTAL_MESSAGES * 9) / 10 : TOTAL_MESSAGES - joinCount;
            System.out.println("Phase 2: Generating " + textCount + " TEXT messages...");

            String[] messagePool = Arrays.copyOfRange(Constants.MESSAGE_POOL,
                    0, Math.min(10, Constants.MESSAGE_POOL.length));

            for (int i = 0; i < textCount; i++) {
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

                if ((i + 1) % 100000 == 0) {
                    System.out.println("  Generated " + (i + 1) + " / " + textCount + " TEXT messages");
                }
            }

            System.out.println("✅ Generated " + textCount + " TEXT messages");

            // LEAVE messages (optional)
            if (SEND_LEAVE_MESSAGES) {
                int leaveCount = TOTAL_MESSAGES / 20;
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
            System.out.println("═══════════════════════════════════════════════════════");

        } catch (Exception e) {
            System.err.println("Error generating messages: " + e.getMessage());
            e.printStackTrace();
        }
    }

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

    private void printProgress() {
        long sent = messagesSent.get();
        long received = messagesReceived.get();
        long acked = acksSent.get();
        long completed = messagesCompleted.get();
        long dropped = receiverQueueDropped.get();
        long elapsed = System.currentTimeMillis() - startTime;
        double seconds = elapsed / 1000.0;

        if (seconds > 0) {
            double throughput = completed / seconds;
            double completionPercentage = (completed * 100.0) / TOTAL_MESSAGES;

            int msgQueueDepth = messageQueue.size();
            int receiverQueueDepth = receiverQueue.size();
            int ackQueueDepth = ackQueue.size();

            System.out.printf(
                    "[%02d:%02d] Sent: %,d | Rcvd: %,d | Acked: %,d | Completed: %,d/%,d (%.1f%%) | " +
                            "Throughput: %.0f msg/s | MsgQ: %,d | RcvQ: %,d | AckQ: %,d | Dropped: %,d%n",
                    (int)(seconds / 60), (int)(seconds % 60),
                    sent, received, acked, completed, TOTAL_MESSAGES, completionPercentage,
                    throughput, msgQueueDepth, receiverQueueDepth, ackQueueDepth, dropped
            );
        }
    }

    private void waitForCompletion() throws InterruptedException {
        System.out.println();
        System.out.println("Waiting for message processing to complete...");

        // Wait for all messages to be sent
        while (messagesSent.get() < TOTAL_MESSAGES) {
            Thread.sleep(1000);

            if ((System.currentTimeMillis() - startTime) > 3600000) {
                System.out.println("Timeout reached. Proceeding with partial results.");
                break;
            }
        }

        System.out.println("All messages sent! Waiting for ACK confirmations...");

        // Wait for all messages to complete
        long lastCompletedCount = messagesCompleted.get();
        int idleCount = 0;

        while (messagesCompleted.get() < TOTAL_MESSAGES) {
            Thread.sleep(1000);

            long currentCompleted = messagesCompleted.get();
            long currentSent = messagesSent.get();
            long currentAcked = acksSent.get();

            if ((idleCount % 10) == 0) {
                System.out.printf("  Progress: Sent=%,d | AcksSent=%,d | Completed=%,d/%,d (%.1f%%) | " +
                                "Pending=%,d | RcvQ=%,d | AckQ=%,d%n",
                        currentSent, currentAcked, currentCompleted, TOTAL_MESSAGES,
                        (currentCompleted * 100.0) / TOTAL_MESSAGES,
                        pendingMessages.size(), receiverQueue.size(), ackQueue.size());
            }

            if (currentCompleted == lastCompletedCount) {
                idleCount++;
                if (idleCount > 60) {
                    System.out.println("Message completion stalled. Checking state...");
                    System.out.println("  Messages sent: " + currentSent);
                    System.out.println("  ACKs sent: " + currentAcked);
                    System.out.println("  Messages completed: " + currentCompleted);
                    System.out.println("  Pending messages: " + pendingMessages.size());

                    if (receiverQueue.size() == 0 && ackQueue.size() == 0) {
                        System.out.println("Queues empty. Proceeding with shutdown.");
                        break;
                    }
                }
            } else {
                lastCompletedCount = currentCompleted;
                idleCount = 0;
            }
        }

        System.out.println("Message lifecycle complete!");
        System.out.println("  Final: Sent=" + messagesSent.get() +
                " | AcksSent=" + acksSent.get() +
                " | Completed=" + messagesCompleted.get());
    }

    private void shutdown() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("Shutting down client...");
        System.out.println("═══════════════════════════════════════════════════════");

        shutdownExecutor(messageGeneratorExecutor, "MessageGenerator");
        shutdownExecutor(senderExecutor, "Sender");

        System.out.println("Draining receiver queue (" + receiverQueue.size() + " remaining)...");
        shutdownExecutor(receiverExecutor, "Receiver");

        System.out.println("Draining ACK queue (" + ackQueue.size() + " remaining)...");
        shutdownExecutor(ackSenderExecutor, "AckSender");

        shutdownExecutor(monitorExecutor, "Monitor");

        if (consumerConnectionPool != null) {
            consumerConnectionPool.closeAll();
        }
        if (producerConnectionPool != null) {
            producerConnectionPool.closeAll();
        }

        System.out.println("✓ Client shutdown complete");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    System.out.println("⚠️  " + name + " executor did not terminate in time, forcing shutdown...");
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void printFinalReport() {
        long totalTime = System.currentTimeMillis() - startTime;
        long sent = messagesSent.get();
        long received = messagesReceived.get();
        long acked = acksSent.get();
        long completed = messagesCompleted.get();
        long ackFailed = acksFailed.get();
        long dropped = receiverQueueDropped.get();

        double throughput = (totalTime > 0) ? (completed * 1000.0) / totalTime : 0;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║            HIGH-THROUGHPUT TEST RESULTS              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Total Messages: " + String.format("%,d", TOTAL_MESSAGES));
        System.out.println("  User Pool: " + MAX_USERS + " users (fixed)");
        System.out.println("  Sender Threads: " + SENDER_THREAD_POOL_SIZE);
        System.out.println("  Receiver Threads: " + RECEIVER_THREAD_POOL_SIZE);
        System.out.println("  ACK Sender Threads: " + ACK_SENDER_THREAD_POOL_SIZE);
        System.out.println("  Producer Connections: 40 (2 per room)");
        System.out.println("  Consumer Connections: 40 (2 per room)");
        System.out.println();
        System.out.println("Performance Results:");
        System.out.println("  Duration: " + (totalTime/1000) + " seconds (" +
                String.format("%.1f", totalTime/60000.0) + " minutes)");
        System.out.println();

        System.out.println("Message Counts:");
        System.out.println("  Messages Sent: " + String.format("%,d", sent));
        System.out.println("  Messages Received: " + String.format("%,d", received));
        System.out.println("  ACKs Sent: " + String.format("%,d", acked));
        System.out.println("  Messages COMPLETED: " + String.format("%,d", completed) +
                " (" + String.format("%.1f%%", (completed * 100.0) / sent) + " of sent)");
        System.out.println();

        System.out.println("⭐ End-to-End Throughput: " + String.format("%.2f", throughput) + " messages/sec");
        System.out.println("   (Complete lifecycle: send → receive → ACK → ACK confirmation from producer)");
        System.out.println();

        System.out.println("Error Statistics:");
        System.out.println("  ACKs Failed: " + String.format("%,d", ackFailed));
        System.out.println("  Messages Dropped (Queue Full): " + String.format("%,d", dropped));
        System.out.println("  Connection Failures: " + metrics.getConnectionFailures());

        if (acked > 0) {
            double ackSuccessRate = ((acked - ackFailed) * 100.0) / acked;
            System.out.println("  ACK Success Rate: " + String.format("%.2f%%", ackSuccessRate));
        }

        System.out.println();
        System.out.println("Queue Statistics:");
        System.out.println("  Final Message Queue Depth: " + messageQueue.size());
        System.out.println("  Final Receiver Queue Depth: " + receiverQueue.size());
        System.out.println("  Final ACK Queue Depth: " + ackQueue.size());
        System.out.println("  Remaining Pending Messages: " + pendingMessages.size());
        System.out.println();

        System.out.println("Message Distribution:");
        System.out.println("  JOIN: " + String.format("%,d", MAX_USERS) + " (" +
                String.format("%.1f%%", MAX_USERS * 100.0 / TOTAL_MESSAGES) + ")");

        if (SEND_LEAVE_MESSAGES) {
            int textCount = (TOTAL_MESSAGES * 9) / 10;
            int leaveCount = TOTAL_MESSAGES / 20;
            System.out.println("  TEXT: " + String.format("%,d", textCount) + " (" +
                    String.format("%.1f%%", textCount * 100.0 / TOTAL_MESSAGES) + ")");
            System.out.println("  LEAVE: " + String.format("%,d", leaveCount) + " (" +
                    String.format("%.1f%%", leaveCount * 100.0 / TOTAL_MESSAGES) + ")");
        } else {
            int textCount = TOTAL_MESSAGES - MAX_USERS;
            System.out.println("  TEXT: " + String.format("%,d", textCount) + " (" +
                    String.format("%.1f%%", textCount * 100.0 / TOTAL_MESSAGES) + ")");
            System.out.println("  LEAVE: 0 (disabled)");
        }

        System.out.println();
        System.out.println("═════════════════════════════════════════════════════");
        System.out.println("Next Steps:");
        System.out.println("  1. Wait 2-3 minutes for consumer to flush all batches");
        System.out.println("  2. Call metrics API:");
        System.out.println("     curl http://" + consumerHost + ":" + consumerPort + "/consumer-server/api/metrics");
        System.out.println("═════════════════════════════════════════════════════");
        System.out.println();
    }

    // Queue Entry Classes
    public static class ReceivedMessageEntry {
        private final ChatMessage message;
        private final long receivedTimestamp;

        public ReceivedMessageEntry(ChatMessage message) {
            this.message = message;
            this.receivedTimestamp = System.currentTimeMillis();
        }

        public ChatMessage getMessage() {
            return message;
        }

        public long getReceivedTimestamp() {
            return receivedTimestamp;
        }
    }

    public static class AckQueueEntry {
        private final ChatMessage originalMessage;
        private final long queuedTimestamp;
        private int retryCount;

        public AckQueueEntry(ChatMessage originalMessage) {
            this.originalMessage = originalMessage;
            this.queuedTimestamp = System.currentTimeMillis();
            this.retryCount = 0;
        }

        public ChatMessage getOriginalMessage() {
            return originalMessage;
        }

        public long getQueuedTimestamp() {
            return queuedTimestamp;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void incrementRetryCount() {
            this.retryCount++;
        }
    }
}

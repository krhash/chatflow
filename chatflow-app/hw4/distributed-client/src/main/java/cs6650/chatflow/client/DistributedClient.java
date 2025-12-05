package cs6650.chatflow.client;

import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.connection.ProducerConnectionPool;
import cs6650.chatflow.client.connection.ReceiverConnectionPool;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageQueueEntry;
import cs6650.chatflow.client.queues.MessageQueue;
import cs6650.chatflow.client.workers.AckSenderWorker;
import cs6650.chatflow.client.workers.MessageListenerWorker;
import cs6650.chatflow.client.workers.MessageProcessorWorker;
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
 * Distributed Client with Separated Listener and Processor Architecture
 *
 * Architecture:
 * 1. Message Generation → MessageQueue → 100 Sender Threads → Producer (40 connections)
 * 2. Consumer (40 connections) → 20 Listeners (1 per room) → ReceiverQueue
 * 3. ReceiverQueue → 100 Processor Threads → Check & Queue ACKs
 * 4. AckQueue → 20 ACK Sender Threads → Producer (reuses connections)
 * 5. ACK echoes back through consumer → Detected by processors → messagesCompleted++
 */
public class DistributedClient {

    private static final Logger logger = LoggerFactory.getLogger(DistributedClient.class);

    // ========== Configuration ==========
    private static final int TOTAL_MESSAGES = 500000;
    private static final int JOIN_MESSAGE_PERCENTAGE = 5;  // 20% JOIN messages

    private static final int SENDER_THREAD_POOL_SIZE = 100;
    private static final int LISTENER_THREAD_POOL_SIZE = 20;   // 1 per room
    private static final int PROCESSOR_THREAD_POOL_SIZE = 100; // Process from queue
    private static final int ACK_SENDER_THREAD_POOL_SIZE = 20;
    // ===================================

    private final String producerHost;
    private final int producerPort;
    private final String consumerHost;
    private final int consumerPort;

    // Queues
    private final MessageQueue messageQueue = new MessageQueue();
    private final BlockingQueue<ReceivedMessageEntry> receiverQueue =
            new LinkedBlockingQueue<>(100_000);
    private final BlockingQueue<AckQueueEntry> ackQueue =
            new LinkedBlockingQueue<>(100_000);

    // Statistics
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0);
    private final AtomicLong acksQueued = new AtomicLong(0);
    private final AtomicLong acksSent = new AtomicLong(0);
    private final AtomicLong acksFailed = new AtomicLong(0);
    private final AtomicLong receiverQueueDropped = new AtomicLong(0);
    private final AtomicLong messagesCompleted = new AtomicLong(0);

    private final long startTime = System.currentTimeMillis();

    // Track sent message IDs
    private final Set<String> sentMessageIds = ConcurrentHashMap.newKeySet();
    private final ConcurrentHashMap<String, String> ackToOriginalMessageId = new ConcurrentHashMap<>();

    private final ClientMetrics metrics = new ClientMetrics();

    // Executors
    private ExecutorService messageGeneratorExecutor;
    private ExecutorService senderExecutor;
    private ExecutorService listenerExecutor;     // NEW: For listeners
    private ExecutorService processorExecutor;    // NEW: For processors
    private ExecutorService ackSenderExecutor;
    private ScheduledExecutorService monitorExecutor;

    // Connection pools
    private ReceiverConnectionPool consumerConnectionPool;
    private ProducerConnectionPool producerConnectionPool;

    private final List<String> userPool = new ArrayList<>();
    private final Gson gson = new GsonBuilder().create();

    public DistributedClient(String producerHost, int producerPort, String consumerHost, int consumerPort) {
        this.producerHost = producerHost;
        this.producerPort = producerPort;
        this.consumerHost = consumerHost;
        this.consumerPort = consumerPort;

        // Create user pool (will use subset for JOIN messages)
        for (int i = 1; i <= 25000; i++) {
            userPool.add(String.valueOf(i));
        }
        System.out.println("Created user pool: " + userPool.size() + " users");
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
            System.err.println("Using defaults: localhost 8080 localhost 8081");
        }

        DistributedClient client = new DistributedClient(producerHost, producerPort, consumerHost, consumerPort);
        client.start();
    }

    public void start() {
        try {
            logger.info("Starting Distributed Client with Separated Listener/Processor Architecture...");
            logger.info("Target: {} messages ({}% JOIN, {}% TEXT)",
                    TOTAL_MESSAGES, JOIN_MESSAGE_PERCENTAGE, 100 - JOIN_MESSAGE_PERCENTAGE);
            logger.info("Threads: {} senders, {} listeners, {} processors, {} ACK senders",
                    SENDER_THREAD_POOL_SIZE, LISTENER_THREAD_POOL_SIZE,
                    PROCESSOR_THREAD_POOL_SIZE, ACK_SENDER_THREAD_POOL_SIZE);
            logger.info("Connections: 40 producer (2/room), 40 consumer (2/room)");

            initializeExecutors();
            startMessageGeneration();
            startSenders();
            startListeners();       // NEW
            startProcessors();      // NEW
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

    private void initializeExecutors() {
        messageGeneratorExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "MessageGenerator"));
        senderExecutor = Executors.newFixedThreadPool(SENDER_THREAD_POOL_SIZE,
                r -> new Thread(r, "Sender-" + r.hashCode()));
        listenerExecutor = Executors.newFixedThreadPool(LISTENER_THREAD_POOL_SIZE,
                r -> new Thread(r, "Listener-" + r.hashCode()));
        processorExecutor = Executors.newFixedThreadPool(PROCESSOR_THREAD_POOL_SIZE,
                r -> new Thread(r, "Processor-" + r.hashCode()));
        ackSenderExecutor = Executors.newFixedThreadPool(ACK_SENDER_THREAD_POOL_SIZE,
                r -> new Thread(r, "AckSender-" + r.hashCode()));
        monitorExecutor = Executors.newScheduledThreadPool(1, r -> new Thread(r, "Monitor"));

        initializeConsumerConnectionPool();
        initializeProducerConnectionPool();
    }

    private void initializeConsumerConnectionPool() {
        consumerConnectionPool = new ReceiverConnectionPool(
                consumerHost, consumerPort, Constants.CONSUMER_SERVER_PATH, 2
        );
        metrics.markStartTime();
        System.out.println("Consumer connection pool initialized: 40 connections (2 per room)");
    }

    private void initializeProducerConnectionPool() {
        producerConnectionPool = new ProducerConnectionPool(producerHost, producerPort, Constants.PRODUCER_SERVER_PATH);
        System.out.println("Producer connection pool initialized: 40 connections (2 per room)");
    }

    private void startMessageGeneration() {
        messageGeneratorExecutor.submit(this::generateMessages);
        System.out.println("Started message generator thread");
    }

    private void startSenders() {
        for (int i = 0; i < SENDER_THREAD_POOL_SIZE; i++) {
            senderExecutor.submit(new MessageSenderWorker(
                    messageQueue, producerConnectionPool, messagesSent, sentMessageIds, metrics, gson
            ));
        }
        System.out.println("Started " + SENDER_THREAD_POOL_SIZE + " sender threads");
    }

    /**
     * NEW: Start listener threads - one per room.
     */
    private void startListeners() {
        for (int roomId = Constants.MIN_ROOM_ID; roomId <= Constants.MAX_ROOM_ID; roomId++) {
            String roomIdStr = "room" + roomId;

            listenerExecutor.submit(new MessageListenerWorker(
                    roomIdStr,
                    consumerConnectionPool,
                    receiverQueue,
                    messagesReceived,
                    receiverQueueDropped
            ));
        }
        System.out.println("Started " + LISTENER_THREAD_POOL_SIZE + " listener threads (1 per room)");
    }

    /**
     * NEW: Start processor threads - process from receiver queue.
     */
    private void startProcessors() {
        for (int i = 0; i < PROCESSOR_THREAD_POOL_SIZE; i++) {
            processorExecutor.submit(new MessageProcessorWorker(
                    receiverQueue,
                    ackQueue,
                    sentMessageIds,
                    acksQueued,
                    messagesCompleted,
                    ackToOriginalMessageId,
                    metrics
            ));
        }
        System.out.println("Started " + PROCESSOR_THREAD_POOL_SIZE + " processor threads");
    }

    private void startAckSenders() {
        for (int i = 0; i < ACK_SENDER_THREAD_POOL_SIZE; i++) {
            ackSenderExecutor.submit(new AckSenderWorker(
                    ackQueue, producerConnectionPool, acksSent, acksFailed,
                    sentMessageIds, metrics, gson
            ));
        }
        System.out.println("Started " + ACK_SENDER_THREAD_POOL_SIZE + " ACK sender threads");
    }

    private void startMonitoring() {
        monitorExecutor.scheduleAtFixedRate(this::printProgress, 5, 5, TimeUnit.SECONDS);
    }

    private void generateMessages() {
        try {
            System.out.println("Starting message generation...");
            Random random = new Random();

            // ========== UPDATED: 20% JOIN messages ==========
            int joinCount = (TOTAL_MESSAGES * JOIN_MESSAGE_PERCENTAGE) / 100;
            System.out.println("Phase 1: Generating " + joinCount + " JOIN messages (" +
                    JOIN_MESSAGE_PERCENTAGE + "%)...");

            for (int i = 0; i < joinCount; i++) {
                String userId = userPool.get(i % userPool.size());
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

            // ========== UPDATED: 80% TEXT messages ==========
            int textCount = TOTAL_MESSAGES - joinCount;
            System.out.println("Phase 2: Generating " + textCount + " TEXT messages (" +
                    ((100 - JOIN_MESSAGE_PERCENTAGE)) + "%)...");

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

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════");
            System.out.println("Message generation completed!");
            System.out.println("  Total queued: " + messageQueue.size() + " messages");
            System.out.println("  Distribution: " + joinCount + " JOIN (" + JOIN_MESSAGE_PERCENTAGE + "%), " +
                    textCount + " TEXT (" + (100 - JOIN_MESSAGE_PERCENTAGE) + "%)");
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

        while (messagesSent.get() < TOTAL_MESSAGES) {
            Thread.sleep(1000);
            if ((System.currentTimeMillis() - startTime) > 3600000) {
                System.out.println("Timeout reached.");
                break;
            }
        }

        System.out.println("All messages sent! Waiting for ACK echoes...");

        long lastCompletedCount = messagesCompleted.get();
        int idleCount = 0;

        while (messagesCompleted.get() < TOTAL_MESSAGES) {
            Thread.sleep(1000);

            long currentCompleted = messagesCompleted.get();

            if ((idleCount % 10) == 0) {
                System.out.printf("  Progress: Sent=%,d | AcksSent=%,d | Completed=%,d/%,d (%.1f%%) | " +
                                "Pending=%,d | RcvQ=%,d | AckQ=%,d%n",
                        messagesSent.get(), acksSent.get(), currentCompleted, TOTAL_MESSAGES,
                        (currentCompleted * 100.0) / TOTAL_MESSAGES,
                        ackToOriginalMessageId.size(), receiverQueue.size(), ackQueue.size());
            }

            if (currentCompleted == lastCompletedCount) {
                idleCount++;
                if (idleCount > 60) {
                    System.out.println("Completion stalled. Final state:");
                    System.out.println("  Sent: " + messagesSent.get());
                    System.out.println("  ACKs sent: " + acksSent.get());
                    System.out.println("  Completed: " + currentCompleted);
                    System.out.println("  Pending mappings: " + ackToOriginalMessageId.size());

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
    }

    private void shutdown() {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════");
        System.out.println("Shutting down client...");
        System.out.println("═══════════════════════════════════════════════════════");

        shutdownExecutor(messageGeneratorExecutor, "MessageGenerator");
        shutdownExecutor(senderExecutor, "Sender");
        shutdownExecutor(listenerExecutor, "Listener");
        shutdownExecutor(processorExecutor, "Processor");
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
                    System.out.println("⚠️  " + name + " did not terminate, forcing shutdown...");
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

        int joinCount = (TOTAL_MESSAGES * JOIN_MESSAGE_PERCENTAGE) / 100;
        int textCount = TOTAL_MESSAGES - joinCount;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════╗");
        System.out.println("║            HIGH-THROUGHPUT TEST RESULTS              ║");
        System.out.println("╚══════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("Configuration:");
        System.out.println("  Total Messages: " + String.format("%,d", TOTAL_MESSAGES));
        System.out.println("  JOIN: " + String.format("%,d", joinCount) + " (" + JOIN_MESSAGE_PERCENTAGE + "%)");
        System.out.println("  TEXT: " + String.format("%,d", textCount) + " (" + (100 - JOIN_MESSAGE_PERCENTAGE) + "%)");
        System.out.println("  Threads: " + SENDER_THREAD_POOL_SIZE + " senders, " +
                LISTENER_THREAD_POOL_SIZE + " listeners, " +
                PROCESSOR_THREAD_POOL_SIZE + " processors, " +
                ACK_SENDER_THREAD_POOL_SIZE + " ACK senders");
        System.out.println("  Connections: 40 producer, 40 consumer");
        System.out.println();
        System.out.println("Performance:");
        System.out.println("  Duration: " + (totalTime/1000) + " sec (" +
                String.format("%.1f", totalTime/60000.0) + " min)");
        System.out.println();
        System.out.println("Counts:");
        System.out.println("  Sent: " + String.format("%,d", sent));
        System.out.println("  Received: " + String.format("%,d", received));
        System.out.println("  ACKs Sent: " + String.format("%,d", acked));
        System.out.println("  Completed: " + String.format("%,d", completed) +
                " (" + String.format("%.1f%%", (completed * 100.0) / sent) + ")");
        System.out.println();
        System.out.println("⭐ End-to-End Throughput: " + String.format("%.2f", throughput) + " msg/sec");
        System.out.println();
        System.out.println("Errors:");
        System.out.println("  ACK Failures: " + ackFailed);
        System.out.println("  Dropped: " + dropped);
        System.out.println("  Connection Failures: " + metrics.getConnectionFailures());
        System.out.println();
        System.out.println("Queues:");
        System.out.println("  Message: " + messageQueue.size());
        System.out.println("  Receiver: " + receiverQueue.size());
        System.out.println("  ACK: " + ackQueue.size());
        System.out.println("  Pending Mappings: " + ackToOriginalMessageId.size());
        System.out.println();
        System.out.println("═════════════════════════════════════════════════════");
    }

    public static class ReceivedMessageEntry {
        private final ChatMessage message;
        private final long receivedTimestamp;

        public ReceivedMessageEntry(ChatMessage message) {
            this.message = message;
            this.receivedTimestamp = System.currentTimeMillis();
        }

        public ChatMessage getMessage() { return message; }
        public long getReceivedTimestamp() { return receivedTimestamp; }
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

        public ChatMessage getOriginalMessage() { return originalMessage; }
        public long getQueuedTimestamp() { return queuedTimestamp; }
        public int getRetryCount() { return retryCount; }
        public void incrementRetryCount() { this.retryCount++; }
    }
}

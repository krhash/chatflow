package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.DistributedClient.AckQueueEntry;
import cs6650.chatflow.client.DistributedClient.ReceivedMessageEntry;
import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.connection.ReceiverConnectionPool;
import cs6650.chatflow.client.model.ChatMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker thread that:
 * 1. Registers a lightweight listener on a WebSocket connection
 * 2. Processes messages from the receiver queue
 * 3. Checks if messages were sent by this client
 * 4. Queues ACKs for messages we sent
 *
 * SIMPLIFIED: No longer tracks ACK messages coming back from consumer.
 * ACK confirmations now come directly from producer.
 */
public class MessageReceiverWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MessageReceiverWorker.class);

    private final String roomId;
    private final ReceiverConnectionPool connectionPool;
    private final BlockingQueue<ReceivedMessageEntry> receiverQueue;
    private final BlockingQueue<AckQueueEntry> ackQueue;
    private final Set<String> sentMessageIds;
    private final AtomicLong messagesReceived;
    private final AtomicLong acksQueued;
    private final AtomicLong receiverQueueDropped;
    private final ClientMetrics metrics;

    public MessageReceiverWorker(String roomId,
                                 ReceiverConnectionPool connectionPool,
                                 BlockingQueue<ReceivedMessageEntry> receiverQueue,
                                 BlockingQueue<AckQueueEntry> ackQueue,
                                 Set<String> sentMessageIds,
                                 AtomicLong messagesReceived,
                                 AtomicLong acksQueued,
                                 AtomicLong receiverQueueDropped,
                                 ClientMetrics metrics) {
        this.roomId = roomId;
        this.connectionPool = connectionPool;
        this.receiverQueue = receiverQueue;
        this.ackQueue = ackQueue;
        this.sentMessageIds = sentMessageIds;
        this.messagesReceived = messagesReceived;
        this.acksQueued = acksQueued;
        this.receiverQueueDropped = receiverQueueDropped;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        try {
            // Register lightweight listener
            connectionPool.addMessageListener(roomId, this::queueMessage);

            logger.info("Receiver worker started for room {}", roomId);

            // Process messages from queue
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    ReceivedMessageEntry entry = receiverQueue.poll(1, TimeUnit.SECONDS);

                    if (entry != null) {
                        processMessage(entry.getMessage());
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

        } catch (Exception e) {
            logger.error("Error in receiver worker for room {}: {}", roomId, e.getMessage(), e);
        } finally {
            connectionPool.removeMessageListener(roomId, this::queueMessage);
            logger.info("Receiver worker stopped for room {}", roomId);
        }
    }

    /**
     * FAST: Called on WebSocket I/O thread - just queue the message!
     */
    private void queueMessage(ChatMessage message) {
        try {
            messagesReceived.incrementAndGet();
            metrics.recordMessageReceived();

            boolean queued = receiverQueue.offer(
                    new ReceivedMessageEntry(message),
                    100,
                    TimeUnit.MILLISECONDS
            );

            if (!queued) {
                receiverQueueDropped.incrementAndGet();
                logger.warn("Receiver queue full! Dropped message: {}", message.getMessageId());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            receiverQueueDropped.incrementAndGet();
            logger.warn("Interrupted while queuing message: {}", message.getMessageId());
        } catch (Exception e) {
            logger.error("Error queuing message: {}", e.getMessage(), e);
        }
    }

    /**
     * SIMPLIFIED: Process message and queue ACK if it's our message.
     * No longer tracks ACK messages coming back - they come from producer now!
     */
    private void processMessage(ChatMessage message) {
        try {
            String messageId = message.getMessageId();

            // Check if this is a message we sent (original messages only, not ACKs)
            if (sentMessageIds.remove(messageId)) {
                // This is an original message (JOIN/TEXT/LEAVE) coming back - queue ACK
                queueAck(message);
            }
            // Note: We ignore ACK messages echoed from consumer - they're redundant now

        } catch (Exception e) {
            logger.error("Error processing message {}: {}", message.getMessageId(), e.getMessage(), e);
        }
    }

    /**
     * Queue ACK for asynchronous sending by ACK sender threads.
     */
    private void queueAck(ChatMessage originalMessage) {
        try {
            boolean queued = ackQueue.offer(
                    new AckQueueEntry(originalMessage),
                    100,
                    TimeUnit.MILLISECONDS
            );

            if (queued) {
                acksQueued.incrementAndGet();
            } else {
                logger.warn("ACK queue full! Dropped ACK for message: {}", originalMessage.getMessageId());
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while queuing ACK for message: {}", originalMessage.getMessageId());
        } catch (Exception e) {
            logger.error("Error queuing ACK: {}", e.getMessage(), e);
        }
    }
}

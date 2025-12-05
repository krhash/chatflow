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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker thread that processes messages from consumer and detects ACK echoes.
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
    private final AtomicLong messagesCompleted;
    private final ConcurrentHashMap<String, String> ackToOriginalMessageId;
    private final ClientMetrics metrics;

    public MessageReceiverWorker(String roomId,
                                 ReceiverConnectionPool connectionPool,
                                 BlockingQueue<ReceivedMessageEntry> receiverQueue,
                                 BlockingQueue<AckQueueEntry> ackQueue,
                                 Set<String> sentMessageIds,
                                 AtomicLong messagesReceived,
                                 AtomicLong acksQueued,
                                 AtomicLong receiverQueueDropped,
                                 AtomicLong messagesCompleted,
                                 ConcurrentHashMap<String, String> ackToOriginalMessageId,
                                 ClientMetrics metrics) {
        this.roomId = roomId;
        this.connectionPool = connectionPool;
        this.receiverQueue = receiverQueue;
        this.ackQueue = ackQueue;
        this.sentMessageIds = sentMessageIds;
        this.messagesReceived = messagesReceived;
        this.acksQueued = acksQueued;
        this.receiverQueueDropped = receiverQueueDropped;
        this.messagesCompleted = messagesCompleted;
        this.ackToOriginalMessageId = ackToOriginalMessageId;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        try {
            connectionPool.addMessageListener(roomId, this::queueMessage);
            logger.info("Receiver worker started for room {}", roomId);

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
        } catch (Exception e) {
            logger.error("Error queuing message: {}", e.getMessage(), e);
        }
    }

    private void processMessage(ChatMessage message) {
        try {
            String messageId = message.getMessageId();

            if (sentMessageIds.remove(messageId)) {
                // Check if this is an ACK echo
                String originalMessageId = ackToOriginalMessageId.remove(messageId);

                if (originalMessageId != null) {
                    // ACK echo received - original message complete!
                    messagesCompleted.incrementAndGet();
                    logger.debug("Message {} completed (ACK echo received)", originalMessageId);
                } else {
                    // Original message received - queue ACK
                    queueAck(message);
                }
            }

        } catch (Exception e) {
            logger.error("Error processing message {}: {}", message.getMessageId(), e.getMessage(), e);
        }
    }

    private void queueAck(ChatMessage originalMessage) {
        try {
            String ackMessageId = originalMessage.getMessageId() + "-DELIVERY_ACK";
            ackToOriginalMessageId.put(ackMessageId, originalMessage.getMessageId());

            boolean queued = ackQueue.offer(
                    new AckQueueEntry(originalMessage),
                    100,
                    TimeUnit.MILLISECONDS
            );

            if (queued) {
                acksQueued.incrementAndGet();
            } else {
                logger.warn("ACK queue full! Dropped ACK for message: {}", originalMessage.getMessageId());
                ackToOriginalMessageId.remove(ackMessageId);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            String ackMessageId = originalMessage.getMessageId() + "-DELIVERY_ACK";
            ackToOriginalMessageId.remove(ackMessageId);
        } catch (Exception e) {
            logger.error("Error queuing ACK: {}", e.getMessage(), e);
        }
    }
}

package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.DistributedClient;
import cs6650.chatflow.client.DistributedClient.AckQueueEntry;
import cs6650.chatflow.client.DistributedClient.ReceivedMessageEntry;
import cs6650.chatflow.client.commons.ClientMetrics;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Worker thread that processes messages from the receiver queue.
 *
 * Responsibilities:
 * 1. Poll messages from receiver queue
 * 2. Check if message was sent by this client - if yes, queue ACK
 * 3. If message is ACK type, parse original ID and mark complete
 *
 * 100 processor threads work in parallel processing the shared receiver queue.
 */
public class MessageProcessorWorker implements Runnable, DistributedClient.Stoppable {

    private static final Logger logger = LoggerFactory.getLogger(MessageProcessorWorker.class);

    private final BlockingQueue<ReceivedMessageEntry> receiverQueue;
    private final BlockingQueue<AckQueueEntry> ackQueue;
    private final Set<String> sentMessageIds;
    private final AtomicLong acksQueued;
    private final AtomicLong messagesCompleted;
    private final ClientMetrics metrics;
    private volatile boolean running = true;
    private Thread workerThread;

    public MessageProcessorWorker(BlockingQueue<ReceivedMessageEntry> receiverQueue,
                                  BlockingQueue<AckQueueEntry> ackQueue,
                                  Set<String> sentMessageIds,
                                  AtomicLong acksQueued,
                                  AtomicLong messagesCompleted,
                                  ClientMetrics metrics) {
        this.receiverQueue = receiverQueue;
        this.ackQueue = ackQueue;
        this.sentMessageIds = sentMessageIds;
        this.acksQueued = acksQueued;
        this.messagesCompleted = messagesCompleted;
        this.metrics = metrics;
    }

    @Override
    public void run() {
        this.workerThread = Thread.currentThread();
        try {
            logger.info("Message processor worker started");

            while (running) {
                try {
                    ReceivedMessageEntry entry = receiverQueue.poll(1, TimeUnit.SECONDS);

                    if (entry != null) {
                        processMessage(entry.getMessage());
                    }

                } catch (InterruptedException e) {
                    if (running) {
                        break;
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error in message processor worker: {}", e.getMessage(), e);
        } finally {
            logger.info("Message processor worker stopped");
        }
    }

    /**
     * Process message: check if original (queue ACK) or ACK (mark complete).
     */
    private void processMessage(ChatMessage message) {
        try {
            String messageId = message.getMessageId();

            if (sentMessageIds.remove(messageId)) {
                // It's an original message we sent, queue ACK
                queueAck(message);
            } else if (message.getMessageType().equals(Constants.MESSAGE_TYPE_ACK)) {
                // It's an ACK, parse original ID and mark complete
                String msg = message.getMessage();
                if (msg.startsWith("DELIVERY_ACK:")) {
                    messagesCompleted.incrementAndGet();
                }
            }
            // Ignore other messages (from other clients)

        } catch (Exception e) {
            logger.error("Error processing message {}: {}", message.getMessageId(), e.getMessage(), e);
        }
    }

    /**
     * Queue ACK for sending.
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
        } catch (Exception e) {
            logger.error("Error queuing ACK: {}", e.getMessage(), e);
        }
    }

    @Override
    public void stop() {
        running = false;
        if (workerThread != null) {
            workerThread.interrupt();
        }
    }
}

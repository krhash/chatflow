package cs6650.chatflow.client.workers;

import cs6650.chatflow.client.DistributedClient.ReceivedMessageEntry;
import cs6650.chatflow.client.connection.ReceiverConnectionPool;
import cs6650.chatflow.client.model.ChatMessage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight listener thread that registers on a specific room's consumer connection.
 *
 * Responsibility: ONLY queue incoming messages to the receiver queue.
 * Does NOT process messages - that's done by MessageProcessorWorker threads.
 *
 * One listener per room = 20 total listeners.
 */
public class MessageListenerWorker implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(MessageListenerWorker.class);

    private final String roomId;
    private final ReceiverConnectionPool connectionPool;
    private final BlockingQueue<ReceivedMessageEntry> receiverQueue;
    private final AtomicLong messagesReceived;
    private final AtomicLong receiverQueueDropped;

    public MessageListenerWorker(String roomId,
                                 ReceiverConnectionPool connectionPool,
                                 BlockingQueue<ReceivedMessageEntry> receiverQueue,
                                 AtomicLong messagesReceived,
                                 AtomicLong receiverQueueDropped) {
        this.roomId = roomId;
        this.connectionPool = connectionPool;
        this.receiverQueue = receiverQueue;
        this.messagesReceived = messagesReceived;
        this.receiverQueueDropped = receiverQueueDropped;
    }

    @Override
    public void run() {
        try {
            // Register lightweight callback that just queues
            connectionPool.addMessageListener(roomId, this::queueMessage);

            logger.info("Message listener started for room {}", roomId);

            // Keep thread alive - just wait for shutdown signal
            while (!Thread.currentThread().isInterrupted()) {
                Thread.sleep(5000);  // Wake up occasionally to check interrupt
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.info("Listener for room {} interrupted", roomId);
        } catch (Exception e) {
            logger.error("Error in listener for room {}: {}", roomId, e.getMessage(), e);
        } finally {
            connectionPool.removeMessageListener(roomId, this::queueMessage);
            logger.info("Message listener stopped for room {}", roomId);
        }
    }

    /**
     * FAST: Called on WebSocket I/O thread - just queue the message!
     * This is the callback registered with ReceiverConnectionPool.
     */
    private void queueMessage(ChatMessage message) {
        try {
            messagesReceived.incrementAndGet();

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
}

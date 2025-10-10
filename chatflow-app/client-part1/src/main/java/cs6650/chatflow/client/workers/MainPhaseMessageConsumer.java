package cs6650.chatflow.client.workers;

import com.google.gson.Gson;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.MessageQueueEntry;
import cs6650.chatflow.client.queues.MessageQueue;
import cs6650.chatflow.client.util.MessageTimer;
import cs6650.chatflow.client.websocket.ChatflowWebSocketClient;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumer thread that takes message entries from queue and sends them via WebSocket connection pool.
 * Routes messages to the correct connection based on room ID.
 * Implements retry logic and tracks message send timestamps.
 */
public class MainPhaseMessageConsumer implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(MainPhaseMessageConsumer.class);

    private final MessageQueue messageQueue;
    private final WebSocketConnectionPool connectionPool;
    private final MessageTimer messageTimer;
    private final AtomicInteger messagesSent;
    private final int totalMessages;
    private final Gson gson = new Gson();

    /**
     * Creates message consumer.
     * @param messageQueue queue to consume message entries from
     * @param connectionPool WebSocket connection pool
     * @param messageTimer timer for tracking message timeouts
     * @param messagesSent global counter for sent messages
     * @param totalMessages total messages to be sent
     */
    public MainPhaseMessageConsumer(MessageQueue messageQueue, WebSocketConnectionPool connectionPool,
                                    MessageTimer messageTimer, AtomicInteger messagesSent, int totalMessages) {
        this.messageQueue = messageQueue;
        this.connectionPool = connectionPool;
        this.messageTimer = messageTimer;
        this.messagesSent = messagesSent;
        this.totalMessages = totalMessages;
    }

    @Override
    public void run() {
        logger.debug("Starting message consumption...");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Check if all messages have been sent - if so, stop gracefully
                if (messagesSent.get() >= totalMessages) {
                    logger.debug("All messages sent, stopping gracefully");
                    break;
                }

                // Try to get next message entry with timeout to avoid indefinite blocking
                MessageQueueEntry entry = messageQueue.poll(1000); // Wait up to 1 second

                if (entry != null) {
                    // Send message with retry logic
                    sendMessageWithRetry(entry);
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted, shutting down");
        }

        logger.debug("Finished processing");
    }

    /**
     * Sends a message with retry logic.
     * @param entry message entry containing message and target room ID
     */
    private void sendMessageWithRetry(MessageQueueEntry entry) {
        String messageJson = gson.toJson(entry.getMessage());
        long delay = Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                // Get connection for the specific room ID
                ChatflowWebSocketClient connection = connectionPool.getConnectionByRoomId(entry.getRoomId());

                if (connection == null) {
                    logger.error("No connection available for room ID {} for message {}, skipping",
                        entry.getRoomId(), entry.getMessage().getMessageId());
                    return;
                }

                // Check if connection is open
                if (!connection.isOpen()) {
                    // Try to reconnect the failed connection
                    if (connectionPool.reconnect(connection)) {
                        // Get the new connection (reconnect replaced it in the pool)
                        connection = connectionPool.getConnectionByRoomId(entry.getRoomId());
                        if (connection == null || !connection.isOpen()) {
                            logger.warn("Connection still unavailable after reconnection attempt for room {} message {}, skipping",
                                entry.getRoomId(), entry.getMessage().getMessageId());
                            return;
                        }
                    } else {
                        logger.warn("Failed to reconnect connection for room {} message {}, skipping",
                            entry.getRoomId(), entry.getMessage().getMessageId());
                        return;
                    }
                }

                // Record send timestamp for timeout tracking
                messageTimer.recordMessageSent(entry.getMessage());

                // Send message
                connection.send(messageJson);

                // Increment global counter
                messagesSent.incrementAndGet();

                // Success - return
                return;

            } catch (Exception e) {
                if (attempt == Constants.MESSAGE_RETRY_ATTEMPTS) {
                    // All retries exhausted
                    logger.error("Failed to send message {} after {} attempts: {}",
                        entry.getMessage().getMessageId(), Constants.MESSAGE_RETRY_ATTEMPTS, e.getMessage());
                    return;
                }

                // Wait before retry with exponential backoff
                try {
                    Thread.sleep(delay);
                    delay = Math.min(delay * 2, Constants.MESSAGE_RETRY_MAX_DELAY_MILLIS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}

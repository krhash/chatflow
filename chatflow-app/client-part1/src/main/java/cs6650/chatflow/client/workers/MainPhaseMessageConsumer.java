package cs6650.chatflow.client.workers;

import com.google.gson.Gson;
import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.queues.MessageQueue;
import cs6650.chatflow.client.util.MessageTimer;
import cs6650.chatflow.client.websocket.WebSocketConnectionPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumer thread that takes messages from queue and sends them via WebSocket connection pool.
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
     * @param messageQueue queue to consume messages from
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

                // Try to get next message with timeout to avoid indefinite blocking
                ChatMessage message = messageQueue.poll(1000); // Wait up to 1 second

                if (message != null) {
                    // Send message with retry logic
                    sendMessageWithRetry(message);
                }
                // If no message available, loop will check completion condition again
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted, shutting down");
        }

        logger.debug("Finished processing");
    }

    /**
     * Sends a message with retry logic.
     * @param message message to send
     */
    private void sendMessageWithRetry(ChatMessage message) {
        String messageJson = gson.toJson(message);
        long delay = Constants.MESSAGE_RETRY_INITIAL_DELAY_MILLIS;

        for (int attempt = 1; attempt <= Constants.MESSAGE_RETRY_ATTEMPTS; attempt++) {
            try {
                // Get connection from pool
                org.java_websocket.client.WebSocketClient connection = connectionPool.getNextConnection();

                // Check if connection is open
                if (!connection.isOpen()) {
                    throw new IllegalStateException("WebSocket connection is closed");
                }

                // Record send timestamp for timeout tracking
                messageTimer.recordMessageSent(message);

                // Send message
                connection.send(messageJson);

                // Increment global counter
                messagesSent.incrementAndGet();

                // Success - return
                return;

            } catch (IllegalStateException e) {
                // Connection closed - don't retry, connection pool should handle this
                logger.warn("Connection closed for message {}, skipping", message.getMessageId());
                return;
            } catch (Exception e) {
                if (attempt == Constants.MESSAGE_RETRY_ATTEMPTS) {
                    // All retries exhausted
                    logger.error("Failed to send message {} after {} attempts: {}",
                        message.getMessageId(), Constants.MESSAGE_RETRY_ATTEMPTS, e.getMessage());
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

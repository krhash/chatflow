package cs6650.chatflow.client.workers;

import com.google.gson.Gson;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.model.MessageResponse;
import cs6650.chatflow.client.util.MessageTimer;
import cs6650.chatflow.client.queues.ResponseQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Processes WebSocket responses asynchronously.
 * Updates latency tracking and message counters.
 */
public class MainPhaseResponseWorker implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(MainPhaseResponseWorker.class);

    private final ResponseQueue responseQueue;
    private final MessageTimer messageTimer;
    private final AtomicInteger messagesReceived;
    private final Map<String, Long> responseLatencies;
    private final Gson gson = new Gson();

    /**
     * Creates response processor.
     * @param responseQueue queue to process responses from
     * @param messageTimer timer for tracking message timeouts
     * @param messagesReceived global counter for received messages
     * @param responseLatencies map to store response latencies
     */
    public MainPhaseResponseWorker(ResponseQueue responseQueue, MessageTimer messageTimer,
                                   AtomicInteger messagesReceived, Map<String, Long> responseLatencies) {
        this.responseQueue = responseQueue;
        this.messageTimer = messageTimer;
        this.messagesReceived = messagesReceived;
        this.responseLatencies = responseLatencies;
    }

    @Override
    public void run() {
        logger.debug("Starting response processing...");

        try {
            while (!Thread.currentThread().isInterrupted()) {
                // Get next response from queue
                MessageResponse response = responseQueue.take();

                // Process the response
                processResponse(response);
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("Interrupted, shutting down");
        }
    }

    /**
     * Processes a single response.
     * @param response the response to process
     */
    private void processResponse(MessageResponse response) {
        try {
            // Parse response message
            ChatMessage message = gson.fromJson(response.getMessageJson(), ChatMessage.class);

            if (message.getMessageId() != null) {
                // Record response received - this removes from timeout tracking
                messageTimer.recordMessageResponse(message.getMessageId());

                // Calculate and store latency if we have send timestamp
                // Note: In current implementation, latency tracking is handled separately
                // This ensures proper message correlation and timeout management
                messagesReceived.incrementAndGet();
            }

        } catch (Exception e) {
            logger.error("Error processing response: {}", e.getMessage());
        }
    }
}

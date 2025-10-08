package cs6650.chatflow.client.websocket;

import com.google.gson.Gson;
import cs6650.chatflow.client.model.ChatMessage;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import cs6650.chatflow.client.model.MessageResponse;
import cs6650.chatflow.client.util.ResponseQueue;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket client using Java-WebSocket library.
 * Handles message sending, response correlation, and latency tracking.
 */
public class WebSocketClient extends org.java_websocket.client.WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketClient.class);

    private final Map<String, Long> sentMessages;
    private final Map<String, Long> responseLatencies;
    private final CountDownLatch allResponsesReceived;
    private final AtomicInteger totalMessagesReceived;
    private final ResponseQueue responseQueue;
    private final Gson gson = new Gson();

    /**
     * Creates WebSocket client for warmup phase (backward compatibility).
     * @param serverUri WebSocket server URI
     * @param sentMessages map to store sent message timestamps
     * @param responseLatencies map to store response latencies
     * @param allResponsesReceived latch to signal response completion
     * @param totalMessagesReceived global counter for received messages
     */
    public WebSocketClient(URI serverUri, Map<String, Long> sentMessages,
                           Map<String, Long> responseLatencies, CountDownLatch allResponsesReceived,
                           AtomicInteger totalMessagesReceived) {
        super(serverUri);
        this.sentMessages = sentMessages;
        this.responseLatencies = responseLatencies;
        this.allResponsesReceived = allResponsesReceived;
        this.totalMessagesReceived = totalMessagesReceived;
        this.responseQueue = null; // Not used in warmup mode
    }

    /**
     * Creates WebSocket client for main phase connection pool.
     * @param serverUri WebSocket server URI
     * @param sentMessages map to store sent message timestamps (null for pool)
     * @param responseLatencies map to store response latencies (null for pool)
     * @param allResponsesReceived latch to signal response completion (null for pool)
     * @param responseQueue queue for asynchronous response processing
     */
    public WebSocketClient(URI serverUri, Map<String, Long> sentMessages,
                           Map<String, Long> responseLatencies, CountDownLatch allResponsesReceived,
                           ResponseQueue responseQueue) {
        super(serverUri);
        this.sentMessages = sentMessages;
        this.responseLatencies = responseLatencies;
        this.allResponsesReceived = allResponsesReceived;
        this.totalMessagesReceived = null; // Not used in pool mode
        this.responseQueue = responseQueue;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        // Connection opened successfully
    }

    @Override
    public void onMessage(String message) {
        try {
            // Parse response and correlate with sent message
            ChatMessage response = gson.fromJson(message, ChatMessage.class);

            if (response.getMessageId() != null) {
                Long sentTime = sentMessages.get(response.getMessageId());
                if (sentTime != null) {
                    long latencyNanos = System.nanoTime() - sentTime;
                    responseLatencies.put(response.getMessageId(), latencyNanos);
                }
            }

            // Increment global counter
            if (totalMessagesReceived != null) {
                totalMessagesReceived.incrementAndGet();
            }

            // Signal response received
            allResponsesReceived.countDown();

        } catch (Exception e) {
            logger.error("Error processing Java-WebSocket response: {}", e.getMessage());
            // Even on error, count it as a response to avoid hanging
            allResponsesReceived.countDown();
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        // Connection closed
    }

    @Override
    public void onError(Exception ex) {
        logger.error("Java-WebSocket error: {}", ex.getMessage());
    }

    /**
     * Get the CountDownLatch for tracking response completion.
     */
    public CountDownLatch getResponseLatch() {
        return allResponsesReceived;
    }
}

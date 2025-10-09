package cs6650.chatflow.client.websocket;

import com.google.gson.Gson;
import cs6650.chatflow.client.model.MessageResponse;
import cs6650.chatflow.client.util.ResponseQueue;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;

/**
 * WebSocket client for main phase using ResponseQueue.
 * Optimized for high-throughput producer-consumer architecture.
 */
public class MainPhaseWebSocketClient extends org.java_websocket.client.WebSocketClient {

    private static final Logger logger = LoggerFactory.getLogger(MainPhaseWebSocketClient.class);

    private final ResponseQueue responseQueue;
    private final Gson gson = new Gson();

    /**
     * Creates WebSocket client for main phase.
     * @param serverUri WebSocket server URI
     * @param responseQueue queue for asynchronous response processing
     */
    public MainPhaseWebSocketClient(URI serverUri, ResponseQueue responseQueue) {
        super(serverUri);
        this.responseQueue = responseQueue;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        // Connection opened successfully
    }

    @Override
    public void onMessage(String message) {
        try {
            logger.debug("Received message: " + message);
            // Create response wrapper with receive timestamp
            MessageResponse response = new MessageResponse(message, System.nanoTime());

            // Queue for asynchronous processing (non-blocking)
            responseQueue.offer(response);

        } catch (Exception e) {
            logger.error("Error queuing WebSocket response: {}", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.info("WebSocket connection closed: {} - {}", code, reason);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket error: {}", ex.getMessage());
    }
}

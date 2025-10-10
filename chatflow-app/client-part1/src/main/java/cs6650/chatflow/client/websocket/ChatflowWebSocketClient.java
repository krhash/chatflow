package cs6650.chatflow.client.websocket;

import cs6650.chatflow.client.model.MessageResponse;
import org.java_websocket.WebSocket;
import org.java_websocket.framing.Framedata;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.function.Consumer;

/**
 * Unified WebSocket client with configurable response handling.
 * Uses a Consumer to handle responses, allowing different behaviors for warmup vs main phase.
 */
public class ChatflowWebSocketClient extends org.java_websocket.client.WebSocketClient {
    private static final Logger logger = LoggerFactory.getLogger(ChatflowWebSocketClient.class);

    private final Consumer<MessageResponse> responseHandler;

    /**
     * Creates WebSocket client with custom response handler.
     * @param serverUri WebSocket server URI
     * @param responseHandler function to call when responses are received
     */
    public ChatflowWebSocketClient(URI serverUri, Consumer<MessageResponse> responseHandler) {
        super(serverUri);
        this.responseHandler = responseHandler;
    }

    @Override
    public void onOpen(ServerHandshake handshakedata) {
        logger.debug("Websocket Connection opened - " +  handshakedata.toString());
    }

    @Override
    public void onMessage(String message) {
        try {
            // Handle response using the configured handler
            MessageResponse response = new MessageResponse(message, System.nanoTime());
            responseHandler.accept(response);
        } catch (Exception e) {
            logger.error("Error handling WebSocket response: {}", e.getMessage());
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        logger.debug("WebSocket connection closed: {} - {}", code, reason);
    }

    @Override
    public void onError(Exception ex) {
        logger.error("WebSocket error: {}", ex.getMessage());
    }

    @Override
    public void onWebsocketPing(WebSocket conn, Framedata f) {
        logger.debug("Websocket Ping: {}", f.toString());
        super.onWebsocketPing(conn, f);
    }
}

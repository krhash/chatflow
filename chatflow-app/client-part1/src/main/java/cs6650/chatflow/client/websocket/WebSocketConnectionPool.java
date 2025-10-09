package cs6650.chatflow.client.websocket;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.util.ResponseQueue;
import com.google.gson.Gson;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of reusable WebSocket connections for load distribution.
 * Manages connection lifecycle and provides round-robin load balancing.
 */
public class WebSocketConnectionPool {

    private final MainPhaseWebSocketClient[] connections;
    private final AtomicInteger nextConnectionIndex = new AtomicInteger(0);
    private final ResponseQueue responseQueue;

    /**
     * Creates a connection pool with configured number of connections.
     * @param serverUri base server URI
     * @param responseQueue queue for response processing
     * @throws URISyntaxException if URI construction fails
     * @throws InterruptedException if connection is interrupted
     */
    public WebSocketConnectionPool(URI serverUri, ResponseQueue responseQueue) throws URISyntaxException, InterruptedException {
        this.responseQueue = responseQueue;
        this.connections = new MainPhaseWebSocketClient[Constants.MAIN_PHASE_CONNECTION_POOL_SIZE];

        // Initialize all connections
        for (int i = 0; i < connections.length; i++) {
            connections[i] = new MainPhaseWebSocketClient(serverUri, responseQueue);
            connections[i].connectBlocking();
        }
    }

    /**
     * Gets the next available connection using round-robin distribution.
     * @return WebSocket client for sending messages
     */
    public org.java_websocket.client.WebSocketClient getNextConnection() {
        int index = nextConnectionIndex.getAndIncrement() % connections.length;
        return connections[index];
    }

    /**
     * Gets a specific connection by index.
     * @param index connection index (0 to pool size - 1)
     * @return WebSocket client at specified index
     */
    public org.java_websocket.client.WebSocketClient getConnection(int index) {
        if (index < 0 || index >= connections.length) {
            throw new IllegalArgumentException("Invalid connection index: " + index);
        }
        return connections[index];
    }

    /**
     * Gets the total number of connections in the pool.
     * @return pool size
     */
    public int getPoolSize() {
        return connections.length;
    }

    /**
     * Checks if all connections are currently open.
     * @return true if all connections are healthy
     */
    public boolean areAllConnectionsOpen() {
        for (MainPhaseWebSocketClient connection : connections) {
            if (!connection.isOpen()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Gets the number of currently open connections.
     * @return count of healthy connections
     */
    public int getOpenConnectionCount() {
        int count = 0;
        for (MainPhaseWebSocketClient connection : connections) {
            if (connection.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sends a message through the next available connection.
     * @param message the message to send
     * @return true if message was sent successfully
     */
    public boolean sendMessage(ChatMessage message) {
        try {
            org.java_websocket.client.WebSocketClient connection = getNextConnection();
            if (connection != null && connection.isOpen()) {
                Gson gson = new Gson();
                String jsonMessage = gson.toJson(message);
                connection.send(jsonMessage);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Closes all connections in the pool.
     */
    public void closeAll() {
        for (MainPhaseWebSocketClient connection : connections) {
            if (connection != null) {
                connection.close(1000, "main phase complete");
            }
        }
    }

    /**
     * Gets connection pool statistics.
     * @return formatted statistics string
     */
    public String getStats() {
        return String.format("Connection Pool: %d/%d open",
            getOpenConnectionCount(), getPoolSize());
    }
}

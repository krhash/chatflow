package cs6650.chatflow.client.websocket;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.queues.ResponseQueue;
import com.google.gson.Gson;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of reusable WebSocket connections for load distribution.
 * Manages connection lifecycle and provides round-robin load balancing.
 * Handles automatic reconnection on connection failures.
 */
public class WebSocketConnectionPool {

    private final ChatflowWebSocketClient[] connections;
    private final AtomicInteger nextConnectionIndex = new AtomicInteger(0);
    private final AtomicInteger reconnections;
    private final ResponseQueue responseQueue;
    private final String baseUri;


    /**
     * Creates a connection pool with configured number of connections.
     * Each connection uses a randomly generated room ID for load distribution.
     * @param baseUri base server URI (e.g., ws://server:port/chat)
     * @param responseQueue queue for response processing
     * @param reconnections counter for tracking reconnection attempts
     * @throws URISyntaxException if URI construction fails
     * @throws InterruptedException if connection is interrupted
     */
    public WebSocketConnectionPool(String baseUri, ResponseQueue responseQueue, AtomicInteger reconnections) throws URISyntaxException, InterruptedException {
        this.baseUri = baseUri;
        this.responseQueue = responseQueue;
        this.reconnections = reconnections;
        this.connections = new ChatflowWebSocketClient[Constants.MAIN_PHASE_CONNECTION_POOL_SIZE];

        // Initialize all connections with response queue callback
        for (int i = 0; i < connections.length; i++) {
            // Generate random room ID for each connection
            int roomId = (int) (Math.random() * Constants.ROOM_COUNT) + 1;
            URI serverUri = new URI(String.format("%s/room%d", baseUri, roomId));
            connections[i] = new ChatflowWebSocketClient(serverUri, response -> {
                // Queue response for asynchronous processing
                responseQueue.offer(response);
            });
            connections[i].connectBlocking();
        }
    }

    /**
     * Gets the next available connection using round-robin distribution.
     * @return WebSocket client for sending messages
     */
    public ChatflowWebSocketClient getNextConnection() {
        int index = nextConnectionIndex.getAndIncrement() % connections.length;
        return connections[index];
    }

    /**
     * Gets a specific connection by index.
     * @param index connection index (0 to pool size - 1)
     * @return WebSocket client at specified index
     */
    public ChatflowWebSocketClient getConnection(int index) {
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
        for (ChatflowWebSocketClient connection : connections) {
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
        for (ChatflowWebSocketClient connection : connections) {
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
            ChatflowWebSocketClient connection = getNextConnection();
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
        for (ChatflowWebSocketClient connection : connections) {
            if (connection != null) {
                connection.close(1000, "main phase complete");
            }
        }
    }

    /**
     * Attempts to reconnect a failed connection with retry logic.
     * @param connection the connection to reconnect
     * @return true if reconnection was successful
     */
    public boolean reconnect(ChatflowWebSocketClient connection) {
        // Find the connection index
        int connectionIndex = -1;
        for (int i = 0; i < connections.length; i++) {
            if (connections[i] == connection) {
                connectionIndex = i;
                break;
            }
        }

        if (connectionIndex == -1) {
            return false; // Connection not found in pool
        }

        // Try reconnection with retries
        for (int attempt = 1; attempt <= Constants.CONNECTION_RECONNECT_ATTEMPTS; attempt++) {
            try {
                // Generate new random room ID for reconnection
                int roomId = (int) (Math.random() * Constants.ROOM_COUNT) + 1;
                URI serverUri = new URI(String.format("%s/room%d", baseUri, roomId));

                // Create new connection
                ChatflowWebSocketClient newConnection = new ChatflowWebSocketClient(serverUri, response -> {
                    // Queue response for asynchronous processing
                    responseQueue.offer(response);
                });

                // Attempt to connect
                newConnection.connectBlocking();

                // Replace the old connection
                connections[connectionIndex] = newConnection;

                // Increment reconnection counter
                reconnections.incrementAndGet();

                return true;
            } catch (Exception e) {
                if (attempt == Constants.CONNECTION_RECONNECT_ATTEMPTS) {
                    // All attempts failed
                    return false;
                }
                // Continue to next attempt
            }
        }

        return false;
    }

    /**
     * Gets the total number of reconnections performed.
     * @return number of reconnections
     */
    public int getReconnectionCount() {
        return reconnections.get();
    }

    /**
     * Gets connection pool statistics.
     * @return formatted statistics string
     */
    public String getStats() {
        return String.format("Connection Pool: %d/%d open, %d reconnections",
            getOpenConnectionCount(), getPoolSize(), getReconnectionCount());
    }
}

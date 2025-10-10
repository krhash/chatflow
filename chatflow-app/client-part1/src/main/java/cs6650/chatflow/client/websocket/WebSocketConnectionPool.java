package cs6650.chatflow.client.websocket;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import cs6650.chatflow.client.queues.ResponseQueue;
import com.google.gson.Gson;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pool of reusable WebSocket connections mapped by room ID.
 * Each connection is associated with a specific room ID (1-8).
 * Manages connection lifecycle and handles automatic reconnection on failures.
 */
public class WebSocketConnectionPool {

    private final Map<Integer, ChatflowWebSocketClient> connections;
    private final AtomicInteger reconnections;
    private final ResponseQueue responseQueue;
    private final String baseUri;


    /**
     * Creates a connection pool with sequential room IDs.
     * Creates connections for room IDs 1 through MAIN_PHASE_CONNECTION_POOL_SIZE.
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
        this.connections = new ConcurrentHashMap<>();

        // Initialize connections with sequential room IDs (1, 2, 3, ..., poolSize)
        for (int roomId = 1; roomId <= Constants.MAIN_PHASE_CONNECTION_POOL_SIZE; roomId++) {
            URI serverUri = new URI(String.format("%s/room%d", baseUri, roomId));
            ChatflowWebSocketClient connection = new ChatflowWebSocketClient(serverUri, response -> {
                // Queue response for asynchronous processing
                responseQueue.offer(response);
            });
            connection.connectBlocking();
            connections.put(roomId, connection);
        }
    }

    /**
     * Gets a connection by room ID.
     * @param roomId room ID (1 to MAIN_PHASE_CONNECTION_POOL_SIZE)
     * @return WebSocket client for the specified room
     */
    public ChatflowWebSocketClient getConnectionByRoomId(int roomId) {
        return connections.get(roomId);
    }

    /**
     * Gets the next available connection using round-robin distribution.
     * @return WebSocket client for sending messages
     * @deprecated Use getConnectionByRoomId(int) for room-specific routing
     */
    @Deprecated
    public ChatflowWebSocketClient getNextConnection() {
        // For backward compatibility, return first available connection
        return connections.values().iterator().next();
    }

    /**
     * Gets a specific connection by index.
     * @param index connection index (0 to pool size - 1)
     * @return WebSocket client at specified index
     * @deprecated Use getConnectionByRoomId(int) instead
     */
    @Deprecated
    public ChatflowWebSocketClient getConnection(int index) {
        // For backward compatibility, map index to roomId (index + 1)
        return connections.get(index + 1);
    }

    /**
     * Gets the total number of connections in the pool.
     * @return pool size
     */
    public int getPoolSize() {
        return connections.size();
    }

    /**
     * Checks if all connections are currently open.
     * @return true if all connections are healthy
     */
    public boolean areAllConnectionsOpen() {
        for (ChatflowWebSocketClient connection : connections.values()) {
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
        for (ChatflowWebSocketClient connection : connections.values()) {
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
        for (ChatflowWebSocketClient connection : connections.values()) {
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
        connection.reconnectBlocking();
        // Find the room ID for this connection
        Integer roomId = null;
        for (Map.Entry<Integer, ChatflowWebSocketClient> entry : connections.entrySet()) {
            if (entry.getValue() == connection) {
                roomId = entry.getKey();
                break;
            }
        }

        if (roomId == null) {
            return false; // Connection not found in pool
        }

        // Try reconnection with retries
        for (int attempt = 1; attempt <= Constants.CONNECTION_RECONNECT_ATTEMPTS; attempt++) {
            try {
                // Use the same room ID for reconnection (don't change room assignment)
                URI serverUri = new URI(String.format("%s/room%d", baseUri, roomId));

                // Create new connection
                ChatflowWebSocketClient newConnection = new ChatflowWebSocketClient(serverUri, response -> {
                    // Queue response for asynchronous processing
                    responseQueue.offer(response);
                });

                // Attempt to connect
                newConnection.connectBlocking();

                // Replace the old connection
                connections.put(roomId, newConnection);

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

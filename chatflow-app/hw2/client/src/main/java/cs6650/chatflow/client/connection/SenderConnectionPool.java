package cs6650.chatflow.client.connection;

import cs6650.chatflow.client.commons.Constants;
import com.google.gson.Gson;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple WebSocket connection pool for DELIVERY_ACK messages.
 * Maintains individual WebSocket connections for each room (1-20).
 * Each room has its own dedicated connection for sending acknowledgments.
 */
public class SenderConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(SenderConnectionPool.class);

    private final Map<String, SimpleWebSocketClient> connections;
    private final Gson gson = new Gson();
    private final String serverHost;
    private final int serverPort;
    private final String serverPath;

    /**
     * Creates a connection pool with 20 connections (one for each room).
     * Uses default configuration from Constants.
     */
    public SenderConnectionPool() {
        this(Constants.PRODUCER_SERVER_HOST, Constants.PRODUCER_SERVER_PORT, Constants.PRODUCER_SERVER_PATH);
    }

    /**
     * Creates a connection pool with 20 connections using specified server configuration.
     * @param serverHost The producer server hostname
     * @param serverPort The producer server port
     * @param serverPath The producer server context path
     */
    public SenderConnectionPool(String serverHost, int serverPort, String serverPath) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverPath = serverPath;
        this.connections = new ConcurrentHashMap<>();

        // Initialize 20 connections for rooms 1-20
        for (int roomId = 1; roomId <= 20; roomId++) {
            initializeConnection("room" + roomId);
        }
    }

    /**
     * Initialize a WebSocket connection for the specified room.
     */
    private void initializeConnection(String roomId) {
        try {
            // Connect to producer server for this specific room
            String wsUri = "ws://" + serverHost + ":" + serverPort + serverPath + "/" + roomId;
            URI uri = URI.create(wsUri);

            SimpleWebSocketClient client = new SimpleWebSocketClient(uri, roomId);
            boolean connected = client.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);

            if (connected && client.isOpen()) {
                connections.put(roomId, client);
                System.out.println("ACK connection established for " + roomId);
            } else {
                System.err.println("Failed to establish ACK connection for " + roomId);
            }

        } catch (Exception e) {
            System.err.println("Error initializing ACK connection for " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Gets the WebSocket connection for the specified room ID.
     * @param roomId room ID (e.g., "room1", "room2", ..., "room20")
     * @return WebSocket client for the room, or null if connection not available
     */
    public SimpleWebSocketClient getConnection(String roomId) {
        SimpleWebSocketClient client = connections.get(roomId);
        if (client == null || !client.isOpen()) {
            logger.warn("Connection for room {} is not available or closed", roomId);
            return null;
        }
        return client;
    }

    /**
     * Sends a DELIVERY_ACK message using the connection for the specified room.
     * @param message the message to send
     * @param roomId room ID for routing the acknowledgment
     * @return true if message was sent successfully
     */
    public boolean sendAckMessage(String message, String roomId) {
        try {
            SimpleWebSocketClient connection = getConnection(roomId);
            if (connection != null && connection.isOpen()) {
                connection.send(message);
                return true;
            } else {
                System.err.println("No open connection available for ACK to " + roomId);
                return false;
            }
        } catch (Exception e) {
            System.err.println("Error sending ACK to " + roomId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Sends a ChatMessage as DELIVERY_ACK using the connection for the message's room.
     * @param ackMessage the acknowledgment message to send
     * @return true if message was sent successfully
     */
    public boolean sendAckMessage(SimpleChatMessage ackMessage) {
        return sendAckMessage(gson.toJson(ackMessage), ackMessage.getRoomId());
    }

    /**
     * Gets the number of active connections.
     * @return count of open connections
     */
    public int getActiveConnectionCount() {
        int count = 0;
        for (SimpleWebSocketClient client : connections.values()) {
            if (client.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Closes all connections in the pool.
     */
    public void closeAll() {
        for (SimpleWebSocketClient client : connections.values()) {
            if (client != null) {
                client.close();
            }
        }
        System.out.println("All ACK connections closed");
    }

    /**
     * Simple ChatMessage class for DELIVERY_ACK messages.
     */
    public static class SimpleChatMessage {
        private String messageId;
        private String userId;
        private String username;
        private String message;
        private String roomId;
        private String messageType;
        private String timestamp;

        public SimpleChatMessage() {}

        // Getters and setters
        public String getMessageId() { return messageId; }
        public void setMessageId(String messageId) { this.messageId = messageId; }

        public String getUserId() { return userId; }
        public void setUserId(String userId) { this.userId = userId; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getRoomId() { return roomId; }
        public void setRoomId(String roomId) { this.roomId = roomId; }

        public String getMessageType() { return messageType; }
        public void setMessageType(String messageType) { this.messageType = messageType; }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }
}

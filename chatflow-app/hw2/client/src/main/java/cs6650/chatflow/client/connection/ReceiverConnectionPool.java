package cs6650.chatflow.client.connection;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple connection pool for managing WebSocket connections to the consumer server.
 * Each connection is dedicated to a specific room and receives messages for that room.
 * Similar to WebSocketConnectionPool but for consumer server connections (receiving messages).
 */
public class ReceiverConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(ReceiverConnectionPool.class);

    private final Map<String, SimpleConsumerWebSocketClient> roomConnections = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<ChatMessage>>> messageListeners = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().create();
    private final String serverHost;
    private final int serverPort;
    private final String serverPath;

    /**
     * Creates a connection pool with connections for all rooms.
     * Uses default configuration from Constants.
     */
    public ReceiverConnectionPool() {
        this(Constants.CONSUMER_SERVER_HOST, Constants.CONSUMER_SERVER_PORT, Constants.CONSUMER_SERVER_PATH);
    }

    /**
     * Creates a connection pool with connections for all rooms using specified server configuration.
     * @param serverHost The consumer server hostname
     * @param serverPort The consumer server port
     * @param serverPath The consumer server context path
     */
    public ReceiverConnectionPool(String serverHost, int serverPort, String serverPath) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverPath = serverPath;

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
            // Connect to consumer server for this specific room
            String wsUri = "ws://" + serverHost + ":" + serverPort + serverPath + roomId;
            URI uri = URI.create(wsUri);

            SimpleConsumerWebSocketClient client = new SimpleConsumerWebSocketClient(uri, roomId);
            boolean connected = client.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);

            if (connected && client.isOpen()) {
                roomConnections.put(roomId, client);
                messageListeners.put(roomId, new CopyOnWriteArrayList<>());
                System.out.println("Consumer connection established for " + roomId);
            } else {
                System.err.println("Failed to establish consumer connection for " + roomId);
            }

        } catch (Exception e) {
            System.err.println("Error initializing consumer connection for " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Gets the WebSocket connection for the specified room ID.
     * @param roomId room ID (e.g., "room1", "room2", ..., "room20")
     * @return WebSocket client for the room, or null if connection not available
     */
    public SimpleConsumerWebSocketClient getConnection(String roomId) {
        SimpleConsumerWebSocketClient client = roomConnections.get(roomId);
        if (client == null || !client.isOpen()) {
            logger.warn("Consumer connection for room {} is not available or closed", roomId);
            return null;
        }
        return client;
    }

    /**
     * Register a message listener for a specific room.
     * @param roomId room ID (e.g., "room1")
     * @param listener callback function to handle messages
     */
    public void addMessageListener(String roomId, Consumer<ChatMessage> listener) {
        messageListeners.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(listener);
        logger.debug("Added message listener for room {}", roomId);
    }

    /**
     * Remove a message listener for a specific room.
     * @param roomId room ID (e.g., "room1")
     * @param listener callback function to remove
     */
    public void removeMessageListener(String roomId, Consumer<ChatMessage> listener) {
        CopyOnWriteArrayList<Consumer<ChatMessage>> listeners = messageListeners.get(roomId);
        if (listeners != null) {
            listeners.remove(listener);
            logger.debug("Removed message listener for room {}", roomId);
        }
    }

    /**
     * Get the number of active connections.
     * @return count of open connections
     */
    public int getActiveConnectionCount() {
        int count = 0;
        for (SimpleConsumerWebSocketClient client : roomConnections.values()) {
            if (client != null && client.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Handle incoming messages from consumer server and notify listeners.
     */
    private void handleMessage(String messageJson, String roomId) {
        try {
            ChatMessage message = parseChatMessage(messageJson);
            if (message != null) {
                // Set the room ID from the connection
                message.setRoomId(roomId);

                // Notify all listeners for this room
                CopyOnWriteArrayList<Consumer<ChatMessage>> listeners = messageListeners.get(roomId);
                if (listeners != null) {
                    listeners.forEach(listener -> {
                        try {
                            listener.accept(message);
                        } catch (Exception e) {
                            logger.error("Error in message listener for room {}: {}", roomId, e.getMessage(), e);
                        }
                    });
                }
            }
        } catch (Exception e) {
            logger.error("Error parsing message from consumer server for room {}: {}", roomId, e.getMessage(), e);
        }
    }

    /**
     * Parse incoming JSON message to ChatMessage object.
     */
    private ChatMessage parseChatMessage(String messageJson) {
        try {
            ChatMessage chatMessage = gson.fromJson(messageJson, ChatMessage.class);
            return chatMessage;
        } catch (Exception e) {
            logger.error("Failed to parse chat message: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Closes all connections in the pool.
     */
    public void closeAll() {
        for (SimpleConsumerWebSocketClient client : roomConnections.values()) {
            if (client != null) {
                client.close();
            }
        }
        roomConnections.clear();
        messageListeners.clear();
        System.out.println("All consumer connections closed");
    }

    /**
     * Simple WebSocket client for consumer server connections (receiving messages).
     */
    public class SimpleConsumerWebSocketClient extends WebSocketClient {

        private final String roomId;

        public SimpleConsumerWebSocketClient(URI serverUri, String roomId) {
            super(serverUri);
            this.roomId = roomId;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            logger.info("Consumer connection for room {} opened", roomId);
        }

        @Override
        public void onMessage(String message) {
            handleMessage(message, roomId);
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            logger.info("Consumer connection for room {} closed: {}", roomId, reason);
        }

        @Override
        public void onError(Exception ex) {
            logger.error("Consumer connection for room {} error: {}", roomId, ex.getMessage(), ex);
        }
    }
}

package cs6650.chatflow.client.connection;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * High-throughput connection pool for managing WebSocket connections to the consumer server.
 * Supports multiple connections per room (default 5) for parallel message receiving.
 *
 * Architecture:
 * - 20 rooms × 5 connections per room = 100 total connections
 * - Each connection has multiple listeners (receiver threads)
 * - Non-blocking message dispatch to listeners
 *
 * This pool is read-only - connections only receive messages, never send.
 */
public class ReceiverConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(ReceiverConnectionPool.class);

    private final Map<String, List<ConsumerWebSocketClient>> roomConnections = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<Consumer<ChatMessage>>> messageListeners = new ConcurrentHashMap<>();
    private final Gson gson = new GsonBuilder().create();
    private final String serverHost;
    private final int serverPort;
    private final String serverPath;
    private final int connectionsPerRoom;

    /**
     * Creates a connection pool with default 1 connection per room (backward compatible).
     */
    public ReceiverConnectionPool() {
        this(Constants.CONSUMER_SERVER_HOST, Constants.CONSUMER_SERVER_PORT,
                Constants.CONSUMER_SERVER_PATH, 1);
    }

    /**
     * Creates a connection pool using specified server configuration.
     *
     * @param serverHost The consumer server hostname
     * @param serverPort The consumer server port
     * @param serverPath The consumer server context path
     */
    public ReceiverConnectionPool(String serverHost, int serverPort, String serverPath) {
        this(serverHost, serverPort, serverPath, 1);
    }

    /**
     * Creates a connection pool with multiple connections per room.
     *
     * @param serverHost The consumer server hostname
     * @param serverPort The consumer server port
     * @param serverPath The consumer server context path
     * @param connectionsPerRoom Number of connections per room (e.g., 5 for 100 total connections)
     */
    public ReceiverConnectionPool(String serverHost, int serverPort, String serverPath, int connectionsPerRoom) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverPath = serverPath;
        this.connectionsPerRoom = connectionsPerRoom;

        // Initialize multiple connections for each room (room1-room20)
        for (int roomId = 1; roomId <= 20; roomId++) {
            String roomIdStr = "room" + roomId;
            initializeConnectionsForRoom(roomIdStr);
        }
    }

    /**
     * Initialize multiple WebSocket connections for the specified room.
     */
    private void initializeConnectionsForRoom(String roomId) {
        List<ConsumerWebSocketClient> connections = new CopyOnWriteArrayList<>();

        for (int i = 0; i < connectionsPerRoom; i++) {
            try {
                String wsUri = "ws://" + serverHost + ":" + serverPort + serverPath + roomId;
                URI uri = URI.create(wsUri);

                // Create client with message handler callback
                ConsumerWebSocketClient client = new ConsumerWebSocketClient(
                        uri,
                        roomId,
                        this::handleMessage  // Pass handleMessage as callback
                );

                boolean connected = client.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);

                if (connected && client.isOpen()) {
                    connections.add(client);
                    logger.info("Consumer connection {} established for {} (total: {})",
                            i + 1, roomId, connections.size());
                } else {
                    logger.error("Failed to establish consumer connection {} for {}", i + 1, roomId);
                }

            } catch (Exception e) {
                logger.error("Error initializing consumer connection for {}: {}", roomId, e.getMessage());
            }
        }

        roomConnections.put(roomId, connections);
        messageListeners.put(roomId, new CopyOnWriteArrayList<>());

        logger.info("Initialized {} consumer connections for room {}", connections.size(), roomId);
    }

    /**
     * Gets all WebSocket connections for the specified room.
     *
     * @param roomId room ID (e.g., "room1", "room2", ..., "room20")
     * @return List of WebSocket clients for the room
     */
    public List<ConsumerWebSocketClient> getConnections(String roomId) {
        return roomConnections.get(roomId);
    }

    /**
     * Gets the first available connection for the specified room (backward compatibility).
     *
     * @param roomId room ID
     * @return First WebSocket client for the room, or null if not available
     */
    public ConsumerWebSocketClient getConnection(String roomId) {
        List<ConsumerWebSocketClient> connections = roomConnections.get(roomId);
        if (connections == null || connections.isEmpty()) {
            logger.warn("Consumer connection for room {} is not available", roomId);
            return null;
        }

        // Return first open connection
        for (ConsumerWebSocketClient client : connections) {
            if (client != null && client.isOpen()) {
                return client;
            }
        }

        logger.warn("No open consumer connections for room {}", roomId);
        return null;
    }

    /**
     * Register a message listener for a specific room.
     * The listener will be called for ALL connections in that room.
     *
     * @param roomId room ID (e.g., "room1")
     * @param listener callback function to handle messages
     */
    public void addMessageListener(String roomId, Consumer<ChatMessage> listener) {
        messageListeners.computeIfAbsent(roomId, k -> new CopyOnWriteArrayList<>()).add(listener);
        logger.debug("Added message listener for room {} (total listeners: {})",
                roomId, messageListeners.get(roomId).size());
    }

    /**
     * Remove a message listener for a specific room.
     *
     * @param roomId room ID (e.g., "room1")
     * @param listener callback function to remove
     */
    public void removeMessageListener(String roomId, Consumer<ChatMessage> listener) {
        CopyOnWriteArrayList<Consumer<ChatMessage>> listeners = messageListeners.get(roomId);
        if (listeners != null) {
            listeners.remove(listener);
            logger.debug("Removed message listener for room {} (remaining listeners: {})",
                    roomId, listeners.size());
        }
    }

    /**
     * Get the number of active connections for a specific room.
     *
     * @param roomId room ID
     * @return number of open connections for the room
     */
    public int getActiveConnectionCount(String roomId) {
        List<ConsumerWebSocketClient> connections = roomConnections.get(roomId);
        if (connections == null) {
            return 0;
        }

        int count = 0;
        for (ConsumerWebSocketClient client : connections) {
            if (client != null && client.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Get the total number of active connections across all rooms.
     *
     * @return total count of open connections
     */
    public int getTotalActiveConnectionCount() {
        int totalCount = 0;
        for (String roomId : roomConnections.keySet()) {
            totalCount += getActiveConnectionCount(roomId);
        }
        return totalCount;
    }

    /**
     * Handle incoming messages from consumer server and notify listeners.
     * This is called by each ConsumerWebSocketClient's onMessage handler via callback.
     *
     * @param messageJson JSON string of the message
     * @param roomId room ID the message was received on
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
     *
     * @param messageJson JSON string
     * @return ChatMessage object, or null if parsing fails
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
        int totalClosed = 0;
        for (List<ConsumerWebSocketClient> connections : roomConnections.values()) {
            for (ConsumerWebSocketClient client : connections) {
                if (client != null) {
                    client.close();
                    totalClosed++;
                }
            }
        }
        roomConnections.clear();
        messageListeners.clear();
        logger.info("Closed {} consumer connections", totalClosed);
    }
}

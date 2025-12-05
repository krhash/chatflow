package cs6650.chatflow.client.connection;

import cs6650.chatflow.client.commons.Constants;
import cs6650.chatflow.client.model.ChatMessage;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket connection pool for sending messages to producer server.
 * Supports multiple connections per room for high concurrency.
 *
 * Used for sending all message types: JOIN, TEXT, LEAVE, and ACK messages.
 * Connections receive ACK_CONFIRMATION messages directly from producer.
 */
public class ProducerConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(ProducerConnectionPool.class);

    private static final int CONNECTIONS_PER_ROOM = 2;

    private final Map<String, List<ProducerWebSocketClient>> connections;
    private final Map<String, AtomicInteger> connectionIndex;
    private final String serverHost;
    private final int serverPort;
    private final String serverPath;
    private final Consumer<ChatMessage> ackConfirmationHandler;  // NEW

    /**
     * Creates a connection pool with default configuration.
     */
    public ProducerConnectionPool() {
        this(Constants.PRODUCER_SERVER_HOST, Constants.PRODUCER_SERVER_PORT,
                Constants.PRODUCER_SERVER_PATH, null);
    }

    /**
     * Creates a connection pool with specified server configuration and ACK handler.
     *
     * @param serverHost The producer server hostname
     * @param serverPort The producer server port
     * @param serverPath The producer server context path
     * @param ackConfirmationHandler Handler for ACK_CONFIRMATION messages
     */
    public ProducerConnectionPool(String serverHost, int serverPort, String serverPath,
                                  Consumer<ChatMessage> ackConfirmationHandler) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverPath = serverPath;
        this.ackConfirmationHandler = ackConfirmationHandler;
        this.connections = new ConcurrentHashMap<>();
        this.connectionIndex = new ConcurrentHashMap<>();

        // Initialize multiple connections for each room (room1-room20)
        for (int roomId = 1; roomId <= 20; roomId++) {
            String roomIdStr = "room" + roomId;
            List<ProducerWebSocketClient> roomConnections = new CopyOnWriteArrayList<>();
            AtomicInteger index = new AtomicInteger(0);

            for (int i = 0; i < CONNECTIONS_PER_ROOM; i++) {
                initializeConnection(roomIdStr, roomConnections);
            }

            connections.put(roomIdStr, roomConnections);
            connectionIndex.put(roomIdStr, index);
        }
    }

    /**
     * Initialize a WebSocket connection for the specified room.
     */
    private void initializeConnection(String roomId, List<ProducerWebSocketClient> roomConnections) {
        try {
            String wsUri = "ws://" + serverHost + ":" + serverPort + serverPath + "/" + roomId;
            URI uri = URI.create(wsUri);

            // Pass ACK confirmation handler to client
            ProducerWebSocketClient client = new ProducerWebSocketClient(uri, roomId, ackConfirmationHandler);
            boolean connected = client.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);

            if (connected && client.isOpen()) {
                roomConnections.add(client);
                logger.info("Producer connection established for {} (total: {})",
                        roomId, roomConnections.size());
            } else {
                logger.error("Failed to establish producer connection for {}", roomId);
            }

        } catch (Exception e) {
            logger.error("Error initializing producer connection for {}: {}", roomId, e.getMessage());
        }
    }

    /**
     * Gets an available WebSocket connection for the specified room.
     * Uses round-robin distribution among available connections for the room.
     */
    public ProducerWebSocketClient getConnection(String roomId) {
        List<ProducerWebSocketClient> roomConnections = connections.get(roomId);
        if (roomConnections == null || roomConnections.isEmpty()) {
            logger.warn("No connections available for room {}", roomId);
            return null;
        }

        // Round-robin selection
        int maxAttempts = roomConnections.size();
        int startIndex = connectionIndex.get(roomId).getAndIncrement() % roomConnections.size();

        for (int i = 0; i < maxAttempts; i++) {
            int index = (startIndex + i) % roomConnections.size();
            ProducerWebSocketClient client = roomConnections.get(index);
            if (client != null && client.isOpen()) {
                return client;
            }
        }

        logger.error("No open connections available for room {}", roomId);
        return null;
    }

    /**
     * Gets the number of active connections for a specific room.
     */
    public int getActiveConnectionCount(String roomId) {
        List<ProducerWebSocketClient> roomConnections = connections.get(roomId);
        if (roomConnections == null) {
            return 0;
        }

        int count = 0;
        for (ProducerWebSocketClient client : roomConnections) {
            if (client != null && client.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gets the total number of active connections across all rooms.
     */
    public int getTotalActiveConnectionCount() {
        int totalCount = 0;
        for (Map.Entry<String, List<ProducerWebSocketClient>> entry : connections.entrySet()) {
            totalCount += getActiveConnectionCount(entry.getKey());
        }
        return totalCount;
    }

    /**
     * Closes all connections in the pool.
     */
    public void closeAll() {
        int totalClosed = 0;
        for (List<ProducerWebSocketClient> roomConnections : connections.values()) {
            for (ProducerWebSocketClient client : roomConnections) {
                if (client != null) {
                    client.close();
                    totalClosed++;
                }
            }
        }
        logger.info("Closed {} producer connections", totalClosed);
    }
}

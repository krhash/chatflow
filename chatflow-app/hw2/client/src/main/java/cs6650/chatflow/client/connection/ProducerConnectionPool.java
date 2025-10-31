package cs6650.chatflow.client.connection;

import cs6650.chatflow.client.commons.Constants;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * WebSocket connection pool for sending chat messages to producer server.
 * Supports multiple connections per room for high concurrency.
 */
public class ProducerConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(ProducerConnectionPool.class);

    private static final int CONNECTIONS_PER_ROOM = 5; // Multiple connections per room

    private final Map<String, List<SimpleWebSocketClient>> connections;
    private final Map<String, AtomicInteger> connectionIndex;
    private final String serverHost;
    private final int serverPort;
    private final String serverPath;

    /**
     * Creates a connection pool with multiple connections for each room.
     * Uses default configuration from Constants.
     */
    public ProducerConnectionPool() {
        this(Constants.PRODUCER_SERVER_HOST, Constants.PRODUCER_SERVER_PORT, Constants.PRODUCER_SERVER_PATH);
    }

    /**
     * Creates a connection pool with multiple connections for each room using specified server configuration.
     * @param serverHost The producer server hostname
     * @param serverPort The producer server port
     * @param serverPath The producer server context path
     */
    public ProducerConnectionPool(String serverHost, int serverPort, String serverPath) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverPath = serverPath;
        this.connections = new ConcurrentHashMap<>();
        this.connectionIndex = new ConcurrentHashMap<>();

        // Initialize multiple connections for each room (room1-room20)
        for (int roomId = 1; roomId <= 20; roomId++) {
            String roomIdStr = "room" + roomId;
            List<SimpleWebSocketClient> roomConnections = new CopyOnWriteArrayList<>();
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
    private void initializeConnection(String roomId, List<SimpleWebSocketClient> roomConnections) {
        try {
            // Connect to producer server for this specific room
            String wsUri = "ws://" + serverHost + ":" + serverPort + serverPath + "/" + roomId;
            URI uri = URI.create(wsUri);

            SimpleWebSocketClient client = new SimpleWebSocketClient(uri, roomId);
            boolean connected = client.connectBlocking(10, java.util.concurrent.TimeUnit.SECONDS);

            if (connected && client.isOpen()) {
                roomConnections.add(client);
                System.out.println("Producer connection established for " + roomId +
                                 " (total: " + roomConnections.size() + ")");
            } else {
                System.err.println("Failed to establish producer connection for " + roomId);
            }

        } catch (Exception e) {
            System.err.println("Error initializing producer connection for " + roomId + ": " + e.getMessage());
        }
    }

    /**
     * Gets an available WebSocket connection for the specified room.
     * Uses round-robin distribution among available connections for the room.
     * @param roomId room ID (e.g., "room1", "room2", ..., "room20")
     * @return A WebSocket client for the room, or null if no connection available
     */
    public SimpleWebSocketClient getConnection(String roomId) {
        List<SimpleWebSocketClient> roomConnections = connections.get(roomId);
        if (roomConnections == null || roomConnections.isEmpty()) {
            logger.warn("No connections available for room {}", roomId);
            return null;
        }

        // Find an available connection (round-robin)
        int maxAttempts = roomConnections.size();
        int startIndex = connectionIndex.get(roomId).getAndIncrement() % roomConnections.size();

        for (int i = 0; i < maxAttempts; i++) {
            int index = (startIndex + i) % roomConnections.size();
            SimpleWebSocketClient client = roomConnections.get(index);
            if (client != null && client.isOpen()) {
                return client;
            }
        }

        logger.error("No open connections available for room {}", roomId);
        return null;
    }

    /**
     * Gets the number of active connections for a specific room.
     * @param roomId room ID
     * @return number of open connections for the room
     */
    public int getActiveConnectionCount(String roomId) {
        List<SimpleWebSocketClient> roomConnections = connections.get(roomId);
        if (roomConnections == null) {
            return 0;
        }

        int count = 0;
        for (SimpleWebSocketClient client : roomConnections) {
            if (client != null && client.isOpen()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Gets the total number of active connections across all rooms.
     * @return total count of open connections
     */
    public int getTotalActiveConnectionCount() {
        int totalCount = 0;
        for (Map.Entry<String, List<SimpleWebSocketClient>> entry : connections.entrySet()) {
            totalCount += getActiveConnectionCount(entry.getKey());
        }
        return totalCount;
    }

    /**
     * Closes all connections in the pool.
     */
    public void closeAll() {
        int totalClosed = 0;
        for (List<SimpleWebSocketClient> roomConnections : connections.values()) {
            for (SimpleWebSocketClient client : roomConnections) {
                if (client != null) {
                    client.close();
                    totalClosed++;
                }
            }
        }
        System.out.println("Closed " + totalClosed + " producer connections");
    }
}

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
 * WebSocket connection pool for sending messages to producer server.
 * Used for sending all message types: JOIN, TEXT, LEAVE, and ACK.
 */
public class ProducerConnectionPool {

    private static final Logger logger = LoggerFactory.getLogger(ProducerConnectionPool.class);

    private static final int CONNECTIONS_PER_ROOM = 2;

    private final Map<String, List<ProducerWebSocketClient>> connections;
    private final Map<String, AtomicInteger> connectionIndex;
    private final String serverHost;
    private final int serverPort;
    private final String serverPath;

    public ProducerConnectionPool() {
        this(Constants.PRODUCER_SERVER_HOST, Constants.PRODUCER_SERVER_PORT, Constants.PRODUCER_SERVER_PATH);
    }

    public ProducerConnectionPool(String serverHost, int serverPort, String serverPath) {
        this.serverHost = serverHost;
        this.serverPort = serverPort;
        this.serverPath = serverPath;
        this.connections = new ConcurrentHashMap<>();
        this.connectionIndex = new ConcurrentHashMap<>();

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

    private void initializeConnection(String roomId, List<ProducerWebSocketClient> roomConnections) {
        try {
            String wsUri = "ws://" + serverHost + ":" + serverPort + serverPath + "/" + roomId;
            URI uri = URI.create(wsUri);

            ProducerWebSocketClient client = new ProducerWebSocketClient(uri, roomId);
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

    public ProducerWebSocketClient getConnection(String roomId) {
        List<ProducerWebSocketClient> roomConnections = connections.get(roomId);
        if (roomConnections == null || roomConnections.isEmpty()) {
            logger.warn("No connections available for room {}", roomId);
            return null;
        }

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

    public int getActiveConnectionCount(String roomId) {
        List<ProducerWebSocketClient> roomConnections = connections.get(roomId);
        if (roomConnections == null) return 0;

        int count = 0;
        for (ProducerWebSocketClient client : roomConnections) {
            if (client != null && client.isOpen()) count++;
        }
        return count;
    }

    public int getTotalActiveConnectionCount() {
        int totalCount = 0;
        for (Map.Entry<String, List<ProducerWebSocketClient>> entry : connections.entrySet()) {
            totalCount += getActiveConnectionCount(entry.getKey());
        }
        return totalCount;
    }

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

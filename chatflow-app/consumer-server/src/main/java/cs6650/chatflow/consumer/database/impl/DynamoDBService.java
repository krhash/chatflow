package cs6650.chatflow.consumer.database.impl;

import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.*;
import com.amazonaws.services.dynamodbv2.document.utils.ValueMap;
import com.amazonaws.services.dynamodbv2.model.*;
import cs6650.chatflow.consumer.database.DatabaseConfig;
import cs6650.chatflow.consumer.database.DatabaseService;
import cs6650.chatflow.consumer.database.batch.DynamoDBBatchWriter;
import cs6650.chatflow.consumer.database.batch.MessageBuffer;
import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import cs6650.chatflow.consumer.database.model.DatabaseMetrics;
import cs6650.chatflow.consumer.database.util.ThroughputTracker;
import cs6650.chatflow.consumer.model.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * DynamoDB implementation of DatabaseService
 * Uses MessageBuffer for batching and thread pool management
 */
public class DynamoDBService implements DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDBService.class);

    // AWS Clients
    private AmazonDynamoDB client;
    private DynamoDB dynamoDB;
    private Table table;

    // Configuration
    private final String tableName;
    private final String roomIndexName;
    private final String userIndexName;
    private final String typeIndexName;

    // Batch Processing - Delegated to MessageBuffer
    private MessageBuffer messageBuffer;

    // Metrics
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong failedReads = new AtomicLong(0);
    private final List<Long> readLatencies = new ArrayList<>();

    private ThroughputTracker throughputTracker = new ThroughputTracker();

    public DynamoDBService() {
        this.tableName = DatabaseConfig.getDynamoDBTableName();
        this.roomIndexName = DatabaseConfig.getDynamoDBRoomIndex();
        this.userIndexName = DatabaseConfig.getDynamoDBUserIndex();
        this.typeIndexName = DatabaseConfig.getDynamoDBTypeIndex();
    }

    @Override
    public void initialize() {
        logger.info("Initializing DynamoDB service...");

        try {
            // Validate configuration
            DatabaseConfig.validateConfiguration();

            // Log configuration
            DatabaseConfig.logConfiguration();

            // Create DynamoDB client
            this.client = AmazonDynamoDBClientBuilder.standard()
                    .withRegion(DatabaseConfig.getAwsRegion())
                    .withCredentials(new DefaultAWSCredentialsProviderChain())
                    .build();

            this.dynamoDB = new DynamoDB(client);
            this.table = dynamoDB.getTable(tableName);

            // Verify table exists
            TableDescription tableDesc = table.describe();
            logger.info("Connected to DynamoDB table: {} (status: {})",
                    tableName, tableDesc.getTableStatus());

            // Create batch writer
            DynamoDBBatchWriter batchWriter = new DynamoDBBatchWriter(
                    dynamoDB,
                    tableName,
                    DatabaseConfig.getMaxRetries(),
                    DatabaseConfig.getRetryBaseDelayMs()
            );

            ThroughputTracker tracker = new ThroughputTracker();
            this.throughputTracker = tracker;


            // Initialize message buffer with configuration
            this.messageBuffer = new MessageBuffer(
                    DatabaseConfig.getBatchSize(),
                    DatabaseConfig.getFlushIntervalMs(),
                    DatabaseConfig.getMaxQueueSize(),
                    DatabaseConfig.getCorePoolSize(),
                    DatabaseConfig.getMaxPoolSize(),
                    DatabaseConfig.getTaskQueueSize(),
                    batchWriter,
                    tracker
            );

            // Start message buffer
            messageBuffer.start();

            logger.info("DynamoDB service initialized successfully");

        } catch (Exception e) {
            logger.error("Failed to initialize DynamoDB service", e);
            throw new RuntimeException("DynamoDB initialization failed", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down DynamoDB service...");

        try {
            // Stop message buffer (flushes pending writes)
            if (messageBuffer != null) {
                messageBuffer.stop();
            }

            // Close DynamoDB connection
            if (dynamoDB != null) {
                dynamoDB.shutdown();
            }

            logger.info("DynamoDB service shut down successfully");
            logger.info("Final metrics: {}", getMetrics());

        } catch (Exception e) {
            logger.error("Error during shutdown", e);
        }
    }

    @Override
    public boolean isHealthy() {
        try {
            if (client == null || table == null || messageBuffer == null) {
                return false;
            }

            // Quick health check - describe table
            table.describe();

            // Check buffer health
            int queueSize = messageBuffer.getQueueSize();
            if (queueSize > DatabaseConfig.getMaxQueueSize() * 0.9) {
                logger.warn("Message queue nearly full: {}/{}",
                        queueSize, DatabaseConfig.getMaxQueueSize());
            }

            return true;
        } catch (Exception e) {
            logger.error("Health check failed", e);
            return false;
        }
    }

    // ========== Write Operations ==========

    @Override
    public void writeMessage(ChatEvent event, String roomId) {
        try {
            // Convert ChatEvent to DatabaseMessage
            DatabaseMessage dbMessage = DatabaseMessage.fromChatEvent(event, roomId);

            // Add to buffer (non-blocking)
            boolean added = messageBuffer.add(dbMessage);

            if (!added) {
                logger.debug("Failed to add message to buffer: {}", event.getMessageId());
            } else {
                throughputTracker.recordWrite(1);
            }

        } catch (Exception e) {
            logger.error("Failed to queue message: {}", event.getMessageId(), e);
        }
    }

    @Override
    public void writeBatch(List<ChatEvent> events, String roomId) {
        for (ChatEvent event : events) {
            writeMessage(event, roomId);
        }
    }

    @Override
    public int flushPendingWrites() {
        if (messageBuffer == null) {
            return 0;
        }
        return messageBuffer.flush();
    }

    // ========== Core Query Operations ==========

    @Override
    public List<ChatEvent> getMessagesInTimeRange(int roomId, long startTime, long endTime) {
        long queryStart = System.currentTimeMillis();

        try {
            Index index = table.getIndex(roomIndexName);

            QuerySpec spec = new QuerySpec()
                    .withHashKey("room_id", roomId)
                    .withRangeKeyCondition(
                            new RangeKeyCondition("timestamp").between(startTime, endTime)
                    )
                    .withScanIndexForward(false) // Descending order
                    .withMaxResultSize(1000);

            ItemCollection<QueryOutcome> items = index.query(spec);

            List<ChatEvent> messages = new ArrayList<>();
            for (Item item : items) {
                messages.add(itemToChatEvent(item));
            }

            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            logger.debug("Query 1: Retrieved {} messages for room {} in {}ms",
                    messages.size(), roomId, duration);

            return messages;

        } catch (Exception e) {
            logger.error("Query 1 failed for room {}", roomId, e);
            failedReads.incrementAndGet();
            return Collections.emptyList();
        }
    }

    @Override
    public List<ChatEvent> getUserMessageHistory(String userId, long startTime, long endTime) {
        long queryStart = System.currentTimeMillis();

        try {
            Index index = table.getIndex(userIndexName);

            QuerySpec spec = new QuerySpec()
                    .withHashKey("user_id", userId)
                    .withRangeKeyCondition(
                            new RangeKeyCondition("timestamp").between(startTime, endTime)
                    )
                    .withScanIndexForward(false);

            ItemCollection<QueryOutcome> items = index.query(spec);

            List<ChatEvent> messages = new ArrayList<>();
            for (Item item : items) {
                messages.add(itemToChatEvent(item));
            }

            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            logger.debug("Query 2: Retrieved {} messages for user {} in {}ms",
                    messages.size(), userId, duration);

            return messages;

        } catch (Exception e) {
            logger.error("Query 2 failed for user {}", userId, e);
            failedReads.incrementAndGet();
            return Collections.emptyList();
        }
    }

    @Override
    public int countActiveUsers(long startTime, long endTime) {
        long queryStart = System.currentTimeMillis();

        try {
            ScanSpec spec = new ScanSpec()
                    .withFilterExpression("#ts BETWEEN :start AND :end")
                    .withNameMap(Collections.singletonMap("#ts", "timestamp"))
                    .withValueMap(new ValueMap()
                            .withLong(":start", startTime)
                            .withLong(":end", endTime));

            ItemCollection<ScanOutcome> items = table.scan(spec);

            Set<String> uniqueUsers = new HashSet<>();
            for (Item item : items) {
                uniqueUsers.add(item.getString("user_id"));
            }

            int count = uniqueUsers.size();

            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            logger.debug("Query 3: Counted {} active users in {}ms", count, duration);

            return count;

        } catch (Exception e) {
            logger.error("Query 3 failed", e);
            failedReads.incrementAndGet();
            return 0;
        }
    }

    @Override
    public Map<Integer, Long> getUserRooms(String userId) {
        long queryStart = System.currentTimeMillis();

        try {
            Index index = table.getIndex(userIndexName);

            QuerySpec spec = new QuerySpec()
                    .withHashKey("user_id", userId)
                    .withProjectionExpression("room_id, #ts")
                    .withNameMap(Collections.singletonMap("#ts", "timestamp"));

            ItemCollection<QueryOutcome> items = index.query(spec);

            Map<Integer, Long> roomActivity = new HashMap<>();
            for (Item item : items) {
                int roomId = item.getInt("room_id");
                long timestamp = item.getLong("timestamp");

                // Keep the latest timestamp for each room
                roomActivity.merge(roomId, timestamp, Math::max);
            }

            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            logger.debug("Query 4: Found {} rooms for user {} in {}ms",
                    roomActivity.size(), userId, duration);

            return roomActivity;

        } catch (Exception e) {
            logger.error("Query 4 failed for user {}", userId, e);
            failedReads.incrementAndGet();
            return Collections.emptyMap();
        }
    }

    // ========== Analytics Operations ==========

    @Override
    public Map<String, Long> getMessageDistribution(long startTime, long endTime, String granularity) {
        try {
            ScanSpec spec = new ScanSpec()
                    .withFilterExpression("#ts BETWEEN :start AND :end")
                    .withNameMap(Collections.singletonMap("#ts", "timestamp"))
                    .withValueMap(new ValueMap()
                            .withLong(":start", startTime)
                            .withLong(":end", endTime))
                    .withProjectionExpression("#ts");

            ItemCollection<ScanOutcome> items = table.scan(spec);

            long divisor = "second".equals(granularity) ? 1000 : 60000;
            Map<Long, Long> distribution = new HashMap<>();

            for (Item item : items) {
                long ts = item.getLong("timestamp");
                long bucket = (ts / divisor) * divisor;
                distribution.merge(bucket, 1L, Long::sum);
            }

            // Convert to readable format
            Map<String, Long> result = new TreeMap<>();
            distribution.forEach((bucket, count) -> {
                result.put(new Date(bucket).toString(), count);
            });

            totalReads.incrementAndGet();
            return result;

        } catch (Exception e) {
            logger.error("Message distribution query failed", e);
            failedReads.incrementAndGet();
            return Collections.emptyMap();
        }
    }

    @Override
    public List<Map.Entry<String, Integer>> getTopActiveUsers(int limit) {
        try {
            ScanSpec spec = new ScanSpec()
                    .withProjectionExpression("user_id");

            ItemCollection<ScanOutcome> items = table.scan(spec);

            Map<String, Integer> userCounts = new HashMap<>();
            for (Item item : items) {
                String userId = item.getString("user_id");
                userCounts.merge(userId, 1, Integer::sum);
            }

            List<Map.Entry<String, Integer>> topUsers = userCounts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .collect(Collectors.toList());

            totalReads.incrementAndGet();
            logger.debug("Top {} users retrieved", limit);

            return topUsers;

        } catch (Exception e) {
            logger.error("Top users query failed", e);
            failedReads.incrementAndGet();
            return Collections.emptyList();
        }
    }

    @Override
    public List<Map.Entry<Integer, Integer>> getTopActiveRooms(int limit) {
        try {
            ScanSpec spec = new ScanSpec()
                    .withProjectionExpression("room_id");

            ItemCollection<ScanOutcome> items = table.scan(spec);

            Map<Integer, Integer> roomCounts = new HashMap<>();
            for (Item item : items) {
                int roomId = item.getInt("room_id");
                roomCounts.merge(roomId, 1, Integer::sum);
            }

            List<Map.Entry<Integer, Integer>> topRooms = roomCounts.entrySet().stream()
                    .sorted(Map.Entry.<Integer, Integer>comparingByValue().reversed())
                    .limit(limit)
                    .collect(Collectors.toList());

            totalReads.incrementAndGet();
            logger.debug("Top {} rooms retrieved", limit);

            return topRooms;

        } catch (Exception e) {
            logger.error("Top rooms query failed", e);
            failedReads.incrementAndGet();
            return Collections.emptyList();
        }
    }

    @Override
    public Map<String, Object> getUserParticipationPattern(String userId) {
        try {
            List<ChatEvent> userMessages = getUserMessageHistory(userId, 0, Long.MAX_VALUE);

            Map<String, Object> pattern = new HashMap<>();
            pattern.put("totalMessages", userMessages.size());

            // Room distribution
            Map<Integer, Long> roomCounts = new HashMap<>();
            for (ChatEvent event : userMessages) {
                roomCounts.merge(1, 1L, Long::sum); // Simplified
            }
            pattern.put("roomDistribution", roomCounts);

            // Message type distribution
            Map<String, Long> typeCounts = userMessages.stream()
                    .collect(Collectors.groupingBy(
                            ChatEvent::getMessageType,
                            Collectors.counting()
                    ));
            pattern.put("messageTypeDistribution", typeCounts);

            // Activity timeline
            if (!userMessages.isEmpty()) {
                String firstTs = userMessages.get(userMessages.size() - 1).getTimestamp();
                String lastTs = userMessages.get(0).getTimestamp();
                pattern.put("firstActivity", new Date(Long.parseLong(firstTs)));
                pattern.put("lastActivity", new Date(Long.parseLong(lastTs)));
            }

            return pattern;

        } catch (Exception e) {
            logger.error("User participation pattern query failed", e);
            return Collections.emptyMap();
        }
    }

    // ========== Utility Operations ==========

    @Override
    public long getTotalMessageCount() {
        try {
            DescribeTableResult result = client.describeTable(tableName);
            return result.getTable().getItemCount();
        } catch (Exception e) {
            logger.error("Failed to get total message count", e);
            return 0;
        }
    }

    @Override
    public DatabaseMetrics getMetrics() {
        DatabaseMetrics metrics = new DatabaseMetrics();

        if (messageBuffer != null) {
            metrics.setTotalWrites(messageBuffer.getTotalWrites());
            metrics.setFailedWrites(messageBuffer.getFailedWrites());
            metrics.setPendingWrites(messageBuffer.getQueueSize());

            // Calculate write latency statistics
            List<Long> latencies = messageBuffer.getWriteLatencies();
            if (!latencies.isEmpty()) {
                metrics.setAvgWriteLatencyMs(calculateAverage(latencies));
                metrics.setP95WriteLatencyMs(calculatePercentile(latencies, 0.95));
                metrics.setP99WriteLatencyMs(calculatePercentile(latencies, 0.99));
            }
        }

        metrics.setTotalReads(totalReads.get());
        metrics.setFailedReads(failedReads.get());

        // Calculate read latency statistics
        synchronized (readLatencies) {
            if (!readLatencies.isEmpty()) {
                metrics.setAvgReadLatencyMs(calculateAverage(readLatencies));
            }
        }

        return metrics;
    }

    @Override
    public String getDatabaseType() {
        return "DynamoDB";
    }

    // ========== Helper Methods ==========

    private ChatEvent itemToChatEvent(Item item) {
        ChatEvent event = new ChatEvent();
        event.setMessageId(item.getString("message_id"));
        event.setUserId(item.getString("user_id"));
        event.setUsername(item.getString("username"));
        event.setMessage(item.getString("message"));
        event.setMessageType(item.getString("message_type"));
        event.setTimestamp(String.valueOf(item.getLong("timestamp")));
        return event;
    }

    private synchronized void recordReadLatency(long latencyMs) {
        readLatencies.add(latencyMs);

        // Keep only last 1000 measurements
        if (readLatencies.size() > 1000) {
            readLatencies.remove(0);
        }
    }

    private double calculateAverage(List<Long> values) {
        return values.stream().mapToLong(Long::longValue).average().orElse(0.0);
    }

    private double calculatePercentile(List<Long> values, double percentile) {
        if (values.isEmpty()) return 0.0;

        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);

        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, index));
    }
}

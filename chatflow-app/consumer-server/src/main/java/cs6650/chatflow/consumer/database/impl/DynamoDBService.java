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
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * DynamoDB implementation with deduplication and DLQ support.
 * Properly tracks duplicates separately from failures.
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

    // Batch Processing
    private MessageBuffer messageBuffer;

    // Read Metrics
    private final AtomicLong totalReads = new AtomicLong(0);
    private final AtomicLong failedReads = new AtomicLong(0);
    private final List<Long> readLatencies = new ArrayList<>();

    private ThroughputTracker throughputTracker = new ThroughputTracker();

    public DynamoDBService() {
        this.tableName = DatabaseConfig.getDynamoDBTableName();
        this.roomIndexName = DatabaseConfig.getDynamoDBRoomIndex();
        this.userIndexName = DatabaseConfig.getDynamoDBUserIndex();
    }

    @Override
    public void initialize() {
        logger.info("Initializing DynamoDB service with deduplication and DLQ...");

        try {
            // Validate configuration
            DatabaseConfig.validateConfiguration();
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

            // Create batch writer with deduplication support
            DynamoDBBatchWriter batchWriter = new DynamoDBBatchWriter(
                    dynamoDB,
                    tableName,
                    DatabaseConfig.getMaxRetries(),
                    DatabaseConfig.getRetryBaseDelayMs()
            );

            ThroughputTracker tracker = new ThroughputTracker();
            this.throughputTracker = tracker;

            // Initialize message buffer with DLQ
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

            // Start message buffer (also starts DLQ)
            messageBuffer.start();

            logger.info("✅ DynamoDB service initialized with deduplication and DLQ");

        } catch (Exception e) {
            logger.error("Failed to initialize DynamoDB service", e);
            throw new RuntimeException("DynamoDB initialization failed", e);
        }
    }

    @Override
    public void shutdown() {
        logger.info("Shutting down DynamoDB service...");

        try {
            // Stop message buffer (flushes pending writes and processes DLQ)
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

            // Quick health check
            table.describe();

            // Check buffer health
            int queueSize = messageBuffer.getQueueSize();
            if (queueSize > DatabaseConfig.getMaxQueueSize() * 0.9) {
                logger.warn("Message queue nearly full: {}/{}",
                        queueSize, DatabaseConfig.getMaxQueueSize());
            }

            // Check DLQ health
            int dlqSize = messageBuffer.getDLQSize();
            if (dlqSize > 100) {
                logger.warn("DLQ growing large: {} messages", dlqSize);
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
            DatabaseMessage dbMessage = DatabaseMessage.fromChatEvent(event, roomId);

            boolean added = messageBuffer.add(dbMessage);

            if (!added) {
                logger.debug("Message added to DLQ: {}", event.getMessageId());
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

    // ========== Query Operations (unchanged) ==========

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
                    .withScanIndexForward(false)
                    .withMaxResultSize(1000);

            ItemCollection<QueryOutcome> items = index.query(spec);

            List<ChatEvent> messages = new ArrayList<>();
            for (Item item : items) {
                messages.add(itemToChatEvent(item));
            }

            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            logger.debug("Query: Retrieved {} messages for room {} in {}ms",
                    messages.size(), roomId, duration);

            return messages;

        } catch (Exception e) {
            logger.error("Query failed for room {}", roomId, e);
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

            return messages;

        } catch (Exception e) {
            logger.error("Query failed for user {}", userId, e);
            failedReads.incrementAndGet();
            return Collections.emptyList();
        }
    }

    @Override
    public int countActiveUsers(long startTime, long endTime) {
        long queryStart = System.currentTimeMillis();

        try {
            int totalSegments = 8;
            Set<String> uniqueUsers = ConcurrentHashMap.newKeySet();
            ExecutorService scanExecutor = Executors.newFixedThreadPool(totalSegments);
            List<Future<?>> futures = new ArrayList<>();

            for (int segment = 0; segment < totalSegments; segment++) {
                final int seg = segment;

                Future<?> future = scanExecutor.submit(() -> {
                    try {
                        ScanSpec spec = new ScanSpec()
                                .withFilterExpression("#ts BETWEEN :start AND :end")
                                .withProjectionExpression("user_id")
                                .withNameMap(Collections.singletonMap("#ts", "timestamp"))
                                .withValueMap(new ValueMap()
                                        .withLong(":start", startTime)
                                        .withLong(":end", endTime))
                                .withSegment(seg)
                                .withTotalSegments(totalSegments);

                        ItemCollection<ScanOutcome> items = table.scan(spec);

                        for (Item item : items) {
                            uniqueUsers.add(item.getString("user_id"));
                        }

                    } catch (Exception e) {
                        logger.error("Parallel scan segment {} failed", seg, e);
                    }
                });

                futures.add(future);
            }

            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    logger.error("Error in parallel scan", e);
                }
            }

            scanExecutor.shutdown();

            int count = uniqueUsers.size();
            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            return count;

        } catch (Exception e) {
            logger.error("Count query failed", e);
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
                roomActivity.merge(roomId, timestamp, Math::max);
            }

            long duration = System.currentTimeMillis() - queryStart;
            recordReadLatency(duration);
            totalReads.incrementAndGet();

            return roomActivity;

        } catch (Exception e) {
            logger.error("Query failed for user {}", userId, e);
            failedReads.incrementAndGet();
            return Collections.emptyMap();
        }
    }

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

            Map<String, Long> result = new TreeMap<>();
            distribution.forEach((bucket, count) -> {
                result.put(new Date(bucket).toString(), count);
            });

            totalReads.incrementAndGet();
            return result;

        } catch (Exception e) {
            logger.error("Distribution query failed", e);
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

            Map<Integer, Long> roomCounts = new HashMap<>();
            for (ChatEvent event : userMessages) {
                roomCounts.merge(1, 1L, Long::sum);
            }
            pattern.put("roomDistribution", roomCounts);

            Map<String, Long> typeCounts = userMessages.stream()
                    .collect(Collectors.groupingBy(
                            ChatEvent::getMessageType,
                            Collectors.counting()
                    ));
            pattern.put("messageTypeDistribution", typeCounts);

            if (!userMessages.isEmpty()) {
                String firstTs = userMessages.get(userMessages.size() - 1).getTimestamp();
                String lastTs = userMessages.get(0).getTimestamp();
                pattern.put("firstActivity", new Date(Long.parseLong(firstTs)));
                pattern.put("lastActivity", new Date(Long.parseLong(lastTs)));
            }

            return pattern;

        } catch (Exception e) {
            logger.error("Participation pattern query failed", e);
            return Collections.emptyMap();
        }
    }

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

            List<Long> latencies = messageBuffer.getWriteLatencies();
            if (!latencies.isEmpty()) {
                metrics.setAvgWriteLatencyMs(calculateAverage(latencies));
                metrics.setP95WriteLatencyMs(calculatePercentile(latencies, 0.95));
                metrics.setP99WriteLatencyMs(calculatePercentile(latencies, 0.99));
            }
        }

        metrics.setTotalReads(totalReads.get());
        metrics.setFailedReads(failedReads.get());

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

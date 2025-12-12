package cs6650.chatflow.consumer.api;

import cs6650.chatflow.consumer.database.DatabaseService;
import cs6650.chatflow.consumer.database.model.DatabaseMetrics;
import cs6650.chatflow.consumer.model.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Background service that pre-computes and caches metrics.
 * This ensures the MetricsServlet is non-blocking and extremely fast.
 */
public class MetricsService {
    private static final Logger logger = LoggerFactory.getLogger(MetricsService.class);

    private final DatabaseService databaseService;
    private final ScheduledExecutorService scheduler;
    
    // The cache for the latest metrics snapshot
    private volatile Map<String, Object> cachedMetrics = new HashMap<>();
    private volatile long lastUpdateTime = 0;

    public MetricsService(DatabaseService databaseService) {
        this.databaseService = databaseService;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Metrics-Computer");
            t.setDaemon(true);
            return t;
        });
        
        // Initialize with empty metrics
        cachedMetrics.put("status", "Initializing...");
    }

    public void start() {
        logger.info("Starting MetricsService background computer...");
        // Compute metrics every 5 seconds
        scheduler.scheduleAtFixedRate(this::computeMetrics, 0, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        logger.info("Stopping MetricsService...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Fast, non-blocking retrieval of the latest metrics.
     */
    public Map<String, Object> getMetrics() {
        return cachedMetrics;
    }

    /**
     * The heavy lifting happens here, in the background thread.
     */
    private void computeMetrics() {
        try {
            long startTime = System.currentTimeMillis();
            Map<String, Object> newMetrics = new HashMap<>();

            // Add metadata
            newMetrics.put("timestamp", new Date().toString());
            newMetrics.put("lastUpdatedMsAgo", System.currentTimeMillis() - lastUpdateTime);
            newMetrics.put("databaseType", databaseService.getDatabaseType());

            // Execute core queries
            newMetrics.put("coreQueries", executeCoreQueries(databaseService));

            // Execute analytics queries
            newMetrics.put("analytics", executeAnalyticsQueries(databaseService));

            // Add database metrics
            newMetrics.put("databaseMetrics", getDatabaseMetricsMap(databaseService));

            // Update the cache atomically
            this.cachedMetrics = newMetrics;
            this.lastUpdateTime = System.currentTimeMillis();
            
            long duration = System.currentTimeMillis() - startTime;
            logger.debug("Metrics re-computed in {}ms", duration);

        } catch (Exception e) {
            logger.error("Failed to compute metrics", e);
        }
    }

    // ==================================================================================
    // The logic below is moved from MetricsServlet to here
    // ==================================================================================

    private Map<String, Object> executeCoreQueries(DatabaseService db) {
        Map<String, Object> coreQueries = new LinkedHashMap<>();
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (60 * 60 * 1000); // 1 hour ago

        // Query 1: Get messages for a room in time range
        try {
            long q1Start = System.currentTimeMillis();
            List<ChatEvent> room1Messages = db.getMessagesInTimeRange(1, startTime, endTime);
            long q1Duration = System.currentTimeMillis() - q1Start;

            Map<String, Object> q1Result = new HashMap<>();
            q1Result.put("roomId", 1);
            q1Result.put("messageCount", room1Messages.size());
            q1Result.put("durationMs", q1Duration);
            q1Result.put("targetMs", 100);
            q1Result.put("status", q1Duration < 100 ? "PASS" : "FAIL");
            q1Result.put("sampleMessages", room1Messages.stream().limit(3).toArray());

            coreQueries.put("query1_roomMessages", q1Result);

        } catch (Exception e) {
            coreQueries.put("query1_roomMessages", createErrorResult("Query failed: " + e.getMessage()));
        }

        // Query 2: Get user's message history
        try {
            // Optimization: Don't scan full table for sample user. Use a hardcoded one or one from room 1.
            // For stability, let's try to find a user from the room 1 messages we just fetched.
            String sampleUserId = "1"; // Default
            List<ChatEvent> room1Messages = (List<ChatEvent>) ((Map<String, Object>)coreQueries.get("query1_roomMessages")).get("sampleMessages");
            
            // If we didn't get messages from the previous query, try a quick fetch
            if (room1Messages == null || room1Messages.isEmpty()) {
                 List<ChatEvent> recent = db.getMessagesInTimeRange(1, startTime, endTime);
                 if (!recent.isEmpty()) {
                     sampleUserId = recent.get(0).getUserId();
                 }
            }

            long q2Start = System.currentTimeMillis();
            List<ChatEvent> userHistory = db.getUserMessageHistory(sampleUserId, startTime, endTime);
            long q2Duration = System.currentTimeMillis() - q2Start;

            Map<String, Object> q2Result = new HashMap<>();
            q2Result.put("userId", sampleUserId);
            q2Result.put("messageCount", userHistory.size());
            q2Result.put("durationMs", q2Duration);
            q2Result.put("targetMs", 200);
            q2Result.put("status", q2Duration < 200 ? "PASS" : "FAIL");

            coreQueries.put("query2_userHistory", q2Result);

        } catch (Exception e) {
            coreQueries.put("query2_userHistory", createErrorResult("Query failed: " + e.getMessage()));
        }

        // Query 3: Count active users
        try {
            long q3Start = System.currentTimeMillis();
            int activeUsers = db.countActiveUsers(startTime, endTime);
            long q3Duration = System.currentTimeMillis() - q3Start;

            Map<String, Object> q3Result = new HashMap<>();
            q3Result.put("uniqueUsers", activeUsers);
            q3Result.put("durationMs", q3Duration);
            q3Result.put("targetMs", 500);
            q3Result.put("status", q3Duration < 500 ? "PASS" : "FAIL");

            coreQueries.put("query3_activeUsers", q3Result);

        } catch (Exception e) {
            coreQueries.put("query3_activeUsers", createErrorResult("Query failed: " + e.getMessage()));
        }

        // Query 4: Get rooms user has participated in
        try {
            String sampleUserId = "1"; // Default
            // Reuse sample user logic
            
            long q4Start = System.currentTimeMillis();
            Map<Integer, Long> userRooms = db.getUserRooms(sampleUserId);
            long q4Duration = System.currentTimeMillis() - q4Start;

            Map<String, Object> q4Result = new HashMap<>();
            q4Result.put("userId", sampleUserId);
            q4Result.put("roomCount", userRooms.size());
            q4Result.put("rooms", userRooms);
            q4Result.put("durationMs", q4Duration);
            q4Result.put("targetMs", 50);
            q4Result.put("status", q4Duration < 50 ? "PASS" : "FAIL");

            coreQueries.put("query4_userRooms", q4Result);

        } catch (Exception e) {
            coreQueries.put("query4_userRooms", createErrorResult("Query failed: " + e.getMessage()));
        }

        return coreQueries;
    }

    private Map<String, Object> executeAnalyticsQueries(DatabaseService db) {
        Map<String, Object> analytics = new LinkedHashMap<>();
        long endTime = System.currentTimeMillis();
        long startTime = endTime - (60 * 60 * 1000);

        try {
            analytics.put("totalMessages", db.getTotalMessageCount());

            List<Map.Entry<String, Integer>> topUsers = db.getTopActiveUsers(10);
            analytics.put("topActiveUsers", convertToMapList(topUsers));

            List<Map.Entry<Integer, Integer>> topRooms = db.getTopActiveRooms(20);
            analytics.put("topActiveRooms", convertToMapList(topRooms));

            Map<String, Long> messagesPerSecond = db.getMessageDistribution(startTime, endTime, "second");
            analytics.put("messagesPerSecondSample",
                    messagesPerSecond.entrySet().stream().limit(10).collect(
                            LinkedHashMap::new,
                            (m, e) -> m.put(e.getKey(), e.getValue()),
                            Map::putAll
                    ));
            analytics.put("totalTimeWindows", messagesPerSecond.size());

        } catch (Exception e) {
            analytics.put("error", e.getMessage());
        }

        return analytics;
    }

    private Map<String, Object> getDatabaseMetricsMap(DatabaseService db) {
        DatabaseMetrics metrics = db.getMetrics();
        Map<String, Object> metricsMap = new LinkedHashMap<>();
        metricsMap.put("totalWrites", metrics.getTotalWrites());
        metricsMap.put("totalReads", metrics.getTotalReads());
        metricsMap.put("failedWrites", metrics.getFailedWrites());
        metricsMap.put("failedReads", metrics.getFailedReads());
        metricsMap.put("pendingWrites", metrics.getPendingWrites());
        metricsMap.put("avgWriteLatencyMs", String.format("%.2f", metrics.getAvgWriteLatencyMs()));
        metricsMap.put("avgReadLatencyMs", String.format("%.2f", metrics.getAvgReadLatencyMs()));
        metricsMap.put("p95WriteLatencyMs", String.format("%.2f", metrics.getP95WriteLatencyMs()));
        metricsMap.put("p99WriteLatencyMs", String.format("%.2f", metrics.getP99WriteLatencyMs()));
        return metricsMap;
    }

    private <K, V> List<Map<String, Object>> convertToMapList(List<Map.Entry<K, V>> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<K, V> entry : entries) {
            Map<String, Object> map = new HashMap<>();
            map.put("key", entry.getKey());
            map.put("value", entry.getValue());
            result.add(map);
        }
        return result;
    }

    private Map<String, Object> createErrorResult(String error) {
        Map<String, Object> result = new HashMap<>();
        result.put("error", error);
        result.put("status", "ERROR");
        return result;
    }
}

package cs6650.chatflow.consumer.api;

import cs6650.chatflow.consumer.commons.Constants;
import cs6650.chatflow.consumer.database.DatabaseService;
import cs6650.chatflow.consumer.database.model.DatabaseMetrics;
import cs6650.chatflow.consumer.model.ChatEvent;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletContext;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.*;

/**
 * Metrics API endpoint for database query results and analytics.
 * Called by client after test completion to retrieve all metrics.
 */
@WebServlet(Constants.METRICS_ENDPOINT)
public class MetricsServlet extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MetricsServlet.class);
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        logger.info("Metrics API called from: {}", req.getRemoteAddr());

        long apiStartTime = System.currentTimeMillis();

        try {
            // Get database service from servlet context
            ServletContext context = getServletContext();
            DatabaseService databaseService = (DatabaseService) context.getAttribute("databaseService");

            if (databaseService == null) {
                logger.error("Database service not found in servlet context");
                sendError(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "Database service not available");
                return;
            }

            // Build metrics response
            Map<String, Object> response = new HashMap<>();

            // Add metadata
            response.put("timestamp", new Date().toString());
            response.put("databaseType", databaseService.getDatabaseType());

            // Execute core queries
            logger.info("Executing core queries...");
            response.put("coreQueries", executeCoreQueries(databaseService));

            // Execute analytics queries
            logger.info("Executing analytics queries...");
            response.put("analytics", executeAnalyticsQueries(databaseService));

            // Add database metrics
            logger.info("Retrieving database metrics...");
            response.put("databaseMetrics", getDatabaseMetricsMap(databaseService));

            // Add API execution time
            long apiDuration = System.currentTimeMillis() - apiStartTime;
            response.put("apiExecutionTimeMs", apiDuration);

            // Send JSON response
            resp.setContentType("application/json");
            resp.setCharacterEncoding("UTF-8");
            resp.setStatus(HttpServletResponse.SC_OK);
            resp.getWriter().write(gson.toJson(response));

            logger.info("Metrics API completed in {}ms", apiDuration);

        } catch (Exception e) {
            logger.error("Error generating metrics", e);
            sendError(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Failed to generate metrics: " + e.getMessage());
        }
    }

    /**
     * Execute all core queries required by assignment
     */
    private Map<String, Object> executeCoreQueries(DatabaseService db) {
        Map<String, Object> coreQueries = new LinkedHashMap<>();

        // Calculate time range (last hour by default, or all time if no data)
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
            logger.info("Query 1: {} messages in {}ms (target: <100ms) [{}]",
                    room1Messages.size(), q1Duration, q1Result.get("status"));

        } catch (Exception e) {
            logger.error("Query 1 failed", e);
            coreQueries.put("query1_roomMessages", createErrorResult("Query failed: " + e.getMessage()));
        }

        // Query 2: Get user's message history (use first user from room 1)
        try {
            // First get a sample user
            List<ChatEvent> room1Messages = db.getMessagesInTimeRange(1, 0, Long.MAX_VALUE);

            if (!room1Messages.isEmpty()) {
                String sampleUserId = room1Messages.get(0).getUserId();

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
                logger.info("Query 2: {} messages for user {} in {}ms (target: <200ms) [{}]",
                        userHistory.size(), sampleUserId, q2Duration, q2Result.get("status"));
            } else {
                coreQueries.put("query2_userHistory", createErrorResult("No messages to query"));
            }

        } catch (Exception e) {
            logger.error("Query 2 failed", e);
            coreQueries.put("query2_userHistory", createErrorResult("Query failed: " + e.getMessage()));
        }

        // Query 3: Count active users in time window
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
            logger.info("Query 3: {} active users in {}ms (target: <500ms) [{}]",
                    activeUsers, q3Duration, q3Result.get("status"));

        } catch (Exception e) {
            logger.error("Query 3 failed", e);
            coreQueries.put("query3_activeUsers", createErrorResult("Query failed: " + e.getMessage()));
        }

        // Query 4: Get rooms user has participated in
        try {
            // Use sample user from query 2
            List<ChatEvent> room1Messages = db.getMessagesInTimeRange(1, 0, Long.MAX_VALUE);

            if (!room1Messages.isEmpty()) {
                String sampleUserId = room1Messages.get(0).getUserId();

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
                logger.info("Query 4: {} rooms for user {} in {}ms (target: <50ms) [{}]",
                        userRooms.size(), sampleUserId, q4Duration, q4Result.get("status"));
            } else {
                coreQueries.put("query4_userRooms", createErrorResult("No messages to query"));
            }

        } catch (Exception e) {
            logger.error("Query 4 failed", e);
            coreQueries.put("query4_userRooms", createErrorResult("Query failed: " + e.getMessage()));
        }

        return coreQueries;
    }

    /**
     * Execute analytics queries
     */
    private Map<String, Object> executeAnalyticsQueries(DatabaseService db) {
        Map<String, Object> analytics = new LinkedHashMap<>();

        long endTime = System.currentTimeMillis();
        long startTime = endTime - (60 * 60 * 1000); // Last hour

        try {
            // Total message count
            analytics.put("totalMessages", db.getTotalMessageCount());

            // Top active users
            List<Map.Entry<String, Integer>> topUsers = db.getTopActiveUsers(10);
            analytics.put("topActiveUsers", convertToMapList(topUsers));
            logger.info("Top users count: {}", topUsers.size());

            // Top active rooms
            List<Map.Entry<Integer, Integer>> topRooms = db.getTopActiveRooms(20);
            analytics.put("topActiveRooms", convertToMapList(topRooms));
            logger.info("Top rooms count: {}", topRooms.size());

            // Messages per second distribution
            Map<String, Long> messagesPerSecond = db.getMessageDistribution(startTime, endTime, "second");
            analytics.put("messagesPerSecondSample",
                    messagesPerSecond.entrySet().stream().limit(10).collect(
                            LinkedHashMap::new,
                            (m, e) -> m.put(e.getKey(), e.getValue()),
                            Map::putAll
                    ));
            analytics.put("totalTimeWindows", messagesPerSecond.size());

        } catch (Exception e) {
            logger.error("Analytics queries failed", e);
            analytics.put("error", e.getMessage());
        }

        return analytics;
    }

    /**
     * Get database metrics as map
     */
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

    /**
     * Helper: Convert list of entries to list of maps for JSON
     */
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

    /**
     * Helper: Create error result
     */
    private Map<String, Object> createErrorResult(String error) {
        Map<String, Object> result = new HashMap<>();
        result.put("error", error);
        result.put("status", "ERROR");
        return result;
    }

    /**
     * Helper: Send error response
     */
    private void sendError(HttpServletResponse resp, int status, String message) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");
        resp.setStatus(status);

        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("timestamp", new Date().toString());

        resp.getWriter().write(gson.toJson(error));
    }
}

package cs6650.chatflow.consumer.database;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import cs6650.chatflow.consumer.model.ChatEvent;
import cs6650.chatflow.consumer.database.model.DatabaseMetrics;
import java.util.List;
import java.util.Map;

/**
 * Generic database service interface.
 * Implementations: DynamoDB, PostgreSQL, MongoDB, etc.
 */
public interface DatabaseService {

    // ========== Lifecycle Management ==========

    void initialize();
    void shutdown();
    boolean isHealthy();

    // ========== Write Operations ==========

    /**
     * Write a single message with explicit roomId
     * @param event The chat event to persist
     * @param roomId The room identifier
     */
    void writeMessage(ChatEvent event, String roomId);

    /**
     * Write multiple messages in batch
     * @param events List of chat events
     * @param roomId The room identifier
     */
    void writeBatch(List<ChatEvent> events, String roomId);

    /**
     * Force flush all pending writes (blocking)
     * @return Number of messages flushed
     */
    int flushPendingWrites();

    // ========== Core Query Operations ==========

    List<ChatEvent> getMessagesInTimeRange(int roomId, long startTime, long endTime);
    List<ChatEvent> getUserMessageHistory(String userId, long startTime, long endTime);
    int countActiveUsers(long startTime, long endTime);
    Map<Integer, Long> getUserRooms(String userId);

    // ========== Analytics Operations ==========

    Map<String, Long> getMessageDistribution(long startTime, long endTime, String granularity);
    List<Map.Entry<String, Integer>> getTopActiveUsers(int limit);
    List<Map.Entry<Integer, Integer>> getTopActiveRooms(int limit);
    Map<String, Object> getUserParticipationPattern(String userId);

    // ========== Utility Operations ==========

    long getTotalMessageCount();
    DatabaseMetrics getMetrics();
    String getDatabaseType();
    DynamoDB getDynamoDB();
}

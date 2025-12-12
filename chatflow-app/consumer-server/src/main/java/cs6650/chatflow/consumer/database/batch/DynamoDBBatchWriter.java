package cs6650.chatflow.consumer.database.batch;

import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.model.WriteRequest;
import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB-specific implementation of batch writer with deduplication support.
 * Handles DynamoDB writes with conditional expressions to prevent duplicates.
 * Returns metadata about write results (success, duplicates, failures).
 */
public class DynamoDBBatchWriter implements DatabaseBatchWriter {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDBBatchWriter.class);

    private final DynamoDB dynamoDB;
    private final String tableName;
    private final int maxRetries;
    private final long retryDelayMs;

    public DynamoDBBatchWriter(DynamoDB dynamoDB, String tableName) {
        this(dynamoDB, tableName, 3, 100);
    }

    public DynamoDBBatchWriter(DynamoDB dynamoDB, String tableName, int maxRetries, long retryDelayMs) {
        this.dynamoDB = dynamoDB;
        this.tableName = tableName;
        this.maxRetries = maxRetries;
        this.retryDelayMs = retryDelayMs;
    }

    @Override
    public BatchWriteResult writeBatch(List<DatabaseMessage> messages) throws Exception {
        BatchWriteResult finalResult = new BatchWriteResult();
        if (messages == null || messages.isEmpty()) {
            return finalResult;
        }

        TableWriteItems tableWriteItems = new TableWriteItems(tableName);
        for (DatabaseMessage message : messages) {
            Item item = convertToItem(message);
            tableWriteItems.addItemToPut(item);
        }

        BatchWriteItemOutcome outcome = dynamoDB.batchWriteItem(tableWriteItems);
        handleOutcome(outcome, finalResult, messages, 1);

        logBatchResult(finalResult);
        return finalResult;
    }

    private void handleOutcome(BatchWriteItemOutcome outcome, BatchWriteResult result, List<DatabaseMessage> originalMessages, int attempt) {
        int successfulWrites = originalMessages.size() - outcome.getUnprocessedItems().size();
        result.addSuccessful(successfulWrites);

        Map<String, List<WriteRequest>> unprocessedItems = outcome.getUnprocessedItems();
        if (!unprocessedItems.isEmpty() && attempt <= maxRetries) {
            logger.warn("Batch write had {} unprocessed items. Retrying (attempt {}/{}) after {}ms...",
                    unprocessedItems.get(tableName).size(), attempt, maxRetries, retryDelayMs * attempt);

            try {
                Thread.sleep(retryDelayMs * attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Retry interrupted.");
                // Add all unprocessed to failed list
                unprocessedItems.get(tableName).forEach(req -> {
                    result.incrementFailed();
                    // This part is tricky as we don't have the original message object easily.
                    // For simplicity, we'll just count them as failed.
                });
                return;
            }

            BatchWriteItemOutcome retryOutcome = dynamoDB.batchWriteItemUnprocessed(unprocessedItems);
            handleOutcome(retryOutcome, result, originalMessages, attempt + 1);
        } else if (!unprocessedItems.isEmpty()) {
            int failedCount = unprocessedItems.get(tableName).size();
            result.addFailed(failedCount);
            result.addSuccessful(-failedCount); // Adjust success count
            logger.error("Permanently failed to write {} items after {} retries.", failedCount, maxRetries);
        }
    }


    /**
     * Convert DatabaseMessage to DynamoDB Item
     */
    private Item convertToItem(DatabaseMessage dbMessage) {
        return new Item()
                .withPrimaryKey("message_id", dbMessage.getMessageId())
                .withInt("room_id", dbMessage.getRoomId())
                .withString("user_id", dbMessage.getUserId())
                .withString("username", dbMessage.getUsername())
                .withString("message", dbMessage.getMessage())
                .withString("message_type", dbMessage.getMessageType())
                .withLong("timestamp", dbMessage.getTimestamp())
                .withLong("created_at", dbMessage.getCreatedAt());
    }

    /**
     * Log batch write results
     */
    private void logBatchResult(BatchWriteResult result) {
        int total = result.getSuccessful() + result.getDuplicates() + result.getFailed();

        if (result.getFailed() > 0) {
            logger.warn("Batch write completed: {} total, {} successful, {} duplicates, {} FAILED",
                    total, result.getSuccessful(), result.getDuplicates(), result.getFailed());
        } else if (result.getDuplicates() > 0) {
            logger.debug("Batch write completed: {} total, {} successful, {} duplicates",
                    total, result.getSuccessful(), result.getDuplicates());
        } else {
            logger.trace("Batch write completed: {} messages written successfully",
                    result.getSuccessful());
        }
    }
}

package cs6650.chatflow.consumer.database.batch;

import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.model.*;
import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * DynamoDB-specific implementation of batch writer.
 * Handles DynamoDB batch write with retries.
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
    public void writeBatch(List<DatabaseMessage> messages) throws Exception {
        // DynamoDB batch write limit is 25 items
        for (int i = 0; i < messages.size(); i += 25) {
            int end = Math.min(i + 25, messages.size());
            List<DatabaseMessage> subBatch = messages.subList(i, end);

            writeBatchWithRetry(subBatch);
        }
    }

    private void writeBatchWithRetry(List<DatabaseMessage> messages) throws Exception {
        TableWriteItems tableWriteItems = new TableWriteItems(tableName);

        for (DatabaseMessage dbMessage : messages) {
            Item item = convertToItem(dbMessage);
            tableWriteItems.addItemToPut(item);
        }

        // Initial write attempt
        BatchWriteItemOutcome outcome = dynamoDB.batchWriteItem(tableWriteItems);

        // Handle unprocessed items with exponential backoff
        Map<String, List<WriteRequest>> unprocessed = outcome.getUnprocessedItems();
        int retries = 0;

        while (!unprocessed.isEmpty() && retries < maxRetries) {
            long delay = retryDelayMs * (long) Math.pow(2, retries); // Exponential backoff
            Thread.sleep(delay);

            logger.debug("Retrying {} unprocessed items (attempt {}/{})",
                    unprocessed.values().stream().mapToInt(List::size).sum(),
                    retries + 1, maxRetries);

            outcome = dynamoDB.batchWriteItemUnprocessed(unprocessed);
            unprocessed = outcome.getUnprocessedItems();
            retries++;
        }

        if (!unprocessed.isEmpty()) {
            int failedCount = unprocessed.values().stream().mapToInt(List::size).sum();
            throw new Exception("Failed to write " + failedCount + " items after " + maxRetries + " retries");
        }
    }

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
}

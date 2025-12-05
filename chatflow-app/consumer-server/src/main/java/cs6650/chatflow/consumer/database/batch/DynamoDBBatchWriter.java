package cs6650.chatflow.consumer.database.batch;

import com.amazonaws.services.dynamodbv2.document.*;
import com.amazonaws.services.dynamodbv2.document.spec.PutItemSpec;
import com.amazonaws.services.dynamodbv2.model.*;
import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

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
        BatchWriteResult result = new BatchWriteResult();
        Table table = dynamoDB.getTable(tableName);

        for (DatabaseMessage message : messages) {
            try {
                Item item = convertToItem(message);

                // Use conditional write to prevent duplicates
                PutItemSpec spec = new PutItemSpec()
                        .withItem(item)
                        .withConditionExpression("attribute_not_exists(message_id)");

                table.putItem(spec);

                result.incrementSuccessful();
                logger.trace("Successfully wrote message: {}", message.getMessageId());

            } catch (ConditionalCheckFailedException e) {
                // Message already exists - this is NOT an error in multi-instance setup
                result.incrementDuplicate();
                logger.debug("Duplicate message prevented: {} (already exists in database)",
                        message.getMessageId());

            } catch (ProvisionedThroughputExceededException e) {
                // Throughput exceeded - retry with backoff
                boolean retried = retryWithBackoff(table, message, result);
                if (!retried) {
                    result.incrementFailed();
                    result.addFailedMessage(message);
                    logger.error("Failed to write message {} after retries: throughput exceeded",
                            message.getMessageId());
                }

            } catch (Exception e) {
                // Genuine error - mark as failed
                result.incrementFailed();
                result.addFailedMessage(message);
                logger.error("Failed to write message {}: {}",
                        message.getMessageId(), e.getMessage(), e);
            }
        }

        logBatchResult(result);
        return result;
    }

    /**
     * Retry write with exponential backoff for throughput exceptions
     */
    private boolean retryWithBackoff(Table table, DatabaseMessage message, BatchWriteResult result) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                long delay = retryDelayMs * (long) Math.pow(2, attempt - 1);
                Thread.sleep(delay);

                logger.debug("Retrying write for message {} (attempt {}/{})",
                        message.getMessageId(), attempt, maxRetries);

                Item item = convertToItem(message);
                PutItemSpec spec = new PutItemSpec()
                        .withItem(item)
                        .withConditionExpression("attribute_not_exists(message_id)");

                table.putItem(spec);

                result.incrementSuccessful();
                logger.debug("Successfully wrote message {} on retry attempt {}",
                        message.getMessageId(), attempt);
                return true;

            } catch (ConditionalCheckFailedException e) {
                // Duplicate - not a failure
                result.incrementDuplicate();
                logger.debug("Duplicate detected on retry for message: {}", message.getMessageId());
                return true;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.error("Retry interrupted for message {}", message.getMessageId());
                return false;

            } catch (Exception e) {
                logger.warn("Retry attempt {} failed for message {}: {}",
                        attempt, message.getMessageId(), e.getMessage());

                if (attempt == maxRetries) {
                    return false;
                }
            }
        }
        return false;
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

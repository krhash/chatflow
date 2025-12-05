package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;

import java.util.List;

/**
 * Interface for database batch writers.
 * Implementations should handle database-specific batch write operations
 * with proper deduplication and error tracking.
 *
 * The interface returns BatchWriteResult to enable proper tracking of:
 * - Successful writes (new records created)
 * - Duplicate writes prevented (record already exists - NOT a failure)
 * - Failed writes (genuine errors requiring DLQ retry)
 */
public interface DatabaseBatchWriter {

    /**
     * Write a batch of messages to the database with deduplication support.
     *
     * Implementations should:
     * - Use conditional writes to prevent duplicates (e.g., attribute_not_exists)
     * - Track successful, duplicate, and failed writes separately
     * - Handle retries for transient errors (throughput exceeded, network issues)
     * - Return detailed results for proper metric tracking
     *
     * @param messages List of messages to write
     * @return BatchWriteResult containing counts of successful/duplicate/failed writes
     * @throws Exception if the entire batch operation fails catastrophically
     */
    BatchWriteResult writeBatch(List<DatabaseMessage> messages) throws Exception;
}

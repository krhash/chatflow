package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;
import java.util.List;

/**
 * Interface for batch database write operations.
 * Implementations handle the actual database write logic.
 */
public interface DatabaseBatchWriter {

    /**
     * Write a batch of messages to the database.
     * This method should handle retries and error recovery internally.
     *
     * @param messages List of messages to write
     * @throws Exception if write fails after all retries
     */
    void writeBatch(List<DatabaseMessage> messages) throws Exception;
}

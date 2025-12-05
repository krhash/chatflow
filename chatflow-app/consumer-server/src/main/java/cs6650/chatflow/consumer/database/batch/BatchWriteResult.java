package cs6650.chatflow.consumer.database.batch;

import cs6650.chatflow.consumer.database.model.DatabaseMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Result of a batch write operation containing success/failure/duplicate counts.
 * Used to distinguish between genuine failures and benign duplicates.
 *
 * This class enables proper tracking of:
 * - Successful writes (new records created)
 * - Duplicate writes prevented (record already exists - NOT a failure)
 * - Failed writes (genuine errors requiring retry)
 *
 * Usage:
 * <pre>
 * BatchWriteResult result = batchWriter.writeBatch(messages);
 *
 * System.out.println("Successful: " + result.getSuccessful());
 * System.out.println("Duplicates: " + result.getDuplicates());
 * System.out.println("Failed: " + result.getFailed());
 *
 * if (result.hasFailures()) {
 *     deadLetterQueue.addAll(result.getFailedMessages());
 * }
 * </pre>
 */
public class BatchWriteResult {
    private int successful = 0;
    private int duplicates = 0;
    private int failed = 0;
    private final List<DatabaseMessage> failedMessages = new ArrayList<>();

    /**
     * Increment successful write count
     */
    public void incrementSuccessful() {
        successful++;
    }

    /**
     * Increment duplicate write count (not a failure!)
     */
    public void incrementDuplicate() {
        duplicates++;
    }

    /**
     * Increment failed write count
     */
    public void incrementFailed() {
        failed++;
    }

    /**
     * Add a message that failed to write (for DLQ)
     *
     * @param message The message that failed
     */
    public void addFailedMessage(DatabaseMessage message) {
        failedMessages.add(message);
    }

    /**
     * Add multiple failed messages
     *
     * @param messages List of messages that failed
     */
    public void addFailedMessages(List<DatabaseMessage> messages) {
        failedMessages.addAll(messages);
    }

    // ========== Getters ==========

    /**
     * Get number of successful writes
     *
     * @return Count of messages successfully written to database
     */
    public int getSuccessful() {
        return successful;
    }

    /**
     * Get number of duplicate writes prevented
     *
     * @return Count of messages that already existed in database
     */
    public int getDuplicates() {
        return duplicates;
    }

    /**
     * Get number of failed writes
     *
     * @return Count of messages that failed to write
     */
    public int getFailed() {
        return failed;
    }

    /**
     * Get list of messages that failed to write
     *
     * @return Unmodifiable list of failed messages
     */
    public List<DatabaseMessage> getFailedMessages() {
        return Collections.unmodifiableList(failedMessages);
    }

    /**
     * Get total number of messages processed
     *
     * @return successful + duplicates + failed
     */
    public int getTotal() {
        return successful + duplicates + failed;
    }

    /**
     * Check if there were any failures
     *
     * @return true if failed > 0
     */
    public boolean hasFailures() {
        return failed > 0;
    }

    /**
     * Check if there were any duplicates
     *
     * @return true if duplicates > 0
     */
    public boolean hasDuplicates() {
        return duplicates > 0;
    }

    /**
     * Check if all writes were successful (no failures, duplicates OK)
     *
     * @return true if failed == 0
     */
    public boolean isFullySuccessful() {
        return failed == 0;
    }

    /**
     * Get success rate percentage (excludes duplicates from calculation)
     *
     * @return Percentage of successful writes out of non-duplicate writes
     */
    public double getSuccessRate() {
        int nonDuplicates = successful + failed;
        return nonDuplicates > 0 ? (successful * 100.0 / nonDuplicates) : 100.0;
    }

    /**
     * Get duplicate rate percentage
     *
     * @return Percentage of duplicate writes out of total
     */
    public double getDuplicateRate() {
        int total = getTotal();
        return total > 0 ? (duplicates * 100.0 / total) : 0.0;
    }

    @Override
    public String toString() {
        return String.format("BatchWriteResult{total=%d, successful=%d, duplicates=%d, failed=%d}",
                getTotal(), successful, duplicates, failed);
    }

    /**
     * Get detailed string representation
     *
     * @return Detailed statistics including rates
     */
    public String toDetailedString() {
        return String.format(
                "BatchWriteResult{" +
                        "total=%d, successful=%d, duplicates=%d, failed=%d, " +
                        "successRate=%.2f%%, duplicateRate=%.2f%%}",
                getTotal(), successful, duplicates, failed,
                getSuccessRate(), getDuplicateRate()
        );
    }
}

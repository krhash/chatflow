package cs6650.chatflow.client.commons;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Metrics class for tracking client performance and statistics.
 */
public class ClientMetrics {
    // Message counters
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesReceived = new AtomicLong(0); // Can be higher than sent due to multiple receivers
    private final AtomicLong messagesAcked = new AtomicLong(0); // Successfully ACK'd messages

    // Connection metrics
    private final AtomicInteger connectionFailures = new AtomicInteger(0);
    private final AtomicInteger retryCount = new AtomicInteger(0);

    // Timing
    private volatile long startTime = 0;
    private volatile long endTime = 0;
    private volatile long lastAckTime = 0;

    /**
     * Mark the start time when connections begin.
     */
    public void markStartTime() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Mark the end time when all messages are acknowledged.
     */
    public void markEndTime() {
        this.endTime = System.currentTimeMillis();
        this.lastAckTime = this.endTime;
    }

    /**
     * Record a message being sent.
     */
    public void recordMessageSent() {
        messagesSent.incrementAndGet();
    }

    /**
     * Record a message being received.
     */
    public void recordMessageReceived() {
        messagesReceived.incrementAndGet();
    }

    /**
     * Record a message being successfully acknowledged.
     */
    public void recordMessageAcked() {
        messagesAcked.incrementAndGet();
        this.lastAckTime = System.currentTimeMillis();
    }

    /**
     * Record a connection failure.
     */
    public void recordConnectionFailure() {
        connectionFailures.incrementAndGet();
    }

    /**
     * Record a retry attempt.
     */
    public void recordRetry() {
        retryCount.incrementAndGet();
    }

    /**
     * Get total runtime from start to last ACK.
     * @return runtime in milliseconds
     */
    public long getTotalRuntime() {
        if (startTime == 0) return 0;
        long endTimeForCalc = endTime > 0 ? endTime : lastAckTime;
        return endTimeForCalc - startTime;
    }

    /**
     * Calculate messages per second throughput.
     * @return messages per second
     */
    public double getMessagesPerSecond() {
        long runtime = getTotalRuntime();
        if (runtime == 0) return 0.0;
        long messages = messagesSent.get();
        return (messages * 1000.0) / runtime;
    }

    /**
     * Get messages sent.
     */
    public long getMessagesSent() {
        return messagesSent.get();
    }

    /**
     * Get messages received.
     */
    public long getMessagesReceived() {
        return messagesReceived.get();
    }

    /**
     * Get messages acknowledged.
     */
    public long getMessagesAcked() {
        return messagesAcked.get();
    }

    /**
     * Get connection failures.
     */
    public int getConnectionFailures() {
        return connectionFailures.get();
    }

    /**
     * Get retry count.
     */
    public int getRetryCount() {
        return retryCount.get();
    }

    /**
     * Check if all sent messages have been acknowledged.
     */
    public boolean areAllMessagesAcked() {
        return messagesAcked.get() >= messagesSent.get();
    }

    /**
     * Get a summary of all metrics.
     */
    @Override
    public String toString() {
        return String.format(
            "ClientMetrics{sent=%d, received=%d, acked=%d, connFailures=%d, retries=%d, " +
            "runtime=%.2fs, throughput=%.1f msg/sec}",
            messagesSent.get(),
            messagesReceived.get(),
            messagesAcked.get(),
            connectionFailures.get(),
            retryCount.get(),
            getTotalRuntime() / 1000.0,
            getMessagesPerSecond()
        );
    }

    /**
     * Generate a detailed metrics report.
     */
    public String generateReport(String clientName) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================================================================================\n");
        sb.append(String.format("%s METRICS REPORT\n", clientName));
        sb.append("================================================================================\n");

        sb.append(String.format("MESSAGES:\n"));
        sb.append(String.format("  Total Sent:       %d\n", messagesSent.get()));
        sb.append(String.format("  Total Received:   %d\n", messagesReceived.get()));
        sb.append(String.format("  Total ACK'd:      %d\n", messagesAcked.get()));
        sb.append(String.format("  Success Rate:     %.1f%%\n", (messagesAcked.get() * 100.0) / Math.max(messagesSent.get(), 1)));

        sb.append(String.format("\nCONNECTIONS:\n"));
        sb.append(String.format("  Failures:         %d\n", connectionFailures.get()));
        sb.append(String.format("  Retries:          %d\n", retryCount.get()));

        sb.append(String.format("\nPERFORMANCE:\n"));
        sb.append(String.format("  Total Runtime:    %.2f seconds\n", getTotalRuntime() / 1000.0));
        sb.append(String.format("  Throughput:       %.1f messages/second\n", getMessagesPerSecond()));



        sb.append("================================================================================\n");
        return sb.toString();
    }
}

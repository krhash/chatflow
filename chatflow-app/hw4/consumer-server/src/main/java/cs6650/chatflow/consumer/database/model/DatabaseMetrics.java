package cs6650.chatflow.consumer.database.model;

/**
 * Database performance metrics with duplicate tracking.
 * Separates duplicates from failures for accurate monitoring.
 */
public class DatabaseMetrics {
    private long totalWrites;
    private long totalDuplicates;  // NEW: Track prevented duplicates
    private long totalReads;
    private long failedWrites;
    private long failedReads;
    private double avgWriteLatencyMs;
    private double avgReadLatencyMs;
    private double p95WriteLatencyMs;
    private double p99WriteLatencyMs;
    private int pendingWrites;
    private int dlqSize;           // NEW: DLQ size
    private long dlqRecovered;     // NEW: DLQ recovered messages
    private long dlqAbandoned;     // NEW: DLQ abandoned messages
    private long lastFlushTime;

    // Getters and setters
    public long getTotalWrites() { return totalWrites; }
    public void setTotalWrites(long totalWrites) { this.totalWrites = totalWrites; }

    public long getTotalDuplicates() { return totalDuplicates; }
    public void setTotalDuplicates(long totalDuplicates) { this.totalDuplicates = totalDuplicates; }

    public long getTotalReads() { return totalReads; }
    public void setTotalReads(long totalReads) { this.totalReads = totalReads; }

    public long getFailedWrites() { return failedWrites; }
    public void setFailedWrites(long failedWrites) { this.failedWrites = failedWrites; }

    public long getFailedReads() { return failedReads; }
    public void setFailedReads(long failedReads) { this.failedReads = failedReads; }

    public double getAvgWriteLatencyMs() { return avgWriteLatencyMs; }
    public void setAvgWriteLatencyMs(double avgWriteLatencyMs) { this.avgWriteLatencyMs = avgWriteLatencyMs; }

    public double getAvgReadLatencyMs() { return avgReadLatencyMs; }
    public void setAvgReadLatencyMs(double avgReadLatencyMs) { this.avgReadLatencyMs = avgReadLatencyMs; }

    public double getP95WriteLatencyMs() { return p95WriteLatencyMs; }
    public void setP95WriteLatencyMs(double p95WriteLatencyMs) { this.p95WriteLatencyMs = p95WriteLatencyMs; }

    public double getP99WriteLatencyMs() { return p99WriteLatencyMs; }
    public void setP99WriteLatencyMs(double p99WriteLatencyMs) { this.p99WriteLatencyMs = p99WriteLatencyMs; }

    public int getPendingWrites() { return pendingWrites; }
    public void setPendingWrites(int pendingWrites) { this.pendingWrites = pendingWrites; }

    public int getDlqSize() { return dlqSize; }
    public void setDlqSize(int dlqSize) { this.dlqSize = dlqSize; }

    public long getDlqRecovered() { return dlqRecovered; }
    public void setDlqRecovered(long dlqRecovered) { this.dlqRecovered = dlqRecovered; }

    public long getDlqAbandoned() { return dlqAbandoned; }
    public void setDlqAbandoned(long dlqAbandoned) { this.dlqAbandoned = dlqAbandoned; }

    public long getLastFlushTime() { return lastFlushTime; }
    public void setLastFlushTime(long lastFlushTime) { this.lastFlushTime = lastFlushTime; }

    @Override
    public String toString() {
        return String.format(
                "DatabaseMetrics{" +
                        "writes=%d, duplicates=%d (prevented), reads=%d, " +
                        "failedWrites=%d, avgWriteLatency=%.2fms, " +
                        "pendingWrites=%d, dlqSize=%d, dlqRecovered=%d, dlqAbandoned=%d}",
                totalWrites, totalDuplicates, totalReads,
                failedWrites, avgWriteLatencyMs,
                pendingWrites, dlqSize, dlqRecovered, dlqAbandoned
        );
    }

    /**
     * Get success rate percentage (excluding duplicates)
     */
    public double getSuccessRate() {
        long total = totalWrites + failedWrites;
        return total > 0 ? (totalWrites * 100.0 / total) : 100.0;
    }

    /**
     * Get DLQ recovery rate percentage
     */
    public double getDlqRecoveryRate() {
        long total = dlqRecovered + dlqAbandoned;
        return total > 0 ? (dlqRecovered * 100.0 / total) : 100.0;
    }
}

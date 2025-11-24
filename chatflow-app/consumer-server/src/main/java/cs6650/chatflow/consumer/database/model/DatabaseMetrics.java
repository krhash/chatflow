package cs6650.chatflow.consumer.database.model;

/**
 * Database performance metrics
 */
public class DatabaseMetrics {
    private long totalWrites;
    private long totalReads;
    private long failedWrites;
    private long failedReads;
    private double avgWriteLatencyMs;
    private double avgReadLatencyMs;
    private double p95WriteLatencyMs;
    private double p99WriteLatencyMs;
    private int pendingWrites;
    private long lastFlushTime;

    // Getters and setters
    public long getTotalWrites() { return totalWrites; }
    public void setTotalWrites(long totalWrites) { this.totalWrites = totalWrites; }

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

    public long getLastFlushTime() { return lastFlushTime; }
    public void setLastFlushTime(long lastFlushTime) { this.lastFlushTime = lastFlushTime; }

    @Override
    public String toString() {
        return String.format("DatabaseMetrics{writes=%d, reads=%d, failedWrites=%d, avgWriteLatency=%.2fms, pendingWrites=%d}",
                totalWrites, totalReads, failedWrites, avgWriteLatencyMs, pendingWrites);
    }
}

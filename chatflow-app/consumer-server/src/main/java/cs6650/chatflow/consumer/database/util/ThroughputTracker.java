package cs6650.chatflow.consumer.database.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks write throughput and logs final statistics when threshold is reached.
 * Automatically logs throughput when total writes reach configured milestones.
 */
public class ThroughputTracker {
    private static final Logger logger = LoggerFactory.getLogger(ThroughputTracker.class);

    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong firstMessageTime = new AtomicLong(0);
    private final AtomicLong lastMessageTime = new AtomicLong(0);
    private final AtomicLong testStartTime = new AtomicLong(0);

    // Milestone tracking
    private volatile boolean milestone100kLogged = false;
    private volatile boolean milestone250kLogged = false;
    private volatile boolean milestone500kLogged = false;

    /**
     * Record a write operation
     */
    public void recordWrite(int messageCount) {
        long now = System.currentTimeMillis();
        long total = totalWrites.addAndGet(messageCount);

        // Record first message time
        firstMessageTime.compareAndSet(0, now);

        // Update last message time
        lastMessageTime.set(now);

        // Set test start time on first write
        testStartTime.compareAndSet(0, now);

        // Check milestones
        checkMilestones(total);
    }

    /**
     * Check and log milestones
     */
    private void checkMilestones(long total) {
        if (total >= 100000 && !milestone100kLogged) {
            milestone100kLogged = true;
            logThroughput("100K Milestone", total);
        }

        if (total >= 250000 && !milestone250kLogged) {
            milestone250kLogged = true;
            logThroughput("250K Milestone", total);
        }

        if (total >= 500000 && !milestone500kLogged) {
            milestone500kLogged = true;
            logThroughput("500K COMPLETE", total);
            logFinalStatistics();
        }
    }

    /**
     * Log throughput at current point
     */
    private void logThroughput(String milestone, long totalMessages) {
        long now = System.currentTimeMillis();
        long startTime = testStartTime.get();

        if (startTime > 0) {
            long durationMs = now - startTime;
            long durationSec = durationMs / 1000;

            if (durationSec > 0) {
                double throughput = (double) totalMessages / durationSec;

                logger.info("========================================");
                logger.info("📊 {} REACHED", milestone);
                logger.info("========================================");
                logger.info("Total Messages Written: {}", totalMessages);
                logger.info("Duration: {} seconds ({} ms)", durationSec, durationMs);
                logger.info("Average Throughput: {:.2f} messages/sec", throughput);
                logger.info("========================================");
            }
        }
    }

    /**
     * Log final comprehensive statistics
     */
    public void logFinalStatistics() {
        long total = totalWrites.get();
        long startTime = testStartTime.get();
        long endTime = lastMessageTime.get();

        if (startTime > 0 && total > 0) {
            long durationMs = endTime - startTime;
            long durationSec = durationMs / 1000;

            double throughput = (double) total / durationSec;
            double messagesPerMs = (double) total / durationMs;

            logger.info("");
            logger.info("╔════════════════════════════════════════════════════════╗");
            logger.info("║          FINAL WRITE THROUGHPUT STATISTICS            ║");
            logger.info("╚════════════════════════════════════════════════════════╝");
            logger.info("");
            logger.info("Test Completion Summary:");
            logger.info("  Total Messages Written:     {}", total);
            logger.info("  Test Duration:              {} seconds", durationSec);
            logger.info("  Duration (precise):         {} ms", durationMs);
            logger.info("");
            logger.info("Throughput Metrics:");
            logger.info("  Average Throughput:         {:.2f} messages/sec", throughput);
            logger.info("  Messages per millisecond:   {:.4f}", messagesPerMs);
            logger.info("  Time per message:           {:.4f} ms", 1.0 / messagesPerMs);
            logger.info("");
            logger.info("Performance Analysis:");

            // Target analysis
            double targetThroughput = 8333.33;  // 500K / 60s
            double performanceRatio = (throughput / targetThroughput) * 100;

            logger.info("  Target Throughput:          {:.2f} messages/sec", targetThroughput);
            logger.info("  Actual vs Target:           {:.2f}%", performanceRatio);

            if (performanceRatio >= 100) {
                logger.info("  Status:                     ✅ EXCEEDED TARGET");
            } else if (performanceRatio >= 90) {
                logger.info("  Status:                     ✅ MEETS TARGET");
            } else {
                logger.info("  Status:                     ⚠️  BELOW TARGET");
            }

            logger.info("");
            logger.info("╚════════════════════════════════════════════════════════╝");
            logger.info("");
        }
    }

    /**
     * Get current throughput
     */
    public double getCurrentThroughput() {
        long total = totalWrites.get();
        long startTime = testStartTime.get();

        if (startTime > 0 && total > 0) {
            long durationSec = (System.currentTimeMillis() - startTime) / 1000;
            if (durationSec > 0) {
                return (double) total / durationSec;
            }
        }
        return 0.0;
    }

    public long getTotalWrites() {
        return totalWrites.get();
    }

    public long getTestDurationSeconds() {
        long startTime = testStartTime.get();
        if (startTime > 0) {
            return (System.currentTimeMillis() - startTime) / 1000;
        }
        return 0;
    }

    /**
     * Reset tracker for new test
     */
    public void reset() {
        totalWrites.set(0);
        firstMessageTime.set(0);
        lastMessageTime.set(0);
        testStartTime.set(0);
        milestone100kLogged = false;
        milestone250kLogged = false;
        milestone500kLogged = false;
    }
}

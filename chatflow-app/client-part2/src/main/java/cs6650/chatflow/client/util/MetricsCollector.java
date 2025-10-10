package cs6650.chatflow.client.util;

import cs6650.chatflow.client.model.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Collects detailed performance metrics for WebSocket load testing.
 * Records per-message latencies, writes to CSV, and calculates statistical aggregations.
 */
public class MetricsCollector {
    private static final Logger logger = LoggerFactory.getLogger(MetricsCollector.class);

    private static final String CSV_HEADER = "timestamp,messageType,latency,statusCode,roomId";
    private static final String CSV_FILENAME = "metrics.csv";

    // Per-message tracking
    private final Map<String, MessageMetrics> messageMetrics;
    private final FileWriter csvWriter;

    // Statistical aggregations
    private final List<Long> latencies;
    private final Map<Integer, AtomicLong> roomMessageCount;
    private final Map<String, AtomicLong> messageTypeCount;

    // Throughput tracking (10-second buckets)
    private final Map<Long, AtomicLong> throughputBuckets;
    private final long bucketSizeMillis = 10_000; // 10 seconds

    // Room timing tracking for throughput calculation
    private final Map<Integer, Long> roomStartTimes;
    private final Map<Integer, Long> roomEndTimes;

    // Async CSV writing
    private final ExecutorService csvWriterExecutor;

    /**
     * Creates a new metrics collector.
     */
    public MetricsCollector() throws IOException {
        this.messageMetrics = new ConcurrentHashMap<>();
        this.latencies = Collections.synchronizedList(new ArrayList<>());
        this.roomMessageCount = new ConcurrentHashMap<>();
        this.messageTypeCount = new ConcurrentHashMap<>();
        this.throughputBuckets = new ConcurrentHashMap<>();
        this.roomStartTimes = new ConcurrentHashMap<>();
        this.roomEndTimes = new ConcurrentHashMap<>();

        // Initialize async CSV writer
        this.csvWriterExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "CsvWriter");
            t.setDaemon(true); // Don't prevent JVM shutdown
            return t;
        });

        // Initialize CSV file
        Files.deleteIfExists(Paths.get(CSV_FILENAME));
        this.csvWriter = new FileWriter(CSV_FILENAME);
        csvWriter.write(CSV_HEADER + "\n");
        csvWriter.flush();

        logger.info("MetricsCollector initialized - writing to {}", CSV_FILENAME);
    }

    /**
     * Records when a message is sent.
     * @param message the message being sent
     * @param roomId the target room ID
     */
    public void recordMessageSent(ChatMessage message, int roomId) {
        long sendTime = System.currentTimeMillis();
        String messageId = message.getMessageId();

        MessageMetrics metrics = new MessageMetrics();
        metrics.sendTime = sendTime;
        metrics.messageType = message.getMessageType();
        metrics.roomId = roomId;

        messageMetrics.put(messageId, metrics);

        // Update room and message type counters
        roomMessageCount.computeIfAbsent(roomId, k -> new AtomicLong()).incrementAndGet();
        messageTypeCount.computeIfAbsent(message.getMessageType(), k -> new AtomicLong()).incrementAndGet();

        // Track room timing for throughput calculation
        roomStartTimes.compute(roomId, (k, v) -> v == null ? sendTime : Math.min(v, sendTime));
        roomEndTimes.compute(roomId, (k, v) -> v == null ? sendTime : Math.max(v, sendTime));

        // Update throughput bucket
        long bucket = sendTime / bucketSizeMillis;
        throughputBuckets.computeIfAbsent(bucket, k -> new AtomicLong()).incrementAndGet();
    }

    /**
     * Records when a message response is received and calculates latency.
     * @param messageId the message ID
     * @param receiveTime the timestamp when response was received
     * @param statusCode the HTTP status code (200 for success)
     */
    public void recordMessageReceived(String messageId, long receiveTime, int statusCode) {
        MessageMetrics metrics = messageMetrics.get(messageId);
        if (metrics == null) {
            logger.warn("Received response for unknown message: {}", messageId);
            return;
        }

        long latency = receiveTime - metrics.sendTime;
        metrics.receiveTime = receiveTime;
        metrics.latency = latency;
        metrics.statusCode = statusCode;

        // Add to latencies list for statistical calculations
        latencies.add(latency);

        // Write to CSV asynchronously to avoid blocking response processing
        final String csvLine = String.format("%d,%s,%d,%d,%d\n",
            metrics.sendTime, metrics.messageType, latency, statusCode, metrics.roomId);

        csvWriterExecutor.submit(() -> {
            try {
                csvWriter.write(csvLine);
                csvWriter.flush();
            } catch (IOException e) {
                logger.error("Failed to write metrics to CSV: {}", e.getMessage());
            }
        });

        logger.debug("Recorded latency for message {}: {}ms", messageId, latency);
    }

    /**
     * Calculates statistical metrics after test completion.
     * @return StatisticalMetrics object with all calculated values
     */
    public StatisticalMetrics calculateStatistics() {
        // Shutdown async CSV writer and wait for completion
        csvWriterExecutor.shutdown();
        try {
            if (!csvWriterExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                csvWriterExecutor.shutdownNow();
                logger.warn("CSV writer executor did not terminate cleanly");
            }
        } catch (InterruptedException e) {
            csvWriterExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        // Close CSV writer
        try {
            csvWriter.close();
        } catch (IOException e) {
            logger.error("Failed to close CSV writer: {}", e.getMessage());
        }

        StatisticalMetrics stats = new StatisticalMetrics();

        if (latencies.isEmpty()) {
            logger.warn("No latency data available for statistical calculations");
            return stats;
        }

        // Sort latencies for percentile calculations
        List<Long> sortedLatencies = new ArrayList<>(latencies);
        Collections.sort(sortedLatencies);

        // Basic statistics
        stats.totalMessages = latencies.size();
        stats.meanLatency = latencies.stream().mapToLong(Long::longValue).average().orElse(0.0);
        stats.minLatency = sortedLatencies.get(0);
        stats.maxLatency = sortedLatencies.get(sortedLatencies.size() - 1);

        // Median
        int middle = sortedLatencies.size() / 2;
        if (sortedLatencies.size() % 2 == 0) {
            stats.medianLatency = (sortedLatencies.get(middle - 1) + sortedLatencies.get(middle)) / 2.0;
        } else {
            stats.medianLatency = sortedLatencies.get(middle).doubleValue();
        }

        // Percentiles
        stats.percentile95 = calculatePercentile(sortedLatencies, 95);
        stats.percentile99 = calculatePercentile(sortedLatencies, 99);

        // Per-room throughput (messages per second)
        stats.roomThroughput = new HashMap<>();
        for (Map.Entry<Integer, AtomicLong> entry : roomMessageCount.entrySet()) {
            int roomId = entry.getKey();
            long messageCount = entry.getValue().get();
            Long startTime = roomStartTimes.get(roomId);
            Long endTime = roomEndTimes.get(roomId);

            if (startTime != null && endTime != null && startTime < endTime) {
                double durationSeconds = (endTime - startTime) / 1000.0;
                double throughputPerSecond = messageCount / durationSeconds;
                stats.roomThroughput.put(roomId, (long) throughputPerSecond);
            } else {
                stats.roomThroughput.put(roomId, 0L);
            }
        }

        // Message type distribution
        stats.messageTypeDistribution = messageTypeCount.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get()
            ));

        // Throughput over time (messages per second in 10-second buckets)
        long totalDurationBuckets = throughputBuckets.keySet().stream()
            .max(Long::compare).orElse(0L) - throughputBuckets.keySet().stream()
            .min(Long::compare).orElse(0L) + 1;

        if (totalDurationBuckets > 0) {
            stats.averageThroughputPerSecond = latencies.size() / (totalDurationBuckets * (bucketSizeMillis / 1000.0));
        }

        logger.info("Calculated statistics for {} messages", stats.totalMessages);
        return stats;
    }

    /**
     * Calculates percentile from sorted list.
     */
    private double calculatePercentile(List<Long> sortedLatencies, int percentile) {
        if (sortedLatencies.isEmpty()) return 0.0;

        double index = (percentile / 100.0) * (sortedLatencies.size() - 1);
        int lowerIndex = (int) Math.floor(index);
        int upperIndex = (int) Math.ceil(index);

        if (lowerIndex == upperIndex) {
            return sortedLatencies.get(lowerIndex).doubleValue();
        }

        long lowerValue = sortedLatencies.get(lowerIndex);
        long upperValue = sortedLatencies.get(upperIndex);
        return lowerValue + (upperValue - lowerValue) * (index - lowerIndex);
    }

    /**
     * Inner class to hold per-message metrics data.
     */
    private static class MessageMetrics {
        long sendTime;
        long receiveTime;
        long latency;
        String messageType;
        int roomId;
        int statusCode;
    }

    /**
     * Container class for statistical metrics results.
     */
    public static class StatisticalMetrics {
        public int totalMessages;
        public double meanLatency;
        public double medianLatency;
        public double percentile95;
        public double percentile99;
        public long minLatency;
        public long maxLatency;
        public double averageThroughputPerSecond;
        public Map<Integer, Long> roomThroughput;
        public Map<String, Long> messageTypeDistribution;

        public StatisticalMetrics() {
            this.roomThroughput = new HashMap<>();
            this.messageTypeDistribution = new HashMap<>();
        }
    }
}

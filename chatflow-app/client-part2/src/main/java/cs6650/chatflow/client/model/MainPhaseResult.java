package cs6650.chatflow.client.model;

import cs6650.chatflow.client.util.MetricsCollector;

import java.util.Map;

/**
 * Result of main phase execution.
 */
public class MainPhaseResult {
    private final int messagesReceived;
    private final int messagesFailed;
    private final long testEndTime;
    private final int totalConnections;
    private final int openConnections;
    private final int reconnections;

    // Performance metrics
    private final MetricsCollector.StatisticalMetrics statisticalMetrics;

    public MainPhaseResult(int messagesReceived, int messagesFailed, long testEndTime,
                          int totalConnections, int openConnections, int reconnections,
                          MetricsCollector.StatisticalMetrics statisticalMetrics) {
        this.messagesReceived = messagesReceived;
        this.messagesFailed = messagesFailed;
        this.testEndTime = testEndTime;
        this.totalConnections = totalConnections;
        this.openConnections = openConnections;
        this.reconnections = reconnections;
        this.statisticalMetrics = statisticalMetrics;
    }

    public int getMessagesReceived() {
        return messagesReceived;
    }

    public int getMessagesFailed() {
        return messagesFailed;
    }

    public long getTestEndTime() {
        return testEndTime;
    }

    public int getTotalConnections() {
        return totalConnections;
    }

    public int getOpenConnections() {
        return openConnections;
    }

    public int getReconnections() {
        return reconnections;
    }

    public MetricsCollector.StatisticalMetrics getStatisticalMetrics() {
        return statisticalMetrics;
    }
}

package cs6650.chatflow.client.model;

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

    public MainPhaseResult(int messagesReceived, int messagesFailed, long testEndTime,
                          int totalConnections, int openConnections, int reconnections) {
        this.messagesReceived = messagesReceived;
        this.messagesFailed = messagesFailed;
        this.testEndTime = testEndTime;
        this.totalConnections = totalConnections;
        this.openConnections = openConnections;
        this.reconnections = reconnections;
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
}

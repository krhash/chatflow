package cs6650.chatflow.client.coordinator;

/**
 * Result of main phase execution.
 */
public class MainPhaseResult {
    private final int messagesReceived;
    private final long testEndTime;

    public MainPhaseResult(int messagesReceived, long testEndTime) {
        this.messagesReceived = messagesReceived;
        this.testEndTime = testEndTime;
    }

    public int getMessagesReceived() {
        return messagesReceived;
    }

    public long getTestEndTime() {
        return testEndTime;
    }
}

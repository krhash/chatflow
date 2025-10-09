package cs6650.chatflow.client.commons;

/**
 * Configuration constants for client warmup phase.
 */
public class Constants {

    // Thread configuration
    public static final int WARMUP_THREADS = 32;
    public static final int MESSAGES_PER_THREAD = 1000;
    public static final int ROOM_COUNT = 20;

    // Message types for random generation
    public static final String[] MESSAGE_TYPES = {"TEXT", "JOIN", "LEAVE"};

    // Predefined message pool for random selection
    public static final String[] MESSAGE_POOL = {
            "Hello!", "How's it going?", "Good morning", "Happy chatting", "What's new?",
            "Did you see that?", "This is fun", "Great day today", "Keep it up", "Cheers!",
            "Nice to meet you", "How are you?", "What's up?", "Good afternoon", "Good evening",
            "Have a great day", "See you later", "Talk to you soon", "Thanks for chatting", "You're welcome",
            "That's interesting", "Tell me more", "I agree", "That's funny", "Well done",
            "Congratulations", "Good luck", "Take care", "Stay safe", "Be well",
            "How's the weather?", "What's your plan?", "Sounds good", "I'm excited", "Let's go",
            "That's awesome", "No problem", "My pleasure", "Absolutely", "Definitely",
            "I understand", "Makes sense", "Good point", "Well said", "Exactly",
            "Totally agree", "Same here", "Me too", "Likewise", "Cheers to that"
    };

    // Connection retry configuration
    public static final int MAX_RECONNECT_ATTEMPTS = 5;
    public static final long BASE_BACKOFF_MILLIS = 1000L;

    // Timeout configuration
    public static final int RESPONSE_TIMEOUT_SECONDS = 30;

    // Message retry configuration
    public static final int MESSAGE_RETRY_ATTEMPTS = 5;
    public static final long MESSAGE_RETRY_INITIAL_DELAY_MILLIS = 100;
    public static final long MESSAGE_RETRY_MAX_DELAY_MILLIS = 2000;

    // Load test configuration
    public static final int TOTAL_MESSAGES = 500_000;
    public static final int WARMUP_TOTAL_MESSAGES = WARMUP_THREADS * MESSAGES_PER_THREAD;

    // Main phase configuration
    public static final int MAIN_PHASE_CONNECTION_POOL_SIZE = 8;
    public static final int MAIN_PHASE_CONSUMER_THREADS = 8;
    public static final int RESPONSE_THREAD_POOL_SIZE = 8;
    public static final int RETRY_WORKER_THREADS = 8;
    public static final int MESSAGE_QUEUE_CAPACITY = 5000;
    public static final int RESPONSE_QUEUE_CAPACITY = 2000;
    public static final int DEAD_LETTER_QUEUE_CAPACITY = 1000;
    public static final int MESSAGE_TIMEOUT_MILLIS = 60_000; // 60 seconds timeout for message response
    public static final int DLQ_RETRY_ATTEMPTS = 2;
    public static final int PROGRESS_REPORT_INTERVAL = 10000;

    // Queue monitoring configuration
    public static final int MONITORING_INTERVAL_SECONDS = 5;
    public static final int QUEUE_STARVATION_THRESHOLD = 10;
    public static final double QUEUE_UNDERUTILIZATION_PERCENT = 20.0;
    public static final double RESPONSE_QUEUE_HIGH_WATERMARK = 80.0;
}

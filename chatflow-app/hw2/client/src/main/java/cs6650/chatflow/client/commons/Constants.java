package cs6650.chatflow.client.commons;

/**
 * Constants for the Distributed Chat Client.
 * Based on client-part1 constants but adapted for distributed architecture.
 */
public class Constants {

    // Server endpoints
    public static final String PRODUCER_SERVER_HOST = "localhost";
    public static final int PRODUCER_SERVER_PORT = 8080;
    public static final String PRODUCER_SERVER_PATH = "/chatflow-server/chat";

    public static final String CONSUMER_SERVER_HOST = "localhost";
    public static final int CONSUMER_SERVER_PORT = 8081;
    public static final String CONSUMER_SERVER_PATH = "/consumer-server/chatflow-receiver/";

    // Message types
    public static final String MESSAGE_TYPE_TEXT = "TEXT";
    public static final String MESSAGE_TYPE_JOIN = "JOIN";
    public static final String MESSAGE_TYPE_LEAVE = "LEAVE";
    public static final String MESSAGE_TYPE_ACK = "ACK";

    // Messages will be continuously sent and received, no fixed total
    // public static final int TOTAL_MESSAGES = 1; // Removed fixed message count

    // Room configuration
    public static final int MIN_ROOM_ID = 1;
    public static final int MAX_ROOM_ID = 20;
    public static final int TOTAL_ROOMS = 20;

    // Message queue capacity
    public static final int MESSAGE_QUEUE_CAPACITY = 5000;

    // User ID prefix for this client
    public static final String USER_ID_PREFIX = "distributed-user-";

    // Predefined message pool for random selection (copied from client-part1)
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

    private Constants() {
        // Utility class
    }
}

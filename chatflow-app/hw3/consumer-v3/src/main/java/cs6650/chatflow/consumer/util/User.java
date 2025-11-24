package cs6650.chatflow.consumer.util;

import javax.websocket.Session;

/**
 * Represents a user connected to a room.
 */
public class User {
    private final String userId;
    private Session session;
    private long connectTime;

    public User(String userId, Session session) {
        this.userId = userId;
        this.session = session;
        this.connectTime = System.currentTimeMillis();
    }

    public String getUserId() {
        return userId;
    }

    public Session getSession() {
        return session;
    }

    public void setSession(Session session) {
        this.session = session;
    }

    public long getConnectTime() {
        return connectTime;
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        User user = (User) obj;
        return userId.equals(user.userId);
    }

    @Override
    public int hashCode() {
        return userId.hashCode();
    }

    @Override
    public String toString() {
        return "User{" +
                "userId='" + userId + '\'' +
                ", connected=" + isConnected() +
                ", connectTime=" + connectTime +
                '}';
    }
}

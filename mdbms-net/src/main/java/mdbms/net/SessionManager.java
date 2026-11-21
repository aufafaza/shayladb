package mdbms.net;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private final Map<String, ClientSession> activeSessions = new ConcurrentHashMap<>();

    public String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public ClientSession newSession() {
        while (true) {
            ClientSession session = new ClientSession(generateSessionId());
            if (activeSessions.putIfAbsent(session.getSessionId(), session) == null) {
                return session;
            }
        }
    }

    public boolean removeSession(String sessionId) {
        return activeSessions.remove(sessionId) != null;
    }

    public int getSessionCount() {
        return activeSessions.size();
    }

    public Map<String, ClientSession> getActiveSessions() {
        return Collections.unmodifiableMap(Map.copyOf(activeSessions));
    }

    public boolean containsSession(String sessionId) {
        return activeSessions.containsKey(sessionId);
    }
}

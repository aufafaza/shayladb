package mdbms.net;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    private Map<String, ClientSession> activeSession = new ConcurrentHashMap<>();

    public String generateSessionId() {
        String newId = UUID.randomUUID().toString().replace("-", "");
        while (activeSession.containsKey(newId)) {
            newId = UUID.randomUUID().toString().replace("-", "");
        }
        return newId;
    }

    public ClientSession newSession() {
        ClientSession session = new ClientSession(generateSessionId());
        activeSession.put(session.getSessionId(), session);
        return session;
    }

    public void removeSession(String sessionId) {
        activeSession.remove(sessionId);
    }
}

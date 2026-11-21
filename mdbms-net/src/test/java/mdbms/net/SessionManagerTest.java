package mdbms.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

class SessionManagerTest {
    @Test
    void concurrentRegistrationUsesUniqueIdsAndNoRegistrationIsLost() throws Exception {
        SessionManager manager = new SessionManager();
        int sessionCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        Set<String> ids = ConcurrentHashMap.newKeySet();
        List<Future<ClientSession>> registrations = new ArrayList<>();

        try {
            for (int i = 0; i < sessionCount; i++) {
                registrations.add(executor.submit(() -> {
                    start.await();
                    ClientSession session = manager.newSession();
                    ids.add(session.getSessionId());
                    return session;
                }));
            }
            start.countDown();
            for (Future<ClientSession> registration : registrations) {
                registration.get();
            }
        } finally {
            executor.shutdownNow();
        }

        assertEquals(sessionCount, ids.size());
        assertEquals(sessionCount, manager.getSessionCount());
        assertEquals(sessionCount, manager.getActiveSessions().size());
    }

    @Test
    void concurrentRemovalOnlyRemovesTheRequestedSessions() throws Exception {
        SessionManager manager = new SessionManager();
        List<ClientSession> sessions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            sessions.add(manager.newSession());
        }

        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> removals = new ArrayList<>();
        try {
            for (ClientSession session : sessions) {
                removals.add(executor.submit(() -> {
                    start.await();
                    return manager.removeSession(session.getSessionId());
                }));
            }
            start.countDown();

            int removed = 0;
            for (Future<Boolean> removal : removals) {
                if (removal.get()) {
                    removed++;
                }
            }
            assertEquals(sessions.size(), removed);
        } finally {
            executor.shutdownNow();
        }

        assertEquals(0, manager.getSessionCount());
        assertTrue(manager.getActiveSessions().isEmpty());
    }

    @Test
    void snapshotDoesNotChangeWithLaterRegistrationsOrRemovals() {
        SessionManager manager = new SessionManager();
        ClientSession first = manager.newSession();

        java.util.Map<String, ClientSession> snapshot = manager.getActiveSessions();
        ClientSession second = manager.newSession();
        assertTrue(snapshot.containsKey(first.getSessionId()));
        assertFalse(snapshot.containsKey(second.getSessionId()));
        assertEquals(2, manager.getSessionCount());

        manager.removeSession(first.getSessionId());
        assertTrue(snapshot.containsKey(first.getSessionId()));
    }

    @Test
    void removalIsAtomicAndDuplicateRemovalFails() {
        SessionManager manager = new SessionManager();
        ClientSession session = manager.newSession();

        assertTrue(manager.removeSession(session.getSessionId()));
        assertFalse(manager.removeSession(session.getSessionId()));
        assertFalse(manager.containsSession(session.getSessionId()));
        assertEquals(Collections.emptyMap(), manager.getActiveSessions());
    }
}

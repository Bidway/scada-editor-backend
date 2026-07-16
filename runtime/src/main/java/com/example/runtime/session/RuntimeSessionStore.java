package com.example.runtime.session;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class RuntimeSessionStore {

    private final ConcurrentMap<String, RuntimeSession> sessions = new ConcurrentHashMap<>();

    public void put(RuntimeSession session) {
        sessions.put(session.getId(), session);
    }

    public RuntimeSession get(String sessionId) {
        return sessions.get(sessionId);
    }

    public RuntimeSession remove(String sessionId) {
        return sessions.remove(sessionId);
    }

    public Collection<RuntimeSession> all() {
        return sessions.values();
    }
}

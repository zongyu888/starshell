package com.aifinalshell.session;

import com.aifinalshell.config.AppConfig;
import com.aifinalshell.service.DatabaseManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chat sessions and messages
 */
public class SessionManager {
    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);
    private static SessionManager instance;
    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();
    private volatile Session currentSession;
    private volatile Long currentSessionId;

    private SessionManager() {}

    public static synchronized SessionManager getInstance() {
        if (instance == null) {
            instance = new SessionManager();
        }
        return instance;
    }

    public Session createSession(String name, Long serverId) {
        Session session = new Session(name);
        session.setServerId(serverId);
        try {
            session = DatabaseManager.getInstance().saveSession(session);
            sessions.put(session.getId(), session);
            logger.info("Created session: {} (id={})", name, session.getId());
        } catch (Exception e) {
            logger.error("Failed to create session", e);
        }
        return session;
    }

    public Session getSession(Long id) {
        Session session = sessions.get(id);
        if (session == null) {
            try {
                session = DatabaseManager.getInstance().getSession(id);
                if (session != null) {
                    sessions.put(id, session);
                }
            } catch (Exception e) {
                logger.error("Failed to get session {}", id, e);
            }
        }
        return session;
    }

    public List<Session> getAllSessions() {
        try {
            List<Session> list = DatabaseManager.getInstance().getAllSessions();
            // 合并更新：保留已有对象的 messages 列表，更新元数据；新对象才 put
            for (Session s : list) {
                Session existing = sessions.get(s.getId());
                if (existing != null) {
                    // 已有对象，保留其内存中的 messages（可能比 DB 新），更新元数据
                    existing.setName(s.getName());
                    existing.setServerId(s.getServerId());
                    // 不替换 messages
                } else {
                    sessions.put(s.getId(), s);
                }
            }
            // 移除已删除的会话
            Set<Long> dbIds = new HashSet<>();
            for (Session s : list) dbIds.add(s.getId());
            sessions.keySet().removeIf(id -> !dbIds.contains(id));
            List<Session> ordered = new ArrayList<>(sessions.values());
            ordered.sort(Comparator
                    .comparing(Session::getCreatedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(Session::getId,
                            Comparator.nullsLast(Comparator.naturalOrder())));
            return ordered;
        } catch (Exception e) {
            logger.error("Failed to get sessions", e);
            return new ArrayList<>();
        }
    }

    public void setCurrentSession(Session session) {
        this.currentSession = session;
        if (session != null) {
            this.currentSessionId = session.getId();
            // 确保映射表里有同一对象（若映射表有同 id 但不同实例，用 currentSession 替换）
            if (session.getId() != null) {
                Session existing = sessions.get(session.getId());
                if (existing != null && existing != session) {
                    // 映射表已有不同实例，用 currentSession 替换以保证一致
                    sessions.put(session.getId(), session);
                    // 合并 existing 的 messages 到 session（避免丢失）
                    if (session.getMessages().isEmpty() && !existing.getMessages().isEmpty()) {
                        session.setMessages(existing.getMessages());
                    }
                } else if (existing == null) {
                    sessions.put(session.getId(), session);
                }
            }
            // M-P2-4: persist last session id
            try {
                AppConfig.getInstance().setLastSessionId(String.valueOf(session.getId()));
            } catch (Exception e) {
                logger.warn("Failed to persist last session id", e);
            }
            if (session.getMessages().isEmpty()) {
                try {
                    List<Message> messages = DatabaseManager.getInstance().getMessages(session.getId());
                    session.setMessages(messages);
                } catch (Exception e) {
                    logger.error("Failed to load messages for session {}", session.getId(), e);
                }
            }
        } else {
            this.currentSessionId = null;
        }
    }

    public Session getCurrentSession() {
        if (currentSessionId != null) {
            Session cached = sessions.get(currentSessionId);
            if (cached != null) return cached;
        }
        return currentSession;  // 兜底
    }

    public void addMessage(Long sessionId, Message message) {
        try {
            message.setSessionId(sessionId);
            DatabaseManager.getInstance().saveMessage(message);
            Session session = sessions.get(sessionId);
            if (session != null) {
                session.addMessage(message);
            }
            // 兜底：若 currentSession 是同一 session 也更新
            if (currentSession != null && currentSession.getId() != null
                && currentSession.getId().equals(sessionId) && currentSession != session) {
                currentSession.addMessage(message);
            }
        } catch (Exception e) {
            logger.error("Failed to save message", e);
        }
    }

    public void deleteSession(Long id) {
        try {
            DatabaseManager.getInstance().deleteSession(id);
            sessions.remove(id);
            if (currentSession != null && currentSession.getId() != null && currentSession.getId().equals(id)) {
                currentSession = null;
            }
            if (currentSessionId != null && currentSessionId.equals(id)) {
                currentSessionId = null;
            }
        } catch (Exception e) {
            logger.error("Failed to delete session {}", id, e);
        }
    }
}

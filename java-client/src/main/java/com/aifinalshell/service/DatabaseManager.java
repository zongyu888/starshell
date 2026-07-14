package com.aifinalshell.service;

import com.aifinalshell.model.*;
import com.aifinalshell.session.Session;
import com.aifinalshell.session.Message;
import com.aifinalshell.util.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class DatabaseManager {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);
    private static DatabaseManager instance;
    private Connection connection;

    private DatabaseManager() {
        initDatabase();
    }

    public static synchronized DatabaseManager getInstance() {
        if (instance == null) {
            instance = new DatabaseManager();
        }
        return instance;
    }

    private void initDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:h2:./data/aifinalshell;AUTO_SERVER=TRUE", "sa", "");
            createTables();
            logger.info("数据库初始化成功");
        } catch (SQLException e) {
            logger.error("数据库初始化失败", e);
        }
    }

    private void createTables() throws SQLException {
        Statement stmt = connection.createStatement();

        stmt.execute("CREATE TABLE IF NOT EXISTS server_config (" +
                "id IDENTITY PRIMARY KEY, " +
                "name VARCHAR(100), " +
                "host VARCHAR(100), " +
                "port INT DEFAULT 22, " +
                "username VARCHAR(50), " +
                "password VARCHAR(200), " +
                "private_key_path VARCHAR(500), " +
                "log_path VARCHAR(500) DEFAULT '/var/log', " +
                "monitor_interval INT DEFAULT 5, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        stmt.execute("CREATE TABLE IF NOT EXISTS log_entry (" +
                "id IDENTITY PRIMARY KEY, " +
                "server_id BIGINT, " +
                "source VARCHAR(100), " +
                "level VARCHAR(20), " +
                "message TEXT, " +
                "raw_log TEXT, " +
                "timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "analyzed BOOLEAN DEFAULT FALSE, " +
                "ai_analysis TEXT)");

        stmt.execute("CREATE TABLE IF NOT EXISTS alert_record (" +
                "id IDENTITY PRIMARY KEY, " +
                "server_id BIGINT, " +
                "alert_type VARCHAR(50), " +
                "severity VARCHAR(20), " +
                "title VARCHAR(200), " +
                "message TEXT, " +
                "ai_suggestion TEXT, " +
                "acknowledged BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "acknowledged_at TIMESTAMP)");

        stmt.execute("CREATE TABLE IF NOT EXISTS deploy_task (" +
                "id IDENTITY PRIMARY KEY, " +
                "server_id BIGINT, " +
                "name VARCHAR(200), " +
                "description TEXT, " +
                "script TEXT, " +
                "status VARCHAR(20) DEFAULT 'PENDING', " +
                "output TEXT, " +
                "rollback_script TEXT, " +
                "rollback_available BOOLEAN DEFAULT FALSE, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "completed_at TIMESTAMP)");

        // Session table
        stmt.execute("CREATE TABLE IF NOT EXISTS chat_session (" +
                "id IDENTITY PRIMARY KEY, " +
                "name VARCHAR(200), " +
                "server_id BIGINT, " +
                "model VARCHAR(100), " +
                "agent VARCHAR(50), " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        // Message table
        stmt.execute("CREATE TABLE IF NOT EXISTS chat_message (" +
                "id IDENTITY PRIMARY KEY, " +
                "session_id BIGINT, " +
                "role VARCHAR(20), " +
                "content TEXT, " +
                "model VARCHAR(100), " +
                "tokens_used INT DEFAULT 0, " +
                "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        stmt.close();
    }

    public ServerConfig saveServerConfig(ServerConfig config) throws SQLException {
        String sql;
        if (config.getId() == null) {
            sql = "INSERT INTO server_config (name, host, port, username, password, private_key_path, log_path, monitor_interval) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        } else {
            sql = "UPDATE server_config SET name=?, host=?, port=?, username=?, password=?, " +
                    "private_key_path=?, log_path=?, monitor_interval=?, updated_at=CURRENT_TIMESTAMP WHERE id=?";
        }

        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        stmt.setString(1, config.getName());
        stmt.setString(2, config.getHost());
        stmt.setInt(3, config.getPort());
        stmt.setString(4, config.getUsername());
        // Encrypt password before storing
        stmt.setString(5, SecurityUtils.encrypt(config.getPassword()));
        stmt.setString(6, config.getPrivateKeyPath());
        stmt.setString(7, config.getLogPath());
        stmt.setInt(8, config.getMonitorInterval());

        if (config.getId() != null) {
            stmt.setLong(9, config.getId());
        }

        stmt.executeUpdate();

        if (config.getId() == null) {
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                config.setId(rs.getLong(1));
            }
        }

        stmt.close();
        return config;
    }

    public List<ServerConfig> getAllServerConfigs() throws SQLException {
        List<ServerConfig> configs = new ArrayList<>();
        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM server_config ORDER BY name");

        while (rs.next()) {
            ServerConfig config = new ServerConfig();
            config.setId(rs.getLong("id"));
            config.setName(rs.getString("name"));
            config.setHost(rs.getString("host"));
            config.setPort(rs.getInt("port"));
            config.setUsername(rs.getString("username"));
            // Decrypt password when loading
            config.setPassword(SecurityUtils.decrypt(rs.getString("password")));
            config.setPrivateKeyPath(rs.getString("private_key_path"));
            config.setLogPath(rs.getString("log_path"));
            config.setMonitorInterval(rs.getInt("monitor_interval"));
            configs.add(config);
        }

        stmt.close();
        return configs;
    }

    public void deleteServerConfig(Long id) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement("DELETE FROM server_config WHERE id=?");
        stmt.setLong(1, id);
        stmt.executeUpdate();
        stmt.close();
    }

    public LogEntry saveLogEntry(LogEntry entry) throws SQLException {
        String sql = "INSERT INTO log_entry (server_id, source, level, message, raw_log, timestamp, analyzed, ai_analysis) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        stmt.setLong(1, entry.getServerId());
        stmt.setString(2, entry.getSource());
        stmt.setString(3, entry.getLevel());
        stmt.setString(4, entry.getMessage());
        stmt.setString(5, entry.getRawLog());
        stmt.setTimestamp(6, Timestamp.valueOf(entry.getTimestamp()));
        stmt.setBoolean(7, entry.isAnalyzed());
        stmt.setString(8, entry.getAiAnalysis());

        stmt.executeUpdate();
        ResultSet rs = stmt.getGeneratedKeys();
        if (rs.next()) {
            entry.setId(rs.getLong(1));
        }

        stmt.close();
        return entry;
    }

    public List<LogEntry> getRecentLogs(Long serverId, int limit) throws SQLException {
        List<LogEntry> logs = new ArrayList<>();
        PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM log_entry WHERE server_id=? ORDER BY timestamp DESC LIMIT ?");
        stmt.setLong(1, serverId);
        stmt.setInt(2, limit);

        ResultSet rs = stmt.executeQuery();
        while (rs.next()) {
            LogEntry entry = new LogEntry();
            entry.setId(rs.getLong("id"));
            entry.setServerId(rs.getLong("server_id"));
            entry.setSource(rs.getString("source"));
            entry.setLevel(rs.getString("level"));
            entry.setMessage(rs.getString("message"));
            entry.setRawLog(rs.getString("raw_log"));
            entry.setTimestamp(rs.getTimestamp("timestamp").toLocalDateTime());
            entry.setAnalyzed(rs.getBoolean("analyzed"));
            entry.setAiAnalysis(rs.getString("ai_analysis"));
            logs.add(entry);
        }

        stmt.close();
        return logs;
    }

    public AlertRecord saveAlertRecord(AlertRecord alert) throws SQLException {
        String sql = "INSERT INTO alert_record (server_id, alert_type, severity, title, message, ai_suggestion, acknowledged, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        stmt.setLong(1, alert.getServerId());
        stmt.setString(2, alert.getAlertType());
        stmt.setString(3, alert.getSeverity());
        stmt.setString(4, alert.getTitle());
        stmt.setString(5, alert.getMessage());
        stmt.setString(6, alert.getAiSuggestion());
        stmt.setBoolean(7, alert.isAcknowledged());
        stmt.setTimestamp(8, Timestamp.valueOf(alert.getCreatedAt()));

        stmt.executeUpdate();
        ResultSet rs = stmt.getGeneratedKeys();
        if (rs.next()) {
            alert.setId(rs.getLong(1));
        }

        stmt.close();
        return alert;
    }

    public List<AlertRecord> getRecentAlerts(int limit) throws SQLException {
        List<AlertRecord> alerts = new ArrayList<>();
        PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM alert_record ORDER BY created_at DESC LIMIT ?");
        stmt.setInt(1, limit);
        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            AlertRecord alert = new AlertRecord();
            alert.setId(rs.getLong("id"));
            alert.setServerId(rs.getLong("server_id"));
            alert.setAlertType(rs.getString("alert_type"));
            alert.setSeverity(rs.getString("severity"));
            alert.setTitle(rs.getString("title"));
            alert.setMessage(rs.getString("message"));
            alert.setAiSuggestion(rs.getString("ai_suggestion"));
            alert.setAcknowledged(rs.getBoolean("acknowledged"));
            alert.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            alerts.add(alert);
        }

        stmt.close();
        return alerts;
    }

    public DeployTask saveDeployTask(DeployTask task) throws SQLException {
        String sql;
        if (task.getId() == null) {
            sql = "INSERT INTO deploy_task (server_id, name, description, script, status, output, rollback_script, rollback_available, created_at, completed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        } else {
            sql = "UPDATE deploy_task SET server_id=?, name=?, description=?, script=?, status=?, output=?, " +
                    "rollback_script=?, rollback_available=?, completed_at=? WHERE id=?";
        }

        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        stmt.setLong(1, task.getServerId());
        stmt.setString(2, task.getName());
        stmt.setString(3, task.getDescription());
        stmt.setString(4, task.getScript());
        stmt.setString(5, task.getStatus());
        stmt.setString(6, task.getOutput());
        stmt.setString(7, task.getRollbackScript());
        stmt.setBoolean(8, task.isRollbackAvailable());

        if (task.getId() == null) {
            stmt.setTimestamp(9, task.getCreatedAt() != null ? Timestamp.valueOf(task.getCreatedAt()) : null);
            stmt.setTimestamp(10, task.getCompletedAt() != null ? Timestamp.valueOf(task.getCompletedAt()) : null);
        } else {
            stmt.setTimestamp(9, task.getCompletedAt() != null ? Timestamp.valueOf(task.getCompletedAt()) : null);
            stmt.setLong(10, task.getId());
        }

        stmt.executeUpdate();

        if (task.getId() == null) {
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                task.setId(rs.getLong(1));
            }
        }

        stmt.close();
        return task;
    }

    // ========== Session Methods ==========

    public Session saveSession(Session session) throws SQLException {
        String sql;
        if (session.getId() == null) {
            sql = "INSERT INTO chat_session (name, server_id, model, agent, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        } else {
            sql = "UPDATE chat_session SET name=?, server_id=?, model=?, agent=?, updated_at=? WHERE id=?";
        }

        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        stmt.setString(1, session.getName());
        if (session.getServerId() != null) stmt.setLong(2, session.getServerId()); else stmt.setNull(2, java.sql.Types.BIGINT);
        stmt.setString(3, session.getModel());
        stmt.setString(4, session.getAgent());
        stmt.setTimestamp(5, session.getCreatedAt() != null ? Timestamp.valueOf(session.getCreatedAt()) : Timestamp.valueOf(java.time.LocalDateTime.now()));
        stmt.setTimestamp(6, Timestamp.valueOf(java.time.LocalDateTime.now()));

        if (session.getId() != null) {
            stmt.setLong(7, session.getId());
        }

        stmt.executeUpdate();

        if (session.getId() == null) {
            ResultSet rs = stmt.getGeneratedKeys();
            if (rs.next()) {
                session.setId(rs.getLong(1));
            }
        }

        stmt.close();
        return session;
    }

    public Session getSession(Long id) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement("SELECT * FROM chat_session WHERE id=?");
        stmt.setLong(1, id);
        ResultSet rs = stmt.executeQuery();

        Session session = null;
        if (rs.next()) {
            session = new Session();
            session.setId(rs.getLong("id"));
            session.setName(rs.getString("name"));
            session.setServerId(rs.getLong("server_id"));
            session.setModel(rs.getString("model"));
            session.setAgent(rs.getString("agent"));
            if (rs.getTimestamp("created_at") != null) session.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            if (rs.getTimestamp("updated_at") != null) session.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
        }

        stmt.close();
        return session;
    }

    public List<Session> getAllSessions() throws SQLException {
        List<Session> sessions = new ArrayList<>();
        Statement stmt = connection.createStatement();
        ResultSet rs = stmt.executeQuery("SELECT * FROM chat_session ORDER BY updated_at DESC");

        while (rs.next()) {
            Session session = new Session();
            session.setId(rs.getLong("id"));
            session.setName(rs.getString("name"));
            session.setServerId(rs.getLong("server_id"));
            session.setModel(rs.getString("model"));
            session.setAgent(rs.getString("agent"));
            if (rs.getTimestamp("created_at") != null) session.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            if (rs.getTimestamp("updated_at") != null) session.setUpdatedAt(rs.getTimestamp("updated_at").toLocalDateTime());
            sessions.add(session);
        }

        stmt.close();
        return sessions;
    }

    public void deleteSession(Long id) throws SQLException {
        PreparedStatement stmt = connection.prepareStatement("DELETE FROM chat_message WHERE session_id=?");
        stmt.setLong(1, id);
        stmt.executeUpdate();
        stmt.close();

        stmt = connection.prepareStatement("DELETE FROM chat_session WHERE id=?");
        stmt.setLong(1, id);
        stmt.executeUpdate();
        stmt.close();
    }

    // ========== Message Methods ==========

    public void saveMessage(Message message) throws SQLException {
        String sql = "INSERT INTO chat_message (session_id, role, content, model, tokens_used, created_at) VALUES (?, ?, ?, ?, ?, ?)";

        PreparedStatement stmt = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
        stmt.setLong(1, message.getSessionId());
        stmt.setString(2, message.getRole());
        stmt.setString(3, message.getContent());
        stmt.setString(4, message.getModel());
        stmt.setInt(5, message.getTokensUsed());
        stmt.setTimestamp(6, message.getCreatedAt() != null ? Timestamp.valueOf(message.getCreatedAt()) : Timestamp.valueOf(java.time.LocalDateTime.now()));

        stmt.executeUpdate();

        ResultSet rs = stmt.getGeneratedKeys();
        if (rs.next()) {
            message.setId(rs.getLong(1));
        }

        stmt.close();
    }

    public List<Message> getMessages(Long sessionId) throws SQLException {
        List<Message> messages = new ArrayList<>();
        PreparedStatement stmt = connection.prepareStatement("SELECT * FROM chat_message WHERE session_id=? ORDER BY created_at");
        stmt.setLong(1, sessionId);
        ResultSet rs = stmt.executeQuery();

        while (rs.next()) {
            Message msg = new Message();
            msg.setId(rs.getLong("id"));
            msg.setSessionId(rs.getLong("session_id"));
            msg.setRole(rs.getString("role"));
            msg.setContent(rs.getString("content"));
            msg.setModel(rs.getString("model"));
            msg.setTokensUsed(rs.getInt("tokens_used"));
            if (rs.getTimestamp("created_at") != null) msg.setCreatedAt(rs.getTimestamp("created_at").toLocalDateTime());
            messages.add(msg);
        }

        stmt.close();
        return messages;
    }

    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            logger.error("Failed to close database", e);
        }
    }
}

package com.aifinalshell.ssh;

import com.aifinalshell.model.ServerConfig;
import com.jcraft.jsch.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

public class SshConnectionManager {
    private static final Logger logger = LoggerFactory.getLogger(SshConnectionManager.class);
    private static SshConnectionManager instance;
    // Key is now a String connectionKey (e.g. "session_123_server_456") instead of just serverId
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ChannelShell> shellChannels = new ConcurrentHashMap<>();

    private SshConnectionManager() {}

    public static synchronized SshConnectionManager getInstance() {
        if (instance == null) {
            instance = new SshConnectionManager();
        }
        return instance;
    }

    /**
     * Create a unique connection key for a session-server pair
     */
    public static String connectionKey(Long sessionId, Long serverId) {
        return sessionId + "_" + serverId;
    }

    public Session connect(String connectionKey, ServerConfig config) throws JSchException {
        if (sessions.containsKey(connectionKey) && sessions.get(connectionKey).isConnected()) {
            return sessions.get(connectionKey);
        }

        JSch jsch = new JSch();

        if (config.getPrivateKeyPath() != null && !config.getPrivateKeyPath().isEmpty()) {
            jsch.addIdentity(config.getPrivateKeyPath());
        }

        Session session = jsch.getSession(config.getUsername(), config.getHost(), config.getPort());

        if (config.getPassword() != null && !config.getPassword().isEmpty()) {
            session.setPassword(config.getPassword());
        }

        java.util.Properties props = new java.util.Properties();
        // Use "accept-new" instead of "no" for better security:
        // Accepts new host keys automatically but warns when an existing host key changes
        props.put("StrictHostKeyChecking", "accept-new");
        session.setServerAliveInterval(60000);
        session.setServerAliveCountMax(3);
        props.put("PreferredAuthentications", "publickey,keyboard-interactive,password");
        session.setConfig(props);

        try {
            session.connect(30000);
        } catch (JSchException e) {
            // P0-7: 主机密钥变更检测。accept-new 策略下，已记录的主机密钥发生变更时会在此抛出。
            // 这是潜在中间人攻击的高危信号，用专用前缀标记，供上层 UI 区分并给出明确安全提示，
            // 而非混同于普通"连接失败"。不提供一键"信任并继续"，强制用户确认后手动更新 known_hosts。
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.contains("HostKey") || m.contains("REMOTE HOST IDENTIFICATION")
                    || m.contains("has changed") || m.contains("HOSTKEY")) {
                logger.warn("检测到主机密钥变更: {} host={}", connectionKey, config.getHost());
                throw new JSchException("HOST_KEY_CHANGED:" + m);
            }
            // 认证失败检测（密码错误、密钥被拒等）。用专用前缀标记，供上层 UI 弹出密码重输对话框。
            // 注意：主机密钥变更的判断必须先于认证失败，避免误归类。
            if (isAuthFailure(m)) {
                logger.warn("SSH 认证失败: {} host={}", connectionKey, config.getHost());
                throw new JSchException("AUTH_FAILED:" + m);
            }
            throw e;
        }

        if (session.isConnected()) {
            sessions.put(connectionKey, session);
            logger.info("SSH连接成功: {} key={}", config, connectionKey);
        }

        return session;
    }

    /**
     * 判断 JSchException 消息是否表示认证失败（密码错误、密钥被拒、所有认证方法耗尽等）。
     * 覆盖 JSch 0.1.55 与 OpenSSH 常见认证失败消息，供上层 UI 弹出密码重输对话框。
     * 主机密钥变更类消息不在此列（由调用方先判断 HOST_KEY_CHANGED）。
     */
    private static boolean isAuthFailure(String m) {
        if (m == null || m.isEmpty()) return false;
        String ml = m.toLowerCase();
        return ml.contains("auth fail")
                || ml.contains("auth cancellation")
                || ml.contains("authentication failed")
                || ml.contains("permission denied")
                || ml.contains("all configured authentication methods failed")
                || (ml.contains("publickey") && ml.contains("password") && ml.contains("fail"))
                || ml.contains("failed to authenticate");
    }

    public void disconnect(String connectionKey) {
        ChannelShell shellChannel = shellChannels.remove(connectionKey);
        if (shellChannel != null && shellChannel.isConnected()) {
            shellChannel.disconnect();
        }

        Session session = sessions.remove(connectionKey);
        if (session != null && session.isConnected()) {
            session.disconnect();
        }

        logger.info("SSH连接已断开: {}", connectionKey);
    }

    /**
     * Disconnect all connections for a given session
     */
    public void disconnectSession(Long sessionId) {
        String prefix = sessionId + "_";
        sessions.keySet().removeIf(key -> {
            if (key.startsWith(prefix)) {
                disconnect(key);
                return true;
            }
            return false;
        });
    }

    public void disconnectAll() {
        sessions.keySet().forEach(this::disconnect);
    }

    public ChannelShell openShell(String connectionKey) throws JSchException {
        Session session = sessions.get(connectionKey);
        if (session == null || !session.isConnected()) {
            throw new JSchException("SSH会话未连接: " + connectionKey);
        }

        ChannelShell channel = (ChannelShell) session.openChannel("shell");
        channel.setPtyType("xterm-256color", 120, 40, 0, 0);
        channel.connect(10000);

        shellChannels.put(connectionKey, channel);
        return channel;
    }

    public String executeCommand(String connectionKey, String command) throws JSchException, IOException {
        Session session = sessions.get(connectionKey);
        if (session == null || !session.isConnected()) {
            throw new JSchException("SSH会话未连接: " + connectionKey);
        }

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setInputStream(null);
        channel.setErrStream(null);

        InputStream in = channel.getInputStream();
        channel.connect();

        StringBuilder output = new StringBuilder();
        byte[] buffer = new byte[8192];
        long startTime = System.currentTimeMillis();
        long timeout = 300000;

        while (true) {
            while (in.available() > 0) {
                int len = in.read(buffer);
                if (len < 0) break;
                output.append(new String(buffer, 0, len, StandardCharsets.UTF_8));
            }
            if (channel.isClosed()) break;
            if (System.currentTimeMillis() - startTime > timeout) {
                logger.warn("命令执行超时: {}", command);
                break;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        channel.disconnect();
        return output.toString();
    }

    public void startTailLog(String connectionKey, String logPath, java.util.function.Consumer<String> onLine) throws JSchException, IOException {
        Session session = sessions.get(connectionKey);
        if (session == null || !session.isConnected()) {
            throw new JSchException("SSH会话未连接: " + connectionKey);
        }

        // Sanitize log path to prevent command injection
        String safePath = logPath.replaceAll("[^a-zA-Z0-9._/\\-]", "");

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand("tail -f " + safePath + " 2>/dev/null");
        channel.setInputStream(null);

        InputStream in = channel.getInputStream();
        channel.connect();

        Thread readerThread = new Thread(() -> {
            try {
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
                String line;
                while ((line = reader.readLine()) != null) {
                    onLine.accept(line);
                }
            } catch (IOException e) {
                if (!channel.isClosed()) {
                    logger.error("读取日志流失败", e);
                }
            } finally {
                channel.disconnect();
            }
        });
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public SftpChannel openSftp(String connectionKey) throws JSchException {
        Session session = sessions.get(connectionKey);
        if (session == null || !session.isConnected()) {
            throw new JSchException("SSH会话未连接: " + connectionKey);
        }

        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.connect(10000);
        return new SftpChannel(channel);
    }

    public boolean isConnected(String connectionKey) {
        Session session = sessions.get(connectionKey);
        return session != null && session.isConnected();
    }

    // Backward-compatible methods using a default server key. New session-aware UI code uses String keys.
    // These use a shared "default" connection per server

    private String defaultKey(Long serverId) {
        return "default_" + serverId;
    }

    public Session connect(Long serverId, ServerConfig config) throws JSchException {
        return connect(defaultKey(serverId), config);
    }

    public void disconnect(Long serverId) {
        disconnect(defaultKey(serverId));
    }

    public ChannelShell openShell(Long serverId) throws JSchException {
        return openShell(defaultKey(serverId));
    }

    public String executeCommand(Long serverId, String command) throws JSchException, IOException {
        return executeCommand(defaultKey(serverId), command);
    }

    public SftpChannel openSftp(Long serverId) throws JSchException {
        return openSftp(defaultKey(serverId));
    }

    public boolean isConnected(Long serverId) {
        return isConnected(defaultKey(serverId));
    }

    /**
     * Check if any connection to a server exists
     */
    public boolean isServerConnected(Long serverId) {
        String suffix = "_" + serverId;
        for (String key : sessions.keySet()) {
            if (key.endsWith(suffix)) {
                Session session = sessions.get(key);
                if (session != null && session.isConnected()) return true;
            }
        }
        return false;
    }

    /**
     * Return any live connection for a server except the supplied key.
     * Monitoring uses this when one of several tabs connected to the same
     * server is closed, so the server-level monitor can move to a surviving
     * session instead of being stopped accidentally.
     */
    public String findConnectedKey(Long serverId, String excludedKey) {
        if (serverId == null) return null;
        String suffix = "_" + serverId;
        for (String key : sessions.keySet()) {
            if (key.equals(excludedKey) || !key.endsWith(suffix)) continue;
            Session session = sessions.get(key);
            if (session != null && session.isConnected()) return key;
        }
        return null;
    }

    public static class SftpChannel {
        private final ChannelSftp channel;

        public SftpChannel(ChannelSftp channel) {
            this.channel = channel;
        }

        public void upload(String localPath, String remotePath) throws SftpException {
            channel.put(localPath, remotePath);
        }

        public void download(String remotePath, String localPath) throws SftpException {
            channel.get(remotePath, localPath);
        }

        public java.util.Vector<ChannelSftp.LsEntry> listFiles(String path) throws SftpException {
            return channel.ls(path);
        }

        public void mkdir(String path) throws SftpException {
            channel.mkdir(path);
        }

        public void rm(String path) throws SftpException {
            channel.rm(path);
        }

        public void rmdir(String path) throws SftpException {
            channel.rmdir(path);
        }

        public void rename(String oldPath, String newPath) throws SftpException {
            channel.rename(oldPath, newPath);
        }

        public long getFileSize(String remotePath) throws SftpException {
            ChannelSftp.LsEntry entry = (ChannelSftp.LsEntry) channel.ls(remotePath).firstElement();
            return entry.getAttrs().getSize();
        }

        public String readTextFile(String remotePath) throws SftpException, IOException {
            InputStream in = channel.get(remotePath);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            return sb.toString();
        }

        public void writeTextFile(String remotePath, String content) throws SftpException {
            channel.put(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)), remotePath);
        }

        public void close() {
            if (channel != null && channel.isConnected()) {
                channel.disconnect();
            }
        }
    }
}

package com.aifinalshell.controller;

import com.aifinalshell.ai.ServerContext;
import com.aifinalshell.ai.ServerContextFetcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 服务器上下文管理助手。
 * 管理每个会话的服务器上下文缓存，支持快速拉取和完整拉取两种模式。
 * 连接服务器时自动拉取快速上下文，首次AI对话时按需拉取完整上下文。
 */
public class ServerContextHelper {

    private static final Logger logger = LoggerFactory.getLogger(ServerContextHelper.class);

    /** 每个会话对应的服务器上下文缓存 */
    private static final Map<Long, ServerContext> sessionContexts = new ConcurrentHashMap<>();

    /** 已拉取过完整上下文的会话ID集合 */
    private static final Set<Long> fetchedFull = ConcurrentHashMap.newKeySet();

    /**
     * 共享线程池（B9）：避免每次拉取上下文都 new Thread。
     * 守护线程，固定 4 个，复用以降低线程创建开销。
     */
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "ServerContext-Worker");
        t.setDaemon(true);
        return t;
    });

    /**
     * 后台拉取快速服务器上下文（连接服务器时调用）。
     * 只拉取核心信息：系统信息、CPU、内存、磁盘、网络、端口、Java环境。
     *
     * @param sshKey    SSH连接键
     * @param serverId  服务器ID
     * @param sessionId 会话ID
     */
    public static void fetchQuickContext(String sshKey, Long serverId, Long sessionId) {
        EXECUTOR.submit(() -> {
            try {
                ServerContext ctx = ServerContextFetcher.getInstance().fetchQuick(sshKey, serverId);
                sessionContexts.put(sessionId, ctx);
            } catch (Exception e) {
                logger.debug("快速上下文拉取失败: sessionId={}, {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 按需拉取完整服务器上下文（首次AI对话时调用）。
     * 每个会话只拉取一次完整上下文，后续复用缓存。
     *
     * @param sshKey    SSH连接键
     * @param serverId  服务器ID
     * @param sessionId 会话ID
     */
    public static void fetchFullContextIfNeeded(String sshKey, Long serverId, Long sessionId) {
        if (fetchedFull.contains(sessionId)) {
            return; // 已拉取过完整上下文，跳过
        }
        EXECUTOR.submit(() -> {
            try {
                ServerContext ctx = ServerContextFetcher.getInstance().fetch(sshKey, serverId);
                sessionContexts.put(sessionId, ctx);
                fetchedFull.add(sessionId);
            } catch (Exception e) {
                logger.debug("完整上下文拉取失败: sessionId={}, {}", sessionId, e.getMessage());
            }
        });
    }

    /**
     * 获取指定会话的提示词上下文。
     *
     * @param sessionId 会话ID
     * @return 格式化的服务器上下文字符串，不存在时返回null
     */
    public static String getPromptContext(Long sessionId) {
        ServerContext ctx = sessionContexts.get(sessionId);
        if (ctx == null) {
            return null;
        }
        return ServerContextFetcher.getInstance().toPromptContext(ctx);
    }

    /**
     * 清理指定会话的上下文缓存（断开连接时调用）。
     *
     * @param sessionId 会话ID
     */
    public static void clearContext(Long sessionId) {
        sessionContexts.remove(sessionId);
        fetchedFull.remove(sessionId);
    }
}

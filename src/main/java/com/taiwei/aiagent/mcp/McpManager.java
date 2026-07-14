package com.taiwei.aiagent.mcp;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.taiwei.aiagent.settings.AiAgentSettings;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Project-level MCP 连接管理器：按配置建立/维护 {@link McpClient} 连接，
 * 并将各服务器暴露的工具适配为 {@link McpToolAdapter} 供 Agent 使用。
 * 首次调用 {@link #getActiveTools()} 时才会惰性启动所有已启用的连接，
 * 而不是在项目打开时立即连接。
 */
public class McpManager implements Disposable {

    private static final Logger LOG = Logger.getInstance(McpManager.class);

    private final Project project;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private volatile boolean startedOnce = false;

    public McpManager(@NotNull Project project) {
        this.project = project;
    }

    public static McpManager getInstance(@NotNull Project project) {
        return project.getService(McpManager.class);
    }

    /**
     * 启动所有已启用且尚未连接的 MCP 服务器。幂等：已连接的服务器不会被重复启动。
     */
    public void startAll() {
        lifecycleLock.lock();
        try {
            startedOnce = true;
            for (AiAgentSettings.McpConfig config : AiAgentSettings.getInstance().getMcpConfigs()) {
                if (config.enabled && !connections.containsKey(config.name)) {
                    doStart(config);
                }
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 关闭所有当前连接。
     */
    public void stopAll() {
        lifecycleLock.lock();
        try {
            for (String name : new ArrayList<>(connections.keySet())) {
                doStop(name);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 启动（或重启）单个 MCP 服务器连接，并持久化到 {@link #connections}。
     */
    public McpInitResult startConnection(AiAgentSettings.McpConfig config) {
        lifecycleLock.lock();
        try {
            doStop(config.name);
            return doStart(config);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 关闭指定名称的连接。
     */
    public void stopConnection(String name) {
        lifecycleLock.lock();
        try {
            doStop(name);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 测试连接是否可用：完成 initialize 握手后立即关闭，不写入 {@link #connections}，不影响现有连接。
     */
    public McpInitResult testConnection(AiAgentSettings.McpConfig config) {
        McpClient client = createClient(config);
        try {
            return client.initialize();
        } catch (Exception e) {
            LOG.warn("MCP testConnection failed for '" + config.name + "'", e);
            return McpInitResult.failure("Failed to connect: " + e.getMessage());
        } finally {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 获取所有已连接服务器暴露的工具（已按 disabledTools 过滤）。
     * 首次调用时惰性启动所有已启用的服务器。
     */
    public List<McpToolAdapter> getActiveTools() {
        if (!startedOnce) {
            startAll();
        }
        List<McpToolAdapter> result = new ArrayList<>();
        for (Connection conn : connections.values()) {
            result.addAll(conn.tools);
        }
        return result;
    }

    private McpInitResult doStart(AiAgentSettings.McpConfig config) {
        McpClient client = createClient(config);
        McpInitResult result;
        try {
            result = client.initialize();
        } catch (Exception e) {
            LOG.warn("Failed to initialize MCP server '" + config.name + "'", e);
            result = McpInitResult.failure("Failed to connect: " + e.getMessage());
        }
        if (!result.isSuccess()) {
            LOG.warn("MCP server '" + config.name + "' failed to initialize: " + result.getErrorMessage());
            try {
                client.close();
            } catch (Exception ignored) {
            }
            return result;
        }

        List<McpToolInfo> toolInfos;
        try {
            toolInfos = client.listTools();
        } catch (Exception e) {
            LOG.warn("Failed to list tools for MCP server '" + config.name + "'", e);
            toolInfos = Collections.emptyList();
        }

        Set<String> disabled = config.disabledTools == null ? Collections.emptySet() : new HashSet<>(config.disabledTools);
        List<McpToolAdapter> adapters = new ArrayList<>();
        for (McpToolInfo info : toolInfos) {
            if (disabled.contains(info.getName())) continue;
            adapters.add(new McpToolAdapter(info, client, config.name));
        }

        connections.put(config.name, new Connection(client, adapters));
        LOG.info("MCP server '" + config.name + "' connected with " + adapters.size() + " tool(s)");
        return result;
    }

    private void doStop(String name) {
        Connection conn = connections.remove(name);
        if (conn != null) {
            try {
                conn.client.close();
            } catch (Exception e) {
                LOG.warn("Error closing MCP connection '" + name + "'", e);
            }
        }
    }

    private McpClient createClient(AiAgentSettings.McpConfig config) {
        switch (config.transportType) {
            case STDIO:
                return new StdioMcpClient(config);
            case SSE:
                return new HttpSseMcpClient(config);
            case STREAMABLE_HTTP:
                return new StreamableHttpMcpClient(config);
            default:
                throw new IllegalArgumentException("Unsupported MCP transport type: " + config.transportType);
        }
    }

    @Override
    public void dispose() {
        stopAll();
    }

    private static final class Connection {
        final McpClient client;
        final List<McpToolAdapter> tools;

        Connection(McpClient client, List<McpToolAdapter> tools) {
            this.client = client;
            this.tools = tools;
        }
    }
}

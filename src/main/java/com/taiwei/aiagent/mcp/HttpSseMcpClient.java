package com.taiwei.aiagent.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.settings.AiAgentSettings;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MCP 客户端：HTTP+SSE 传输（旧版协议）
 * GET 建立 SSE 连接，服务器通过 "endpoint" 事件下发 POST 消息端点地址，
 * 后续 JSON-RPC 请求/响应分别通过 POST 发送、SSE "message" 事件接收。
 */
public class HttpSseMcpClient implements McpClient {

    private static final Logger LOG = Logger.getInstance(HttpSseMcpClient.class);
    private static final long RECONNECT_DELAY_SECONDS = 2;

    private final AiAgentSettings.McpConfig config;
    private final Gson gson = new Gson();
    private final OkHttpClient httpClient;
    private final AtomicLong idGenerator = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();

    private volatile EventSource eventSource;
    private volatile String messageEndpoint;
    private volatile CompletableFuture<String> endpointFuture = new CompletableFuture<>();
    private volatile boolean closed = false;
    private volatile boolean connected = false;

    public HttpSseMcpClient(AiAgentSettings.McpConfig config) {
        this.config = config;
        int timeout = config.timeoutSeconds > 0 ? config.timeoutSeconds : 30;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(timeout, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived SSE stream
                .build();
    }

    @Override
    public synchronized McpInitResult initialize() {
        try {
            connectSse();
            int timeout = config.timeoutSeconds > 0 ? config.timeoutSeconds : 30;
            String endpoint;
            try {
                endpoint = endpointFuture.get(timeout, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                return McpInitResult.failure("Timed out waiting for SSE 'endpoint' event from '" + config.name + "'");
            } catch (ExecutionException e) {
                return McpInitResult.failure("SSE connection failed: " + e.getCause());
            }
            this.messageEndpoint = endpoint;

            JsonObject params = McpJsonRpc.buildInitializeParams("taiwei-ai-agent", "1.0");
            JsonObject response = sendRequest("initialize", params);
            if (response == null) {
                return McpInitResult.failure("MCP server '" + config.name + "' did not respond to initialize");
            }
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                return McpInitResult.failure(err);
            }

            McpInitResult result = parseInitResult(response);
            sendNotificationAsync("notifications/initialized", new JsonObject());
            connected = true;
            return result;
        } catch (Exception e) {
            LOG.warn("Failed to initialize SSE MCP server '" + config.name + "'", e);
            return McpInitResult.failure("Failed to connect to MCP server: " + e.getMessage());
        }
    }

    private McpInitResult parseInitResult(JsonObject response) {
        JsonObject result = response.getAsJsonObject("result");
        String serverName = config.name;
        String serverVersion = "";
        String protocolVersion = McpJsonRpc.PROTOCOL_VERSION;
        if (result != null) {
            if (result.has("protocolVersion") && !result.get("protocolVersion").isJsonNull()) {
                protocolVersion = result.get("protocolVersion").getAsString();
            }
            if (result.has("serverInfo") && result.get("serverInfo").isJsonObject()) {
                JsonObject info = result.getAsJsonObject("serverInfo");
                if (info.has("name") && !info.get("name").isJsonNull()) serverName = info.get("name").getAsString();
                if (info.has("version") && !info.get("version").isJsonNull()) serverVersion = info.get("version").getAsString();
            }
        }
        return McpInitResult.success(serverName, serverVersion, protocolVersion);
    }

    @Override
    public List<McpToolInfo> listTools() {
        if (!connected) return Collections.emptyList();
        try {
            JsonObject response = sendRequest("tools/list", new JsonObject());
            if (response == null) return Collections.emptyList();
            String err = McpJsonRpc.extractError(response);
            if (err != null) {
                LOG.warn("tools/list failed for '" + config.name + "': " + err);
                return Collections.emptyList();
            }
            return McpToolListParser.parse(response.getAsJsonObject("result"));
        } catch (Exception e) {
            LOG.warn("Failed to list tools for '" + config.name + "'", e);
            return Collections.emptyList();
        }
    }

    @Override
    public String callTool(String toolName, String argumentsJson) {
        if (!connected) return "Error: MCP server '" + config.name + "' is not connected";
        try {
            JsonObject params = McpToolListParser.buildCallParams(gson, toolName, argumentsJson);
            if (params == null) return "Error: invalid tool arguments JSON";

            JsonObject response = sendRequest("tools/call", params);
            if (response == null) {
                return "Error: MCP server '" + config.name + "' timed out calling tool '" + toolName + "'";
            }
            String err = McpJsonRpc.extractError(response);
            if (err != null) return "Error: " + err;
            return McpJsonRpc.extractToolResultText(response.getAsJsonObject("result"));
        } catch (Exception e) {
            LOG.warn("Failed to call tool '" + toolName + "' on '" + config.name + "'", e);
            return "Error calling MCP tool: " + e.getMessage();
        }
    }

    @Override
    public boolean ping() {
        if (!connected) return false;
        try {
            JsonObject response = sendRequest("ping", new JsonObject());
            return response != null && McpJsonRpc.extractError(response) == null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean isConnected() {
        return connected;
    }

    @Override
    public String getServerName() {
        return config.name;
    }

    @Override
    public synchronized void close() {
        closed = true;
        connected = false;
        if (eventSource != null) {
            eventSource.cancel();
        }
        failAllPending("MCP client closed");
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ========== SSE lifecycle ==========

    private void connectSse() {
        Request.Builder builder = new Request.Builder().url(config.url);
        addHeaders(builder);

        EventSource.Factory factory = EventSources.createFactory(httpClient);
        eventSource = factory.newEventSource(builder.build(), new EventSourceListener() {
            @Override
            public void onOpen(EventSource es, Response response) {
                LOG.info("SSE connection opened for MCP server '" + config.name + "'");
            }

            @Override
            public void onEvent(EventSource es, String id, String type, String data) {
                handleSseEvent(type, data);
            }

            @Override
            public void onFailure(EventSource es, Throwable t, Response response) {
                connected = false;
                if (!endpointFuture.isDone()) {
                    endpointFuture.completeExceptionally(t != null ? t : new IOException("SSE connection failed"));
                }
                if (!closed) {
                    LOG.warn("SSE connection failed for MCP server '" + config.name + "': "
                            + (t != null ? t.getMessage() : "unknown error") + "; reconnecting");
                    scheduleReconnect();
                }
            }

            @Override
            public void onClosed(EventSource es) {
                connected = false;
                if (!closed) {
                    scheduleReconnect();
                }
            }
        });
    }

    private void scheduleReconnect() {
        if (closed) return;
        CompletableFuture.delayedExecutor(RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS).execute(() -> {
            if (closed) return;
            LOG.info("Reconnecting SSE for MCP server '" + config.name + "'");
            endpointFuture = new CompletableFuture<>();
            connectSse();
        });
    }

    private void handleSseEvent(String type, String data) {
        if ("endpoint".equals(type)) {
            String resolved = resolveEndpoint(data);
            messageEndpoint = resolved;
            connected = true;
            if (!endpointFuture.isDone()) {
                endpointFuture.complete(resolved);
            }
            return;
        }
        try {
            JsonObject obj = gson.fromJson(data, JsonObject.class);
            if (McpJsonRpc.isResponse(obj)) {
                long id = obj.get("id").getAsLong();
                CompletableFuture<JsonObject> future = pending.remove(id);
                if (future != null) future.complete(obj);
            }
        } catch (JsonSyntaxException e) {
            LOG.debug("Ignoring non-JSON SSE data from '" + config.name + "': " + data);
        }
    }

    private String resolveEndpoint(String data) {
        try {
            HttpUrl base = HttpUrl.parse(config.url);
            HttpUrl resolved = base != null ? base.resolve(data.trim()) : null;
            return resolved != null ? resolved.toString() : data.trim();
        } catch (Exception e) {
            return data.trim();
        }
    }

    // ========== JSON-RPC over POST ==========

    private JsonObject sendRequest(String method, JsonObject params) throws IOException {
        if (messageEndpoint == null) {
            throw new IOException("MCP SSE message endpoint not established for '" + config.name + "'");
        }
        long id = idGenerator.getAndIncrement();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(id, future);
        postMessage(McpJsonRpc.buildRequest(id, method, params));
        try {
            int timeout = config.timeoutSeconds > 0 ? config.timeoutSeconds : 30;
            return future.get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            LOG.warn("MCP SSE request timed out: method=" + method + ", server=" + config.name);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            LOG.warn("MCP SSE request failed: method=" + method, e);
            return null;
        } finally {
            pending.remove(id);
        }
    }

    private void sendNotificationAsync(String method, JsonObject params) {
        try {
            postMessage(McpJsonRpc.buildNotification(method, params));
        } catch (IOException e) {
            LOG.warn("Failed to send notification '" + method + "' to '" + config.name + "'", e);
        }
    }

    private void postMessage(JsonObject payload) throws IOException {
        Request.Builder builder = new Request.Builder()
                .url(messageEndpoint)
                .post(RequestBody.create(payload.toString(), MediaType.parse("application/json")));
        addHeaders(builder);
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("POST to MCP message endpoint failed: HTTP " + response.code());
            }
        }
    }

    private void addHeaders(Request.Builder builder) {
        if (config.headers != null) {
            for (AiAgentSettings.HeaderEntry h : config.headers) {
                if (h.key != null && !h.key.isEmpty()) {
                    builder.addHeader(h.key, h.value == null ? "" : h.value);
                }
            }
        }
    }

    private void failAllPending(String message) {
        for (Long id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<JsonObject> future = pending.remove(id);
            if (future != null) future.completeExceptionally(new IOException(message));
        }
    }
}

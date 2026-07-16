package com.taiwei.aiagent.llm.openai;

import com.google.gson.*;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.LlmStreamListener;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.tool.Tool;
import com.taiwei.aiagent.settings.AiAgentSettings;
import okhttp3.*;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI 兼容的 LLM 客户端实现
 * 支持 OpenAI、DeepSeek、通义千问等兼容 OpenAI 接口的模型
 */
public class OpenAiLlmClient implements LlmClient {

    private static final Logger LOG = Logger.getInstance(OpenAiLlmClient.class);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final OkHttpClient httpClient;
    private final Gson gson;
    private volatile EventSource currentEventSource;

    public OpenAiLlmClient(String baseUrl, String apiKey, String model) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        this.apiKey = apiKey;
        this.model = model;
        LOG.info("OpenAiLlmClient 创建 - baseUrl=" + this.baseUrl + ", model=" + model);
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build();
        this.gson = new GsonBuilder().create();
    }

    @Override
    public LlmResponse chat(List<ChatMessage> messages, List<Tool> tools) {
        String url = baseUrl + "chat/completions";
        LOG.info("LLM 请求 - url=" + url + ", model=" + model);
        JsonObject requestBody = buildRequestBody(messages, tools, false);

        Request request = buildRequest(requestBody);

        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                return LlmResponse.error("HTTP " + response.code() + ": " + body);
            }

            return parseResponse(body);
        } catch (IOException e) {
            return LlmResponse.error("请求失败: " + e.getMessage());
        }
    }

    @Override
    public void chatStream(List<ChatMessage> messages, List<Tool> tools, LlmStreamListener listener) {
        JsonObject requestBody = buildRequestBody(messages, tools, true);

        Request request = buildRequest(requestBody);

        EventSource.Factory factory = EventSources.createFactory(httpClient);

        LOG.info("chatStream 开始 - model=" + model + ", messages=" + messages.size() + ", tools=" + (tools != null ? tools.size() : 0));
        EventSource eventSource = factory.newEventSource(request, new EventSourceListener() {

            // 用于累积流式工具调用
            private final Map<Integer, StringBuilder> toolCallIdMap = new ConcurrentHashMap<>();
            private final Map<Integer, StringBuilder> toolCallNameMap = new ConcurrentHashMap<>();
            private final Map<Integer, StringBuilder> toolCallArgsMap = new ConcurrentHashMap<>();

            @Override
            public void onOpen(EventSource eventSource, Response response) {
                LOG.info("SSE onOpen - HTTP " + response.code() + ", model=" + model);
                currentEventSource = eventSource;
            }

            @Override
            public void onEvent(EventSource eventSource, String id, String type, String data) {
                LOG.info("SSE onEvent 收到数据: " + (data.length() > 500 ? data.substring(0, 500) + "..." : data));

                if ("[DONE]".equals(data)) {
                    // 如果有累积的工具调用，回调
                    flushToolCalls(listener);
                    listener.onComplete();
                    return;
                }

                try {
                    JsonObject chunk = gson.fromJson(data, JsonObject.class);
                    if (chunk == null) return;

                    // 检查 API 错误响应（很多 OpenAI 兼容 API 在流式模式下通过 SSE 事件返回错误）
                    if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
                        JsonObject errorObj = chunk.getAsJsonObject("error");
                        String errorMsg = errorObj.has("message") ? errorObj.get("message").getAsString() : "未知 API 错误";
                        LOG.warn("SSE 流中收到 API 错误: " + errorMsg);
                        listener.onError("API 错误: " + errorMsg, null);
                        return;
                    }

                    // 解析 usage（通常在最后一个 chunk 中，choices 可能为空）
                    if (chunk.has("usage") && !chunk.get("usage").isJsonNull()) {
                        JsonObject usageObj = chunk.getAsJsonObject("usage");
                        LlmResponse.Usage usage = new LlmResponse.Usage();
                        usage.setPromptTokens(usageObj.get("prompt_tokens").getAsInt());
                        usage.setCompletionTokens(usageObj.get("completion_tokens").getAsInt());
                        usage.setTotalTokens(usageObj.get("total_tokens").getAsInt());
                        listener.onUsage(usage);
                    }

                    if (!chunk.has("choices") || chunk.get("choices").isJsonNull()) return;
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.size() == 0) return;

                    JsonElement firstChoice = choices.get(0);
                    if (firstChoice == null || firstChoice.isJsonNull()) return;
                    JsonObject choiceObj = firstChoice.getAsJsonObject();
                    if (choiceObj == null || !choiceObj.has("delta") || choiceObj.get("delta").isJsonNull()) return;
                    JsonObject delta = choiceObj.getAsJsonObject("delta");
                    if (delta == null) return;

                    // 处理内容增量
                    if (delta.has("content") && !delta.get("content").isJsonNull()) {
                        String content = delta.get("content").getAsString();
                        if (content != null && !content.isEmpty()) {
                            listener.onContent(content);
                        }
                    }

                    // 处理工具调用增量
                    if (delta.has("tool_calls") && !delta.get("tool_calls").isJsonNull()) {
                        JsonArray toolCalls = delta.getAsJsonArray("tool_calls");
                        if (toolCalls != null) {
                            for (JsonElement elem : toolCalls) {
                                if (elem == null || elem.isJsonNull()) continue;
                                JsonObject tc = elem.getAsJsonObject();
                                if (!tc.has("index") || tc.get("index").isJsonNull()) continue;
                                int index = tc.get("index").getAsInt();

                                if (tc.has("id") && !tc.get("id").isJsonNull()) {
                                    toolCallIdMap.computeIfAbsent(index, k -> new StringBuilder())
                                            .append(tc.get("id").getAsString());
                                }
                                if (tc.has("function") && !tc.get("function").isJsonNull()) {
                                    JsonObject fn = tc.getAsJsonObject("function");
                                    if (fn.has("name") && !fn.get("name").isJsonNull()) {
                                        toolCallNameMap.computeIfAbsent(index, k -> new StringBuilder())
                                                .append(fn.get("name").getAsString());
                                    }
                                    if (fn.has("arguments") && !fn.get("arguments").isJsonNull()) {
                                        toolCallArgsMap.computeIfAbsent(index, k -> new StringBuilder())
                                                .append(fn.get("arguments").getAsString());
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    listener.onError("解析流式数据失败: " + e.getMessage(), e);
                }
            }

            @Override
            public void onFailure(EventSource eventSource, Throwable t, Response response) {
                StringBuilder msg = new StringBuilder("流式请求失败");
                if (response != null) {
                    msg.append(", HTTP ").append(response.code());
                    try {
                        String body = response.body() != null ? response.body().string() : "";
                        if (!body.isEmpty()) {
                            msg.append(", body: ").append(body);
                        }
                    } catch (IOException ignored) {}
                    response.close();
                }
                if (t != null) {
                    msg.append(", error: ").append(t.getMessage());
                }
                LOG.warn(msg.toString());
                if (t != null) {
                    LOG.warn("流式请求失败异常详情", t);
                }
                listener.onError(msg.toString(), t);
            }

            private void flushToolCalls(LlmStreamListener listener) {
                for (Map.Entry<Integer, StringBuilder> entry : toolCallIdMap.entrySet()) {
                    int index = entry.getKey();
                    String id = entry.getValue().toString();
                    String name = toolCallNameMap.containsKey(index) ? toolCallNameMap.get(index).toString() : "";
                    String args = toolCallArgsMap.containsKey(index) ? toolCallArgsMap.get(index).toString() : "";
                    listener.onToolCall(id, name, args);
                }
            }
        });
        currentEventSource = eventSource;
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isEmpty() && model != null && !model.isEmpty();
    }

    @Override
    public String getModelName() {
        return model;
    }

    @Override
    public void cancel() {
        EventSource es = currentEventSource;
        if (es != null) {
            es.cancel();
            currentEventSource = null;
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
    }

    // ========== 内部方法 ==========

    private Request buildRequest(JsonObject requestBody) {
        return new Request.Builder()
                .url(baseUrl + "chat/completions")
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();
    }

    private JsonObject buildRequestBody(List<ChatMessage> messages, List<Tool> tools, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("stream", stream);
        body.addProperty("max_tokens", AiAgentSettings.getInstance().getMaxTokens());

        if (stream) {
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }

        // 消息列表
        JsonArray messagesArray = new JsonArray();
        for (ChatMessage msg : messages) {
            // 跳过无效的 assistant 消息（既无 content 也无 tool_calls）
            if ("assistant".equals(msg.getRole())
                    && (msg.getContent() == null || msg.getContent().isEmpty())
                    && (msg.getToolCalls() == null || msg.getToolCalls().length == 0)) {
                continue;
            }

            JsonObject msgObj = new JsonObject();
            msgObj.addProperty("role", msg.getRole());

            if (msg.hasImages()) {
                JsonArray contentArray = new JsonArray();
                if (msg.getContent() != null && !msg.getContent().isEmpty()) {
                    JsonObject textPart = new JsonObject();
                    textPart.addProperty("type", "text");
                    textPart.addProperty("text", msg.getContent());
                    contentArray.add(textPart);
                }
                for (ChatMessage.ImageContent img : msg.getImageContents()) {
                    JsonObject imagePart = new JsonObject();
                    imagePart.addProperty("type", "image_url");
                    JsonObject imageUrlObj = new JsonObject();
                    imageUrlObj.addProperty("url", "data:" + img.getMimeType() + ";base64," + img.getBase64Data());
                    imagePart.add("image_url", imageUrlObj);
                    contentArray.add(imagePart);
                }
                msgObj.add("content", contentArray);
            } else if (msg.getContent() != null) {
                msgObj.addProperty("content", msg.getContent());
            }

            // 工具调用
            if (msg.getToolCalls() != null) {
                JsonArray tcArray = new JsonArray();
                for (ChatMessage.ToolCall tc : msg.getToolCalls()) {
                    JsonObject tcObj = new JsonObject();
                    tcObj.addProperty("id", tc.getId());
                    tcObj.addProperty("type", tc.getType());
                    JsonObject fnObj = new JsonObject();
                    fnObj.addProperty("name", tc.getFunction().getName());
                    fnObj.addProperty("arguments", tc.getFunction().getArguments());
                    tcObj.add("function", fnObj);
                    tcArray.add(tcObj);
                }
                msgObj.add("tool_calls", tcArray);
            }

            // 工具调用 ID
            if (msg.getToolCallId() != null) {
                msgObj.addProperty("tool_call_id", msg.getToolCallId());
            }

            messagesArray.add(msgObj);
        }
        body.add("messages", messagesArray);

        // 工具定义
        if (tools != null && !tools.isEmpty()) {
            JsonArray toolsArray = new JsonArray();
            for (Tool tool : tools) {
                JsonObject toolObj = new JsonObject();
                toolObj.addProperty("type", "function");
                JsonObject fnDef = new JsonObject();
                fnDef.addProperty("name", tool.getName());
                fnDef.addProperty("description", tool.getDescription());
                fnDef.add("parameters", gson.fromJson(tool.getParametersSchema(), JsonObject.class));
                toolObj.add("function", fnDef);
                toolsArray.add(toolObj);
            }
            body.add("tools", toolsArray);
        }

        return body;
    }

    private LlmResponse parseResponse(String body) {
        try {
            JsonObject json = gson.fromJson(body, JsonObject.class);

            // 检查错误
            if (json.has("error")) {
                JsonObject error = json.getAsJsonObject("error");
                String msg = error.has("message") ? error.get("message").getAsString() : "未知错误";
                return LlmResponse.error(msg);
            }

            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return LlmResponse.error("响应中无 choices 字段");
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");

            String content = message.has("content") && !message.get("content").isJsonNull()
                    ? message.get("content").getAsString() : "";

            // 解析工具调用
            ChatMessage.ToolCall[] toolCalls = null;
            if (message.has("tool_calls") && !message.get("tool_calls").isJsonNull()) {
                JsonArray tcArray = message.getAsJsonArray("tool_calls");
                toolCalls = new ChatMessage.ToolCall[tcArray.size()];
                for (int i = 0; i < tcArray.size(); i++) {
                    JsonObject tc = tcArray.get(i).getAsJsonObject();
                    ChatMessage.ToolCall tcObj = new ChatMessage.ToolCall();
                    tcObj.setId(tc.get("id").getAsString());
                    tcObj.setType(tc.has("type") ? tc.get("type").getAsString() : "function");
                    JsonObject fn = tc.getAsJsonObject("function");
                    ChatMessage.FunctionCall fc = new ChatMessage.FunctionCall();
                    fc.setName(fn.get("name").getAsString());
                    fc.setArguments(fn.get("arguments").getAsString());
                    tcObj.setFunction(fc);
                    toolCalls[i] = tcObj;
                }
            }

            LlmResponse response;
            if (toolCalls != null && toolCalls.length > 0) {
                response = LlmResponse.successWithToolCalls(toolCalls, content);
            } else {
                response = LlmResponse.success(content);
            }

            // 解析 usage
            if (json.has("usage")) {
                JsonObject usageObj = json.getAsJsonObject("usage");
                LlmResponse.Usage usage = new LlmResponse.Usage();
                usage.setPromptTokens(usageObj.get("prompt_tokens").getAsInt());
                usage.setCompletionTokens(usageObj.get("completion_tokens").getAsInt());
                usage.setTotalTokens(usageObj.get("total_tokens").getAsInt());
                response.setUsage(usage);
            }

            response.setRawResponse(body);
            return response;

        } catch (Exception e) {
            return LlmResponse.error("解析响应失败: " + e.getMessage());
        }
    }
}

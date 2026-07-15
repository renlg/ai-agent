package com.taiwei.aiagent.completion;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest;
import com.intellij.codeInsight.inline.completion.InlineCompletionSuggestion;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.taiwei.aiagent.settings.AiAgentSettings;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tab 自动补全提供者
 * 监听编辑器打字，300ms 停滞后获取光标前上下文，调用 LLM 生成补全
 * Tab 接受，Esc 取消
 */
public class InlineCompletionProvider extends com.intellij.codeInsight.inline.completion.InlineCompletionProvider {

    private static final Logger LOG = Logger.getInstance(InlineCompletionProvider.class);
    private static final long DEBOUNCE_MS = 300;
    private static final int MAX_CONTEXT_CHARS = 3000;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "taiwei-completion");
        t.setDaemon(true);
        return t;
    });

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private volatile ScheduledFuture<?> pendingTask;

    @Override
    public String getProviderId() {
        return "taiwei-inline-completion";
    }

    @Override
    public CompletableFuture<InlineCompletionSuggestion> compute(InlineCompletionRequest request) {
        ScheduledFuture<?> prev = pendingTask;
        if (prev != null) {
            prev.cancel(false);
        }

        CompletableFuture<InlineCompletionSuggestion> future = new CompletableFuture<>();

        pendingTask = scheduler.schedule(() -> {
            try {
                Editor editor = request.getEditor();
                Document document = editor.getDocument();
                int offset = editor.getCaretModel().getOffset();

                String text = document.getText();
                String prefix = text.substring(0, Math.min(offset, text.length()));
                String suffix = offset < text.length() ? text.substring(offset) : "";

                if (prefix.trim().isEmpty()) {
                    future.complete(null);
                    return;
                }

                String completion = callLlm(prefix, suffix);
                if (completion != null && !completion.isEmpty()) {
                    future.complete(InlineCompletionSuggestion.text(completion));
                } else {
                    future.complete(null);
                }
            } catch (Exception e) {
                LOG.warn("Inline completion failed", e);
                future.complete(null);
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);

        return future;
    }

    private String callLlm(String prefix, String suffix) {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();

        if (apiKey == null || apiKey.isEmpty() || baseUrl.isEmpty()) {
            return null;
        }

        if (prefix.length() > MAX_CONTEXT_CHARS) {
            prefix = prefix.substring(prefix.length() - MAX_CONTEXT_CHARS);
        }
        if (suffix.length() > MAX_CONTEXT_CHARS) {
            suffix = suffix.substring(0, MAX_CONTEXT_CHARS);
        }

        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("Complete the code. Output only the completion, no explanation.\n\n");
        promptBuilder.append(prefix);
        if (!suffix.isEmpty()) {
            promptBuilder.append("\n/* ... */\n");
            promptBuilder.append(suffix);
        }

        JsonObject requestBody = new JsonObject();
        requestBody.addProperty("model", model);
        requestBody.addProperty("max_tokens", 256);
        requestBody.addProperty("temperature", 0.2);
        requestBody.addProperty("stream", false);

        JsonArray messages = new JsonArray();
        JsonObject msg = new JsonObject();
        msg.addProperty("role", "user");
        msg.addProperty("content", promptBuilder.toString());
        messages.add(msg);
        requestBody.add("messages", messages);

        String url = baseUrl.endsWith("/") ? baseUrl + "chat/completions" : baseUrl + "/chat/completions";

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("Content-Type", "application/json")
                .post(RequestBody.create(requestBody.toString(), MediaType.parse("application/json")))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                return null;
            }
            String body = response.body() != null ? response.body().string() : "";
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();

            if (json.has("error")) {
                return null;
            }

            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) {
                return null;
            }

            JsonObject choice = choices.get(0).getAsJsonObject();
            JsonObject message = choice.getAsJsonObject("message");
            String content = message.has("content") ? message.get("content").getAsString() : "";

            return content.trim();
        } catch (Exception e) {
            LOG.warn("LLM completion call failed", e);
            return null;
        }
    }
}

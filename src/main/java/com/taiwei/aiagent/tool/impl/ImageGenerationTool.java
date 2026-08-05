package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.model.Conversation;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.settings.ImageGenSettings;
import com.taiwei.aiagent.tool.Tool;
import okhttp3.*;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class ImageGenerationTool implements Tool {

    private static final Logger LOG = Logger.getInstance(ImageGenerationTool.class);
    private static final int TIMEOUT_SECONDS = 120;

    /**
     * 提供当前会话引用，用于自动读取最近一条带图用户消息作为图生图参考图。
     * 由 AgentContext 在构造时注入；ToolManagerDialog 等仅展示工具信息的场景不会注入，此时保持 text-to-image 行为。
     */
    private volatile Supplier<Conversation> conversationSupplier;

    public void setConversationSupplier(Supplier<Conversation> conversationSupplier) {
        this.conversationSupplier = conversationSupplier;
    }

    private static OkHttpClient buildHttpClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (AiAgentSettings.getInstance().isBypassHostnameVerificationEnabled()) {
            LOG.warn("已启用跳过主机名校验，图像生成 API 请求将不校验 TLS 证书主机名");
            builder.hostnameVerifier((hostname, session) -> true);
        }
        return builder.build();
    }

    @Override
    public String getName() {
        return "generate_image";
    }

    @Override
    public String getDescription() {
        return "根据文字描述生成图像。接受文本提示词，调用图像生成 API（OpenAI 兼容格式）创建图像，" +
               "生成的图像将内联显示在对话中并可下载保存。适用于需要可视化内容、插图、概念设计的场景。" +
               "如果用户最近一条消息附带了图片（例如“按这张图生成一只猫”“参考这张图……”），会自动将该图片作为参考图进行图生图（image-to-image），无需在参数中传入图片数据。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "prompt": {
                      "type": "string",
                      "description": "图像描述提示词（支持中英文），描述要生成的图像内容、风格、色彩、构图等细节"
                    },
                    "size": {
                      "type": "string",
                      "description": "图像尺寸，可选：256x256、512x512、1024x1024、1792x1024（横版）、1024x1792（竖版）",
                      "enum": ["256x256", "512x512", "1024x1024", "1792x1024", "1024x1792"]
                    },
                    "n": {
                      "type": "integer",
                      "description": "生成图像数量（1-4），默认使用设置中配置的值",
                      "minimum": 1,
                      "maximum": 4
                    }
                  },
                  "required": ["prompt"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        ImageGenSettings settings = ImageGenSettings.getInstance();
        if (!settings.isConfigured()) {
            return "【图像生成失败】请先在设置页面（Settings → Tools → 太微 → 图像生成）配置 API 地址、API Key 和模型名称。";
        }

        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String prompt = args.get("prompt").getAsString().trim();
            if (prompt.isEmpty()) {
                return "【图像生成失败】提示词不能为空";
            }

            String size = args.has("size") ? args.get("size").getAsString() : settings.getImageSize();
            int n = args.has("n") ? args.get("n").getAsInt() : settings.getImageCount();
            n = Math.max(1, Math.min(4, n));

            String baseUrl = settings.getBaseUrl();
            if (!baseUrl.endsWith("/")) baseUrl += "/";

            List<ChatMessage.ImageContent> referenceImages = resolveReferenceImages();
            if (referenceImages != null && !referenceImages.isEmpty()) {
                return generateImageToImage(settings, baseUrl, prompt, size, n, referenceImages);
            }
            return generateTextToImage(settings, baseUrl, prompt, size, n);

        } catch (Exception e) {
            LOG.error("图像生成异常", e);
            return "【图像生成失败】" + e.getMessage();
        }
    }

    /**
     * 从会话中最近一条带图片的用户消息读取参考图（图生图使用），无会话上下文或无图片时返回 null
     */
    private List<ChatMessage.ImageContent> resolveReferenceImages() {
        Supplier<Conversation> supplier = this.conversationSupplier;
        if (supplier == null) {
            return null;
        }
        Conversation conversation = supplier.get();
        if (conversation == null) {
            return null;
        }
        ChatMessage msg = conversation.getLastUserMessageWithImages();
        return msg != null ? msg.getImageContents() : null;
    }

    private String generateTextToImage(ImageGenSettings settings, String baseUrl, String prompt, String size, int n) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.getModelName());
        body.addProperty("prompt", prompt);
        body.addProperty("n", n);
        body.addProperty("size", size);

        String endpoint = baseUrl + "images/generations";
        LOG.info("调用图像生成 API: endpoint=" + endpoint + ", model=" + settings.getModelName());

        try {
            RequestBody requestBody = RequestBody.create(
                    body.toString(), MediaType.parse("application/json; charset=utf-8"));
            Request request = new Request.Builder()
                    .url(endpoint)
                    .post(requestBody)
                    .header("Authorization", "Bearer " + settings.getApiKey())
                    .header("Content-Type", "application/json")
                    .build();

            try (Response response = buildHttpClient().newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    LOG.warn("图像生成 API 失败: HTTP " + response.code() + " " + responseBody);
                    return "【图像生成失败】HTTP " + response.code() + ": " + truncate(responseBody, 300);
                }
                return parseImageResponse(responseBody, prompt, size);
            }
        } catch (IOException e) {
            LOG.error("图像生成网络请求失败", e);
            return "【图像生成失败】网络请求失败: " + e.getMessage();
        }
    }

    /**
     * 图生图：优先调用 OpenAI 兼容的 images/edits（multipart/form-data，标准 i2i 形状）。
     * 若上游不支持该端点（4xx/5xx 或网络异常），回退为 images/generations + image(s) 字段（部分中转支持）。
     */
    private String generateImageToImage(ImageGenSettings settings, String baseUrl, String prompt, String size, int n,
                                          List<ChatMessage.ImageContent> referenceImages) {
        try {
            String result = tryImagesEdits(settings, baseUrl, prompt, size, n, referenceImages);
            if (result != null) {
                return result;
            }
        } catch (Exception e) {
            LOG.warn("images/edits 图生图调用失败，尝试回退到 images/generations + image 字段", e);
        }

        try {
            return tryGenerationsWithImage(settings, baseUrl, prompt, size, n, referenceImages);
        } catch (Exception e) {
            LOG.error("图生图回退调用也失败", e);
            return "【图生图失败】上游不支持参考图: " + e.getMessage();
        }
    }

    /**
     * 返回 null 表示端点不可用/失败，调用方应尝试回退；返回非 null 即为最终结果（成功或明确的失败信息）
     */
    private String tryImagesEdits(ImageGenSettings settings, String baseUrl, String prompt, String size, int n,
                                    List<ChatMessage.ImageContent> referenceImages) throws IOException {
        String endpoint = baseUrl + "images/edits";
        LOG.info("调用图生图 API (edits): endpoint=" + endpoint + ", model=" + settings.getModelName()
                + ", refImages=" + referenceImages.size());

        MultipartBody.Builder multipart = new MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("model", settings.getModelName())
                .addFormDataPart("prompt", prompt)
                .addFormDataPart("n", String.valueOf(n))
                .addFormDataPart("size", size);

        int idx = 0;
        for (ChatMessage.ImageContent img : referenceImages) {
            byte[] bytes = Base64.getDecoder().decode(img.getBase64Data());
            String mimeType = img.getMimeType() != null && !img.getMimeType().isBlank() ? img.getMimeType() : "image/png";
            String ext = mimeType.contains("/") ? mimeType.substring(mimeType.indexOf('/') + 1) : "png";
            String filename = "reference_" + (idx++) + "." + ext;
            multipart.addFormDataPart("image", filename, RequestBody.create(bytes, MediaType.parse(mimeType)));
        }

        Request request = new Request.Builder()
                .url(endpoint)
                .post(multipart.build())
                .header("Authorization", "Bearer " + settings.getApiKey())
                .build();

        try (Response response = buildHttpClient().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                LOG.warn("images/edits 调用失败: HTTP " + response.code() + " " + truncate(responseBody, 300));
                return null;
            }
            return parseImageResponse(responseBody, prompt, size);
        }
    }

    private String tryGenerationsWithImage(ImageGenSettings settings, String baseUrl, String prompt, String size, int n,
                                             List<ChatMessage.ImageContent> referenceImages) throws IOException {
        String endpoint = baseUrl + "images/generations";
        LOG.info("调用图生图 API (generations+image 回退): endpoint=" + endpoint + ", model=" + settings.getModelName());

        JsonObject body = new JsonObject();
        body.addProperty("model", settings.getModelName());
        body.addProperty("prompt", prompt);
        body.addProperty("n", n);
        body.addProperty("size", size);

        if (referenceImages.size() == 1) {
            body.addProperty("image", referenceImages.get(0).getBase64Data());
        } else {
            JsonArray images = new JsonArray();
            for (ChatMessage.ImageContent img : referenceImages) {
                images.add(img.getBase64Data());
            }
            body.add("images", images);
        }

        RequestBody requestBody = RequestBody.create(
                body.toString(), MediaType.parse("application/json; charset=utf-8"));
        Request request = new Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .header("Authorization", "Bearer " + settings.getApiKey())
                .header("Content-Type", "application/json")
                .build();

        try (Response response = buildHttpClient().newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                LOG.warn("images/generations+image 回退调用失败: HTTP " + response.code() + " " + responseBody);
                throw new IOException("HTTP " + response.code() + ": " + truncate(responseBody, 300));
            }
            return parseImageResponse(responseBody, prompt, size);
        }
    }

    private String parseImageResponse(String responseBody, String prompt, String size) {
        try {
            if (responseBody == null || responseBody.isBlank()) {
                return "【图像生成失败】API 返回内容为空";
            }
            com.google.gson.JsonElement respElement = JsonParser.parseString(responseBody);
            if (respElement == null || respElement.isJsonNull() || !respElement.isJsonObject()) {
                return "【图像生成失败】API 返回内容不是有效的 JSON 对象: " + truncate(responseBody, 300);
            }
            JsonObject resp = respElement.getAsJsonObject();
            JsonArray data = resp.getAsJsonArray("data");
            if (data == null || data.size() == 0) {
                return "【图像生成失败】API 返回数据为空: " + truncate(responseBody, 300);
            }

            JsonArray images = new JsonArray();
            for (int i = 0; i < data.size(); i++) {
                if (!data.get(i).isJsonObject()) {
                    continue;
                }
                JsonObject item = data.get(i).getAsJsonObject();

                String url = getNonBlankString(item, "url");
                String b64Json = url == null ? getNonBlankString(item, "b64_json") : null;
                if (url == null && b64Json == null) {
                    continue;
                }

                JsonObject imgObj = new JsonObject();
                imgObj.addProperty("mimeType", "image/png");
                if (url != null) {
                    imgObj.addProperty("url", url);
                } else {
                    imgObj.addProperty("base64", b64Json);
                }

                String revisedPrompt = getNonBlankString(item, "revised_prompt");
                if (revisedPrompt != null) {
                    imgObj.addProperty("revisedPrompt", revisedPrompt);
                }
                images.add(imgObj);
            }

            if (images.size() == 0) {
                return "【图像生成失败】API 返回数据中未找到有效的图像 url 或 b64_json: " + truncate(responseBody, 300);
            }

            JsonObject result = new JsonObject();
            result.addProperty("__type", "generated_image");
            result.addProperty("prompt", prompt);
            result.addProperty("size", size);
            result.add("images", images);
            return result.toString();

        } catch (Exception e) {
            LOG.warn("解析图像生成响应失败", e);
            return "【图像生成失败】解析响应失败: " + e.getMessage();
        }
    }

    /**
     * 安全提取字段为非空字符串：字段缺失、值为 JsonNull、非字符串类型或空白字符串均返回 null，不抛异常。
     */
    private static String getNonBlankString(JsonObject obj, String key) {
        if (!obj.has(key)) {
            return null;
        }
        com.google.gson.JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) {
            return null;
        }
        String s = el.getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }
}

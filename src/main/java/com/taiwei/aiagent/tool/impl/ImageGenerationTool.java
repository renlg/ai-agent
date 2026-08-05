package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.settings.AiAgentSettings;
import com.taiwei.aiagent.settings.ImageGenSettings;
import com.taiwei.aiagent.tool.Tool;
import okhttp3.*;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

public class ImageGenerationTool implements Tool {

    private static final Logger LOG = Logger.getInstance(ImageGenerationTool.class);
    private static final int TIMEOUT_SECONDS = 120;

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
               "生成的图像将内联显示在对话中并可下载保存。适用于需要可视化内容、插图、概念设计的场景。";
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

            JsonObject body = new JsonObject();
            body.addProperty("model", settings.getModelName());
            body.addProperty("prompt", prompt);
            body.addProperty("n", n);
            body.addProperty("size", size);
            body.addProperty("response_format", "b64_json");

            String baseUrl = settings.getBaseUrl();
            if (!baseUrl.endsWith("/")) baseUrl += "/";
            String endpoint = baseUrl + "images/generations";

            LOG.info("调用图像生成 API: endpoint=" + endpoint + ", model=" + settings.getModelName());

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
        } catch (Exception e) {
            LOG.error("图像生成异常", e);
            return "【图像生成失败】" + e.getMessage();
        }
    }

    private String parseImageResponse(String responseBody, String prompt, String size) {
        try {
            JsonObject resp = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray data = resp.getAsJsonArray("data");
            if (data == null || data.size() == 0) {
                return "【图像生成失败】API 返回数据为空: " + truncate(responseBody, 300);
            }

            JsonArray images = new JsonArray();
            for (int i = 0; i < data.size(); i++) {
                JsonObject item = data.get(i).getAsJsonObject();
                JsonObject imgObj = new JsonObject();
                imgObj.addProperty("mimeType", "image/png");

                if (item.has("b64_json")) {
                    imgObj.addProperty("base64", item.get("b64_json").getAsString());
                } else if (item.has("url")) {
                    imgObj.addProperty("url", item.get("url").getAsString());
                }

                if (item.has("revised_prompt")) {
                    imgObj.addProperty("revisedPrompt", item.get("revised_prompt").getAsString());
                }
                images.add(imgObj);
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

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "..." : (s != null ? s : "");
    }
}

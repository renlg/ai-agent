package com.taiwei.aiagent.tool.impl;

import com.aliyun.iqs20241111.Client;
import com.aliyun.iqs20241111.models.UnifiedSearchInput;
import com.aliyun.iqs20241111.models.UnifiedSearchRequest;
import com.aliyun.iqs20241111.models.UnifiedSearchResponse;
import com.aliyun.teaopenapi.models.Config;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.settings.IqsSettings;
import com.taiwei.aiagent.tool.Tool;

/**
 * 网络搜索工具
 * Agent 可以通过此工具调用阿里云 IQS 搜索互联网信息
 */
public class WebSearchTool implements Tool {

    private static final Logger LOG = Logger.getInstance(WebSearchTool.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "搜索互联网信息，获取最新的网络内容。当需要实时信息、最新新闻、技术文档、或本地知识库未覆盖的内容时，使用此工具搜索网络。支持指定时间范围过滤搜索结果。";
    }

    @Override
    public String getParametersSchema() {
        return """
                {
                  "type": "object",
                  "properties": {
                    "query": {
                      "type": "string",
                      "description": "搜索关键词，支持自然语言查询"
                    },
                    "time_range": {
                      "type": "string",
                      "description": "时间范围过滤，可选值：OneDay（一天内）、OneWeek（一周内）、OneMonth（一月内）、OneYear（一年内）。不传则不过滤"
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            // 1. 检查配置
            IqsSettings settings = IqsSettings.getInstance();
            if (!settings.isConfigured()) {
                return "【提示】网络搜索功能尚未配置。请前往 Settings → Tools → 太微 → 网络搜索 页面配置阿里云 AccessKey ID 和 AccessKey Secret，并确保已开通阿里云 IQS 服务。";
            }

            // 2. 解析参数
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String query = args.get("query").getAsString();
            String timeRange = args.has("time_range") ? args.get("time_range").getAsString() : null;

            if (query == null || query.trim().isEmpty()) {
                return "错误: 搜索关键词不能为空";
            }

            // 3. 创建 IQS 客户端
            Config config = new Config();
            config.setAccessKeyId(settings.getAccessKeyId());
            config.setAccessKeySecret(settings.getAccessKeySecret());
            config.setEndpoint(settings.getEndpoint());
            Client client = new Client(config);

            // 4. 构建请求
            UnifiedSearchInput input = new UnifiedSearchInput();
            input.setQuery(query);
            if (timeRange != null && !timeRange.trim().isEmpty()) {
                input.setTimeRange(timeRange);
            }
            UnifiedSearchRequest request = new UnifiedSearchRequest();
            request.setBody(input);

            // 5. 调用搜索
            LOG.info("调用阿里云 IQS 搜索: query=" + query + ", timeRange=" + timeRange);
            UnifiedSearchResponse response = client.unifiedSearch(request);

            // 6. 将结果转换为 JSON 字符串返回
            if (response == null || response.getBody() == null) {
                return "搜索未返回结果";
            }

            return GSON.toJson(response.getBody());

        } catch (com.aliyun.tea.TeaException e) {
            LOG.error("IQS 搜索失败", e);
            String message = e.getMessage();
            if (message != null && message.contains("InvalidAccessKeyId")) {
                return "【搜索失败】AccessKey ID 无效，请检查 Settings → Tools → 太微 → 网络搜索 中的配置是否正确。";
            } else if (message != null && message.contains("SignatureDoesNotMatch")) {
                return "【搜索失败】AccessKey Secret 错误，请检查 Settings → Tools → 太微 → 网络搜索 中的配置是否正确。";
            } else if (message != null && message.contains("Forbidden")) {
                return "【搜索失败】当前 AccessKey 无权限访问 IQS 服务，请确保已开通阿里云 IQS 服务并授予 AliyunIQSFullAccess 权限。";
            } else if (message != null && (message.contains("Timeout") || message.contains("timed out"))) {
                return "【搜索失败】网络请求超时，请检查网络连接后重试。";
            }
            return "【搜索失败】" + (message != null ? message : "未知错误，请稍后重试");

        } catch (Exception e) {
            LOG.error("IQS 搜索异常", e);
            return "【搜索失败】" + e.getMessage();
        }
    }
}

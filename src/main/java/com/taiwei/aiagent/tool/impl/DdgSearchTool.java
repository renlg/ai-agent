package com.taiwei.aiagent.tool.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.tool.Tool;
import okhttp3.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DdgSearchTool implements Tool {

    private static final Logger LOG = Logger.getInstance(DdgSearchTool.class);
    private static final String LITE_URL = "https://lite.duckduckgo.com/lite/";
    private static final int TIMEOUT_SECONDS = 15;

    private final OkHttpClient httpClient;

    public DdgSearchTool() {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    @Override
    public String getName() {
        return "web_search";
    }

    @Override
    public String getDescription() {
        return "搜索互联网信息，获取最新的网络内容。当需要实时信息、最新新闻、技术文档、或本地知识库未覆盖的内容时，使用此工具搜索网络。";
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
                    }
                  },
                  "required": ["query"]
                }
                """;
    }

    @Override
    public String execute(String arguments) {
        try {
            JsonObject args = JsonParser.parseString(arguments).getAsJsonObject();
            String query = args.get("query").getAsString();
            if (query == null || query.trim().isEmpty()) {
                return "错误: 搜索关键词不能为空";
            }

            LOG.info("调用 DuckDuckGo Lite 搜索: query=" + query);

            RequestBody formBody = new FormBody.Builder()
                    .add("q", query)
                    .build();

            Request request = new Request.Builder()
                    .url(LITE_URL)
                    .post(formBody)
                    .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36")
                    .header("Accept", "text/html")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "【搜索失败】HTTP " + response.code();
                }
                String html = response.body() != null ? response.body().string() : "";
                return parseLiteResults(html);
            }

        } catch (IOException e) {
            LOG.error("DuckDuckGo 搜索失败", e);
            return "【搜索失败】网络请求失败: " + e.getMessage();
        } catch (Exception e) {
            LOG.error("DuckDuckGo 搜索异常", e);
            return "【搜索失败】" + e.getMessage();
        }
    }

    private String parseLiteResults(String html) {
        List<JsonObject> results = new ArrayList<>();

        Pattern linkPattern = Pattern.compile(
                "<a[^>]+rel=\"nofollow\"[^>]+class=\"result-link\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>",
                Pattern.DOTALL
        );
        Pattern snippetPattern = Pattern.compile(
                "<td[^>]+class=\"result-snippet\"[^>]*>(.*?)</td>",
                Pattern.DOTALL
        );

        Matcher linkMatcher = linkPattern.matcher(html);
        Matcher snippetMatcher = snippetPattern.matcher(html);

        List<String> links = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        while (linkMatcher.find()) {
            links.add(linkMatcher.group(1));
            titles.add(stripHtml(linkMatcher.group(2)));
        }

        List<String> snippets = new ArrayList<>();
        while (snippetMatcher.find()) {
            snippets.add(stripHtml(snippetMatcher.group(1)));
        }

        int count = Math.min(links.size(), Math.min(snippets.size(), 10));
        for (int i = 0; i < count; i++) {
            JsonObject item = new JsonObject();
            item.addProperty("title", titles.get(i));
            item.addProperty("url", links.get(i));
            item.addProperty("snippet", snippets.get(i));
            results.add(item);
        }

        if (results.isEmpty()) {
            return "搜索未返回结果，请尝试更换关键词";
        }

        JsonArray array = new JsonArray();
        results.forEach(array::add);

        JsonObject wrapper = new JsonObject();
        wrapper.add("results", array);
        wrapper.addProperty("count", results.size());
        return wrapper.toString();
    }

    private String stripHtml(String html) {
        return html.replaceAll("<[^>]+>", "").replaceAll("\\s+", " ").trim();
    }
}

package com.taiwei.aiagent.completion

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.inline.completion.DebouncedInlineCompletionProvider
import com.intellij.codeInsight.inline.completion.InlineCompletionEvent
import com.intellij.codeInsight.inline.completion.InlineCompletionProviderID
import com.intellij.codeInsight.inline.completion.InlineCompletionRequest
import com.intellij.codeInsight.inline.completion.elements.InlineCompletionGrayTextElement
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSingleSuggestion
import com.intellij.codeInsight.inline.completion.suggestion.InlineCompletionSuggestion
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.UserDataHolderBase
import com.taiwei.aiagent.settings.AiAgentSettings
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tab 自动补全提供者
 * 监听编辑器打字，300ms 停滞后获取光标前上下文，调用 LLM 生成补全
 * Tab 接受，Esc 取消
 */
class InlineCompletionProvider : DebouncedInlineCompletionProvider() {

    companion object {
        private val LOG = Logger.getInstance(InlineCompletionProvider::class.java)
        private const val MAX_CONTEXT_CHARS = 3000
        private const val DEBOUNCE_MS = 300L

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("taiwei-inline-completion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return true
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return DEBOUNCE_MS.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val document = editor.document
        val offset = editor.caretModel.offset

        val text = document.text
        val prefix = text.substring(0, minOf(offset, text.length))
        val suffix = if (offset < text.length) text.substring(offset) else ""

        if (prefix.trim().isEmpty()) {
            return InlineCompletionSuggestion.Empty
        }

        val completion = callLlm(prefix, suffix)
        if (completion != null && completion.isNotEmpty()) {
            val element = InlineCompletionGrayTextElement(completion)
            return InlineCompletionSingleSuggestion.build(UserDataHolderBase(), flowOf(element))
        }

        return InlineCompletionSuggestion.Empty
    }

    private fun callLlm(prefix: String, suffix: String): String? {
        val settings = AiAgentSettings.getInstance()
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val model = settings.model

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) {
            return null
        }

        var trimmedPrefix = prefix
        var trimmedSuffix = suffix

        if (trimmedPrefix.length > MAX_CONTEXT_CHARS) {
            trimmedPrefix = trimmedPrefix.substring(trimmedPrefix.length - MAX_CONTEXT_CHARS)
        }
        if (trimmedSuffix.length > MAX_CONTEXT_CHARS) {
            trimmedSuffix = trimmedSuffix.substring(0, MAX_CONTEXT_CHARS)
        }

        val promptBuilder = StringBuilder()
        promptBuilder.append("Complete the code. Output only the completion, no explanation.\n\n")
        promptBuilder.append(trimmedPrefix)
        if (trimmedSuffix.isNotEmpty()) {
            promptBuilder.append("\n/* ... */\n")
            promptBuilder.append(trimmedSuffix)
        }

        val requestBody = JsonObject()
        requestBody.addProperty("model", model)
        requestBody.addProperty("max_tokens", 256)
        requestBody.addProperty("temperature", 0.2)
        requestBody.addProperty("stream", false)

        val messages = JsonArray()
        val msg = JsonObject()
        msg.addProperty("role", "user")
        msg.addProperty("content", promptBuilder.toString())
        messages.add(msg)
        requestBody.add("messages", messages)

        val url = if (baseUrl.endsWith("/")) "${baseUrl}chat/completions" else "$baseUrl/chat/completions"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null

                val body = response.body?.string() ?: return null
                val json = JsonParser.parseString(body).asJsonObject

                if (json.has("error")) return null

                val choices = json.getAsJsonArray("choices")
                if (choices == null || choices.size() == 0) return null

                val choice = choices[0].asJsonObject
                val message = choice.getAsJsonObject("message")
                val content = if (message.has("content")) message["content"].asString else ""

                content.trim()
            }
        } catch (e: Exception) {
            LOG.warn("LLM completion call failed", e)
            null
        }
    }
}

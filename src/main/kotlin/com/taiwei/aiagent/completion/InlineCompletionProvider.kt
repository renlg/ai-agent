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
import com.intellij.openapi.vfs.VirtualFile
import com.taiwei.aiagent.settings.AiAgentSettings
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tab 自动补全提供者
 *
 * 改进点：
 * 1. FIM（Fill-in-the-Middle）格式 — 使用 <PRE>/<SUF>/<MID> 标记，适配主流代码模型
 * 2. 语言/文件类型感知 — 检测当前文件语言，注入语言上下文
 * 3. 智能上下文截断 — 基于缩进/花括号寻找函数边界，而非简单字符截断
 * 4. 流式请求 — 使用 SSE 流式调用，首 token 延迟更低
 * 5. 结果后处理 — 去除 Markdown 围栏、去重复前缀、在停止标记处截断
 */
class InlineCompletionProvider : DebouncedInlineCompletionProvider() {

    companion object {
        private val LOG = Logger.getInstance(InlineCompletionProvider::class.java)

        /** 前缀/后缀最大上下文字符数 */
        private const val MAX_CONTEXT_CHARS = 3000

        /** 防抖延迟（ms） */
        private const val DEBOUNCE_MS = 300L

        /** 后处理：遇到这些行时截断（表示模型开始"解释"了） */
        private val STOP_PATTERNS = listOf(
            "\n```",        // Markdown 围栏结束
            "\n// ",        // 开始写注释说明
            "\n# ",         // Markdown 标题
            "\nExplanation", // 英文解释
            "\nNote:",      // 注意说明
            "\n注意",
            "\n说明",
        )

        /** 文件扩展名 → 语言标签映射 */
        private val EXT_LANGUAGE_MAP = mapOf(
            "java" to "Java", "kt" to "Kotlin", "kts" to "Kotlin",
            "py" to "Python", "js" to "JavaScript", "ts" to "TypeScript",
            "jsx" to "JSX", "tsx" to "TSX",
            "go" to "Go", "rs" to "Rust", "rb" to "Ruby",
            "php" to "PHP", "cs" to "C#", "cpp" to "C++", "c" to "C",
            "h" to "C", "hpp" to "C++",
            "swift" to "Swift", "scala" to "Scala", "dart" to "Dart",
            "vue" to "Vue", "html" to "HTML", "css" to "CSS",
            "scss" to "SCSS", "less" to "Less",
            "sql" to "SQL", "sh" to "Shell", "bash" to "Shell",
            "xml" to "XML", "json" to "JSON", "yaml" to "YAML", "yml" to "YAML",
            "md" to "Markdown",
        )

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override val id: InlineCompletionProviderID
        get() = InlineCompletionProviderID("taiwei-inline-completion")

    override fun isEnabled(event: InlineCompletionEvent): Boolean {
        return AiAgentSettings.getInstance().isCompletionEnabled
    }

    override suspend fun getDebounceDelay(request: InlineCompletionRequest): Duration {
        return DEBOUNCE_MS.milliseconds
    }

    override suspend fun getSuggestionDebounced(request: InlineCompletionRequest): InlineCompletionSuggestion {
        val editor = request.editor
        val document = editor.document
        val offset = editor.caretModel.offset

        val text = document.text
        if (text.isBlank()) return InlineCompletionSuggestion.Empty

        val rawPrefix = text.substring(0, minOf(offset, text.length))
        val rawSuffix = if (offset < text.length) text.substring(offset) else ""

        if (rawPrefix.trim().isEmpty()) {
            return InlineCompletionSuggestion.Empty
        }

        // 检测语言
        val language = detectLanguage(editor)
        val languageContext = if (language.isNotEmpty()) "[$language] " else ""

        // 智能截断：基于函数/缩进边界
        val smartPrefix = smartTruncatePrefix(rawPrefix, MAX_CONTEXT_CHARS)
        val smartSuffix = smartTruncateSuffix(rawSuffix, MAX_CONTEXT_CHARS)

        // 流式调用 LLM
        val completion = callLlmStreaming(smartPrefix, smartSuffix, languageContext) ?: return InlineCompletionSuggestion.Empty

        // 后处理
        val processed = postProcess(completion, rawPrefix)
        if (processed.isEmpty()) return InlineCompletionSuggestion.Empty

        val element = InlineCompletionGrayTextElement(processed)
        return InlineCompletionSingleSuggestion.build(UserDataHolderBase(), flowOf(element))
    }

    // ==================== 1. 语言检测 ====================

    private fun detectLanguage(editor: com.intellij.openapi.editor.Editor): String {
        val vFile: VirtualFile = editor.virtualFile ?: return ""
        val ext = vFile.extension?.lowercase() ?: return ""
        return EXT_LANGUAGE_MAP[ext] ?: ""
    }

    // ==================== 2. 智能上下文截断 ====================

    /**
     * 智能截断前缀：优先在函数/方法边界截断，而非简单字符切割。
     * 策略：从光标向前，寻找缩进回到 0 或花括号匹配的位置。
     */
    private fun smartTruncatePrefix(rawPrefix: String, maxChars: Int): String {
        if (rawPrefix.length <= maxChars) return rawPrefix

        // 从末尾向前取 maxChars 范围内的内容
        val candidate = rawPrefix.substring(rawPrefix.length - maxChars)

        // 尝试找到函数边界：从开头跳过直到找到一个缩进为 0 的非空行
        val lines = candidate.lines()
        var startLine = 0
        for (i in lines.indices) {
            val line = lines[i]
            if (line.isNotBlank() && !line.startsWith(" ") && !line.startsWith("\t")) {
                startLine = i
                break
            }
        }

        return lines.subList(startLine, lines.size).joinToString("\n")
    }

    /**
     * 智能截断后缀：取前 maxChars 个字符，在行边界截断。
     */
    private fun smartTruncateSuffix(rawSuffix: String, maxChars: Int): String {
        if (rawSuffix.length <= maxChars) return rawSuffix
        val truncated = rawSuffix.substring(0, maxChars)
        // 在最后一个换行处截断，避免截断到行中间
        val lastNewline = truncated.lastIndexOf('\n')
        return if (lastNewline > 0) truncated.substring(0, lastNewline) else truncated
    }

    // ==================== 3. 流式 LLM 调用 ====================

    private fun callLlmStreaming(prefix: String, suffix: String, languageContext: String): String? {
        val settings = AiAgentSettings.getInstance()
        val baseUrl = settings.baseUrl
        val apiKey = settings.apiKey
        val model = settings.model

        if (apiKey.isNullOrEmpty() || baseUrl.isEmpty()) return null

        val prompt = buildPrompt(prefix, suffix, languageContext)

        val requestBody = JsonObject()
        requestBody.addProperty("model", model)
        requestBody.addProperty("max_tokens", 256)
        requestBody.addProperty("temperature", 0.0)
        requestBody.addProperty("stream", true)

        // stream_options 用于获取 usage
        val streamOptions = JsonObject()
        streamOptions.addProperty("include_usage", true)
        requestBody.add("stream_options", streamOptions)

        val messages = JsonArray()
        val msg = JsonObject()
        msg.addProperty("role", "user")
        msg.addProperty("content", prompt)
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
            val accumulated = StringBuilder()
            val latch = CountDownLatch(1)
            val errorRef = AtomicReference<String?>(null)

            val call = httpClient.newCall(request)
            val response = call.execute()

            if (!response.isSuccessful) {
                response.close()
                return null
            }

            val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), StandardCharsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val dataLine = line ?: continue
                if (!dataLine.startsWith("data:")) continue
                val data = dataLine.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    latch.countDown()
                    break
                }

                try {
                    val json = JsonParser.parseString(data).asJsonObject
                    // 检查错误
                    if (json.has("error") && !json.get("error").isJsonNull) {
                        errorRef.set("API error in stream")
                        latch.countDown()
                        break
                    }
                    val choices = json.getAsJsonArray("choices") ?: continue
                    if (choices.size() == 0) continue
                    val delta = choices[0].asJsonObject.getAsJsonObject("delta") ?: continue
                    if (delta.has("content") && !delta.get("content").isJsonNull) {
                        accumulated.append(delta.get("content").asString)
                    }
                } catch (_: Exception) {
                    // 跳过无法解析的行
                }
            }
            reader.close()
            response.close()

            if (errorRef.get() != null) {
                LOG.warn("Streaming completion error: ${errorRef.get()}")
                return null
            }

            val result = accumulated.toString().trim()
            if (result.isEmpty()) null else result
        } catch (e: Exception) {
            LOG.warn("LLM streaming completion call failed", e)
            null
        }
    }

    // ==================== 4. 提示词构建 ====================

    private fun buildPrompt(prefix: String, suffix: String, languageContext: String): String {
        val template = loadTemplate("templates/completion_prompt.vm")
        val suffixPart = if (suffix.isNotEmpty()) suffix else ""
        return template
            .replace("\${languageContext}", languageContext)
            .replace("\${prefix}", prefix)
            .replace("\${suffix}", suffixPart)
    }

    private fun loadTemplate(resourcePath: String): String {
        return try {
            javaClass.classLoader.getResourceAsStream(resourcePath)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).readText()
            } ?: "<PRE> \${prefix}<SUF> \${suffix}<MID>"
        } catch (e: Exception) {
            LOG.warn("Failed to load completion template", e)
            "<PRE> \${prefix}<SUF> \${suffix}<MID>"
        }
    }

    // ==================== 5. 结果后处理 ====================

    /**
     * 对 LLM 返回的补全文本做后处理：
     * - 去除 Markdown 代码围栏
     * - 去除与前缀末尾重复的内容
     * - 在停止标记处截断
     * - 去除首尾多余空白
     */
    private fun postProcess(completion: String, rawPrefix: String): String {
        var result = completion

        // 1. 去除 Markdown 代码围栏
        result = stripCodeFence(result)

        // 2. 在停止模式处截断
        for (pattern in STOP_PATTERNS) {
            val idx = result.indexOf(pattern)
            if (idx > 0) {
                result = result.substring(0, idx)
            }
        }

        // 3. 去除与前缀末尾的重复
        result = removeDuplicatePrefix(result, rawPrefix)

        // 4. 去除尾部的多余空白和闭合符号
        result = result.trimEnd()

        // 去除尾部多余的单独闭合括号（模型有时会多输出一个 } 或 )）
        // 但不去除有意义的代码
        if (result.endsWith("\n}") || result.endsWith("\n)")) {
            val trimmed = result.trimEnd().removeSuffix("}").removeSuffix(")").trimEnd()
            if (trimmed.isNotEmpty()) {
                result = trimmed
            }
        }

        return result
    }

    /** 去除 Markdown 代码围栏（```lang ... ```） */
    private fun stripCodeFence(text: String): String {
        var result = text.trim()
        // 去除开头的 ```xxx
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            if (firstNewline > 0) {
                result = result.substring(firstNewline + 1)
            } else {
                result = result.removePrefix("```")
            }
        }
        // 去除结尾的 ```
        if (result.endsWith("```")) {
            result = result.removeSuffix("```")
        }
        return result.trimEnd()
    }

    /**
     * 如果补全内容开头与 prefix 末尾重复，去掉重复部分。
     * 例如 prefix 末尾是 "val x = "，补全以 "val x = 42" 开头 → 只保留 "42"
     */
    private fun removeDuplicatePrefix(completion: String, prefix: String): String {
        if (completion.isEmpty() || prefix.isEmpty()) return completion

        // 取 prefix 最后 80 个字符作为检查窗口
        val window = prefix.takeLast(80)

        // 尝试从长到短匹配重叠
        val maxOverlap = minOf(completion.length, window.length, 80)
        for (overlapLen in maxOverlap downTo 4) {
            val prefixTail = window.substring(window.length - overlapLen)
            if (completion.startsWith(prefixTail)) {
                return completion.substring(overlapLen)
            }
        }

        return completion
    }
}

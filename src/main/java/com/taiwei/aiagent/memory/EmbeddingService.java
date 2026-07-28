package com.taiwei.aiagent.memory;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.intellij.openapi.diagnostic.Logger;
import com.taiwei.aiagent.llm.LlmClient;
import com.taiwei.aiagent.llm.LlmResponse;
import com.taiwei.aiagent.llm.openai.OpenAiLlmClient;
import com.taiwei.aiagent.model.ChatMessage;
import com.taiwei.aiagent.settings.AiAgentSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight embedding provider used for hybrid memory retrieval. Instead of pulling in an
 * ONNX / local-model dependency, it asks the already-configured OpenAI-compatible chat API to
 * emit a compact numeric vector for a text and parses it out of the response. Results are
 * unit-normalized and cached in memory (LRU) so repeated lookups don't burn API calls.
 *
 * <p>Application-level singleton; all public methods are safe for concurrent use. Every method
 * is best-effort: on any failure (no API config, network error, unparseable reply) it returns
 * {@code null} for the affected text so callers can degrade to keyword-only search.
 *
 * <p>Embedding calls block on the LLM API for seconds; invoke them only from background
 * threads (see MemoryManager's embedding executor), never from a chat/tool-execution thread.
 */
public final class EmbeddingService {

    private static final Logger LOG = Logger.getInstance(EmbeddingService.class);
    private static final Gson GSON = new Gson();

    /** Fixed dimensionality of generated vectors; replies are truncated/padded to fit. */
    public static final int DIMENSION = 64;

    private static final int MAX_CACHE_ENTRIES = 2000;
    /** Long memories are truncated before embedding to keep the prompt cheap. */
    private static final int MAX_TEXT_CHARS = 2000;
    private static final Pattern NUMBER_ARRAY = Pattern.compile("\\[[^\\[\\]]+\\]");

    private static volatile EmbeddingService instance;

    /** text -> unit vector, LRU-evicted. */
    private final Map<String, float[]> cache = Collections.synchronizedMap(
            new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });

    private LlmClient client;
    private String clientBaseUrl;
    private String clientApiKey;
    private String clientModel;

    private EmbeddingService() {
    }

    public static EmbeddingService getInstance() {
        if (instance == null) {
            synchronized (EmbeddingService.class) {
                if (instance == null) {
                    instance = new EmbeddingService();
                }
            }
        }
        return instance;
    }

    /** Returns a unit-length embedding of {@code text}, or {@code null} if one can't be generated. */
    public float[] embed(String text) {
        if (text == null || text.isBlank()) return null;
        String key = truncate(text.trim());
        float[] cached = cache.get(key);
        if (cached != null) return cached;
        float[] vector = requestSingle(key);
        if (vector != null) {
            cache.put(key, vector);
        }
        return vector;
    }

    /**
     * Batch variant of {@link #embed(String)}: uncached texts are sent in a single API call.
     * The returned list is index-aligned with the input; failed items are {@code null}.
     */
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>();
        if (texts == null || texts.isEmpty()) return result;

        List<Integer> missIndices = new ArrayList<>();
        List<String> missTexts = new ArrayList<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                result.add(null);
                continue;
            }
            String key = truncate(text.trim());
            float[] cached = cache.get(key);
            result.add(cached);
            if (cached == null) {
                missIndices.add(result.size() - 1);
                missTexts.add(key);
            }
        }
        if (missTexts.isEmpty()) return result;

        List<float[]> batch = requestBatch(missTexts);
        for (int i = 0; i < missIndices.size(); i++) {
            float[] vector = (batch != null && i < batch.size()) ? batch.get(i) : null;
            if (vector == null) {
                // Batch call failed or came back malformed for this item; retry individually.
                vector = requestSingle(missTexts.get(i));
            }
            if (vector != null) {
                cache.put(missTexts.get(i), vector);
                result.set(missIndices.get(i), vector);
            }
        }
        return result;
    }

    /** Cosine similarity of two vectors; 0 when either is null/empty or has zero norm. */
    public static double cosineSimilarity(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0;
        int n = Math.min(a.length, b.length);
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    // ========== LLM plumbing ==========

    private float[] requestSingle(String text) {
        try {
            LlmClient llm = getClient();
            if (llm == null) return null;
            String prompt = "Generate a compact embedding vector for this text. Respond with ONLY a JSON array of exactly "
                    + DIMENSION + " numbers between -1 and 1 that captures the semantic meaning of the text. "
                    + "No explanation, no code fences.\n\nText: " + text;
            LlmResponse response = llm.chat(List.of(ChatMessage.user(prompt)), null);
            if (response == null || !response.isSuccess() || response.getContent() == null) return null;
            Matcher m = NUMBER_ARRAY.matcher(response.getContent());
            return m.find() ? fitAndNormalize(GSON.fromJson(m.group(), float[].class)) : null;
        } catch (Exception e) {
            LOG.warn("Embedding generation failed: " + e.getMessage());
            return null;
        }
    }

    /** One API call for several texts; returns an input-aligned list, or {@code null} on failure. */
    private List<float[]> requestBatch(List<String> texts) {
        if (texts.size() == 1) {
            List<float[]> single = new ArrayList<>();
            single.add(requestSingle(texts.get(0)));
            return single;
        }
        try {
            LlmClient llm = getClient();
            if (llm == null) return null;
            StringBuilder prompt = new StringBuilder();
            prompt.append("Generate a compact embedding vector for each of the ").append(texts.size())
                    .append(" texts below. Respond with ONLY a JSON array containing ").append(texts.size())
                    .append(" arrays, one per text in order, each with exactly ").append(DIMENSION)
                    .append(" numbers between -1 and 1 capturing the semantic meaning. No explanation, no code fences.\n");
            for (int i = 0; i < texts.size(); i++) {
                prompt.append("\nText ").append(i + 1).append(": ").append(texts.get(i));
            }
            LlmResponse response = llm.chat(List.of(ChatMessage.user(prompt.toString())), null);
            if (response == null || !response.isSuccess() || response.getContent() == null) return null;

            String content = response.getContent();
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start < 0 || end <= start) return null;
            JsonElement parsed = JsonParser.parseString(content.substring(start, end + 1));
            if (!parsed.isJsonArray()) return null;
            JsonArray outer = parsed.getAsJsonArray();
            if (outer.size() != texts.size()) return null;

            List<float[]> result = new ArrayList<>();
            for (JsonElement element : outer) {
                result.add(element.isJsonArray()
                        ? fitAndNormalize(GSON.fromJson(element, float[].class))
                        : null);
            }
            return result;
        } catch (Exception e) {
            LOG.warn("Batch embedding generation failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * Lazily builds a chat client from the current settings, rebuilding when the
     * configuration changes (same pattern as AgentContext's cached client).
     */
    private synchronized LlmClient getClient() {
        AiAgentSettings settings = AiAgentSettings.getInstance();
        String baseUrl = settings.getBaseUrl();
        String apiKey = settings.getApiKey();
        String model = settings.getModel();
        if (baseUrl == null || baseUrl.isBlank()) return null;

        if (client != null
                && Objects.equals(baseUrl, clientBaseUrl)
                && Objects.equals(apiKey, clientApiKey)
                && Objects.equals(model, clientModel)) {
            return client;
        }
        if (client != null) {
            client.close();
        }
        client = new OpenAiLlmClient(baseUrl, apiKey, model);
        clientBaseUrl = baseUrl;
        clientApiKey = apiKey;
        clientModel = model;
        return client;
    }

    /** Truncates/zero-pads to {@link #DIMENSION} and scales to unit length. Null if unusable. */
    private static float[] fitAndNormalize(float[] raw) {
        if (raw == null || raw.length == 0) return null;
        float[] vector = new float[DIMENSION];
        System.arraycopy(raw, 0, vector, 0, Math.min(raw.length, DIMENSION));
        double norm = 0;
        for (float v : vector) {
            if (!Float.isFinite(v)) return null;
            norm += v * v;
        }
        if (norm == 0) return null;
        double scale = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] * scale);
        }
        return vector;
    }

    private static String truncate(String text) {
        return text.length() <= MAX_TEXT_CHARS ? text : text.substring(0, MAX_TEXT_CHARS);
    }
}

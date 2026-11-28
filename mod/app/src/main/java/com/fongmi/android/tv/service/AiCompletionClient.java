package com.fongmi.android.tv.service;

import android.text.TextUtils;

import com.fongmi.android.tv.bean.AiConfig;
import com.github.catvod.net.OkHttp;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public final class AiCompletionClient {

    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final int MAX_OUTPUT_TOKENS = 4096;
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private AiCompletionClient() {
    }

    public static final class RequestSpec {
        private final String url;
        private final JsonObject body;
        private final Map<String, String> headers;

        public RequestSpec(String url, JsonObject body, Map<String, String> headers) {
            this.url = url;
            this.body = body;
            this.headers = headers;
        }

        public String getUrl() { return url; }
        public JsonObject getBody() { return body; }
        public Map<String, String> getHeaders() { return headers; }
    }

    public static final class TestResult {
        public final boolean ok;
        public final String message;

        private TestResult(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }

        public static TestResult success(String message) { return new TestResult(true, message); }
        public static TestResult failed(String message) { return new TestResult(false, message); }
    }

    public static TestResult testConfig(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isReady()) return TestResult.failed("请先启用 AI 服务，并填写端点、API key 和模型。");
        try {
            String text = complete(safe, "这是 AI 服务连通性测试。请只返回 JSON: {\"ok\":true,\"message\":\"connected\"}");
            if (TextUtils.isEmpty(text)) return TestResult.failed("接口已响应，但没有解析到 AI 输出。");
            return TestResult.success(excerpt(text));
        } catch (Throwable e) {
            return TestResult.failed(e.getMessage() == null ? "测试失败" : e.getMessage());
        }
    }

    public static String complete(AiConfig config, String prompt) throws Exception {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isReady()) throw new IllegalStateException("AI 配置不完整");
        RequestSpec spec = requestSpec(safe, prompt);
        Request request = buildRequest(spec);
        OkHttpClient client = OkHttp.client().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build();
        try (Response response = client.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) throw new IllegalStateException("HTTP " + response.code() + ": " + excerpt(body));
            String text = extractCompletionText(body, safe);
            if (TextUtils.isEmpty(text)) throw new IllegalStateException("无有效输出: " + excerpt(body));
            return text;
        }
    }

    public static RequestSpec requestSpec(AiConfig config, String prompt) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        JsonObject body = new JsonObject();
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        switch (safe.getProtocol()) {
            case AiConfig.PROTOCOL_OPENAI_CHAT: {
                body.addProperty("model", safe.getModel());
                JsonArray messages = new JsonArray();
                JsonObject message = new JsonObject();
                message.addProperty("role", "user");
                message.addProperty("content", Objects.toString(prompt, ""));
                messages.add(message);
                body.add("messages", messages);
                headers.put("Authorization", "Bearer " + safe.getApiKey());
                break;
            }
            case AiConfig.PROTOCOL_ANTHROPIC_MESSAGES: {
                body.addProperty("model", safe.getModel());
                body.addProperty("max_tokens", MAX_OUTPUT_TOKENS);
                JsonArray anthropicMessages = new JsonArray();
                JsonObject anthropicMessage = new JsonObject();
                anthropicMessage.addProperty("role", "user");
                anthropicMessage.addProperty("content", Objects.toString(prompt, ""));
                anthropicMessages.add(anthropicMessage);
                body.add("messages", anthropicMessages);
                headers.put("x-api-key", safe.getApiKey());
                headers.put("anthropic-version", ANTHROPIC_VERSION);
                break;
            }
            case AiConfig.PROTOCOL_GEMINI_NATIVE: {
                JsonArray contents = new JsonArray();
                JsonObject content = new JsonObject();
                content.addProperty("role", "user");
                JsonArray parts = new JsonArray();
                JsonObject part = new JsonObject();
                part.addProperty("text", Objects.toString(prompt, ""));
                parts.add(part);
                content.add("parts", parts);
                contents.add(content);
                JsonObject generationConfig = new JsonObject();
                generationConfig.addProperty("maxOutputTokens", MAX_OUTPUT_TOKENS);
                body.add("contents", contents);
                body.add("generationConfig", generationConfig);
                break;
            }
            case AiConfig.PROTOCOL_OPENAI_RESPONSES:
            default: {
                body.addProperty("model", safe.getModel());
                JsonArray input = new JsonArray();
                JsonObject inputItem = new JsonObject();
                inputItem.addProperty("role", "user");
                inputItem.addProperty("content", Objects.toString(prompt, ""));
                input.add(inputItem);
                body.add("input", input);
                headers.put("Authorization", "Bearer " + safe.getApiKey());
                break;
            }
        }
        if (!TextUtils.isEmpty(safe.getCustomUserAgent())) {
            headers.put("User-Agent", safe.getCustomUserAgent());
        }
        return new RequestSpec(resolveUrl(safe), body, headers);
    }

    public static Request buildRequest(RequestSpec spec) {
        Request.Builder builder = new Request.Builder()
                .url(spec.getUrl())
                .post(RequestBody.create(spec.getBody().toString(), JSON));
        for (Map.Entry<String, String> header : spec.getHeaders().entrySet()) {
            if (!TextUtils.isEmpty(header.getValue())) builder.header(header.getKey(), header.getValue());
        }
        return builder.build();
    }

    private static String resolveUrl(AiConfig config) {
        String endpoint = cleanBase(config.getEndpoint());
        switch (config.getProtocol()) {
            case AiConfig.PROTOCOL_OPENAI_CHAT:
                if (endpoint.endsWith("/chat/completions")) return endpoint;
                return endpoint + (endpoint.endsWith("/") ? "" : "/") + "chat/completions";
            case AiConfig.PROTOCOL_ANTHROPIC_MESSAGES:
                if (endpoint.endsWith("/messages")) return endpoint;
                return endpoint + (endpoint.endsWith("/") ? "" : "/") + "messages";
            case AiConfig.PROTOCOL_GEMINI_NATIVE:
                return buildGeminiGenerateContentUrl(endpoint, config.getModel(), config.getApiKey());
            case AiConfig.PROTOCOL_OPENAI_RESPONSES:
            default:
                if (endpoint.endsWith("/responses")) return endpoint;
                return endpoint + (endpoint.endsWith("/") ? "" : "/") + "responses";
        }
    }

    private static String buildGeminiGenerateContentUrl(String endpoint, String model, String apiKey) {
        String base = cleanBase(endpoint);
        String modelPath = model == null ? "" : model.trim();
        if (modelPath.startsWith("models/")) modelPath = modelPath.substring("models/".length());
        String url;
        if (base.contains(":generateContent")) {
            url = base;
        } else if (base.endsWith("/models")) {
            url = base + "/" + modelPath + ":generateContent";
        } else {
            url = base + "/models/" + modelPath + ":generateContent";
        }
        if (!TextUtils.isEmpty(apiKey) && !url.contains("key=")) {
            url = url + (url.contains("?") ? "&" : "?") + "key=" + apiKey;
        }
        return url;
    }

    public static String extractCompletionText(String body, AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        switch (safe.getProtocol()) {
            case AiConfig.PROTOCOL_OPENAI_CHAT:
                return extractOpenAiChatText(body);
            case AiConfig.PROTOCOL_ANTHROPIC_MESSAGES:
                return extractAnthropicText(body);
            case AiConfig.PROTOCOL_GEMINI_NATIVE:
                return extractGeminiText(body);
            case AiConfig.PROTOCOL_OPENAI_RESPONSES:
            default:
                return extractResponsesText(body);
        }
    }

    private static String extractOpenAiChatText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.size() == 0) return "";
            JsonObject c0 = choices.get(0).getAsJsonObject();
            if (c0.has("message") && c0.get("message").isJsonObject()) {
                return string(c0.getAsJsonObject("message"), "content");
            }
            return string(c0, "text");
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractAnthropicText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray content = root.getAsJsonArray("content");
            if (content == null) return "";
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : content) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                if ("text".equals(string(o, "type"))) sb.append(string(o, "text"));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractGeminiText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonArray candidates = root.getAsJsonArray("candidates");
            if (candidates == null || candidates.size() == 0) return "";
            JsonObject c0 = candidates.get(0).getAsJsonObject();
            if (!c0.has("content") || !c0.get("content").isJsonObject()) return "";
            JsonArray parts = c0.getAsJsonObject("content").getAsJsonArray("parts");
            if (parts == null) return "";
            StringBuilder sb = new StringBuilder();
            for (JsonElement el : parts) {
                if (!el.isJsonObject()) continue;
                sb.append(string(el.getAsJsonObject(), "text"));
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String extractResponsesText(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            String direct = string(root, "output_text");
            if (!TextUtils.isEmpty(direct)) return direct;
            JsonArray output = root.getAsJsonArray("output");
            if (output == null) return "";
            StringBuilder sb = new StringBuilder();
            for (JsonElement item : output) {
                if (!item.isJsonObject()) continue;
                JsonObject o = item.getAsJsonObject();
                JsonArray content = o.getAsJsonArray("content");
                if (content == null) continue;
                for (JsonElement c : content) {
                    if (!c.isJsonObject()) continue;
                    sb.append(string(c.getAsJsonObject(), "text"));
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String cleanBase(String endpoint) {
        String value = Objects.toString(endpoint, "").trim();
        int q = value.indexOf('?');
        if (q >= 0) value = value.substring(0, q);
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        if (value.startsWith("http://") || value.startsWith("https://")) return value;
        if (value.startsWith("/")) value = value.substring(1);
        return "https://" + value;
    }

    private static String string(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) return "";
        try {
            return o.get(key).getAsString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String excerpt(String s) {
        if (s == null) return "";
        return s.length() <= 160 ? s : s.substring(0, 160);
    }

    public static final class ModelInfo {
        public final String id;
        public final String label;

        public ModelInfo(String id, String label) {
            this.id = id == null ? "" : id;
            this.label = TextUtils.isEmpty(label) ? this.id : label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    public static final class ModelFetchResult {
        public final boolean ok;
        public final String message;
        public final java.util.List<ModelInfo> models;

        private ModelFetchResult(boolean ok, String message, java.util.List<ModelInfo> models) {
            this.ok = ok;
            this.message = message == null ? "" : message;
            this.models = models == null ? new java.util.ArrayList<>() : models;
        }

        public static ModelFetchResult success(java.util.List<ModelInfo> models) {
            return new ModelFetchResult(true, "", models);
        }

        public static ModelFetchResult failed(String message) {
            return new ModelFetchResult(false, message, new java.util.ArrayList<>());
        }
    }

    public static ModelFetchResult fetchModels(AiConfig config) {
        AiConfig safe = config == null ? new AiConfig().sanitize() : config.sanitize();
        if (!safe.isModelFetchReady()) return ModelFetchResult.failed("请先填写端点和 API key。");
        java.util.List<String> urls = buildModelUrlCandidates(safe);
        if (urls.isEmpty()) return ModelFetchResult.failed("无法从端点推导模型列表地址。");
        String lastError = "";
        OkHttpClient client = OkHttp.client().newBuilder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        for (String url : urls) {
            try {
                Request.Builder builder = new Request.Builder().url(url).get();
                applyModelFetchHeaders(builder, safe);
                try (Response response = client.newCall(builder.build()).execute()) {
                    String body = response.body() == null ? "" : response.body().string();
                    if (response.isSuccessful()) {
                        java.util.List<ModelInfo> models = parseModelList(body, safe);
                        if (!models.isEmpty()) return ModelFetchResult.success(models);
                        lastError = "接口成功但未解析到模型";
                        continue;
                    }
                    lastError = "HTTP " + response.code() + ": " + excerpt(body);
                    if (response.code() == 404 || response.code() == 405) continue;
                    return ModelFetchResult.failed(lastError);
                }
            } catch (Throwable e) {
                lastError = e.getMessage() == null ? "请求失败" : e.getMessage();
            }
        }
        return ModelFetchResult.failed(TextUtils.isEmpty(lastError) ? "模型列表获取失败" : lastError);
    }

    private static void applyModelFetchHeaders(Request.Builder builder, AiConfig config) {
        if (AiConfig.PROTOCOL_GEMINI_NATIVE.equals(config.getProtocol())) {
            // key 已在 query
            return;
        }
        if (AiConfig.PROTOCOL_ANTHROPIC_MESSAGES.equals(config.getProtocol())) {
            builder.header("x-api-key", config.getApiKey());
            builder.header("anthropic-version", ANTHROPIC_VERSION);
            return;
        }
        builder.header("Authorization", "Bearer " + config.getApiKey());
    }

    private static java.util.List<String> buildModelUrlCandidates(AiConfig config) {
        java.util.List<String> list = new java.util.ArrayList<>();
        String endpoint = cleanBase(config.getEndpoint());
        if (AiConfig.PROTOCOL_GEMINI_NATIVE.equals(config.getProtocol())) {
            String base = endpoint;
            if (base.contains("/models")) base = base.substring(0, base.indexOf("/models"));
            String u = base + "/models?key=" + config.getApiKey();
            list.add(u);
            list.add(base + "/models?pageSize=100&key=" + config.getApiKey());
            return list;
        }
        // OpenAI-compatible /v1/models
        String root = endpoint;
        for (String suffix : new String[]{"/chat/completions", "/completions", "/responses", "/messages"}) {
            if (root.endsWith(suffix)) {
                root = root.substring(0, root.length() - suffix.length());
                break;
            }
        }
        while (root.endsWith("/")) root = root.substring(0, root.length() - 1);
        if (root.endsWith("/v1")) list.add(root + "/models");
        else list.add(root + "/v1/models");
        list.add(root + "/models");
        return list;
    }

    private static java.util.List<ModelInfo> parseModelList(String body, AiConfig config) {
        java.util.List<ModelInfo> models = new java.util.ArrayList<>();
        try {
            JsonElement el = JsonParser.parseString(body);
            if (!el.isJsonObject()) return models;
            JsonObject root = el.getAsJsonObject();
            JsonArray data = null;
            if (root.has("data") && root.get("data").isJsonArray()) data = root.getAsJsonArray("data");
            else if (root.has("models") && root.get("models").isJsonArray()) data = root.getAsJsonArray("models");
            if (data == null) return models;
            for (JsonElement item : data) {
                if (!item.isJsonObject()) continue;
                JsonObject o = item.getAsJsonObject();
                String id = string(o, "id");
                if (TextUtils.isEmpty(id)) id = string(o, "name");
                if (TextUtils.isEmpty(id)) continue;
                // Gemini: name like models/gemini-2.0-flash
                if (id.startsWith("models/")) id = id.substring("models/".length());
                // filter non-generate models for gemini
                if (AiConfig.PROTOCOL_GEMINI_NATIVE.equals(config.getProtocol())) {
                    String lower = id.toLowerCase(java.util.Locale.ROOT);
                    if (lower.contains("embedding") || lower.contains("aqa") || lower.contains("imagen")) continue;
                }
                models.add(new ModelInfo(id, id));
            }
        } catch (Exception ignored) {
        }
        return models;
    }


}

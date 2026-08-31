package com.fongmi.android.tv.bean;

import android.text.TextUtils;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

public class AiConfig {

    private static final Gson GSON = new Gson();

    public static final String PROTOCOL_OPENAI_RESPONSES = "openai_responses";
    public static final String PROTOCOL_OPENAI_CHAT = "openai_chat";
    public static final String PROTOCOL_ANTHROPIC_MESSAGES = "anthropic_messages";
    public static final String PROTOCOL_GEMINI_NATIVE = "gemini_native";

    public static final String DEFAULT_PROTOCOL = PROTOCOL_GEMINI_NATIVE;
    public static final String DEFAULT_ENDPOINT = "https://generativelanguage.googleapis.com/v1beta";
    public static final String DEFAULT_MODEL = "gemini-2.0-flash";

    @SerializedName("enabled")
    private boolean enabled;
    @SerializedName("recommendation")
    private boolean recommendation;
    @SerializedName("titleExtraction")
    private boolean titleExtraction;
    @SerializedName("protocol")
    private String protocol;
    @SerializedName("endpoint")
    private String endpoint;
    @SerializedName("apiKey")
    private String apiKey;
    @SerializedName("model")
    private String model;
    @SerializedName("customUserAgent")
    private String customUserAgent;

    public static AiConfig objectFrom(String str) {
        try {
            if (TextUtils.isEmpty(str)) return new AiConfig().sanitize();
            AiConfig config = GSON.fromJson(str, AiConfig.class);
            return (config == null ? new AiConfig() : config).sanitize();
        } catch (Exception e) {
            return new AiConfig().sanitize();
        }
    }

    public AiConfig sanitize() {
        if (TextUtils.isEmpty(protocol) || !isSupportedProtocol(protocol)) protocol = DEFAULT_PROTOCOL;
        if (TextUtils.isEmpty(endpoint)) endpoint = defaultEndpoint(protocol);
        if (apiKey == null) apiKey = "";
        if (TextUtils.isEmpty(model)) model = DEFAULT_MODEL;
        if (customUserAgent == null) customUserAgent = "";
        endpoint = endpoint.trim();
        apiKey = apiKey.trim();
        model = model.trim();
        customUserAgent = customUserAgent.trim();
        return this;
    }

    /** 服务可用：已启用且端点/Key/模型齐全 */
    public boolean isReady() {
        return enabled && !TextUtils.isEmpty(endpoint) && !TextUtils.isEmpty(apiKey) && !TextUtils.isEmpty(model);
    }

    public boolean isModelFetchReady() {
        return !TextUtils.isEmpty(endpoint) && !TextUtils.isEmpty(apiKey);
    }

    public boolean isRecommendationEnabled() {
        return isReady() && recommendation;
    }

    public boolean isTitleExtractionEnabled() {
        return isReady() && titleExtraction;
    }

    public String toJson() {
        return GSON.toJson(this);
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isRecommendation() { return recommendation; }
    public void setRecommendation(boolean recommendation) { this.recommendation = recommendation; }
    public boolean isTitleExtraction() { return titleExtraction; }
    public void setTitleExtraction(boolean titleExtraction) { this.titleExtraction = titleExtraction; }
    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }
    public String getEndpoint() { return endpoint; }
    public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getCustomUserAgent() { return customUserAgent; }
    public void setCustomUserAgent(String customUserAgent) { this.customUserAgent = customUserAgent; }

    public static boolean isSupportedProtocol(String protocol) {
        return PROTOCOL_OPENAI_RESPONSES.equals(protocol)
                || PROTOCOL_OPENAI_CHAT.equals(protocol)
                || PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)
                || PROTOCOL_GEMINI_NATIVE.equals(protocol);
    }

    public static String defaultEndpoint(String protocol) {
        if (PROTOCOL_OPENAI_CHAT.equals(protocol)) return "https://api.openai.com/v1/chat/completions";
        if (PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) return "https://api.anthropic.com/v1/messages";
        if (PROTOCOL_OPENAI_RESPONSES.equals(protocol)) return "https://api.openai.com/v1/responses";
        return DEFAULT_ENDPOINT;
    }

    public static String protocolLabel(String protocol) {
        if (PROTOCOL_OPENAI_CHAT.equals(protocol)) return "OpenAI Chat Completions";
        if (PROTOCOL_ANTHROPIC_MESSAGES.equals(protocol)) return "Anthropic Messages";
        if (PROTOCOL_OPENAI_RESPONSES.equals(protocol)) return "OpenAI Responses API";
        return "Gemini Native generateContent";
    }

    public static String[] protocolValues() {
        return new String[]{
                PROTOCOL_GEMINI_NATIVE,
                PROTOCOL_OPENAI_CHAT,
                PROTOCOL_OPENAI_RESPONSES,
                PROTOCOL_ANTHROPIC_MESSAGES
        };
    }

    public static String[] protocolLabels() {
        return new String[]{
                protocolLabel(PROTOCOL_GEMINI_NATIVE),
                protocolLabel(PROTOCOL_OPENAI_CHAT),
                protocolLabel(PROTOCOL_OPENAI_RESPONSES),
                protocolLabel(PROTOCOL_ANTHROPIC_MESSAGES)
        };
    }
}

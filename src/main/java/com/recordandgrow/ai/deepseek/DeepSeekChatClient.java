package com.recordandgrow.ai.deepseek;

import com.recordandgrow.ai.AiCallObserver;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.ai.ChatStream;
import com.recordandgrow.ai.ChatUsage;
import com.recordandgrow.ai.FinishReason;
import com.recordandgrow.ai.config.AiProperties;
import com.recordandgrow.ai.support.JsonHttpClient;
import com.recordandgrow.ai.support.PublisherSseEventStream;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Flow;

/**
 * DeepSeek Chat Completions 客户端。
 */
public final class DeepSeekChatClient implements ChatClient {

    private final AiProperties.Chat props;
    private final String model;
    private final JsonHttpClient http;
    private final DeepSeekSseParser parser;
    private final AiCallObserver observer;
    private final URI chatUri;
    private final String clientName;

    public DeepSeekChatClient(AiProperties.Chat props, String model, ObjectMapper mapper, AiCallObserver observer) {
        this.props = props;
        this.model = model;
        this.http = new JsonHttpClient("deepseek", props.getConnectTimeout(), mapper);
        this.parser = new DeepSeekSseParser(mapper);
        this.observer = observer;
        this.chatUri = URI.create(stripTrailingSlash(props.getBaseUrl()) + "/chat/completions");
        this.clientName = "deepseek/" + model;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        long started = System.nanoTime();
        try {
            DeepSeekWire.ChatResponse wire = http.post(
                    chatUri, props.getApiKey(), requestBody(request, false),
                    DeepSeekWire.ChatResponse.class, props.getTotalTimeout());
            if (wire.choices() == null || wire.choices().isEmpty() || wire.choices().get(0).message() == null) {
                throw new AiException(AiErrorType.BAD_RESPONSE,
                        "DeepSeek 响应缺少 choices[0].message", "deepseek", null, null, null);
            }
            DeepSeekWire.Choice choice = wire.choices().get(0);
            ChatResponse response = new ChatResponse(
                    choice.message().content(),
                    FinishReason.fromWire(choice.finishReason()),
                    toUsage(wire.usage()));
            observer.onChatComplete(clientName, response.usage(), elapsed(started));
            return response;
        } catch (AiException e) {
            observer.onChatError(clientName, e.getType(), elapsed(started), 1);
            throw e;
        }
    }

    @Override
    public ChatStream stream(ChatRequest request) {
        long started = System.nanoTime();
        try {
            HttpResponse<Flow.Publisher<List<ByteBuffer>>> response = http.postStreamPublisher(
                    chatUri, props.getApiKey(), requestBody(request, true), props.getFirstTokenTimeout());
            PublisherSseEventStream source = new PublisherSseEventStream();
            response.body().subscribe(source);
            observer.onStreamStart(clientName);
            return new JdkChatStream(source, parser, observer, clientName,
                    props.getIdleTimeout(), props.getFirstTokenTimeout(), props.getTotalTimeout());
        } catch (AiException e) {
            observer.onChatError(clientName, e.getType(), elapsed(started), 1);
            throw e;
        }
    }

    private Map<String, Object> requestBody(ChatRequest request, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        List<Map<String, String>> messages = request.messages().stream()
                .map(message -> Map.of(
                        "role", message.role().name().toLowerCase(Locale.ROOT),
                        "content", message.content()))
                .toList();
        body.put("messages", messages);
        if (stream) {
            body.put("stream", true);
        }
        body.put("thinking", Map.of("type", props.thinkingEnabled() ? "enabled" : "disabled"));
        body.put("max_tokens", request.maxTokens() == null ? props.getDefaultMaxTokens() : request.maxTokens());
        if (request.temperature() != null) {
            body.put("temperature", request.temperature());
        }
        return body;
    }

    private ChatUsage toUsage(DeepSeekWire.Usage usage) {
        if (usage == null) {
            return ChatUsage.empty();
        }
        int prompt = usage.promptTokens() == null ? 0 : usage.promptTokens();
        int completion = usage.completionTokens() == null ? 0 : usage.completionTokens();
        int total = usage.totalTokens() == null ? prompt + completion : usage.totalTokens();
        return new ChatUsage(prompt, completion, total);
    }

    private static String stripTrailingSlash(String value) {
        String result = value;
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private static Duration elapsed(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}

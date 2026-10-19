package com.recordandgrow.ai.support;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.recordandgrow.ai.AiErrorType;
import com.recordandgrow.ai.AiException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI 兼容错误体解析。只提取 message，不透传原始 body。
 */
public final class OpenAiErrorParser {

    private static final Pattern RETRY_AFTER_SECONDS = Pattern.compile("^\\s*(\\d+)\\s*$");
    private static final int MAX_MESSAGE_LENGTH = 300;

    private OpenAiErrorParser() {
    }

    public static AiException toException(ObjectMapper mapper, String provider, int status,
                                          String body, String retryAfterHeader) {
        String message = extractMessage(mapper, body);
        AiErrorType type = switch (status) {
            case 400, 422 -> AiErrorType.INVALID_REQUEST;
            case 401 -> AiErrorType.AUTHENTICATION;
            case 402 -> AiErrorType.INSUFFICIENT_BALANCE;
            case 429 -> AiErrorType.RATE_LIMITED;
            default -> status >= 500 ? AiErrorType.UPSTREAM_ERROR : AiErrorType.INVALID_REQUEST;
        };
        String safeMessage = provider + " 返回 HTTP " + status
                + (message.isBlank() ? "" : ": " + truncate(message));
        return new AiException(type, safeMessage, provider, status, parseRetryAfter(retryAfterHeader), null);
    }

    public static String extractMessage(ObjectMapper mapper, String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        try {
            ErrorEnvelope envelope = mapper.readValue(body, ErrorEnvelope.class);
            if (envelope != null) {
                if (envelope.error() != null && envelope.error().message() != null) {
                    return envelope.error().message();
                }
                if (envelope.message() != null) {
                    return envelope.message();
                }
            }
        } catch (RuntimeException ignored) {
            // 非标准错误体，退回原文截断
        }
        return body;
    }

    public static Duration parseRetryAfter(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        Matcher matcher = RETRY_AFTER_SECONDS.matcher(header);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        String oneLine = value.replace('\n', ' ').replace('\r', ' ');
        return oneLine.length() <= MAX_MESSAGE_LENGTH
                ? oneLine
                : oneLine.substring(0, MAX_MESSAGE_LENGTH) + "...";
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(ErrorBody error, String message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorBody(String message, String type, String code) {
    }
}

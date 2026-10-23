package com.recordandgrow.agent.route;

import com.recordandgrow.ai.ChatClient;
import com.recordandgrow.ai.ChatMessage;
import com.recordandgrow.ai.ChatRequest;
import com.recordandgrow.ai.ChatResponse;
import com.recordandgrow.config.RoutingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 意图路由：一次小模型调用，把问题分到 {@link QueryRoute} 的四个叶子之一。
 *
 * <p>**失败一律回退 {@link QueryRoute#BOTH}**，绝不因为路由失败少查一路。
 * 兜底与"模型真的判成 BOTH"用 {@link RouteDecision#fallback()} 区分，日志里分开计数。
 *
 * <p>调用有等待上限（{@link RoutingProperties#getTimeout()}）。超时只是不等了，
 * 上游请求仍在后台跑完——分类请求很小，这个浪费可以接受；真要取消得客户端支持中断。
 *
 * <p>Prompt 里只给四类的定义与一两个判别要点，不给示例问题：示例会把模型往示例的领域带。
 */
@Component
public class QueryRouter {

    private static final Logger log = LoggerFactory.getLogger(QueryRouter.class);

    private static final int MAX_OUTPUT_TOKENS = 32;
    private static final double TEMPERATURE = 0.0;

    /** 只认 {@code "route":"X"} 这一种形状；不引 JSON 库，避免被 Jackson 2/3 的版本切换绑住。 */
    private static final Pattern ROUTE_PATTERN = Pattern.compile("\"route\"\\s*:\\s*\"?([A-Za-z]+)\"?");

    static final String SYSTEM_PROMPT = """
            你是问题路由器。判断用户问题需要哪些知识来源，只能选一个：

            TIMELINE：问用户自己的经历、做过什么、某段时间发生了什么、当时怎么安排。
            DOCUMENT：问资料里的知识、方法、步骤、条款、定义、参数，与用户经历无关。
            BOTH：既要知道用户自己的经历，又要用到资料里的知识。
            DIRECT：寒暄、自我介绍、越界提问，或与个人经历和资料都无关的一般性问题。

            判断要点：
            - 只问"我/那段时间/上次"的事，选 TIMELINE；
            - 只问"文档/资料里怎么说"，选 DOCUMENT；
            - 出现"根据我的情况看资料里的办法"这类，选 BOTH；
            - 拿不准时选 BOTH，不要选 DIRECT。

            只输出一行 JSON，不要解释、不要代码块：
            {"route":"TIMELINE|DOCUMENT|BOTH|DIRECT","confidence":0.0}
            """;

    private final ChatClient chatClient;
    private final RoutingProperties properties;
    private final Executor executor;

    public QueryRouter(ChatClient chatClient,
                       RoutingProperties properties,
                       @Qualifier("routingExecutor") Executor executor) {
        this.chatClient = chatClient;
        this.properties = properties;
        this.executor = executor;
    }

    public RouteDecision decide(String question) {
        if (!properties.isEnabled()) {
            return RouteDecision.bothByConfig();
        }
        if (question == null || question.isBlank()) {
            return RouteDecision.fallback("blank-question");
        }
        if (!chatClient.isConfigured()) {
            return RouteDecision.fallback("not-configured");
        }

        ChatRequest request = new ChatRequest(
                List.of(new ChatMessage(ChatMessage.Role.SYSTEM, SYSTEM_PROMPT),
                        new ChatMessage(ChatMessage.Role.USER, "用户问题：" + truncate(question))),
                MAX_OUTPUT_TOKENS,
                TEMPERATURE);

        CompletableFuture<ChatResponse> future =
                CompletableFuture.supplyAsync(() -> chatClient.complete(request), executor);
        try {
            ChatResponse response = future.get(properties.getTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return parse(response == null ? null : response.content());
        } catch (TimeoutException e) {
            future.cancel(true);
            log.warn("routing timeout: timeoutMs={}", properties.getTimeout().toMillis());
            return RouteDecision.fallback("timeout");
        } catch (ExecutionException e) {
            log.warn("routing failed: type={}", e.getCause() == null ? "unknown"
                    : e.getCause().getClass().getSimpleName());
            return RouteDecision.fallback("error");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return RouteDecision.fallback("interrupted");
        }
    }

    /**
     * 解析模型输出。容忍三种情况：纯 JSON、带 ```json 围栏、直接输出叶子名。
     * 其余一律兜底，不猜。
     */
    RouteDecision parse(String content) {
        String text = stripFence(content);
        if (text == null || text.isEmpty()) {
            return RouteDecision.fallback("empty");
        }
        Matcher matcher = ROUTE_PATTERN.matcher(text);
        if (matcher.find()) {
            QueryRoute route = QueryRoute.parse(matcher.group(1));
            return route == null ? RouteDecision.fallback("unknown-route") : RouteDecision.of(route);
        }
        // 模型偶尔只吐一个词；能认出来就用，认不出再兜底
        QueryRoute route = QueryRoute.parse(text);
        return route == null ? RouteDecision.fallback("bad-json") : RouteDecision.of(route);
    }

    private static String stripFence(String content) {
        if (content == null) {
            return null;
        }
        String text = content.trim();
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            if (firstNewline < 0) {
                return "";
            }
            text = text.substring(firstNewline + 1);
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text.trim();
    }

    private String truncate(String question) {
        String trimmed = question.trim();
        int max = properties.getMaxQuestionChars();
        if (max <= 0 || trimmed.length() <= max) {
            return trimmed;
        }
        return trimmed.substring(0, max);
    }
}

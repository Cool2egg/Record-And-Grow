# 模块三：AI 接入 —— 设计方案（待确认）

> 状态：待确认。确认后再从最新 main 切 `module/ai` 实施。
> 日期：2026-10-17
> 依据：当前仓库代码 + DeepSeek / 阿里云百炼官方文档（2026-10-17）。

## 0. 结论摘要

| # | 决策 | 重要度 |
|---|---|---|
| 1 | 定义 `ChatClient` + `EmbeddingClient` 两个接口；业务层只依赖接口、DTO 和 `AiException` | ★★★★★ |
| 2 | DeepSeek 主用 `deepseek-flash`、备用 `deepseek-v4-pro`；默认关闭思考模式 | ★★★★★ |
| 3 | Embedding 用 `text-embedding-v4`，1024 维，单请求最多 10 条 | ★★★★★ |
| 4 | HTTP 用 Spring `RestClient`（底层 JDK HttpClient），不引 WebFlux；流式在虚拟线程消费 | ★★★★ |
| 5 | SSE 用 `SseEmitter`；`start / delta / done / error` + 15s 注释心跳；建立流后不重试 | ★★★★★ |
| 6 | 本模块不改数据库；只加 3 个自检接口，供模块四替换 | ★★★ |

### 标注说明

- `[代码]`：已读当前仓库代码确认。
- `[实测]`：本次实际运行/执行确认。
- `[推断]`：基于官方文档或经验的判断，尚未运行。
- `[待验证]`：尚未验证；包含“只看了官方文档、还没真实调用”的结论。

> 本次只做了仓库与官方文档核对，没有运行应用或调用真实 API。因此外部 API 行为一律标 `[待验证]`，不标“已验证”。

---

## 1. 目标与边界

### 1.1 目标（★★★★★）

- 定义可替换的 `ChatClient` / `EmbeddingClient`，业务层不出现 DeepSeek / Qwen 的 URL、请求体、字段名、错误码。
- 打通 DeepSeek 非流式与流式调用；打通 Qwen embedding 批量调用。
- 建立统一的超时、重试、降级、异常边界，供模块四（时间线问答 + SSE）和模块五（文档向量化）直接复用。
- 用 WireMock 覆盖关键成功/失败路径，测试不依赖真实 API key。
- 预留可观测 hook，本模块不接 Langfuse。

### 1.2 不做（★★★★★）

- 不做 RAG、文档检索、知识图谱、记忆系统、Agent 编排、前端。
- 不引入 Spring AI / LangChain4j / WebFlux / Resilience4j。
- 不做运行时多供应商动态路由、模型档位、首包探测、熔断。
- 不接 Langfuse；不做工具调用 / ReAct；不做多模态；不做 JSON mode。
- 不改数据库；不写业务 prompt（prompt 属于模块四）。

### 1.3 交付物（★★★★）

- 一组可运行代码：接口、DeepSeek 实现、Qwen embedding 实现、SSE 自检接口、测试。
- 一份验证结果：本机 `mvn test` 输出 + `curl -N` 的 SSE 记录 + MockWebServer/WireMock 失败场景记录。
- 文档：本设计说明；README 模块三链接由我挂，学习记录由你写。

---

## 2. 推荐实现方案

### 2.1 调用链（★★★★★）

```
模块四 ChatService ──> ChatClient（接口）
                         └─ FailoverChatClient
                              ├─ DeepSeekChatClient(primary, deepseek-flash)
                              └─ DeepSeekChatClient(fallback, deepseek-v4-pro)

模块五 DocumentService ──> EmbeddingClient（接口）
                              └─ QwenEmbeddingClient(text-embedding-v4)

Controller ──> SseEmitter ──> 虚拟线程消费 ChatStream ──> emitter.send(delta)
```

约束：`com.recordandgrow.ai.deepseek`、`com.recordandgrow.ai.qwen` 只允许被 `ai.config` 引用；业务包只 import `com.recordandgrow.ai` 下的接口和 DTO。[推断]

### 2.2 接口设计（★★★★★）

```java
public interface ChatClient {
    ChatResponse complete(ChatRequest request);

    /**
     * 建立流式请求。方法正常返回表示上游已返回 HTTP 200 并建立流；
     * 之后的读取错误通过 ChatStream.next() 抛出 AiException。
     */
    ChatStream stream(ChatRequest request);
}

public interface ChatStream extends AutoCloseable {
    /** 阻塞读取下一个增量；流正常结束时返回 false。 */
    boolean hasNext();

    ChatChunk next();

    /** 取消上游请求并释放连接；可重复调用。 */
    @Override
    void close();
}

public interface EmbeddingClient {
    /** 按输入顺序返回向量；内部按供应商批量上限自动分批。 */
    List<float[]> embed(List<String> texts);

    /** 向量维度，供模块五建表/校验使用。 */
    int dimensions();
}

public record ChatRequest(List<ChatMessage> messages, Integer maxTokens, Double temperature) {
    public ChatRequest {
        messages = List.copyOf(messages);
    }
}

public record ChatMessage(Role role, String content) {
    public enum Role { SYSTEM, USER, ASSISTANT }
}

public record ChatResponse(String content, FinishReason finishReason, ChatUsage usage) {}

public record ChatChunk(String contentDelta, FinishReason finishReason, ChatUsage usage) {
    public boolean hasContent() {
        return contentDelta != null && !contentDelta.isEmpty();
    }
}

public record ChatUsage(int promptTokens, int completionTokens, int totalTokens) {}

public enum FinishReason {
    STOP, LENGTH, CONTENT_FILTER, TOOL_CALLS, INSUFFICIENT_RESOURCE, ABORTED, UNKNOWN
}
```

设计取舍：

- 用阻塞式 `Iterator`，不用 `Flux`：业务层不需要理解 Reactor；虚拟线程下阻塞读是最直观的模型。[推断]
- 不用回调 `Listener`：取消、超时、异常传播都要额外状态机，迭代器 + `AutoCloseable` 更简单。[推断]
- `ChatRequest` 不出现 `model`、`thinking` 等供应商字段；模型和思考模式属于配置，不属于业务请求。[代码/设计]
- `ChatResponse` 不暴露原始 JSON、provider、requestId；这些只进日志和 observer。[设计]

### 2.3 供应商实现（★★★★★）

#### DeepSeek

- Base URL：`https://api.deepseek.com`，`POST /chat/completions`。[待验证·官方文档]
- 当前模型：`deepseek-flash`、`deepseek-v4-pro`；旧名 `deepseek-chat` / `deepseek-reasoner` 已于 2026-07-24 停用。[待验证·官方文档]
- 思考模式默认开启，`reasoning_effort` 默认 `high`；本模块统一发送 `{"thinking":{"type":"disabled"}}`。[待验证·官方文档]
  - 原因：思考模式下首段是 `reasoning_content`，不是 `content`；SSE 会出现长时间“没有正文”的静默，首包体验和超时判断都变复杂。[推断]
- 流式响应以 `data: [DONE]` 结束；等待推理时可能持续发送 `: keep-alive` 注释。[待验证·官方文档]
- `usage` 在最后一个内容块上；非流式在响应体 `usage`。[待验证·官方文档]
- 错误码：400 / 401 / 402 / 422 / 429 / 500 / 503。[待验证·官方文档]

请求体（流式）：

```json
{
  "model": "deepseek-flash",
  "messages": [{"role": "user", "content": "..."}],
  "stream": true,
  "thinking": {"type": "disabled"},
  "max_tokens": 2048,
  "temperature": 0.3
}
```

#### Qwen Embedding

- Base URL（华北2/北京，OpenAI 兼容）：`https://dashscope.aliyuncs.com/compatible-mode/v1`，`POST /embeddings`。[待验证·官方文档]
- 模型：`text-embedding-v4`；维度可选 2048/1536/1024/768/512/256/128/64，默认 1024。[待验证·官方文档]
- 单请求最多 10 条，每条最多 8192 tokens；`encoding_format` 只支持 `float`。[待验证·官方文档]
- 响应 `data[].index` 不保证按顺序返回，必须按 `index` 排序后再对应输入。[待验证·官方文档]
- `qwen3.7-text-embedding` 是更新的模型，但当前 OpenAI 兼容文档的模型列表只列了 `text-embedding-v4` / `text-embedding-v3`；本模块不选它，避免为它单独接 DashScope 原生协议。[推断/待验证]

请求体：

```json
{
  "model": "text-embedding-v4",
  "input": ["文本1", "文本2"],
  "dimensions": 1024,
  "encoding_format": "float"
}
```

### 2.4 HTTP 客户端选型（★★★★）

| 方案 | 结论 | 理由 |
|---|---|---|
| JDK `HttpClient`（非流式 + `BodyHandlers.ofPublisher()` 流式） | **采用** | 零额外依赖；流式响应以 `Flow.Publisher` 暴露，取消用 `Subscription.cancel()`，不依赖“关闭 InputStream 唤醒阻塞读” |
| Spring `RestClient` | 不采用 | `exchange` 回调返回后响应会被关闭，无法把 InputStream 留给调用方后继续读；再引一套客户端只增加分叉 |
| `WebClient` | 不采用 | 为流式引入 WebFlux/Reactor；在 WebMVC 里桥接 `SseEmitter` 反而更绕 |
| Apache HttpClient 5 | 不采用 | 多一个依赖，没有必须用的能力 |

实现要点：

- 每个 provider 自己持有 `HttpClient`，base URL、连接超时、认证头在构造时固定。
- 非流式：`send` + `BodyHandlers.ofString`，用 Jackson 3（`tools.jackson`）反序列化。
- 流式：`sendAsync` + `BodyHandlers.ofPublisher()`；`PublisherSseEventStream` 在 HttpClient 线程解析字节流（按 `\n` 切行，UTF-8 多字节字符跨 buffer 也安全），把完整事件写入有界队列；消费方用 200ms 超时 `poll`。
- 取消：`SseEventStream.close()` → `Subscription.cancel()`；超时由看门狗触发同一路径。
- 读超时用“看门狗”而不是 socket 读超时：记录 `lastActivityAt`、`firstContentAt`，定时检查 idle / first-token / total 三个 deadline。
- **实测发现**：关闭 `InputStream` 不能保证唤醒阻塞中的 `readLine`（沙箱单测用 `PipedInputStream` 复现）。因此没有采用“阻塞读 + close 唤醒”的方案。[实测·单测]

### 2.5 流式消费方式（★★★★）

| 方案 | 结论 |
|---|---|
| `ChatStream` 迭代器 + 虚拟线程 | **采用**：对外仍是 `while (hasNext())`；内部用有界队列 + 带超时 poll，业务层不接触 Reactor |
| Reactor `Flux` | 不采用：把 Reactor 泄漏到业务层，违背“业务层不认识供应商/框架细节” |
| 回调 Listener | 不采用：取消、超时、异常传播需要额外状态机 |

虚拟线程：单独定义 `aiStreamExecutor`（`Executors.newVirtualThreadPerTaskExecutor()`），不打开全局 `spring.threads.virtual.enabled`，避免影响现有 MVC/DB 行为。[推断]

### 2.6 SSE 生命周期（★★★★★）

服务端接口：`POST /api/ai/chat/stream`，返回 `SseEmitter`。请求体是 JSON，所以前端不能用 `EventSource`（只支持 GET），要用 `fetch` + `ReadableStream` 读取；命令行用 `curl -N`。[推断]

| 场景 | 服务端动作 | 客户端看到 |
|---|---|---|
| 正常结束 `finish_reason=stop` | 发 `done`（含 usage）→ `complete()` | `start` → `delta...` → `done` |
| 输出被截断 `finish_reason=length` | 发 `done`，`finishReason=LENGTH` → `complete()` | `done`，前端可提示“回答被截断” |
| `content_filter` | 发 `done`，`finishReason=CONTENT_FILTER` → `complete()` | `done`，前端提示内容被过滤 |
| `insufficient_system_resource` / `aborted` | 发 `error`（`AI_UPSTREAM_ERROR`）→ `complete()` | `error`，已收到的 delta 保留 |
| 上游 4xx/5xx/连接失败/超时（未发 delta） | 重试/降级；仍失败则发 `error` → `complete()` | `start` → `error` |
| 已发过 delta 后上游断流 | 不重试；发 `error`（`partial=true`）→ `complete()` | 部分内容 + `error` |
| 客户端主动断开 | `onCompletion/onError` → 置取消标记 → 关闭上游 → 停心跳 | 无 |
| 向已断开客户端写失败 | 捕获 `IOException` → 同上，不记 ERROR | 无 |
| 服务端 `SseEmitter` 超时（240s） | `onTimeout` → 关闭上游 → 尝试发 `error` → `complete()` | 连接关闭（error 不保证送达） |
| 等待上游首包 | 每 15s 发 SSE 注释 `: ping` | 连接保持，无业务事件 |

事件格式：

```text
event:start
data:{"requestId":"..."}

event:delta
data:{"content":"我"}

event:done
data:{"finishReason":"STOP","usage":{"promptTokens":12,"completionTokens":45,"totalTokens":57}}

event:error
data:{"code":50301,"errorType":"RATE_LIMITED","message":"AI 服务暂时不可用","partial":false,"requestId":"..."}

: ping
```

响应头：`Content-Type: text/event-stream;charset=UTF-8`、`Cache-Control: no-cache`、`X-Accel-Buffering: no`。[推断]

关键边界：

- 同步参数校验在返回 `SseEmitter` 之前完成，失败仍走 `ApiResponse` + `GlobalExceptionHandler`（400/503）。[代码]
- 一旦开始 SSE，HTTP 状态码固定 200，错误只能通过 `event:error` 表达；不能再依赖 `@RestControllerAdvice`。[推断]
- 心跳与 delta 写同一个 `SseEmitter`，需要确认 `SseEmitter.send` 的并发写是否安全；实施时用单线程顺序写或加锁，并写测试。[待验证]

### 2.7 重试与降级（★★★★★）

#### 哪些错误可重试

| 场景 | 非流式 | 流式（未建立流） | 流式（已建立流） |
|---|---|---|---|
| 连接失败 / DNS / 连接超时 | 可重试 | 可重试 | 不适用 |
| 429 限流 | 可重试（优先 `Retry-After`） | 可重试 | 不重试 |
| 500 / 502 / 503 / 504 | 可重试 | 可重试 | 不重试 |
| 400 / 422 参数错误 | 不重试 | 不重试 | 不重试 |
| 401 鉴权失败 | 不重试 | 不重试 | 不重试 |
| 402 余额不足 | 不重试 | 不重试 | 不重试 |
| 读超时 / 首 token 超时 | 可重试 | 可重试 | 不重试 |
| 响应 JSON 解析失败 | 不重试 | 不重试 | 不重试 |
| 已转发 delta 后断流 | — | — | 不重试 |
| 客户端取消 | 不重试 | 不重试 | 不重试 |

结论：**“流式开始”定义为上游返回 HTTP 200 并成功创建 `ChatStream`。此后一律不重试、不降级**，避免已输出内容重复。若想做到“收到 200 但一个 delta 都没发时还能降级”，需要把重试状态上移到 SSE 层，本期不做。[设计]

#### 重试预算与退避

- 单个请求最多 3 次上游调用：主用 2 次（首次 + 1 次重试）→ 备用 1 次。
- 退避：300ms、800ms，叠加 ±20% 抖动；429 有 `Retry-After` 时优先使用，上限 5s。
- 总 deadline 180s；每次重试前检查剩余时间，不足 2s 直接放弃。
- 只为 429/5xx/超时/网络错误重试；4xx（除 429）不重试。
- 客户端断开后立即停止重试。
- 不做全局令牌桶/熔断：单用户项目，3 次上限 + 退避 + 抖动已足够避免重试风暴；熔断属于明确不做项。[设计]

#### 降级策略

- 主用：`deepseek-flash`；备用：`deepseek-v4-pro`。
- 两者同属 DeepSeek，同一账号/同一 base URL，因此**只能覆盖“单模型过载/限流”，不能覆盖 DeepSeek 整体故障**。这是本期接受的缺口。[设计]
- `FailoverChatClient` 只按顺序尝试，不做健康探测、不做动态路由；备用也失败就抛 `AiException`。[设计]

### 2.8 异常边界（★★★★）

```java
public enum AiErrorType {
    NOT_CONFIGURED(false),
    INVALID_REQUEST(false),        // 400 / 422
    AUTHENTICATION(false),         // 401
    INSUFFICIENT_BALANCE(false),   // 402
    RATE_LIMITED(true),            // 429
    UPSTREAM_ERROR(true),          // 5xx
    TIMEOUT(true),                 // connect / idle / first-token / total
    NETWORK(true),                 // IOException
    STREAM_INTERRUPTED(false),     // 已建立流后断流
    BAD_RESPONSE(false);           // JSON/字段不符合预期

    private final boolean retryable;
}
```

`AiException` 携带：`AiErrorType`、provider、HTTP status（可空）、`Retry-After`（可空）、安全的用户消息。**不把上游原始响应体直接透传给客户端**；日志里截断到 500 字符，并脱敏 API key。[设计]

映射到现有 `ErrorCode`：

- `AI_UNAVAILABLE(50301, "AI 服务暂时不可用", 503)`：网络、超时、429、5xx、鉴权/余额/未配置（对外统一措辞，细节只进日志）。
- `AI_UPSTREAM_ERROR(50201, "AI 服务返回异常", 502)`：400/422/响应格式错误/流中断。

非流式由 `GlobalExceptionHandler` 统一转 `ApiResponse`；流式由 SSE 服务自己发 `event:error`。[设计]

### 2.9 配置与密钥（★★★★）

```yaml
recordandgrow:
  ai:
    chat:
      base-url: ${DEEPSEEK_BASE_URL:https://api.deepseek.com}
      api-key: ${DEEPSEEK_API_KEY:}
      model: ${DEEPSEEK_MODEL:deepseek-flash}
      fallback-model: ${DEEPSEEK_FALLBACK_MODEL:deepseek-v4-pro}
      fallback-enabled: true
      thinking: disabled          # enabled / disabled
      connect-timeout: 5s
      idle-timeout: 30s
      first-token-timeout: 60s
      total-timeout: 180s
      max-attempts: 2             # 主用最多 2 次；备用固定 1 次
      default-max-tokens: 2048
    embedding:
      base-url: ${DASHSCOPE_BASE_URL:https://dashscope.aliyuncs.com/compatible-mode/v1}
      api-key: ${DASHSCOPE_API_KEY:}
      model: text-embedding-v4
      dimensions: 1024
      batch-size: 10
      timeout: 30s
```

- Key 只从环境变量读取；`application.yml` 里只写占位符，默认空串。[代码/设计]
- 应用启动不因 key 缺失而失败（沿用“无 DB 也能启动”的约定）；真正调用时抛 `NOT_CONFIGURED`，日志 WARN。[代码]
- `.gitignore` 增加 `.env`、`.env.*`（保留 `.env.example`，如果以后需要）。[代码：当前只有 `application-local.yml` 被忽略]
- 本地用 IDEA Run Configuration 环境变量，或 `application-local.yml`（已忽略）。[代码]
- 日志禁止打印 `Authorization`、完整 prompt、完整 embedding 向量。[设计]

### 2.10 可观测 hook（★★）

```java
public interface AiCallObserver {
    default void onChatComplete(String client, ChatUsage usage, Duration latency) {}
    default void onChatError(String client, AiErrorType type, Duration latency, int attempts) {}
    default void onStreamStart(String client) {}
    default void onStreamEnd(String client, ChatUsage usage, Duration latency, boolean cancelled) {}
    default void onEmbeddingComplete(int count, int dimensions, ChatUsage usage, Duration latency) {}
}
```

默认注册 `NoopAiCallObserver`；以后接 Langfuse 时只换一个实现。记录：client 名、模型、延迟、token、错误类型、attempt 次数、requestId。不记录 prompt 正文。[设计]

### 2.11 被否掉的方案（★★★★）

- **Spring AI / LangChain4j**：抽象太重，供应商细节仍会漏；学习价值低，且与“手搓”目标冲突。
- **WebFlux / WebClient 全栈**：为一个单用户项目引入两套 Web 栈，收益不抵复杂度。
- **Resilience4j / Spring Retry**：熔断、舱壁本期不做；手写 30 行重试策略更透明。
- **运行时多供应商动态路由**：没有需求；接口可替换 + 静态主备已够。
- **qwen3.7-text-embedding**：更新更好，但当前 OpenAI 兼容接口列表没有它；为了一个模型接第二套 DashScope 原生协议不值得。[推断/待验证]

---

## 3. 涉及的核心知识

1. **OpenAI 兼容协议**：`messages` / `role` / `max_tokens` / `temperature` / `stream` / `usage`；DeepSeek 与 Qwen embedding 都是这个形状。
2. **SSE 线协议**：`event:`、`data:`、空行分隔、`data: [DONE]`、注释行 `: keep-alive`；POST + fetch 与 EventSource 的区别。
3. **流式增量组装**：delta 不是完整 JSON；`finish_reason` 只在最后出现；usage 只在最后一块；不能把每个 chunk 当完整回答。
4. **思考模式**：DeepSeek 默认开启 thinking，正文前有 `reasoning_content`；对首包延迟、超时、SSE 事件设计的影响。
5. **错误分类与重试语义**：可重试 vs 不可重试；幂等性；指数退避 + 抖动；`Retry-After`；重试风暴的边界。
6. **超时分层**：connect / idle / first-token / total / emitter；deadline 传播；看门狗。
7. **Spring MVC 异步**：`SseEmitter` 的 `onCompletion` / `onTimeout` / `onError`；响应已提交后异常处理器失效；心跳；代理缓冲。
8. **客户端取消**：写失败/心跳失败检测断开；关闭上游流；取消与重试的互斥。
9. **Embedding**：维度、批量上限、每行 token 上限、`index` 排序、余弦距离、pgvector 维度约束（1024 适合 `vector` 索引；2048 超出常见 HNSW 上限）。[推断]
10. **安全与测试**：环境变量、日志脱敏；WireMock 场景/延迟/断流；测试不依赖真实 key。

---

## 4. 文件与包结构

```
src/main/java/com/recordandgrow/
├── ai/
│   ├── ChatClient.java
│   ├── ChatStream.java
│   ├── ChatRequest.java
│   ├── ChatResponse.java
│   ├── ChatChunk.java
│   ├── ChatMessage.java
│   ├── ChatUsage.java
│   ├── FinishReason.java
│   ├── EmbeddingClient.java
│   ├── AiException.java
│   ├── AiErrorType.java
│   ├── AiCallObserver.java
│   ├── NoopAiCallObserver.java
│   ├── config/
│   │   ├── AiProperties.java
│   │   └── AiClientConfig.java
│   ├── deepseek/
│   │   ├── DeepSeekChatClient.java
│   │   ├── DeepSeekWire.java          // 请求/响应 record，仅内部使用
│   │   ├── DeepSeekSseParser.java
│   │   └── JdkChatStream.java
│   ├── qwen/
│   │   ├── QwenEmbeddingClient.java
│   │   └── QwenWire.java
│   ├── sse/
│   │   ├── ChatSseService.java
│   │   └── SseEvents.java             // start/delta/done/error 载荷
│   └── support/
│       ├── FailoverChatClient.java
│       ├── RetryPolicy.java
│       ├── SseEventStream.java        // 事件源接口
│       ├── PublisherSseEventStream.java
│       ├── StreamWatchdog.java
│       ├── JsonHttpClient.java
│       └── OpenAiErrorParser.java
├── controller/
│   └── AiDiagnosticController.java    // 自检接口，模块四可替换
└── ai/dto/
    ├── AiChatRequest.java
    ├── AiChatResponse.java
    ├── AiEmbeddingRequest.java
    └── AiEmbeddingResponse.java
```

现有文件改动：

| 文件 | 改动 | 原因 |
|---|---|---|
| `pom.xml` | 加 `wiremock-standalone`（test scope） | 流式/状态码/重试测试，不依赖真实 key |
| `application.yml` | 加 `recordandgrow.ai.*` | 配置集中，key 走环境变量 |
| `.gitignore` | 加 `.env`、`.env.*` | 防止密钥误提交 |
| `ErrorCode.java` | 加 `AI_UNAVAILABLE`、`AI_UPSTREAM_ERROR` | 统一异常出口 |
| `GlobalExceptionHandler.java` | 加 `AiException` 处理 | 非流式接口返回 502/503 |
| `docs/known-issues.md` | 新建，记录模块二遗留 | 你要求先记入已知问题 |
| `README.md` | 模块三学习笔记链接 | 仓库呈现规范 |

---

## 5. 数据库与接口设计

### 5.1 数据库（★★★★★）

本模块**不改表、不加迁移**。模块五再新增 `document_chunk.embedding vector(1024)`；现在先把 `EmbeddingClient.dimensions()` 固定为 1024，避免以后换维度导致全量重嵌入。[设计]

### 5.2 HTTP 自检接口（★★★）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/ai/chat` | 非流式，验证 DeepSeek 基本调用与错误映射 |
| POST | `/api/ai/chat/stream` | SSE 流式，验证 delta/心跳/结束/错误/取消 |
| POST | `/api/ai/embedding` | 验证 Qwen 批量向量与维度 |

请求：

```json
{
  "messages": [
    {"role": "system", "content": "你是简洁的助手。"},
    {"role": "user", "content": "用一句话介绍你自己"}
  ],
  "maxTokens": 512,
  "temperature": 0.3
}
```

非流式响应：

```json
{
  "code": 0,
  "message": "ok",
  "data": {
    "content": "...",
    "finishReason": "STOP",
    "usage": {"promptTokens": 20, "completionTokens": 30, "totalTokens": 50}
  }
}
```

Embedding 响应只返回元信息，不回传 1024 个浮点数：

```json
{"code":0,"message":"ok","data":{"count":2,"dimensions":1024}}
```

这些接口定位为“自检/诊断”，模块四会用真正的业务接口替换；保留它们不影响业务边界。[设计]

### 5.3 SSE 事件（★★★★★）

见 2.6。补充约定：

- 每个 `data:` 都是 JSON（避免换行破坏 SSE 格式）。
- `delta` 只发 `content`，不发 `reasoning_content`（本期 thinking 关闭）。
- `done` 一定在正常结束时发送；`error` 一定在异常结束时发送；两者互斥。
- `requestId` 由服务端生成（UUID），同时进 MDC 和日志，便于把客户端错误和服务端日志对上。[设计]
- 前端不要自动重连：LLM 流不可恢复，重连会重复请求/计费。用 `fetch` 手动控制。[推断]

### 5.4 错误码（★★★★）

| 业务码 | HTTP | 场景 |
|---|---|---|
| 50301 `AI_UNAVAILABLE` | 503 | 网络/超时/429/5xx/未配置/鉴权/余额 |
| 50201 `AI_UPSTREAM_ERROR` | 502 | 400/422/响应格式错误/流中断 |

SSE 里额外带 `errorType`（如 `RATE_LIMITED`）和 `partial`（是否已发过 delta），方便前端和排查，但不暴露上游原始报文。[设计]

---

## 6. 验收标准

### 6.1 必须通过（★★★★★）

- [ ] 本机 `mvn test` 全绿；测试过程不访问真实 DeepSeek/Qwen（可用断网跑一遍确认）。
- [ ] `git grep -n "sk-"` 无命中；仓库无 `.env`、无真实 key。
- [ ] 不配置任何 key 时应用能启动；调用 AI 接口返回 50301，不启动失败。
- [ ] `POST /api/ai/chat` 返回非空 `content`，`usage` 正确。
- [ ] `curl -N POST /api/ai/chat/stream` 能看到 `start` → 多个 `delta` → `done`，最后连接正常关闭。
- [ ] 上游延迟时每 15s 收到 `: ping`，客户端连接不被代理断开。
- [ ] 模拟 429：主用重试/切换备用；模拟 400/401：不重试。
- [ ] 模拟已发 delta 后断流：客户端收到 `error(partial=true)`，没有重复内容。
- [ ] `Ctrl+C` 中断 curl：15s 内服务端日志出现 cancel，上游连接关闭。
- [ ] `POST /api/ai/embedding` 传入 25 条文本：内部发 3 次请求（10/10/5），返回顺序与输入一致，维度 1024。
- [ ] 日志包含 requestId、client、延迟、token、错误类型；不包含 key、完整 prompt、完整向量。

### 6.2 测试矩阵（★★★★★）

| 测试 | 手段 | 断言 |
|---|---|---|
| 非流式成功 | WireMock 200 | content/finishReason/usage |
| 429 后成功 | WireMock scenario：429 → 200 | 调用 2 次，最终成功 |
| 500 后降级 | WireMock：主用 500，备用 200 | 备用被调用，observer 记录 fallback |
| 401 不重试 | WireMock 401 | 只调用 1 次，`AUTHENTICATION` |
| 400 不重试 | WireMock 400 | 只调用 1 次，`INVALID_REQUEST` |
| SSE 正常 | WireMock chunked body + `[DONE]` | delta 拼接、done、usage |
| SSE keep-alive | body 中插入 `: keep-alive` | 解析器忽略注释，不产生 delta |
| SSE 断流 | WireMock 发送部分后关闭 | `STREAM_INTERRUPTED`，不重试 |
| 首 token 超时 | WireMock 延迟超过阈值 | `TIMEOUT` |
| 客户端取消 | 测试用 SseEmitter 双倍/断开的 writer | 上游 `close()` 被调用 |
| Embedding 分批 | 25 条输入 | 3 次请求，10/10/5，按 index 排序 |
| SSE 行解析 | 单元测试：CRLF、空行、多行 data、`[DONE]` | 不丢/不重 |

### 6.3 定义完成（★★★★）

一个模块 = 一组可运行代码 + 一份验证结果 + 文档链接：

- 分支 `module/ai`，小步提交，PR 合并回 main。
- PR 描述贴 `mvn test` 摘要、curl SSE 片段、失败场景测试结果。
- 本设计文档 + `docs/learning/03-ai/` 学习记录链接（记录你写）。

---

## 7. 风险与前置检查

| 风险 | 影响 | 应对 |
|---|---|---|
| DeepSeek 模型名已从 `deepseek-chat` 迁到 `deepseek-flash` / `deepseek-v4-pro` | 按旧名开发会直接 400/404 | 模型名全部走配置；文档链接写进 PR [待验证·官方文档] |
| 思考模式默认开启 | 首包慢、SSE 长时间无正文、成本高 | 默认 `thinking=disabled`；需要时再开 [待验证·官方文档] |
| 同供应商主备不能覆盖 DeepSeek 整体故障 | 降级失效 | 本期接受；接口留好，后续可加第二家 chat |
| SSE 响应已提交后全局异常处理器失效 | 错误格式不统一 | SSE 服务自己发 `event:error`；同步校验放在返回 emitter 之前 |
| 心跳与 delta 并发写 `SseEmitter` | 偶发乱序/异常 | 单写者或加锁；实施时写并发测试 [待验证] |
| 关闭 `InputStream` 不一定能立刻唤醒阻塞读 | 取消后线程短暂滞留 | 看门狗 + socket 读超时兜底；必要时换 WebClient |
| 代理缓冲 SSE | 客户端收不到实时 delta | `X-Accel-Buffering: no` + 心跳 |
| WireMock 流式时序测试不稳定 | CI 偶发失败 | 用确定性延迟/分块；超时阈值留余量 |
| Jackson 3（Spring Boot 4）包名/API 变化 | 编译/解析失败 | 实施第一步先编译验证；必要时用 Spring 消息转换器 |
| 1024 维锁定 | 以后换维度要全量重嵌入 | 现在定 1024；模块五建表前再确认 [推断] |
| 模块二遗留：真实停库 500/503 未实测、`?page=abc` 返回 500 | 与本模块无关但影响“全绿”叙事 | 记入 `docs/known-issues.md`，不在本模块修 |
| **main 有未提交修改** | 无法干净地从 main 切 `module/ai` | 先提交或 stash：`docs/design/overview.md`、`TimelineEntry.java`、`TimelineEntryResponse.java`、两个测试文件；`.attach_pid62` 可删除 [实测] |
| **沙箱只有 JDK 11、无 Maven** | 我无法在这里跑 `mvn test` | 编译验证需先在沙箱装 JDK21 + Maven；运行/集成验证在你本机 [实测] |

---

## 8. 实施顺序（确认后执行）

1. **分支卫生**：处理 main 未提交修改 → `git checkout main && git pull && git checkout -b module/ai`；你本机确认 `mvn test` 全绿。
2. **骨架**：`AiProperties`、DTO、`AiException`、两个接口、no-op observer；先保证编译。
3. **DeepSeek 非流式**：请求/响应、错误映射、重试策略 + WireMock 测试。
4. **SSE 解析与流式**：`PublisherSseEventStream`、`ChatStream`、看门狗 + 单元测试。
5. **主备降级**：`FailoverChatClient` + 场景测试。
6. **Qwen Embedding**：分批、排序、维度校验 + WireMock 测试。
7. **自检接口与 SSE 服务**：Controller、`ChatSseService`、心跳、取消 + 测试。
8. **收尾**：配置、`.gitignore`、`known-issues.md`、README 链接、全量测试、PR。

每一步只改必要文件，单独提交，提交信息用 `feat(ai): ...` / `test(ai): ...`。

---

## 9. 官方依据

- DeepSeek 模型与价格：https://api-docs.deepseek.com/quick_start/pricing
- DeepSeek Chat Completions：https://api-docs.deepseek.com/api/create-chat-completion
- DeepSeek 思考模式：https://api-docs.deepseek.com/guides/thinking_mode
- DeepSeek 错误码：https://api-docs.deepseek.com/quick_start/error_codes
- DeepSeek 限速与 keep-alive：https://api-docs.deepseek.com/quick_start/rate_limit
- Qwen OpenAI 兼容 Embedding：https://help.aliyun.com/zh/model-studio/embedding-interfaces-compatible-with-openai
- Qwen text-embedding-v4：https://help.aliyun.com/zh/model-studio/text-embedding-v4
- Qwen qwen3.7-text-embedding：https://help.aliyun.com/zh/model-studio/qwen3-7-text-embedding

---

## 10. 实施回填（2026-10-17）

### 10.1 已完成的验证（沙箱）

- `mvn -Dtest='!*IntegrationTest' test`：54 个测试通过，覆盖非流式成功、401/400/429/500 映射、SSE 正常/断流/超时、主备降级、Embedding 分批与 index 排序、SSE 自检接口、参数校验。[实测·沙箱]
- `mvn -DskipTests compile`：编译通过。[实测·沙箱]
- 未运行 `TimelineEntryIntegrationTest`：沙箱没有 Docker，Testcontainers 起不了 PostgreSQL。[实测·沙箱]
- 未调用真实 DeepSeek / Qwen API：没有真实 key，全部用 WireMock。[实测·沙箱]

### 10.2 实现偏差

1. **HTTP 客户端从 `RestClient` 改为 JDK `HttpClient`**：`RestClient.exchange` 的回调返回后响应会关闭，不能把 body 留给 `ChatStream` 后继续读；流式改用 `BodyHandlers.ofPublisher()` + `Flow.Subscriber`。[代码]
2. **取消不再依赖关闭 `InputStream`**：单测用 `PipedInputStream` 复现了“关闭流不会唤醒阻塞 read”，改为有界队列 + 带超时 `poll` + `Subscription.cancel()`。[实测·单测]
3. **SSE 响应显式设 UTF-8**：`SseEmitter` 默认不带 charset，MockMvc 按 ISO-8859-1 解码会乱码；Controller 设置 `text/event-stream;charset=UTF-8` 和 `response.setCharacterEncoding("UTF-8")`。[实测·单测]
4. **`ChatSseService` 的测试构造器**：保留一个包级构造器用于短心跳/短超时测试；Spring 注入用 `@Autowired` 标注公共构造器。[代码]

### 10.3 仍需你本机验证

- `mvn test`（含 `TimelineEntryIntegrationTest`，需要 Docker）。
- 配好 `DEEPSEEK_API_KEY` / `DASHSCOPE_API_KEY` 后的真实调用。
- `curl -N` 的 SSE 实时性、Ctrl+C 取消、代理下的心跳。
- 真实停库时的 500/503 与 `?page=abc` 仍是模块二遗留，不在本模块修复。

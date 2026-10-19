# 模块三：AI 接入 —— 学习大纲

> 这是结构草案，不是学习记录。每节的标题、篇幅、写不写，都由你边做边定。
> 用法：在新对话里按节推进，一次只聊一节；每节先自己答问题，再对照代码。

## 使用方式

每节三个部分：

- **先回答**：不看代码，写下你的直觉答案（写错也没关系，这是对照用的）
- **再看**：对应代码位置，验证或推翻你的答案
- **再做**：一个能跑的小实验或一个能讲清的自测题

---

## 第 0 节：先答 5 个问题（开篇自测）

1. 为什么项目明确不用 Spring AI / LangChain4j？如果要用，你会失去什么、得到什么？
2. “业务层不能看到供应商细节”，具体指哪些东西不能出现？
3. 流式响应和普通 HTTP 响应，最大的结构差异是什么？
4. 模型接口失败时，哪些错误值得重试，哪些重试只会更糟？
5. 客户端中途断开了，服务端要怎么知道，又该做什么？

---

## 第 1 节：接口怎么切

**先回答**：如果明天要接第二家 chat 供应商，你希望改哪些文件？改动越少说明接口切得越对。

**再看**

- `ai/ChatClient.java`、`ai/ChatStream.java`、`ai/EmbeddingClient.java`
- `ai/ChatRequest.java`、`ai/ChatResponse.java`、`ai/ChatChunk.java`
- `ai/config/AiClientConfig.java`（装配点）

**要讲清的取舍**

- 为什么 `ChatRequest` 里没有 `model` 和 `thinking`？
- 为什么流式用迭代器，而不是 `Flux` 或回调 `Listener`？
- 为什么 `ChatResponse` 不返回原始 JSON、provider、requestId？
- `ChatStream` 为什么继承 `AutoCloseable`？

**再做**：画出“业务层 → 接口 → 实现”的依赖图，标出哪一层允许 import `ai.deepseek` / `ai.qwen`。

---

## 第 2 节：OpenAI 兼容协议（非流式）

**先回答**：一次 chat 请求最少需要哪些字段？响应里的 `finish_reason` 有哪几种值，分别意味着什么？

**再看**

- `ai/deepseek/DeepSeekWire.java`（线格式）
- `ai/deepseek/DeepSeekChatClient.java`（组装请求、解析响应）
- `ai/support/JsonHttpClient.java`（HTTP 层）

**要讲清**

- 为什么单独建 `Wire` 类，而不是直接复用业务 DTO？
- `tools.jackson`（Jackson 3）和 `com.fasterxml.jackson`（Jackson 2）在 SB4 里的关系。
- `usage` 的三个字段分别是什么，为什么缓存命中会影响成本。

**再做**：用 `curl` 打一次真实 `/api/ai/chat`，把请求体和响应体各抄一份进笔记。

---

## 第 3 节：错误分类、重试与降级

**先回答**：429、500、401、400 各应该怎么处理？为什么 401 重试没有意义？

**再看**

- `ai/AiErrorType.java`（`retryable` 标记）
- `ai/support/OpenAiErrorParser.java`（状态码 → 类型）
- `ai/support/RetryPolicy.java`（退避 + 抖动 + `Retry-After`）
- `ai/support/FailoverChatClient.java`（主备）

**要讲清**

- 指数退避为什么必须加抖动？
- 为什么“主用 2 次 + 备用 1 次”，而不是各 3 次？
- 同供应商主备能覆盖什么故障，不能覆盖什么？
- 为什么“建立流之后”绝不重试？

**再做**：手算一次 3 次失败的重试时间线；再想一个“重试风暴”的具体场景。

---

## 第 4 节：SSE 线协议与流式解析

**先回答**：`data: [DONE]` 是什么？`: keep-alive` 又是什么？两者对客户端的意义有何不同？

**再看**

- `ai/support/PublisherSseEventStream.java`（字节 → 行 → 事件）
- `ai/deepseek/DeepSeekSseParser.java`（事件 → `ChatChunk`）
- `ai/deepseek/JdkChatStream.java`（`ChatChunk` → 迭代器）

**要讲清**

- 为什么“增量”不能当完整 JSON 用？`finish_reason` 和 `usage` 一般出现在哪一块？
- 多字节 UTF-8 字符被拆到两个 buffer 会怎样，代码怎么处理？
- 为什么注释行可以忽略，但不能忽略“它到了”这件事？

**再做**：拿真实 `curl -N` 输出，手工标出每行的类型（注释 / data / 空行 / DONE）。

---

## 第 5 节：取消、超时与背压（本模块最硬的一节）

**先回答**：一条流有几种“结束方式”？每种方式下，服务端和客户端各应该观察到什么？

**再看**

- `ai/support/StreamWatchdog.java`（first-token / idle / total 三个 deadline）
- `ai/support/SseEventStream.java`（`lastActivityNanos`）
- `ai/deepseek/JdkChatStream.java`（`cancelled` / `sawDone` / `finished` 三个状态）
- `ai/sse/ChatSseService.java`（`SseSender` 的锁内取消检查）

**要讲清**

- 三种超时分别防什么？为什么 idle 必须“首包之后”才生效？
- 关闭 `InputStream` 为什么不能可靠唤醒阻塞读？改成队列 + `poll` 后换来了什么？
- 取消的窗口在哪？为什么“锁内再查一次”能堵住它？
- 背压：有界队列 + 带超时 `offer` 解决了什么问题？

**再做**：画出 `JdkChatStream` 的状态机（运行 / 正常结束 / 失败 / 取消 / 超时），标出每个状态由谁置位。

---

## 第 6 节：Spring MVC 异步与 SSE 输出

**先回答**：`SseEmitter` 是什么？为什么它返回之后，`@RestControllerAdvice` 就管不到错误了？

**再看**

- `controller/AiDiagnosticController.java`
- `ai/sse/ChatSseService.java`（`onCompletion` / `onTimeout` / `onError`）
- `ai/sse/SseEvents.java`（事件载荷）

**要讲清**

- 为什么同步校验要放在返回 emitter 之前？
- 为什么 SSE 一旦开始，HTTP 状态码固定 200，错误只能走 `event:error`？
- 心跳的作用是什么？为什么用注释行而不是业务事件？
- 为什么前端不能用 `EventSource` 调这个接口？

**再做**：用 `curl -N` 观察一次完整流，再按 `Ctrl+C`，看服务端日志里出现什么。

---

## 第 7 节：Embedding 与向量

**先回答**：1024 维向量意味着什么？为什么换维度会很麻烦？

**再看**

- `ai/qwen/QwenEmbeddingClient.java`（分批、排序、维度校验）
- `ai/qwen/QwenWire.java`
- `ai/config/AiProperties.java`（`dimensions`、`batchSize`）

**要讲清**

- 为什么响应要按 `index` 重排，不能按返回顺序直接用？
- 为什么单请求最多 10 条，代码怎么处理超过 10 条？
- 维度为什么要在建表前定死？和 pgvector 索引有什么关系？
- `float[]` 和后续要写入的 `vector(1024)` 怎么对上？

**再做**：用真实 key 调一次 `/api/ai/embedding`，记录返回条数、维度、耗时。

---

## 第 8 节：配置、密钥与可观测

**先回答**：API key 应该以什么方式进入应用？缺 key 时应用应该启动失败还是启动成功？

**再看**

- `application.yml` 的 `recordandgrow.ai.*`
- `ai/config/AiProperties.java`
- `ai/AiCallObserver.java` + `ai/NoopAiCallObserver.java`
- `.gitignore`

**要讲清**

- 为什么缺 key 只 WARN、不阻止启动？
- 为什么默认 `thinking=disabled`？打开后流式会变成什么样？
- observer 现在记什么、不记什么？以后接 Langfuse 时要加什么？
- 日志里哪些内容绝不能出现？

**再做**：列出你认为“一次 AI 调用应该记录的 6 个字段”，和 observer 的接口对照。

---

## 第 9 节：测试策略

**先回答**：怎么在不花钱、不联网的情况下测“流式中途断开”？

**再看**

- `ai/deepseek/DeepSeekChatClientTest.java`（WireMock）
- `ai/deepseek/JdkChatStreamTest.java`（时间相关）
- `ai/support/FakeSseEventStream.java`
- `ai/sse/ChatSseServiceTest.java`（`RecordingEmitter`）

**要讲清**

- WireMock 的 scenario 怎么模拟“先 429 后 200”？
- 为什么流式测试不用真实 socket？
- 时间相关测试怎么写才不 flaky？（阈值余量、确定性 fake）
- 哪些测试只能在本机跑？（Testcontainers、真实 API）

**再做**：自己补一个失败用例（比如 `finish_reason=content_filter`），看它落在哪个断言上。

---

## 第 10 节：三个真实 bug 复盘（最有价值的一节）

这三个都是单测没覆盖、代码评审才抓到的，适合写“我踩过的坑”。

### 10.1 取消后消费线程空转

- 现象：客户端断开后，虚拟线程每 200ms 空转，永不退出。
- 根因：`close()` 只清了资源，没让 `hasNext()` 知道“已取消”；`completed` 在 `closed` 后永远为 false。
- 修法：独立 `cancelled` 标志 + 循环内/`poll` 后都检查。
- 要写进笔记的：**“关闭资源”和“通知消费者”是两件事**。

### 10.2 idle 超时把正常流杀掉

- 现象：任何超过 30s 的流都会被中断，即使 token 一直在出。
- 根因：`activity()` 定义了但从没被调用；注释行被丢弃，keep-alive 不能续命。
- 修法：事件源记录 `lastActivityNanos`，每行都更新；idle 只在首包后生效。
- 要写进笔记的：**超时策略要区分“还没开始”和“正在传输”**。

### 10.3 没有 `[DONE]` 的 EOF 被当成成功

- 现象：代理提前关连接，客户端收到 `done`，内容却是截断的。
- 根因：只要 `source.completed()` 就 `finishNormally()`。
- 修法：`sawDone` 为真才算正常结束，否则 `STREAM_INTERRUPTED`。
- 要写进笔记的：**“连接关了”不等于“传输完整了”**。

---

## 第 11 节：能讲给面试官的部分

- 手搓 LLM 接入的取舍：为什么不用框架，抽象边界怎么划。
- 流式与 SSE：线协议、增量组装、心跳、取消、超时分层。
- 容错设计：错误分类、退避抖动、主备降级、重试边界。
- 工程细节：虚拟线程、有界队列背压、UTF-8、密钥管理、可观测 hook。
- 真实缺陷复盘（第 10 节）——这类内容比“我用了什么技术”更有说服力。

---

## 第 12 节：还没验证、别写成结论的清单

- `deepseek-flash` / `deepseek-v4-pro` 的真实可用性与 `thinking=disabled` 行为
- 真实流式最后一块是否带 `usage`
- Qwen 旧域名 `dashscope.aliyuncs.com` 当前是否仍可用
- 代理/网关下 `X-Accel-Buffering: no` 是否生效
- 真实停库时 500/503、`?page=abc` 的 500（模块二遗留）
- Testcontainers 集成测试在本机是否全绿

> 这些在笔记里写“待验证”，不要写“已验证”。

---

## 建议的写作顺序

1 → 2 → 4 → 5 → 6 → 3 → 7 → 8 → 9 → 10 → 11

理由：先建立“请求怎么发、流怎么读”，再讲“出错怎么办”；第 10 节放在最后，因为需要前面所有概念做支撑。

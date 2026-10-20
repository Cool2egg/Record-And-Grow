# 模块四验证结果（2026-10-19）

## 1. 环境

- 沙箱：Ubuntu 22.04、Temurin JDK 21.0.12.1、Maven 3.9.9（本次在会话内临时安装到 `/tmp/tools`）。
- 沙箱无 Docker：`TimelineEntryIntegrationTest` 未在沙箱运行；本机 Windows 结果见 2.1（15/0）。
- 沙箱无真实 API key：所有模型调用由 fake `ChatClient` 模拟。
- 用户本机：IDEA + Docker + 真实 DeepSeek key，负责集成与真实调用验证。

## 2. 编译与测试

命令：

```bash
mvn -DskipTests compile
mvn -Dtest='!*IntegrationTest' test
```

结果：

```text
compile: BUILD SUCCESS
tests:   Tests run: 120, Failures: 0, Errors: 0, Skipped: 0
```

其中 62 个是模块三既有测试，**未修改任何既有测试**；新增 58 个测试覆盖：

- `TimeWindowParserTest`（21）：今天/昨天/前天/明天、本周/上周/上周三/下周、本月/上月/下月、最近 N 天/周/月、绝对日期（`10月17日`、`2026-10-17`、`2026/10/17`、`2026年10月17日`）、数字与年月日之间的空格、全角数字、当前月「10月」→ 本月至今、非当前月「10月」→ 最近一个已开始的 10 月、去年/今年/明年、去年10月、`10/17` 不支持 → 默认窗口、无时间词默认 30 天、指代回落、超过 365 天夹紧、只取第一个时间表达；**重复时间词**（`大前天`、`上上周三`、`上上个周`、`上上个月` 正确解析；`前前天`、`上上周末`、`上上上周三` 回落默认窗口）；**月初/年初 00:00 的零长度窗口**自动补 1 秒。
- `KeywordExtractorTest`（8）：引号短语、中文引号、ASCII 词、停用词、中文自由文本返回空、大小写去重、**顺序无关的 token 级去重（`Go` 与 `Google` 双向）**、数量上限、引号短语不拆子词。
- `ContextAssemblerTest`（8）：编号与 sources 一一对应、单条截断与 excerpt、**先截 300 再丢整条**、多日丢最旧、**单日 50 条等距抽样保留首尾**、**`max-records-chars` 独立封顶**、无命中 `（无）`、`limitTruncated` 与 `budgetTruncated` 分开。
- `TimelineRetrieverTest`（4）：无关键词单次查询、单次上限截断、关键词 0 命中回退、关键词命中不回退。
- `TimelineEntryServiceSearchTest`（7，Mockito，不连库）：`~*` 词边界、ILIKE 通配符、`C++` 退回 ILIKE、多词 AND、`%`/`_` 转义、无关键词只保留 overlap、limit 夹紧到 100。
- `ChatControllerTest`（7）：无 key（非流式）→ 503 且不碰数据库、**无 key（流式）→ 503 且不启动 SSE**、DB 故障 → 503 且不启动 SSE、无命中 `sources.items=[]`、有命中 sources 与 Prompt ref 对应、空问题 400、默认 maxTokens/temperature 来自配置。
- `ChatSseServiceTest` 新增 1 个：`start → sources → delta → done` 顺序（原有 6 个 + 新增 1 个 = 7 个，全部通过）。
- `GlobalExceptionHandlerTest`（7）：原 5 个 + 新增 2 个——MyBatis 连接类异常 → 503/50300，SQL 语法/约束类异常仍 → 500。`ContextAssemblerTest` 同时补了 sources 必须输出 `+08:00` 的断言。

稳定性说明：中途曾有 **1 次未复现的失败**；随后连续 3 次全量（当时 117 个）+ `ChatSseServiceTest` 单类 3 次均全绿，未再出现。

### 2.1 本机集成测试结果

`TimelineEntryIntegrationTest` 已在用户本机运行（2026-10-19 21:04，Windows 11 / `D:\RecordAndGrow`）：

```text
tests="15" errors="0" skipped="0" failures="0"
```

覆盖原有 8 个 + 新增 7 个 `search` 用例。报告曾位于
`target/surefire-reports/TEST-com.recordandgrow.timeline.TimelineEntryIntegrationTest.xml`；
沙箱随后执行 `mvn test` 时因无 Docker 覆盖了这份报告（Linux / `Could not find a valid Docker environment`），
集成结果以本机这次运行为准。

## 3. 本机验证

### 3.1 数据库集成测试（已通过）

状态：**已通过**（本机 15/0）。以下命令保留，供换机或回归时复跑。

```bash
docker compose up -d postgres
mvn test
```

重点看 `TimelineEntryIntegrationTest` 新增的 7 个 `search` 用例：

- 区间重叠 + 软删排除；
- 中文 `ILIKE` 子串；
- ASCII 词边界（`Go` 不命中 `Google`，`Java` 不命中 `JavaScript`）；
- 以符号结尾的 ASCII 词（`C++`）退回 `ILIKE`；
- 多关键词 AND；
- `%` / `_` 转义；
- limit 与 total。

如果词边界用例失败，先检查 PostgreSQL 版本对 `\m` / `\M` 的支持（PG 9.x+ ARE 支持）。

### 3.2 无 key

```bash
# 不设置 DEEPSEEK_API_KEY 启动
curl -i -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"我昨天做了什么？"}'
```

期望：`HTTP 503`，`{"code":50301}`；日志有 `NOT_CONFIGURED`；**不出现** `event:start`，也不应看到时间线查询 SQL。

实测（2026-10-19，18080 无 key 实例）：`/api/chat` 与 `/api/chat/stream` 均返回 `HTTP 503` + `{"code":50301}`；流式响应没有 `event:start`；日志有 `DEEPSEEK_API_KEY 未配置`，没有时间线查询 SQL。**通过。**

### 3.3 有 key + 有数据的流式链路

```bash
curl -s -X POST http://localhost:8080/api/timeline \
  -H 'Content-Type: application/json' \
  -d '{"content":"学习 Spring Boot 的 SseEmitter 与取消语义","startTime":"2026-10-17T20:00:00+08:00","endTime":"2026-10-17T21:30:00+08:00"}'

curl -s -X POST http://localhost:8080/api/timeline \
  -H 'Content-Type: application/json' \
  -d '{"content":"跑步 5km","startTime":"2026-10-18T07:00:00+08:00"}'

curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"我上周做了什么？"}'
```

检查：

- 事件顺序 `start → sources → delta... → done`；
- `sources.items[].ref` 为 `T1..Tn`，`retrieval.included == items.length`；
- 回答中的 `[Tn]` 都能在 sources 找到；
- 用 `jq` 或数据库核对 source 的 `id / startTime / excerpt`。

实测（2026-10-19）：`start → sources(3) → delta* → done`；`finishReason=STOP`；`matched=3 / retrieved=3 / included=3`，无截断；回答引用 `T1/T2/T3` 完整，事实与推断分开表述。sources 时间已统一为 `+08:00`（T2 不再出现 `Z` 导致的 10/17 23:00 显示）。**通过。**

### 3.4 无命中

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"2020 年 3 月我做了什么？"}'
```

期望：`sources.items=[]`；模型回答“没有找到相关记录”；不编造。

实测（2026-10-19）：`items=0`、`matched=0 / retrieved=0 / included=0`；窗口 `2020-03-01 ~ 2020-04-01`、`timeBasis=ABSOLUTE_MONTH`；回答明确说没有记录并建议扩大范围，未编造事件；`done` 存在、无 `error`。**通过。**

### 3.5 DB 故障

```bash
docker compose stop postgres
curl -i -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"question":"我昨天做了什么？"}'
docker compose start postgres
```

期望：`HTTP 503`，`{"code":50300}`；SSE 版本不启动（没有 `event:start`）。

实测（2026-10-19）：修复前实际是 `HTTP 500` + `{"code":50000}`，根因是 `MyBatisSystemException` 没有被 `GlobalExceptionHandler` 覆盖。补了 `MyBatisSystemException` / `PersistenceException` 的连接类根因映射后，非流式与流式均返回 `HTTP 503` + `{"code":50300}`，流式没有 `event:start`。**通过（修复后复测）。**

### 3.6 客户端取消

```bash
timeout 3 curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"question":"总结我最近一个月"}'
```

期望：3 秒内服务端日志出现取消，上游连接关闭；sources 已经发出，客户端保留即可。

实测（2026-10-19 21:45:53）：
`SSE cancelled: requestId=5890a3de-b382-46d0-9396-12b05a229bc6, deltaSent=true`；之后没有新的 `delta`，也没有 `done` / `error`。**通过。**

### 3.7 上游断流（已发 delta）

用 WireMock 或临时把 base-url 指向一个发送部分内容后关闭的 mock：

期望：客户端收到部分 delta + `event:error{partial=true}`，没有重复内容。

说明：这条沿用模块三 `ChatSseServiceTest` 的 `partial=true` 覆盖；模块四没有改 SSE 转发/错误路径，未在本机重复真断流。记为「模块三已验证，本模块未重复」。

## 4. 与模块三行为的差异（有意为之）

| 接口 | 无 key 时 |
|---|---|
| `/api/ai/chat/stream`（自检） | HTTP 200 + `start` + `event:error(NOT_CONFIGURED)`（模块三原行为） |
| `/api/chat/stream`（业务） | **HTTP 503 + `{"code":50301}`，不建立 SSE** |
| `/api/chat`（业务，非流式） | HTTP 503 + `{"code":50301}` |

原因：业务接口在检索前预检，避免无意义地查库和建立流；自检接口保持原样，避免改模块三测试。

## 5. 已知限制

- 单轮无状态：`history` 不接受，「那第二天呢」会回落默认窗口，属于模块十范围。
- 关键词只抽引号短语和 ASCII 词；中文自由文本不做分词，检索退化为时间窗。
- 单次最多取回 100 条；更多时 `retrieval.limitTruncated=true`。
- 预检只能判断 key 是否为空，不能判断 key 是否有效；无效 key 仍会在流内报 `AUTHENTICATION`。
- `MyBatisSystemException` / `PersistenceException` 的**连接类**根因已映射为 50300；SQL 语法、约束冲突等非连接异常仍落到 500（避免把所有 MyBatis 异常都误判成数据库不可用）。

## 6. 复核修复（2026-10-19 第二轮）

- **重复时间词**：新增 `大前天/大后天`、`上上周/上上个周/上上周三`、`上上个月` 的显式规则；
  `前前天`、`上上周末`、`上上上周三` 通过负向断言回落默认窗口，不再静默取相邻区间。
- **关键词去重**：由 `substring` 改为 token 级比较，`Go` / `Google` 的抽取结果与出现顺序无关。
- **预算口径**：新增 `recordandgrow.chat.max-records-chars=6000`，记录区取
  `min(总预算 − 固定开销, 6000)`；文档同步为「总 prompt ≤ 8000」。
- **零点窗口**：`本月/今年/最近 1 天` 在 00:00:00 的 `start == end` 时，`end` 取 `start + 1s`，不再抛异常。
- **流式无 key**：`ChatControllerTest` 补 `/api/chat/stream` 用例（503、无 `event:start`、不查库）。
- **文档一致性**：`@Size(max=2000)` 改为「Service 按配置校验」；日志补 `dropped` 与 `time_parse_failed`（DEBUG）；`overview.md` 进度更新到模块 4。
- **停库 503**：`GlobalExceptionHandler` 新增 `MyBatisSystemException` / `PersistenceException` 处理器，按根因识别连接类异常 → 50300；非连接类仍 500。补 2 个单测。
- **取消可观测**：`ChatSseService.cancel` 增加 `SSE cancelled` 日志（仅非正常完成时打），供 3.6 验证。
- **来源时区**：`ContextAssembler.sources()` 把 `startTime/endTime` 转成聊天时区 `+08:00`，与 Prompt/窗口一致；`ContextAssemblerTest` 加断言。

本轮测试数：**120**（62 个模块三既有测试未改 + 58 个新增）；另加本机集成测试 15 个，全部通过。

> 说明：3.1–3.6 已在本机通过；3.7 沿用模块三单测，本模块未重复真断流。
> SQL 的占位符、转义与 AND 已有 Java 侧单测；`~* '\m...\M'` 与 LIKE 在 PostgreSQL 里的实际语义
> 已由本机集成测试覆盖。

## 7. 相关文档

- 设计说明：`docs/design/module-04-closed-loop.md`
- 模块三验证：`docs/verification/03-ai.md`
- 已知问题：`docs/known-issues.md`

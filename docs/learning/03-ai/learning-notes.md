# 模块一学习记录：项目骨架

> 写在前面：仅做个人记录，不代表本模块内容

## 1. 简单概要

## 2. 模块学习收获
-1 接口怎么切
    主要是为了使换供应商时，业务层不用作改动
    具体实现：只在AiClientConfig里引用各厂商的包，在这里面，构造实体chatclient和embeddingclient，并靠bean注入容器。
    

-2 deepseek返回的是线格式，Jackson负责将其转换成DeepSeekWire对象。

-3  兜底换模型，机制是把chatclient用delegate包装，然后把delegate集群放进failoverchatclient， 
    在里面实现了单种chatclient超过retryable次数后换chatclient，直到遍历完所有delegate为止的逻辑。
    Failoverchatclient依赖：
    AiErrorType：enum，里面有各个报错状态码的retryable布尔值，如果为true会重试，否则会直接抛出异常
    OpenAiErrorParser： 把 HTTP 状态码翻译成 AiErrorType
    RetryPolicy：一些重试策略和逻辑
    AiException:里面会有AiErrorType 

-4 SSE（Server-Sent Events）：是 HTTP 响应体上的一种格式：服务器针对一次请求，在响应体里持续写入“data: 行 + 空行”分隔的消息块，
    两次空行之间的data一起算作一次事件，一次增量，一次增量就是一次显示。直到 [DONE] 或连接结束，是响应体的一种协议。
    后端->客户端：controller调用，service主要用了spring的SseEmitter，先同步建立emitter、建立心跳守护线程、三种结束同一个回调
    ，之后直接返回emitter，虚拟线程里跑剩下的流式逻辑，因为剩下逻辑里会有等网络，等IO等阻塞，虚拟线程阻塞自动挂起脱离载体线程，能节省OS线程。

-5 向量：本节做了服务请求分批，返回结果按index放回数组归序（不归序会导致检索不准确），还有维度校验（防止向量模型与数据库维度不符）
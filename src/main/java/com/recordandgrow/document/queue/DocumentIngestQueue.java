package com.recordandgrow.document.queue;

/**
 * 入库队列抽象。
 *
 * <p>生产用 RocketMQ；单测/集成测试/无 broker 的本机调试用 local 直调。
 * 业务层只依赖这个接口，测试因此完全不需要启动 broker。
 */
public interface DocumentIngestQueue {

    void sendParse(Long documentId);

    void sendEmbed(Long documentId);
}

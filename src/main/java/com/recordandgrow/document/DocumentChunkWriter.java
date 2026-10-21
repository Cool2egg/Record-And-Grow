package com.recordandgrow.document;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 单独一个 Bean，专门负责「一批向量的写入」。
 *
 * <p>必须是独立 Bean：{@code @Transactional} 靠 AOP 代理生效，如果在
 * {@code DocumentIngestService.ingestEmbed()} 里自调用，事务注解会被静默忽略，
 * 每批独立事务就退化成同一个事务（local 模式下甚至会并进上传事务），
 * 断点续跑的前提就没了。
 *
 * <p>REQUIRES_NEW：即使调用方将来套了事务，这一批也独立提交。
 */
@Component
public class DocumentChunkWriter {

    private final DocumentChunkMapper chunkMapper;

    public DocumentChunkWriter(DocumentChunkMapper chunkMapper) {
        this.chunkMapper = chunkMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writeBatch(List<DocumentChunk> chunks, List<float[]> vectors, String model) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException(
                    "向量条数与分块不一致: chunks=" + chunks.size() + ", vectors=" + vectors.size());
        }
        for (int i = 0; i < chunks.size(); i++) {
            // 带 embedding IS NULL 条件，重复消费时后到的一次不会覆盖
            chunkMapper.updateEmbedding(chunks.get(i).getId(),
                    VectorLiterals.toLiteral(vectors.get(i)), model);
        }
    }
}

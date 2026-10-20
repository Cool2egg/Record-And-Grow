package com.recordandgrow.document.chunk;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;

/**
 * jtokkit + cl100k_base 的 token 计数。
 *
 * <p>与 Qwen 的 tokenizer 不一致：中文大约会多算 1.5–2 倍。偏差方向是「块更小」，
 * 对 8192 的输入上限是安全的；要的是块大小稳定，不是和供应商精确一致。
 * 这个偏差必须写进验证文档，别当成精确值。
 */
public final class JtokkitTokenCounter implements TokenCounter {

    private static final EncodingRegistry REGISTRY = Encodings.newDefaultEncodingRegistry();
    private static final Encoding ENCODING = REGISTRY.getEncoding(EncodingType.CL100K_BASE);

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return ENCODING.countTokens(text);
    }

    @Override
    public String name() {
        return "jtokkit/cl100k_base";
    }
}

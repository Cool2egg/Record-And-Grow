package com.recordandgrow.document.storage;

import java.io.InputStream;

/**
 * 原文存储。
 *
 * <p>只有本地文件一种实现；接口留着是为了以后换对象存储时，业务层不用动。
 * 路径会落库但永不出接口。
 */
public interface DocumentStorage {

    /**
     * 保存原文。同一份内容（sha256 相同）重复保存时直接复用已有文件。
     *
     * @param input     原文流
     * @param sha256    内容哈希，作为文件名
     * @param extension 小写扩展名，不带点
     */
    StoredFile store(InputStream input, String sha256, String extension);

    /** 删除原文；文件已不存在时安静返回。 */
    void delete(String storedPath);

    /**
     * @param path 落库路径
     * @param size 字节数
     */
    record StoredFile(String path, long size) {
    }
}

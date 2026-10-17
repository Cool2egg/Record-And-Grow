package com.recordandgrow.infra.response;

import java.util.List;

/**
 * 通用分页结果。后续文档、记忆等模块的分页查询复用。
 *
 * @param <T> 列表元素类型
 */
public class PageResult<T> {

    private List<T> items;
    private long total;
    private long page;
    private long size;

    public PageResult() {
    }

    private PageResult(List<T> items, long total, long page, long size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public static <T> PageResult<T> of(List<T> items, long total, long page, long size) {
        return new PageResult<>(items, total, page, size);
    }

    public List<T> getItems() {
        return items;
    }

    public void setItems(List<T> items) {
        this.items = items;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }
}

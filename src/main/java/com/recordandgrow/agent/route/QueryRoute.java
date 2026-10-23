package com.recordandgrow.agent.route;

/**
 * 一次提问的路由结果：这条问题该查哪些知识源。
 *
 * <p>四个叶子是**互斥**的，不做多标签：单用户场景下"既要时间线又要文档"就是 {@link #BOTH}，
 * 不需要 ragent 那种一次给多个意图打分再配额的机制。
 *
 * <p>枚举顺序即兜底顺序：解析失败一律回落到 {@link #BOTH}（宁多查、不漏查）。
 */
public enum QueryRoute {

    /** 只查时间线：问用户自己的经历、做过什么。 */
    TIMELINE(true, false),

    /** 只查文档：问资料里的知识、方法、条款。 */
    DOCUMENT(false, true),

    /** 两路都查，也是关闭路由与解析失败时的默认值。 */
    BOTH(true, true),

    /** 都不查：寒暄、越界提问，直接回答。 */
    DIRECT(false, false);

    private final boolean timeline;
    private final boolean documents;

    QueryRoute(boolean timeline, boolean documents) {
        this.timeline = timeline;
        this.documents = documents;
    }

    public boolean retrievesTimeline() {
        return timeline;
    }

    public boolean retrievesDocuments() {
        return documents;
    }

    /** 宽松解析：忽略大小写与空白；不认识返回 {@code null}，由调用方决定兜底。 */
    public static QueryRoute parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toUpperCase();
        for (QueryRoute route : values()) {
            if (route.name().equals(normalized)) {
                return route;
            }
        }
        return null;
    }
}

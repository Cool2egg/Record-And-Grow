package com.recordandgrow.agent.route;

/**
 * 路由决策：选中的叶子 + 是否走了兜底。
 *
 * <p>{@code fallback=true} 表示"没路由成功，按 {@link QueryRoute#BOTH} 查"，
 * 与"模型明确判为 BOTH"是两件事，日志与评测必须能分开——否则路由的失败率会被算进 BOTH 的占比里。
 *
 * @param route   实际执行的路由
 * @param fallback 是否兜底
 * @param reason   兜底原因（{@code disabled} / {@code not-configured} / {@code timeout} /
 *                 {@code error} / {@code bad-json} / {@code unknown-route} …），非兜底为 {@code null}
 */
public record RouteDecision(QueryRoute route, boolean fallback, String reason) {

    public RouteDecision {
        if (route == null) {
            throw new IllegalArgumentException("route 不能为空");
        }
    }

    public static RouteDecision of(QueryRoute route) {
        return new RouteDecision(route, false, null);
    }

    public static RouteDecision fallback(String reason) {
        return new RouteDecision(QueryRoute.BOTH, true, reason);
    }

    /** 关闭路由时的决策：查全部，但**不算兜底**（这是配置选择，不是失败）。 */
    public static RouteDecision bothByConfig() {
        return new RouteDecision(QueryRoute.BOTH, false, "disabled");
    }
}

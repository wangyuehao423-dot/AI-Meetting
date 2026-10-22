package com.example.interview.infrastructure.singleflight;

// ============================================================
// CostQuotaExceededException —— 成本配额耗尽异常
// ============================================================
// 由 CallCostTallyService.tryAcquire 判定"近窗 AI 调用次数已到上限"时抛出，
// 让 Singleflight 在配额清零后不再放行新的生产者（真正花钱的 AI 调用），
// 只能回放窗口内已缓存的结果或交给上层降级。
//
// 为什么它会被 FailureClassifier 归为不可重试：
//   它既不是 SocketTimeoutException / TimeoutException / IOException，
//   message 里也没有 "HTTP 5" / "status=5"，classify 沿 cause 链扫不到任何
//   可重试特征，默认返回 NON_RETRYABLE —— 于是不会把它交给超时接管服务去
//   白费一遍重试（配额没恢复前重试多少次都照样超限）。
// ============================================================
public class CostQuotaExceededException extends RuntimeException {

    /**
     * 构造配额耗尽异常。
     *
     * <p>message 带上业务 key，排障时能直接看出是哪一条链路、哪个单飞键
     * 触发了配额上限，而不是千篇一律的 "quota exceeded"。
     *
     * @param key 被限流的业务单飞 key（如 question:sessionId:sequence）
     */
    public CostQuotaExceededException(String key) {
        super("cost quota exceeded, singleflight key=" + key);
    }
}
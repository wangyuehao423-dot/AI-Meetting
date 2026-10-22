// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

// ====================================================================
// FailureClassifier - 失败分类器
// 把异常分成"可重试 / 不可重试"两类，供 Singleflight 决定下一步：
// 可重试的登记给 TimeoutTakeoverService，等 TTL 过期后接管重跑；
// 不可重试的（JSON 解析失败、业务校验不过）直接抛出，重试也没意义。
// 判断时沿 cause 链一路往下找，因为网络异常常被上层包装成自定义异常。
// ====================================================================
@Component
public class FailureClassifier {

    // 失败类型：只有 RETRYABLE 才会触发超时接管重试。
    public enum FailureType {
        // 暂时性故障（网络超时、5xx 等），换一次执行就可能成功
        RETRYABLE,
        // 确定性失败（JSON 解析失败、业务校验失败等），重试多少次都一样
        NON_RETRYABLE
    }

    /**
     * 判断一次失败是否值得重试。
     *
     * <p>为什么要遍历整条 cause 链：AI 调用失败常被上层包装成自定义异常，
     * 真正的 SocketTimeoutException 藏在 cause 里；只看最外层会把它误判成
     * 不可重试，白白丢掉一次补救机会。
     *
     * @param error 生产者抛出的异常
     * @return RETRYABLE 表示可以交给超时接管重试，否则为 NON_RETRYABLE
     */
    public FailureType classify(Throwable error) {
        // 从最外层异常开始，逐层往 cause 里面找。
        Throwable current = error;
        while (current != null) {
            // 网络超时 / IO 异常都属于"等一下再试就好"的暂时性故障。
            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof java.io.IOException) {
                return FailureType.RETRYABLE;
            }
            // 429 限流：上游明确告知"请求太频繁"，等退避时间过去再试大概率成功，
            // 必须归为可重试。限流报文常形如 "429 Too Many Requests" /
            // "Upstream rate limit exceeded"，也可能以 HttpStatusCodeException 形式抛出。
            if (current instanceof org.springframework.web.client.HttpStatusCodeException httpEx
                    && httpEx.getStatusCode().value() == 429) {
                return FailureType.RETRYABLE;
            }
            // 有些 HTTP 客户端不抛专用异常，只在 message 里写状态码；
            // 5xx 说明是服务端临时故障，同样算可重试。
            String message = current.getMessage();
            if (message != null
                    && (message.contains("HTTP 5")
                    || message.contains("status=5"))) {
                return FailureType.RETRYABLE;
            }
            if (message != null) {
                String lower = message.toLowerCase();
                if (lower.contains("429")
                        || lower.contains("rate limit")
                        || lower.contains("too many requests")) {
                    return FailureType.RETRYABLE;
                }
            }
            // 这一层没命中，继续往下一层 cause 找。
            current = current.getCause();
        }
        // 整条链都找不到可重试特征：按不可重试处理，不再浪费重试资源。
        return FailureType.NON_RETRYABLE;
    }
}
// 新增结束

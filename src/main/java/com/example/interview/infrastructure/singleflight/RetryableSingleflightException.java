// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

/**
 * 可重试的 Singleflight 生产者异常。
 *
 * <p>当生产者抛出此异常时，表示失败是暂时性的（如网络超时），
 * 调用方应将任务注册到 TimeoutTakeoverService 等待重试。
 *
 * <p>为什么单独定义一个异常类型而不是复用 RuntimeException：
 * 等待方需要把"被接管的失败"和"真正的业务失败"区分开——前者应该稍后重查，
 * 后者应该立刻报错；靠一个专用类型判断，比在消息字符串里找关键字可靠。
 */
public class RetryableSingleflightException
        extends RuntimeException {
    /**
     * @param message 说明信息，通常写明"已注册到超时接管"
     * @param cause   原始失败原因，保留堆栈便于排查根因
     */
    public RetryableSingleflightException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
// 新增结束

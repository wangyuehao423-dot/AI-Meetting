package com.example.interview.application;

/**
 * 上游 AI 故障：模型超时、限流耗尽，或者返回的内容不符合约定格式。
 *
 * <p>为什么不直接沿用 IllegalArgumentException：那是"客户端参数错"的语义，
 * 会映射成 400。简历上传时客户端只提交了一个合法 PDF，失败的原因是模型
 * 把技能列表返回成了 ```json 围栏包着的文本——错在服务端依赖，不在调用方。
 * 映射成 400 会让前端以为是自己传错了参数，永远不会重试。</p>
 */
public class AiUpstreamException extends RuntimeException {

    public AiUpstreamException(String message) {
        super(message);
    }

    public AiUpstreamException(String message, Throwable cause) {
        super(message, cause);
    }
}

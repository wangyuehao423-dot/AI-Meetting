// 从这里开始是新增的：资源不存在专用异常。
package com.example.interview.application;

/**
 * 资源（岗位 / 简历 / 报告 / 会话）不存在 → HTTP 404。
 *
 * <p>这类错误之前统一走 IllegalArgumentException，被映射成 400。
 * 400 意味着「请求本身不合法」，但「你要的 id=9999 这个岗位没有」是请求
 * 格式完全正确、只是目标资源不存在——404 才是准确语义，前端据此显示
 * 「内容已删除/不存在」，而不是「参数错误」。</p>
 */
public class NotFoundException extends RuntimeException {
    public NotFoundException(String message) {
        super(message);
    }
}
// 新增结束

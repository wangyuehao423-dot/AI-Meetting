// 从这里开始是新增的：认证失败专用异常。
package com.example.interview.application;

/**
 * 认证失败（凭证不对 / 没带 token / token 里没有有效身份）→ HTTP 401。
 *
 * <p>为什么不复用 IllegalArgumentException：那个在 ApiExceptionHandler 里
 * 被映射成 400，而 400 的语义是「你的请求格式有问题」。登录密码错、没带 token
 * 都不是格式问题，是「你是谁没证明」——必须是 401，前端才会正确地跳登录页，
 * 而不是把用户留在页面上傻等、或者提示「参数错误」。</p>
 */
public class AuthException extends RuntimeException {
    public AuthException(String message) {
        super(message);
    }
}
// 新增结束

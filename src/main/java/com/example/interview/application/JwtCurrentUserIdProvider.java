// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// ============================================================
// 当前登录用户 ID 提供者（基于 JWT）
// ============================================================
// 职责：从当前线程绑定的 HTTP 请求里，取出认证过滤器验签后写入的 userId。
//
// 为什么不让 Controller 直接信前端传的 userId：
//   请求体或查询参数里的用户 ID 是能随便伪造的，谁都能拿别人的 ID 去查报告。
//   所以这里只认 JwtAuthenticationFilter 验签后写进 request 的 "userId" 属性，
//   前端传什么一概不看，从根上堵住越权。
// ============================================================
@Component
public class JwtCurrentUserIdProvider
        implements CurrentUserIdProvider {

    /**
     * 读取当前请求已认证用户的 ID。
     *
     * <p>为什么拿不到请求就直接抛异常：这个方法只在 HTTP 请求线程里被调用，
     * 一旦拿不到 RequestAttributes，说明调用方式有问题（比如在异步线程里调用），
     * 此时绝不能返回默认值，否则就变成"匿名用户能看数据"的越权漏洞。</p>
     *
     * @return JWT 中已通过验签的 userId
     */
    @Override
    public long currentUserId() {
        // 拿当前线程绑定的请求上下文，Spring 用 ThreadLocal 保存它。
        RequestAttributes attributes =
                RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            // 不在 HTTP 请求线程里：直接失败，绝不返回默认用户。
            throw new IllegalStateException(
                    "no HTTP request is bound to current thread"
            );
        }
        HttpServletRequest request =
                servlet.getRequest();
        // 读过滤器验签后写入的属性，而不是请求参数。
        Object value = request.getAttribute("userId");
        // 属性必须是数字：缺失说明 JWT 没验过或过滤器没接线，同样直接失败。
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                    "validated JWT userId is missing"
            );
        }
        return number.longValue();
    }
}
// 新增结束

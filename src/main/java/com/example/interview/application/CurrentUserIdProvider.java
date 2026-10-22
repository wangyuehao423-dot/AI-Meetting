// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

/**
 * 当前登录用户 ID 的提供者。
 *
 * <p>为什么抽成接口而不是直接在 Controller 里读请求属性：报告接口只关心
 * "现在是谁"，不该知道 ID 是 JWT 里的还是别的认证方式带的；抽成接口后
 * 单测可以塞一个假实现，不必造 HTTP 请求。</p>
 */
public interface CurrentUserIdProvider {
    long currentUserId();
}
// 新增结束

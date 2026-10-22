package com.example.interview.application;

import java.util.UUID;

/**
 * 面试报告数据查询接口。
 *
 * 为什么是接口而不是具体类：
 * 遵循依赖倒置原则，业务层依赖抽象。
 * 具体实现可以是 MyBatis-Plus Mapper、JPA Repository 或 JDBC Template。
 */
public interface InterviewReportDataRepository {
    /**
     * 加载面试上下文数据。
     *
     * @param sessionId 面试会话 ID
     * @return 格式化的上下文字符串
     */
    String loadContext(UUID sessionId);
}
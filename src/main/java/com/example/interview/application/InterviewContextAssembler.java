package com.example.interview.application;

import java.util.UUID;

import org.springframework.stereotype.Service;

@Service
public class InterviewContextAssembler {

    private final InterviewReportDataRepository data;

    public InterviewContextAssembler(
            InterviewReportDataRepository data
    ) {
        this.data = data;
    }

    /**
     * 组装面试上下文。
     *
     * @param sessionId 面试会话 ID
     * @return 格式化的上下文字符串，包含所有题目、答案、评分和追问
     */
    public String assemble(UUID sessionId) {
        return data.loadContext(sessionId);
    }
}
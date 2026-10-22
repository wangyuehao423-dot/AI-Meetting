package com.example.interview.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public record InterviewSessionSnapshot(
        UUID sessionId,
        InterviewState state,
        long version,
        Set<String> processedRequestIds
)
{
    public InterviewSessionSnapshot
    {
        processedRequestIds = Set.copyOf(processedRequestIds);
    }
    public boolean hasProcessed(String requestId)
    {
        return processedRequestIds.contains(requestId);
    }
    // 创建下一版本快照，更新状态并记录本次已处理的请求 ID。
    public InterviewSessionSnapshot moveTo(
            InterviewState nextState,
            String requestId
    ) {
        var ids = new HashSet<>(
                processedRequestIds
        );
        ids.add(requestId);

        return new InterviewSessionSnapshot(
                // 面试会话 ID，用来贯穿题目、答案、评分和报告。
                sessionId,
                nextState,
                version + 1,
                ids
        );
    }
}

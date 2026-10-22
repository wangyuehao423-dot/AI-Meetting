package com.example.interview.domain;

/**
 * 面试状态机的触发事件（每次用户操作或系统完成任务，都会触发一个事件）
 * 事件 = 推动状态变化的“按钮”
 */
public enum InterviewEvent {

    /** 开始面试（用户点击开始按钮） */
    START,

    /** 简历已上传（系统确认文件存好了） */
    RESUME_UPLOADED,

    /** 岗位已匹配（算完匹配分了） */
    POSITION_MATCHED,

    /** 题目已就绪（AI 出好题了） */
    QUESTION_READY,

    /** 答案已提交（用户点击提交按钮） */
    ANSWER_SUBMITTED,

    /** 评分已完成（AI 打完了分） */
    JUDGE_DONE,

    /** 追问已完成（AI 想好下一道题了） */
    ASK_COMPLETED,

    /** 答题超时（倒计时结束，强制交卷） */
    TIMEOUT,

    /** 结束面试（用户点击结束按钮） */
    END
}
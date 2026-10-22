package com.example.interview.domain;

/**
 * 面试会话的生命周期状态（状态机驱动）
 */
public enum InterviewState {

    /** 初始（刚创建会话，啥也没干） */
    INIT,

    /** 匹配中（算简历和岗位的匹配分） */
    MATCHING,

    /** 出题中（AI 生成题目，等你开始答） */
    QUESTIONING,

    /** 答题中（你正在打字或录音） */
    ANSWERING,

    /** 评分中（AI 正在给你的答案打分） */
    JUDGING,

    /** 追问中（AI 根据分数生成下一道追问题） */
    ASKING,

    /** 已结束（面试彻底完结） */
    ENDED
}
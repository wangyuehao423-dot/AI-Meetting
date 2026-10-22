package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.infrastructure.mysql.AnswerEntity;
import com.example.interview.infrastructure.mysql.AnswerEntityMapper;
import com.example.interview.infrastructure.mysql.FollowUpEntity;
import com.example.interview.infrastructure.mysql.FollowUpMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository 
public class InterviewReportDataRepositoryImpl implements InterviewReportDataRepository{

    private final QuestionMapper questions;
    private final AnswerEntityMapper answers;
    private final FollowUpMapper followUps;

    public InterviewReportDataRepositoryImpl(
            QuestionMapper questions,
            AnswerEntityMapper answers,
            FollowUpMapper followUps
    ) {
        this.questions = questions;
        this.answers = answers;
        this.followUps = followUps;
    }

    @Override
    public String loadContext(UUID sessionId) {
        String id = sessionId.toString();
        // 查询所有题目，按题目序号升序排列，保证报告按面试提问顺序展示
        List<QuestionEntity> questionList =
                questions.selectList(
                        Wrappers.<QuestionEntity>lambdaQuery()
                                .eq(QuestionEntity::getSessionId, id)
                                .orderByAsc(QuestionEntity::getSequenceNo)
                );

        // 查询所有答案（含评分和反馈），按创建时间升序排列
        List<AnswerEntity> answerList =
                answers.selectList(
                        Wrappers.<AnswerEntity>lambdaQuery()
                                .eq(AnswerEntity::getSessionId, id)
                                .orderByAsc(AnswerEntity::getCreatedAt)
                );

        // 查询所有追问记录，按创建时间升序排列
        List<FollowUpEntity> followUpList =
                followUps.selectList(
                        Wrappers.<FollowUpEntity>lambdaQuery()
                                .eq(FollowUpEntity::getSessionId, id)
                                .orderByAsc(FollowUpEntity::getCreatedAt)
                );
        StringBuilder sb = new StringBuilder("以下是本场面试的完整数据，请据此生成报告：\n");

        for (int i = 0; i < questionList.size(); i++) {
            QuestionEntity question = questionList.get(i);
            // 预生成但从未被激活提问的题（PRE_GENERATED，且无作答、无追问）不进报告：
            // 上一题作答后后台会提前把"下一题"写进库，面试若在本题结束，这道题候选人
            // 根本没看到，放进上下文会被 AI 统计成"未作答"，报告内容失真。
            if ("PRE_GENERATED".equals(question.getStatus())
                    && answerList.stream().noneMatch(a -> question.getId().equals(a.getQuestionId()))
                    && followUpList.stream().noneMatch(f -> question.getId().equals(f.getQuestionId()))) {
                continue;
            }
            sb.append("## 第 ").append(question.getSequenceNo()).append(" 题\n")
              .append("题目：").append(question.getContent()).append("\n");

            // 从这里开始是修改的：按 is_follow_up 区分"初次作答"与"追问作答"。
            // 为什么不能再用 findFirst：同一道题在 interview_answer 里最多有两行
            // （is_follow_up = 0 的初次作答、= 1 的追问作答），findFirst 只会随机拿到
            // 时间上更早的那一行，追问的回答与评分就永远进不了报告，报告里
            // "原始回答 → 追问 → 追问回答"这条链会缺后半截。
            // 第一次筛选专门取初次作答（is_follow_up = 0），保证"回答"栏里是原题答案。
            AnswerEntity answer = answerList.stream()
                    .filter(a -> question.getId()
                            .equals(a.getQuestionId()))
                    .filter(a -> a.getIsFollowUp() == null
                            || a.getIsFollowUp() == 0)
                    .findFirst()
                    .orElse(null);
            if (answer != null) {
                sb.append("回答：").append(answer.getAnswer()).append("\n")
                  .append("评分：").append(answer.getScore()).append("\n")
                  .append("反馈：").append(answer.getFeedback()).append("\n");
            } else {
                sb.append("回答：（未作答）\n");
            }

            // 把该题的追问记录（若存在）也追加到对应题目下
            followUpList.stream()
                    .filter(f -> question.getId()
                            .equals(f.getQuestionId()))
                    .forEach(f -> sb.append("追问：")
                            .append(f.getContent()).append("\n"));

            // 新增：追问作答（is_follow_up = 1）紧跟在该题的追问正文之后，
            // 让报告端能完整还原"原题回答 → 追问 → 追问回答"的对比链条。
            answerList.stream()
                    .filter(a -> question.getId()
                            .equals(a.getQuestionId()))
                    .filter(a -> a.getIsFollowUp() != null
                            && a.getIsFollowUp() == 1)
                    .forEach(a -> sb.append("追问回答：").append(a.getAnswer()).append("\n")
                            .append("追问评分：").append(a.getScore()).append("\n")
                            .append("追问反馈：").append(a.getFeedback()).append("\n"));
            // 修改结束
        }

        return sb.toString();
    }
    
}

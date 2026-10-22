package com.example.interview.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.application.FollowUpRepository;
import com.example.interview.infrastructure.mysql.AnswerEntity;
import com.example.interview.infrastructure.mysql.AnswerEntityMapper;
import com.example.interview.infrastructure.mysql.FollowUpExecution;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.yomahub.liteflow.core.NodeComponent;

import ch.qos.logback.core.pattern.parser.Node;

import org.springframework.stereotype.Component;

import java.util.UUID;

public final class FollowUpLiteFlowNodes {
    private FollowUpLiteFlowNodes(){}
     
    @Component ("loadQuestionContext")
    public static final class LoadQuestionContext extends NodeComponent
    {
        private final QuestionMapper questions;
        private final AnswerEntityMapper answers;

        public LoadQuestionContext(
                QuestionMapper questions,
                AnswerEntityMapper answers
        ) {
            this.questions = questions;
            this.answers = answers;
        }

        @Override
        public void process() {
            FollowUpExecution context = this.getSlot().getContextBean(FollowUpExecution.class);
            // 从这里开始是修改的：原实现 questions.selectById(context.getQuestionId()) 直接把 UUID
            // 作为 selectById 的参数。该 SQL 内部规范是“WHERE id = #{id}”，MyBatis 收到一个非简单类型
            // （UUID 不在 MyBatis 的简单类型白名单里）时，会把它当作 bean 反射去取名为 id 的 getter；
            // UUID 没有 id 属性，于是抛 “no getter for property named 'id' in 'class java.util.UUID'”。
            // 这条链正是作答后生成追问的必经节点，一失败 generate()（FollowUpGenerationService 第 92 行）
            // 就提前中断，它后面第 98 行的“推进状态 + 预生成下一题”不再执行——表现为前端一直卡在等待、
            // 既不更新下一题也不报错。
            // 改用 lambdaQuery 显式 eq 到 id 列、参数按字符串传（题目表主键 id 本身就是 varchar），
            // 参数是 String 简单类型，就不会触发 bean 反射了；查询语义与 selectById 完全等价。
            QuestionEntity question = questions.selectOne(
                    Wrappers.<QuestionEntity>lambdaQuery()
                            .eq(
                                    QuestionEntity::getId,
                                    context.getQuestionId().toString()
                            )
            );
            // 修改结束
            if(question == null)
            {
                throw new IllegalStateException(
                    "question not found: "
                                + context.getQuestionId()
                );
            }
            context.setQuestionTitle(question.getContent());
            AnswerEntity latest = answers.selectOne(
                    Wrappers.<AnswerEntity>lambdaQuery()
                            .eq(
                                    AnswerEntity::getSessionId,
                                    context.getSessionId().toString()
                            )
                            .eq(
                                    AnswerEntity::getQuestionId,
                                    context.getQuestionId().toString()
                            )
                            .orderByDesc(AnswerEntity::getId)
            );
            context.setAnswer(
                    latest == null ? "" : latest.getAnswer()
            );
        }
    }

    // 从这里开始是修改的：三个追问生成节点原本写在 LoadQuestionContext 的花括号里面，
    // 它们的二进制名因此多了一层 $LoadQuestionContext（...FollowUpLiteFlowNodes$LoadQuestionContext$GenerateBasicFollowUp），
    // 与规则文件里写的 ...FollowUpLiteFlowNodes$GenerateBasicFollowUp 对不上。
    // LiteFlow 加载链时不校验节点类是否存在，链能起来，但跑到该节点就反射实例化失败。
    // 现在整体左移一层，与 LoadQuestionContext 平级，$LoadQuestionContext 这一段就从二进制名里消失了。
    // 类名、@Component 名、process() 方法体一个字都没改。
    @Component("generateBasicFollowUp")
    public static final class GenerateBasicFollowUp extends NodeComponent
    {
        private final InterviewAiFacade ai;

        public GenerateBasicFollowUp(InterviewAiFacade ai)
        {
            this.ai = ai;
        }
        @Override 
        public void process()
        {
            FollowUpExecution context = this.getSlot().getContextBean(FollowUpExecution.class);
            context.setContent(ai.generateFollowUpText(
                context.getSessionId(),
                context.getQuestionId(),
                context.getScore(),
                context.getLevel(),
                context.getObjective(),
                context.getQuestionTitle(),
                context.getAnswer(),
                "基础追问：先确认概念定义，再拆核心原理，最后要最小可运行例子；"
                        + "语气耐心、逐步解释，不预设候选人已经掌握。"
            ));
        }
    }

    @Component("generateNormalFollowUp")
    public static final class GenerateNormalFollowUp
        extends NodeComponent {
        private final InterviewAiFacade ai;

        public GenerateNormalFollowUp(InterviewAiFacade ai) {
            this.ai = ai;
        }   

        @Override
        public void process() {
            FollowUpExecution context =
                this.getSlot().getContextBean(
                        FollowUpExecution.class
                );
        context.setContent(ai.generateFollowUpText(
                context.getSessionId(),
                context.getQuestionId(),
                context.getScore(),
                context.getLevel(),
                context.getObjective(),
                context.getQuestionTitle(),
                context.getAnswer(),
                "常规追问：补实现细节、排障过程和验证方式；默认候选人已掌握基础，"
                        + "追问不放水也别突然拔高。"
        ));
        }
    }

    @Component("generateDeepFollowUp")
    public static final class GenerateDeepFollowUp
        extends NodeComponent {
        private final InterviewAiFacade ai;

        public GenerateDeepFollowUp(InterviewAiFacade ai) {
            this.ai = ai;
        }

        @Override
        public void process() {
            FollowUpExecution context =
                this.getSlot().getContextBean(
                        FollowUpExecution.class
                );
        context.setContent(ai.generateFollowUpText(
                context.getSessionId(),
                context.getQuestionId(),
                context.getScore(),
                context.getLevel(),
                context.getObjective(),
                context.getQuestionTitle(),
                context.getAnswer(),
                "深度追问：挖边界条件、性能取舍、故障恢复与架构决策；"
                        + "追问要有阶梯感，从候选人的回答逐层加深。"
        ));
        }
    }
    // 修改结束

    @Component("persistFollowUp")
    public static final class PersistFollowUp extends NodeComponent
    {
        private final FollowUpRepository followUps;

        public PersistFollowUp(FollowUpRepository followUps) {
            this.followUps = followUps;
        }

        @Override
        public void process() 
        {
            FollowUpExecution context =
                    this.getSlot().getContextBean(
                            FollowUpExecution.class
                    );
            if (context.getContent() == null
                    || context.getContent().isBlank()) {
                throw new IllegalStateException(
                        "follow-up content is empty, cannot persist"
                );
            }  
            followUps.insertIfAbsent(
                    context.getSessionId(),
                    context.getQuestionId(),
                    context.getContent(),
                    context.getLevel()
            );
        }
    }
}

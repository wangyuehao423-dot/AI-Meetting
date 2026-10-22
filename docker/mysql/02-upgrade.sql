-- AI Meetting database incremental upgrade
-- 针对"本次改动之前已初始化的旧数据卷"执行一次，把库补齐到与 01-schema.sql 一致。
-- 全部是增量操作（ADD COLUMN / ADD INDEX / CREATE TABLE），不会删除或覆盖既有数据。
-- 全新容器（空卷）不需要执行本脚本，01-schema.sql 初始化时已包含全部列。

SET NAMES utf8mb4;
USE interview;

-- 题目表：预生成/取题状态列 + 取题扫描联合索引
ALTER TABLE interview_question
    ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    ADD INDEX idx_question_session_status (session_id, sequence_no, status);

-- 作答表：评分状态、追问标记、重试计数、更新时间
ALTER TABLE interview_answer
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    ADD COLUMN is_follow_up TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP;

-- 作答表唯一键：旧版误把 (session_id, question_id, request_id) 合起来做唯一键，
-- 改成 (request_id) 全局唯一 + (question_id, is_follow_up) 区分初次/追问两次作答。
ALTER TABLE interview_answer
    DROP INDEX uk_answer_request,
    ADD UNIQUE KEY uk_answer_request (request_id),
    ADD UNIQUE KEY uk_answer_question_round (question_id, is_follow_up);

-- 追问表：同一场面试同一道题只允许一条追问
ALTER TABLE interview_follow_up
    ADD UNIQUE KEY uk_follow_up_session_question (session_id, question_id);

-- 报告表：轮询用的生成状态与更新时间
ALTER TABLE interview_report
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'GENERATING' AFTER content,
    ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP;

-- 追问表：score 应为可空。代码 insert 只写 (id, session_id, question_id, level, content)，
-- 旧卷里 score 是 NOT NULL 无默认值，导致链上 persistFollowUp 插入报
-- "Field 'score' doesn't have a default value"、整条追问链回滚。与 01-schema.sql 对齐改为可空。
ALTER TABLE interview_follow_up
    MODIFY COLUMN score DECIMAL(5, 2) NULL;

-- 简历表：AI 技能提取结果列（旧 volume 遗漏，上传简历时由 ResumeAiExtractionService 填充）
ALTER TABLE interview_resume
    ADD COLUMN extracted_skills LONGTEXT NULL AFTER parsed_content;

-- 评分补偿日志表（补偿重投超上限时记一笔，供运维排查失败原因）
CREATE TABLE IF NOT EXISTS interview_judge_compensation_log (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    answer_id BIGINT NOT NULL,
    retry_count INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_compensation_log_answer
        FOREIGN KEY (answer_id) REFERENCES interview_answer (id),
    INDEX idx_compensation_log_answer (answer_id)
) ENGINE = InnoDB;
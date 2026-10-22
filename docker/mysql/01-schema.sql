-- AI Meetting database schema
-- MySQL 8.0+
-- This script is executed automatically when the MySQL container
-- initializes an empty data volume.

CREATE DATABASE IF NOT EXISTS interview
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE interview;

CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    skills JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_resume (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    storage_path VARCHAR(500) NOT NULL,
    parsed_content LONGTEXT,
    extracted_skills LONGTEXT,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_resume_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    INDEX idx_resume_user_created (user_id, created_at)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_session (
    id VARCHAR(36) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    resume_id BIGINT,
    state VARCHAR(32) NOT NULL,
    match_score DECIMAL(5, 2),
    started_at TIMESTAMP NULL,
    ended_at TIMESTAMP NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_session_user
        FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_session_position
        FOREIGN KEY (position_id) REFERENCES interview_position (id),
    CONSTRAINT fk_session_resume
        FOREIGN KEY (resume_id) REFERENCES interview_resume (id),
    INDEX idx_session_user_created (user_id, created_at),
    INDEX idx_session_position (position_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_question (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    position_id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    category VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    -- 从这里开始是新增的：题目状态列。预生成下一题时题目先以 PRE_GENERATED 落库
    -- （占住题号，避免并发取题时撞号），被取用时再 CAS 改成 ACTIVE 才允许作答。
    -- 没有这一列，预生成的题和已淘汰的题无法区分，前端会拿到不能答的题。
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    -- 新增结束
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id),
    CONSTRAINT fk_question_position
        FOREIGN KEY (position_id) REFERENCES interview_position (id),
    UNIQUE KEY uk_question_session_sequence (session_id, sequence_no),
    INDEX idx_question_session (session_id),
    -- 从这里开始是新增的：取题时按 session_id + sequence_no 找最大号、并按 status 过滤，
    -- 这个联合索引正好覆盖那条查询。
    INDEX idx_question_session_status (session_id, sequence_no, status)
    -- 新增结束
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_answer (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    question_id VARCHAR(36) NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    answer LONGTEXT NOT NULL,
    score DECIMAL(5, 2),
    feedback LONGTEXT,
    fencing_token BIGINT,
    status VARCHAR(32) NOT NULL DEFAULT 'SUBMITTED',
    -- 从这里开始是新增的：追问作答标记与补偿重试计数。
    -- is_follow_up：false(0) = 初次作答，true(1) = 追问作答。追问复用原题的
    -- question_id 提交，只能靠这一列区分"同一道题的两次作答"，否则追问答案会
    -- 撞上 uk_answer_question 被当成重复提交静默丢弃。
    is_follow_up TINYINT(1) NOT NULL DEFAULT 0,
    -- retry_count：异步补偿重投评分任务的次数，超过上限就把 status 置为 JUDGE_FAILED。
    retry_count INT NOT NULL DEFAULT 0,
    -- 新增结束
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT fk_answer_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id),
    CONSTRAINT fk_answer_question
        FOREIGN KEY (question_id) REFERENCES interview_question (id),
    UNIQUE KEY uk_answer_request (request_id),
    -- 从这里开始是修改的：唯一键由 (question_id) 改成 (question_id, is_follow_up)，
    -- 同一道题最多两行：一行初次作答、一行追问作答。
    UNIQUE KEY uk_answer_question_round (question_id, is_follow_up),
    -- 修改结束
    INDEX idx_answer_session (session_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_follow_up (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL,
    question_id VARCHAR(36) NOT NULL,
    score DECIMAL(5, 2),
    level VARCHAR(32) NOT NULL,
    content LONGTEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_follow_up_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id),
    CONSTRAINT fk_follow_up_question
        FOREIGN KEY (question_id) REFERENCES interview_question (id),
    -- 从这里开始是新增的：同一场面试的同一道题只允许一条追问。
    -- 仓储层是"先查后插"，并发下仍可能插进两条，这个唯一键是最后一道防线。
    UNIQUE KEY uk_follow_up_session_question (session_id, question_id),
    -- 新增结束
    INDEX idx_follow_up_session (session_id),
    INDEX idx_follow_up_question (question_id)
) ENGINE = InnoDB;

CREATE TABLE IF NOT EXISTS interview_report (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    session_id VARCHAR(36) NOT NULL UNIQUE,
    content LONGTEXT,
    -- 从这里开始是新增的：报告生成状态与更新时间。
    -- 报告生成是一次 10~30 秒的 AI 调用，接口改成"立即受理 + 前端轮询"之后，
    -- 必须有个地方存 GENERATING / COMPLETED / FAILED 这个中间态。
    status VARCHAR(32) NOT NULL DEFAULT 'GENERATING',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,
    -- 新增结束
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_report_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id)
) ENGINE = InnoDB;

-- 从这里开始是新增的：评分补偿日志表。
-- JudgeTaskCompensationService 在"队列里找不到对应评分任务、且补偿次数超过上限"时，
-- 除了把 interview_answer 标记为 JUDGE_FAILED，还会往这张表插一条记录，
-- 供运维排查失败原因与重试分布。没有这张表，补偿到上限的那一步会直接抛异常。
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
-- 新增结束

-- ============================================================
-- 已有数据库的升级脚本（新库不需要执行）
-- ------------------------------------------------------------
-- 上面的 CREATE TABLE 已经包含全部新列，全新的 MySQL 容器直接初始化即可。
-- 但如果你的库是本次改动之前建的，表已存在，CREATE TABLE IF NOT EXISTS 不会
-- 补列，必须手工执行下面这些 ALTER 一次（MySQL 8 不支持 ADD COLUMN IF NOT EXISTS，
-- 所以这里以注释形式给出，避免 docker 初始化新库时重复执行报错）。
--
-- ALTER TABLE interview_question
--     ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
--     ADD INDEX idx_question_session_status (session_id, sequence_no, status);
--
-- -- 已有库若缺少 uk_question_session_sequence 唯一键（历史库常见的缺失项），
-- -- 必须先清理每个 (session_id, sequence_no) 的重复行再补键，否则 ALTER 会失败：
-- -- 保留规则：有答案的优先，其次 ACTIVE，再次最早创建的；其余删除。
-- CREATE TEMPORARY TABLE keep_q AS
--     SELECT keep_id FROM (
--         SELECT q.id AS keep_id,
--                ROW_NUMBER() OVER (
--                    PARTITION BY q.session_id, q.sequence_no
--                    ORDER BY (EXISTS (SELECT 1 FROM interview_answer a
--                                      WHERE a.question_id = q.id)) DESC,
--                             CASE q.status WHEN 'ACTIVE' THEN 0 ELSE 1 END,
--                             q.created_at ASC
--                ) AS rn
--         FROM interview_question q
--     ) t WHERE t.rn = 1;
-- DELETE FROM interview_question
--     WHERE id NOT IN (SELECT keep_id FROM keep_q);
-- ALTER TABLE interview_question
--     ADD UNIQUE KEY uk_question_session_sequence (session_id, sequence_no);
--
-- ALTER TABLE interview_answer
--     ADD COLUMN is_follow_up TINYINT(1) NOT NULL DEFAULT 0,
--     ADD COLUMN retry_count INT NOT NULL DEFAULT 0;
--
-- ALTER TABLE interview_answer
--     DROP INDEX uk_answer_question,
--     ADD UNIQUE KEY uk_answer_question_round (question_id, is_follow_up);
--
-- ALTER TABLE interview_follow_up
--     ADD UNIQUE KEY uk_follow_up_session_question (session_id, question_id);
--
-- ALTER TABLE interview_report
--     ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'GENERATING' AFTER content,
--     ADD COLUMN updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
--         ON UPDATE CURRENT_TIMESTAMP;
--
-- CREATE TABLE IF NOT EXISTS interview_judge_compensation_log (
--     id BIGINT PRIMARY KEY AUTO_INCREMENT,
--     answer_id BIGINT NOT NULL,
--     retry_count INT NOT NULL,
--     reason VARCHAR(500) NOT NULL,
--     created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     CONSTRAINT fk_compensation_log_answer
--         FOREIGN KEY (answer_id) REFERENCES interview_answer (id),
--     INDEX idx_compensation_log_answer (answer_id)
-- ) ENGINE = InnoDB;
-- ============================================================

-- 从这里开始是新增的：岗位种子数据。
-- 为什么必须有这一段：interview_position 表如果为空，GET /api/positions 会返回空数组，
-- 前端 main.jsx 就会退回它自己硬编码的 defaultPositions，而那三个岗位的 id 是字符串
-- （'backend-java' / 'frontend-react' / 'fullstack'）。点"开始面试"时前端把这个字符串
-- 当 positionId 发给 POST /api/interviews，而后端签名是 @RequestParam Long positionId，
-- 于是报 NumberFormatException: For input string: "backend-java"，接口直接 500。
-- 岗位表里有真实的数字主键后，前端就不会再走兜底分支。
--
-- skills 字段是 JSON 类型，PositionController.match 会把它按 JSON 数组解析，
-- 再和简历里 AI 提取出的技能做匹配算匹配度，所以这里必须是合法的 JSON 数组字符串，
-- 不能写普通逗号分隔文本。岗位名称与前端 defaultPositions 保持一致，便于对照。
--
-- 用 INSERT IGNORE + 显式 id：脚本被重复执行（或库里已有同 id 数据）时不会报主键冲突，
-- 方便"已有库升级"和"新容器初始化"两种场景共用同一段语句。
--
-- 注意：执行下面的中文 INSERT 之前必须先 SET NAMES utf8mb4。
-- 原因：mysql 客户端的 default-character-set 默认是 auto，在容器（locale 为 C/POSIX）
-- 或中文 Windows 环境下会退化成单字节字符集（latin1）。这种状态下脚本里的中文 UTF-8
-- 字节会被逐字节当成 latin1 字符、再转存成 utf8mb4，形成"双重编码"：
-- "后端开发工程师"的正确 UTF-8 是 E5908EE7ABAF...，存进库却变成 C3A5C290C5BD...，
-- 前端拿到的就是 "Java åŽç«¯å¼€å‘å·¥ç¨‹å¸ˆ" 这种乱码。
-- 更迷惑的是：用同一个配置错误的客户端 SELECT 回来会对称地显示正常，容易误判为显示问题。
-- 显式 SET NAMES utf8mb4 后，服务端会正确按 UTF-8 解释后续语句里的中文字节，
-- 这样新容器初始化（docker entrypoint 执行本脚本）和手工 source 本脚本都不会再写坏数据。
SET NAMES utf8mb4;

INSERT IGNORE INTO interview_position (id, name, description, skills) VALUES
    (1, 'Java 后端开发工程师', 'Spring Boot、数据库与分布式系统', '["Java","Spring Boot","MySQL","Redis","MongoDB","Docker","分布式"]'),
    (2, '前端开发工程师', 'React、工程化与交互体验', '["JavaScript","TypeScript","React","CSS","前端工程化"]'),
    (3, '全栈开发工程师', '前后端协作与产品落地', '["Java","Spring Boot","React","MySQL","Node.js"]');
-- 新增结束

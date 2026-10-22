# AI Meetting · AI 模拟面试平台

一个基于 Spring Boot 3 + Spring AI + React 的 AI 模拟面试系统：上传简历 → 匹配岗位 → AI 出题 → 语音/文字作答 → AI 评分追问 → 生成面试报告，全流程自动化。

## 功能

| 环节 | 说明 |
| --- | --- |
| 注册 / 登录 | BCrypt 加密密码，JWT 鉴权，Filter 校验签名与有效期 |
| 简历上传解析 | Apache Tika 解析 PDF/Word，AI 提取技能与项目经历 |
| 岗位匹配 | 计算简历与岗位的匹配度并给出差距分析 |
| 题目生成 | 创建面试会话，初始化状态机与快照，AI 生成第一道题 |
| 作答推进 | 文本答题或阿里云 NLS 实时语音转写，提交后异步评分并取下一题 |
| 追问判断 | 评分后按分数走 LiteFlow 追问链，决定是否继续追问 |
| 面试结束 | 状态机收敛到结束态，异步生成综合报告，前端轮询后跳转报告页 |

## 技术栈

**后端**：Java 17 · Spring Boot 3.3.5 · Spring AI 1.0.0-M6（OpenAI 兼容接口）· LiteFlow 2.12.4 · MyBatis-Plus 3.5.12 · MySQL 8 · Redis · MongoDB · Spring WebSocket · JJWT 0.12.6 · Apache Tika 2.9.2 · 阿里云 NLS 实时语音识别

**前端**：React 18 · Vite 5 · 原生 WebSocket · 浏览器 Media API（麦克风采集）

**基础设施**：Docker Compose（MySQL / Redis / MongoDB）

## 架构要点

```text
Controller（application 包）
    -> 业务流程编排 / 状态机推进（domain 包）
    -> Redis 题级分布式锁（infrastructure/lock）
    -> MongoDB 会话快照（infrastructure/snapshot）
    -> 分布式请求合并 + 超时接管 + Fencing Token（infrastructure/singleflight）
    -> AI 出题 / 评分 / 追问 / 报告（ai 包，规则由 resources/skills 下 Markdown 提供）
```

几个值得一看的设计：

- **面试状态机**：会话状态与事件解耦，非法转移直接拒绝，避免"跳步提交答案"。
- **Singleflight + Fencing Token**：同一题并发生成时合并请求，超时后由接管者带更高 token 写回，防止旧结果覆盖新结果。
- **LiteFlow 追问链**：追问策略写在 `src/main/resources/flow/interview.xml`，调整追问逻辑不用改 Java 代码。
- **Prompt 外置**：出题、评分、追问、报告、简历匹配的规则都在 `src/main/resources/skills/*.md`，改 Markdown 即改业务语义。

## 快速开始

### 1. 启动依赖服务

```bash
docker compose up -d
```

| 服务 | 端口 |
| --- | --- |
| MySQL（库名 `interview`，启动自动执行 `docker/mysql/*.sql`） | 15006 |
| Redis | 16379 |
| MongoDB | 27017 |

### 2. 配置环境变量

复制 `.env.example`，把占位值换成自己的真实配置（至少 `INTERVIEW_AI_API_KEY` 和 `INTERVIEW_JWT_SECRET` 必须改）：

```bash
INTERVIEW_DB_URL=jdbc:mysql://localhost:15006/interview
INTERVIEW_DB_USERNAME=root
INTERVIEW_DB_PASSWORD=root
INTERVIEW_REDIS_HOST=localhost
INTERVIEW_REDIS_PORT=16379
INTERVIEW_MONGO_URI=mongodb://localhost:27017/interview
INTERVIEW_JWT_SECRET=replace-with-a-long-random-secret
INTERVIEW_AI_BASE_URL=https://api.openai.com/v1   # 任何 OpenAI 兼容中转站都可以
INTERVIEW_AI_API_KEY=replace-with-your-api-key
INTERVIEW_AI_MODEL=gpt-4o-mini
```

语音转写还需要阿里云 NLS 的三项配置（不配则只能文字答题）：

```bash
ALIYUN_NLS_APP_KEY=...
ALIYUN_NLS_ACCESS_KEY_ID=...
ALIYUN_NLS_ACCESS_KEY_SECRET=...
```

### 3. 启动后端

```bash
mvn spring-boot:run
```

后端默认监听 `18080`（不是 8080，原因见 `application.yml` 顶部注释：Windows 上 Hyper-V/WSL2 会保留部分端口段），可用 `INTERVIEW_SERVER_PORT` 覆盖。

### 4. 启动前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://localhost:5173`。Vite 已把 `/api` 代理到 `18080`、`/ws` 代理到 `ws://localhost:18080`。

## 目录结构

```text
src/main/java/com/example/interview/
├── ai/                    # 出题、评分、追问节点、报告、表现分析
├── application/           # Controller 与业务流程编排
├── config/                # Spring、JWT 过滤器、跨域、线程池、异常处理
├── domain/                # 面试状态、事件、状态机、领域快照
├── infrastructure/        # mysql / lock / snapshot / singleflight
├── skill/                 # Markdown 业务规则加载与调用
└── websocket/             # 阿里云 NLS 实时转写与 WebSocket 通道

src/main/resources/
├── application.yml        # 全部配置都可通过环境变量覆盖
├── flow/interview.xml     # LiteFlow 追问链规则
└── skills/*.md            # 各环节 Prompt 与业务规则

frontend/                  # React + Vite 单页应用
docker/                    # MySQL 初始化脚本
AI-Meetting.md             # 完整开发教学稿（按开发顺序逐文件讲解）
```

## 说明

- 仓库内所有密钥均通过环境变量注入，`application.yml` 中的默认值仅为本地占位（`change-me` / `root`），不含任何真实凭证。
- 本地运行日志、`target/`、`node_modules/`、`data/`（含上传的简历文件）均已在 `.gitignore` 中排除，不会进入版本库。

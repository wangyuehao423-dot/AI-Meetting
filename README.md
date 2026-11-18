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

**后端**：Java 17 · Spring Boot 3.3.5 · Spring AI 1.0.0-M6（OpenAI 兼容接口）· Spring AMQP（RabbitMQ 3.13）· LiteFlow 2.12.4 · MyBatis-Plus 3.5.12 · MySQL 8 · Redis · MongoDB · Nacos 2.4.2（配置中心）· Spring WebSocket · JJWT 0.12.6 · Apache Tika 2.9.2 · 阿里云 NLS 实时语音识别

**前端**：React 18 · Vite 5 · 原生 WebSocket · 浏览器 Media API（麦克风采集）

**基础设施**：Docker Compose（MySQL / Redis / MongoDB / RabbitMQ / Nacos）

## 架构要点

```text
Controller（application 包）
    -> 业务流程编排 / 状态机推进（domain 包）
    -> Redis 题级分布式锁（infrastructure/lock）
    -> MongoDB 会话快照（infrastructure/snapshot）
    -> 分布式请求合并 + 超时接管 + Fencing Token（infrastructure/singleflight）
    -> 异步链路投 RabbitMQ（application/ReportTaskPublisher 等，手动 ack + 延迟重试 + 死信）
    -> AI 出题 / 评分 / 追问 / 报告（ai 包，规则从 Nacos 拉取，classpath 兜底）
```

几个值得一看的设计：

- **面试状态机**：会话状态与事件解耦，非法转移直接拒绝，避免"跳步提交答案"。
- **评分 / 追问 / 报告三条异步链路走 RabbitMQ**：主队列 + 延迟重试队列（TTL 10 秒经 DLX 回主队列）+ 死信队列。手动 ack 保证"先落库再确认"，消费失败投延迟队列而不是 `nack(requeue=true)`——后者会让消息立刻回到队首形成热循环，几秒内把正在限流的 AI 接口打满三次。重试次数跟着消息体的 `retryCount` 走，超过 3 次转死信，留原文给人工排查。
- **Singleflight + Fencing Token**：同一题并发生成时合并请求，超时后由接管者带更高 token 写回，防止旧结果覆盖新结果。
- **LiteFlow 追问链**：追问策略写在 `src/main/resources/flow/interview.xml`，调整追问逻辑不用改 Java 代码。
- **Prompt 托管在配置中心**：出题、评分、追问、报告、简历匹配五份规则托管在 Nacos（dataId `interview-skill-<name>.md`，group `INTERVIEW_SKILL`）。改完在 Nacos 控制台点"发布"，应用通过配置变更监听**秒级生效，不用重新编译打包部署**。Nacos 不可用时自动降级到 jar 内 `src/main/resources/skills/*.md` 打包版本，服务照常启动；新配置解析失败（比如正文被删空）会被**拒绝落地**并保留上一版，避免线上规则悄悄退化。
- **规则版本可观测**：`GET /api/skills` 返回每份规则的内容指纹（version）、来源（nacos / classpath）和生效时间；`GET /api/skills/remote/status` 返回 Nacos 连接状态，用来快速判断"我改了为什么没生效"。

## 快速开始

### 1. 启动依赖服务

```bash
docker compose up -d
```

| 服务 | 端口 |
| --- | --- |
| MySQL（库名 `interview`，启动自动执行 `docker/mysql/*.sql`） | 15006 |
| Redis | 16379 |
| RabbitMQ（管理台 `http://localhost:15672`，账号 interview / interview123） | 5672 / 15672 |
| MongoDB | 27017 |
| Nacos（控制台 `http://localhost:18848/nacos`，默认账号 nacos / nacos） | 18848 |

> MySQL、MongoDB、Nacos 的数据都挂在命名卷（`mysql-data` / `mongo-data` / `nacos-data`）上，重建容器、`docker compose down` 都不会丢已发布的提示词；只有 `docker compose down -v` 才会连卷一起删。
>
> Nacos 除了 8848 还要映射 9848（客户端 gRPC 用「主端口 + 1000」），这里宿主侧对应 19848，少映射会一直报连接失败。

### 2. 配置环境变量

复制 `.env.example`，把占位值换成自己的真实配置（至少 `INTERVIEW_AI_API_KEY` 和 `INTERVIEW_JWT_SECRET` 必须改）：

```bash
INTERVIEW_DB_URL=jdbc:mysql://localhost:15006/interview
INTERVIEW_DB_USERNAME=root
INTERVIEW_DB_PASSWORD=root
INTERVIEW_REDIS_HOST=localhost
INTERVIEW_REDIS_PORT=16379
INTERVIEW_MONGO_URI=mongodb://localhost:27017/interview
INTERVIEW_MQ_HOST=localhost
INTERVIEW_MQ_PORT=5672
INTERVIEW_MQ_USER=interview
INTERVIEW_MQ_PASSWORD=interview123
INTERVIEW_JWT_SECRET=replace-with-a-long-random-secret
INTERVIEW_AI_BASE_URL=https://tokenx24.com/v1   # 任何 OpenAI 兼容中转站都可以
INTERVIEW_AI_API_KEY=replace-with-your-api-key
INTERVIEW_AI_MODEL=glm-5.3-flash
```

语音转写还需要阿里云 NLS 的三项配置（不配则只能文字答题）：

```bash
ALIYUN_NLS_APP_KEY=...
ALIYUN_NLS_ACCESS_KEY_ID=...
ALIYUN_NLS_ACCESS_KEY_SECRET=...
```

### 3. 把提示词发布到 Nacos（首次运行做一次）

```powershell
# 项目根目录执行
.\scripts\publish-skills-to-nacos.ps1
```

脚本会把 `src/main/resources/skills/*.md` 五份规则推到 Nacos 的 `INTERVIEW_SKILL` 分组下，dataId 形如 `interview-skill-interview-scoring.md`。

- 默认**只新建、不覆盖**：Nacos 上已存在同名配置时跳过。原因是线上的规则可能已经被调过很多轮，新环境无脑跑一次脚本不应该把它冲掉。确实要用本地版本覆盖时加 `-Force`。
- 之后改提示词就**不用再跑这个脚本**：直接去 Nacos 控制台（`http://localhost:18848/nacos`）找到对应 dataId，改完点"发布"，应用会自动热更新。
- Nacos 换了地址或端口时用 `-NacosAddr localhost:8848` 指定。

改完可以用接口确认线上到底生效了哪一版：

```bash
curl http://localhost:18080/api/skills              # 五份规则的 version / source / 生效时间
curl http://localhost:18080/api/skills/remote/status # Nacos 通不通
```

### 4. 启动后端

```bash
mvn spring-boot:run
```

后端默认监听 `18080`（不是 8080，原因见 `application.yml` 顶部注释：Windows 上 Hyper-V/WSL2 会保留部分端口段），可用 `INTERVIEW_SERVER_PORT` 覆盖。

### 5. 启动前端

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
├── application/           # Controller、业务流程编排、任务投递器与 MQ 消费者（Judge/FollowUp/Report）
├── config/                # Spring、JWT 过滤器、跨域、线程池、RabbitMQ 队列声明、异常处理
├── domain/                # 面试状态、事件、状态机、领域快照
├── infrastructure/        # mysql / lock / snapshot / singleflight
├── skill/                 # 业务规则：Nacos 拉取 + 变更监听 + 注册表（classpath 兜底）
└── websocket/             # 阿里云 NLS 实时转写与 WebSocket 通道

src/main/resources/
├── application.yml        # 全部配置都可通过环境变量覆盖
├── flow/interview.xml     # LiteFlow 追问链规则
└── skills/*.md            # 各环节 Prompt 与业务规则（Nacos 上的出厂版本 / 兜底副本）

scripts/
└── publish-skills-to-nacos.ps1   # 把 skills/*.md 一键发布到 Nacos

frontend/                  # React + Vite 单页应用
docker/                    # MySQL 初始化脚本
AI-Meetting.md             # 完整开发教学稿（按开发顺序逐文件讲解）
```

## 说明

- 仓库内所有密钥均通过环境变量注入，`application.yml` 中的默认值仅为本地占位（`change-me` / `root`），不含任何真实凭证。
- 本地运行日志、`target/`、`node_modules/`、`data/`（含上传的简历文件）均已在 `.gitignore` 中排除，不会进入版本库。
- `docker-compose.yml` 里的 Nacos 关闭了鉴权（`NACOS_AUTH_ENABLE=false`），只为本地开发省事。**生产环境必须开启鉴权，并把提示词放到独立 namespace、只给业务方只读/受限写权限**——提示词能改就等于 AI 的行为能改，这是配置中心带来的新攻击面。

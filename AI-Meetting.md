# 《AI Meetting》完整项目教学稿：按照正常开发顺序一步步完成

> 代码阅读方式：每次先说明文件路径和文件职责，再按照文件从上到下讲依赖、字段、构造器和方法。简单部分短讲，复杂部分展开；一个文件的各部分讲完后，再给出该文件的完整代码，避免把零散片段当成可以独立运行的代码。

## 一、整个项目最终要实现什么

在正式进入任何模块之前，先明确整个项目的端到端流程。后面学习每个模块时，要始终知道该模块的输入、输出，以及它和前后模块的连接关系。

### 项目整体流程

```text
项目初始化
    -> 启动后端、前端以及 MySQL、Redis、MongoDB
    -> 注册并登录，获取 JWT
    -> 简历上传：上传并解析简历，抽取技能与项目经历
    -> 岗位匹配：选择岗位，计算简历与岗位的匹配度
    -> 题目生成：创建面试会话、初始化状态机和快照，AI 生成第一道题
    -> 作答推进：文本或语音答题，提交答案并投递异步评分任务，取下一题
    -> 追问判断：AI 评分后按分数走 LiteFlow 追问链，决定是否追问
    -> 面试结束：结束会话，状态机收敛到结束态
    -> 报告跳转：异步生成综合报告，前端轮询完成后跳转报告页
```

这条主链路上的七个环节（简历上传 - 岗位匹配 - 题目生成 - 作答推进 - 追问判断 - 面试结束 - 报告跳转）是后面所有模块的骨架，每个模块都要能对上其中一个环节。

### 开发前先理解的几个难点

下面这些概念会在多个模块反复出现。后面遇到对应代码时，要把代码和这里的流程对上，而不是只记住 API 名称。

#### 1. 依赖注入

```text
Controller 需要 UserMapper
    -> Spring 启动时创建 UserMapper
    -> Spring 把它传给 Controller 构造方法
    -> Controller 直接使用，不自己 new 数据库对象
```

这样做是为了让业务类只声明“我需要什么”，对象的创建和替换交给 Spring 管理。

#### 2. JWT 鉴权

```text
登录成功
    -> 服务端把用户身份写入 JWT
    -> 使用密钥签名
    -> 前端保存 Token
    -> 后续请求携带 Authorization: Bearer Token
    -> Filter 验证签名和过期时间
    -> 请求进入 Controller
```

JWT 不是登录凭证的明文替代品。服务端仍然必须验证签名和有效期，不能只解码 Token 就信任其中的 `userId`。

#### 3. 状态机

```text
当前状态 + 业务事件
    -> 查找是否存在合法转移
    -> 存在则得到下一状态
    -> 不存在则拒绝请求
```

状态机只回答“能不能从这里走到那里”，不负责保存数据库、不负责调用 AI。这样流程规则和技术实现不会混在一起。

#### 4. 长会话状态治理与 CAS

一场面试是“长会话”：从创建到结束会连续产生多道题、多次作答和多轮追问。这些数据不能只靠一个内存对象扛着，项目把它拆成两层来治理：

```text
会话状态（state）
    -> 落在 MySQL 的 interview_session 表，由状态机推进
    -> 是“能不能走下一步”的权威判断，必须强一致
完整上下文（已出题、已作答、评分、追问）
    -> 落在 MongoDB 的 interview_session_snapshot 快照
    -> 体积大、读写频繁，适合文档型存储
重复请求
    -> 处理过的 requestId 记进快照的 processedRequestIds，重复请求直接短路
并发修改
    -> 读快照带 version，写回时 CAS 比对 version
    -> version 不一致说明中途被别人改过，拒绝写入并重新加载
```

为什么状态和上下文要分开：状态是判断依据，必须能原子地比对与更新；上下文是给 AI 出题看的历史材料，允许多次覆盖。题目的取用也是同一套思路——预生成的题先以 `PRE_GENERATED` 落库占住题号，被真正取用时再用 CAS 改成 `ACTIVE`，避免并发取题时拿到还没准备好的题。要注意 CAS 和锁不是一回事：锁控制“同一时刻谁能执行”，CAS 控制“基于旧版本做的修改不会覆盖新版本”。

#### 5. 异步队列与补偿

提交答案不应该让用户一直等 AI 评分，项目把“提交”和“评分”拆成两个时间点：

```text
提交答案
    -> 快速保存答案，并把评分任务写入 Redis 队列
    -> 消费者（JudgeTaskConsumerLoop）稍后取任务、调用 AI 评分
    -> 成功则落库，失败则重试
    -> 重试超过上限仍失败，把答案标记为 JUDGE_FAILED
    -> 补偿服务（JudgeTaskCompensationService）再扫一遍，
       仍救不回来就写一条 interview_judge_compensation_log 供排查
```

队列里放的是“任务”，数据库里存的是“任务的状态”，这两者一旦对不上（比如任务丢了但答案还停在待评分），就必须有补偿兜底，否则这道题会永远卡在中间态。追问任务走的是另一条队列（FollowUpTaskPublisher / FollowUpTaskConsumerLoop），结构和评分队列一致。

#### 6. 分布式 Singleflight 与 Fencing Token

```text
多个请求计算同一个 Key
    -> Singleflight 合并成一次 AI 调用
    -> 所有请求共享结果
    -> Fencing Token 标识结果的新旧
    -> 只接受仍然有效的结果
```

Singleflight 解决重复调用，Fencing Token 解决旧任务晚到并覆盖新结果的问题，两者不是同一个功能。项目里的 Singleflight 是分布式的（RedisSingleflight）：合并状态放在 Redis，任何一台实例都能看到；某台实例算到一半挂了，TimeoutTakeoverService 会接管超时任务，而 Fencing Token 保证被接管后旧实例晚到的结果不会覆盖新结果。

#### 7. LiteFlow 追问链

```text
评分结果
    -> 选择 basic、normal 或 deep 链
    -> 按 XML 中的 then 顺序执行整条多节点链
       （loadQuestionContext → 分数段生成节点 → persistFollowUp）
    -> 公共节点在三条链间复用，节点通过共享上下文传数据
    -> 得到追问结果并幂等落库
```

LiteFlow 每链由多个职责单一的节点串行组成：`loadQuestionContext`（加载原题与作答）与 `persistFollowUp`（幂等落库）是三条链共用的公共节点，只有中间按分数段生成的节点不同（`generateBasicFollowUp` / `generateNormalFollowUp` / `generateDeepFollowUp`）。状态机决定是否进入追问阶段，LiteFlow 决定进入追问阶段后具体执行哪条链。规则写在 `src/main/resources/flow/interview.xml`，靠 `application.yml` 里的 `liteflow.rule-source: flow/*.xml` 加载；不配这一项，一条链都不会加载，追问会直接报 chain not found。

#### 8. 阿里云 NLS 分段增量去重

实时语音识别用的是阿里云 NLS（智能语音交互）。它不像“录一段传一段”那样一次给完整结果，而是把一段话切成很多分片（seg_id）边识别边回调，而且同一个分片会先给临时结果、再给最终结果：

```text
浏览器采集麦克风音频
    -> 后端签发 NLS 临时 Token（AliyunNlsTokenService），浏览器直连 NLS 完成识别
    -> NLS 按分片回调，同一 seg_id 可能回调多次
    -> 后端按 sessionId 隔离队列（AsyncTranscriptionBuffer），异步消费分片
    -> 用 TreeMap 按 seg_id 覆盖更新：同一 seg_id 后到的覆盖先到的
    -> 只把 ed=true（最终确认）的连续分片拼成完整文本（TranscriptionSessionContext）
    -> ed=false 的临时结果只做前端预览，绝不落库
```

关键在“覆盖而不是追加”：如果每次回调都往字符串后面接，就会出现“我今今天去去公司”这种重复。项目用 `seg_id` 作为 key 覆盖写，并且只拼接连续已确认的分片，遇到跳号就停下等缺失分片补齐。签发 Token 的 AccessKey 只留在后端，不下发到浏览器。

#### 9. Skill 业务知识体系

出题、评分、追问、报告这些环节，本质上是“让 AI 按某套业务规则干活”。项目不把这些规则硬编码进提示词，而是做成可独立维护的 Skill（业务知识文件）：

```text
resources/skills/*.md
    -> 出题规则 question-generation.md
    -> 评分规则 interview-scoring.md
    -> 追问决策 follow-up-decision.md
    -> 报告生成 report-review.md
    -> 简历匹配 resume-matching.md
    -> 启动时由 SkillLoader 读成字符串，注册进 SkillRegistry
    -> 调 AI 前用 skillPrompt(name) 把对应规则拼到提示词最前面
```

这样做的好处：调提示词只改 Markdown，不用重新编译 Java；同一条规则还能被多个环节复用（比如评分规则同时给评分和追问用）。改规则文件等于改 AI 的行为，所以这些 Markdown 也是需要纳入版本管理的“代码”。

#### 10. AI 返回 JSON

```text
业务上下文
    -> 生成提示词
    -> AI 返回字符串
    -> 解析 JSON
    -> 校验字段、类型和取值范围
    -> 才能保存到数据库
```

AI 返回内容属于外部输入，不能因为提示词要求了 JSON 就跳过解析和校验。

我们先把用户使用系统的完整路径看一遍：

```text
注册
    -> 登录
    -> 上传简历
    -> 解析简历
    -> 选择岗位
    -> 简历与岗位匹配
    -> 创建面试会话
    -> AI 生成第一道题
    -> 文本或语音答题
    -> 提交答案
    -> AI 异步评分
    -> 根据评分生成追问
    -> 继续下一题
    -> 结束面试
    -> 生成综合报告
```

下面的教程严格沿着这条主流程编写。每个大模块内部再拆成连续的小步骤：

```text
登录 1：准备用户数据访问层
登录 2：准备密码加密和 JWT 能力
登录 3：编写注册接口
登录 4：编写登录接口
登录 5：编写 JWT 校验
登录 6：前端保存 Token 并携带请求
```

一个步骤如果需要同时创建多个文件，就在同一个步骤中一起讲，不强行把一个完整功能拆成“一个文件一个步骤”。重点是开发顺序和文件之间的连接。

## 二、项目技术和目录

### 2.1 后端技术

```text
Java 17
Spring Boot 3.3.5
Spring Cloud 2023.0.3 / Spring Cloud Alibaba 2023.0.3.2
Nacos（配置中心与注册中心）
MyBatis-Plus 3.5.12
MySQL 8（mysql-connector-j）
Redis
MongoDB
Spring AI 1.0.0-M6（spring-ai-core + spring-ai-openai-spring-boot-starter）
LiteFlow 2.12.4
Spring WebSocket
Apache Tika 2.9.2（简历解析）
JJWT 0.12.6
Spring Security Crypto（BCrypt）
Lombok
阿里云 NLS 智能语音交互（实时语音识别）
```

### 2.2 前端技术

```text
React 18.3.1 / react-dom 18.3.1
Vite 5.4.11（构建与开发服务器）
@vitejs/plugin-react 4.3.4
原生 WebSocket（实时转写通道）
浏览器 Media API（getUserMedia 采集麦克风音频）
```

前端只有一个 `frontend/` 工程，入口是 `index.html` + `src/main.jsx`；开发服务器配置在 `vite.config.js`，把 `/api` 代理到 `http://localhost:18080`、把 `/ws` 代理到 `ws://localhost:18080`。

### 2.3 后端目录

```text
src/main/java/com/example/interview/
├── InterviewPlatformApplication.java
├── ai/
├── application/
├── config/
├── domain/
├── infrastructure/
│   ├── lock/
│   ├── mysql/
│   ├── singleflight/
│   └── snapshot/
├── skill/
└── websocket/
```

目录职责：

```text
config
    -> Spring、安全（JWT 过滤器）、跨域、异步线程池、定时任务、异常处理配置

domain
    -> 面试状态、事件、状态机、领域快照

application
    -> Controller 和业务流程编排

infrastructure/mysql
    -> 数据库实体和 Mapper

infrastructure/lock
    -> Redis 题级分布式锁

infrastructure/snapshot
    -> MongoDB 会话快照

infrastructure/singleflight
    -> 分布式请求合并、超时接管和 Fencing Token

ai
    -> 出题、评分、追问（LiteFlow 节点）、报告和表现分析

websocket
    -> 阿里云 NLS 实时语音转写与 WebSocket 通道

skill
    -> Markdown 业务规则加载和调用
```

## 三、项目初始化模块

### 模块主要目的

搭建后端、前端、数据库和中间件的运行基础，让后续业务模块可以在一个可启动、可验证的项目上持续开发。

### 模块整体流程

本模块必须按下面的顺序执行。每一步完成后再进入下一步：

```text
1. 创建普通 Maven 项目和目录
    -> 得到一个有启动类的空后端项目
2. 修改 pom.xml 并下载依赖
    -> Maven 能够成功编译项目
3. 创建 Docker、数据库脚本和应用配置文件
    -> MySQL、Redis、MongoDB 有明确的连接地址和初始化数据
4. 创建公共配置类
    -> 跨域、异步、异常处理和初始数据逻辑准备完成
5. 启动并验证
    -> 后端能启动，且能够连接基础设施
```

下面每个小节都会明确标记“创建文件”“修改文件”“执行命令”和“验证结果”。文档中的路径都相对于项目根目录 `interview-platform/`。

业务章节中为了减少重复，`infrastructure/mysql/PositionEntity.java` 这类短路径默认补全为 `src/main/java/com/example/interview/infrastructure/mysql/PositionEntity.java`；如果文件位于 `resources` 或 `frontend`，文档会写出完整根路径。看到短路径时先按这个规则补全，不要在项目根目录随意创建同名文件。

从后面的业务模块开始，如果看到“文件：”或“涉及文件”，含义如下：

```text
文件不存在
    -> 创建这个文件
文件已经存在
    -> 打开并修改这个文件
命令代码块
    -> 在项目根目录的 IDEA Terminal 中执行
Java、XML、YAML、SQL 代码块
    -> 复制到紧挨着它标注的文件中，不要在 Terminal 中执行
```

### 初始化 1：创建普通 Maven 项目和目录

**完成标准：** 创建项目根目录、后端源码目录、资源目录、前端目录和 Spring Boot 启动类。此时还不能连接数据库，因为依赖和应用配置要到后面的小节完成。

#### 1. 创建项目

在 IDEA 中选择 `New Project -> Java`，填写：

```text
JDK：17
Build system：Maven
项目名称：interview-platform
项目位置：选择你准备保存代码的目录
不要选择 Spring Initializr
```

创建后，IDEA 会生成项目根目录和基础 `pom.xml`。如果没有自动生成下面的目录，就在项目根目录手动创建：

```text
src/main/java
src/main/resources
src/test/java
```

#### 2. 创建后端包目录

**操作位置：** 项目根目录 `interview-platform/`。  
**操作类型：** 创建文件夹，不是执行文件。

创建以下目录：

```text
src/main/java/com/example/interview/ai/
src/main/java/com/example/interview/application/
src/main/java/com/example/interview/config/
src/main/java/com/example/interview/domain/
src/main/java/com/example/interview/infrastructure/
src/main/java/com/example/interview/skill/
src/main/java/com/example/interview/websocket/
```

这些目录的用途是：

```text
ai/             AI 调用和提示词
application/    Controller 和应用服务
config/         Spring 公共配置
domain/         领域对象和业务规则
infrastructure/ MySQL、Redis、MongoDB 等基础设施代码
skill/          Markdown 规则加载
websocket/      WebSocket 通信
```

#### 3. 创建前端目录

**操作位置：** 项目根目录。  
**操作类型：** 创建文件夹。  
**用途：** 后续在这里创建 React/Vite 前端，不在本小节执行前端代码。

```text
frontend/
├── index.html
├── package.json
├── vite.config.js
└── src/
```

这四个位置就是前端的脚手架：`package.json` 声明 React 18 + Vite 5 依赖，`index.html` 提供 React 挂载点，`vite.config.js` 配置 `/api`、`/ws` 代理，`src/` 下放 `main.jsx` 等页面代码。

#### 4. 创建后端启动类

**创建文件：** `src/main/java/com/example/interview/InterviewPlatformApplication.java`  
**用途：** 这是后端程序的启动入口，不是命令文件。

```java
package com.example.interview;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class InterviewPlatformApplication {

    // 应用启动入口：由 Spring Boot 创建容器并启动整个后端服务。
    public static void main(String[] args) {
        SpringApplication.run(
                InterviewPlatformApplication.class,
                args
        );
    }
}
```

此时不要启动程序。因为 Spring Boot 依赖还没有加入，先进入“初始化 2”。

### 初始化 2：修改 pom.xml 并下载后端依赖

**完成标准：** `pom.xml` 中有项目基础配置和所有本项目需要的依赖，Maven 能够成功下载依赖并编译。

#### 1. 修改项目配置

**修改文件：** 项目根目录下的 `pom.xml`。  
**操作类型：** 修改 XML 配置文件，不是执行文件。下面的内容是完整的 `pom.xml` 结构，不要把它单独保存成其他文件。

将 `pom.xml` 修改为下面的结构。`<dependencies>` 中的每个 `<dependency>` 都是 Maven 依赖声明，不能直接在命令行执行：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>

  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.5</version>
    <relativePath/>
  </parent>

  <groupId>com.example</groupId>
  <artifactId>interview-platform</artifactId>
  <version>0.0.1-SNAPSHOT</version>
  <name>interview-platform</name>
  <description>AI 面试平台</description>

  <properties>
    <java.version>17</java.version>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <spring-cloud.version>2023.0.3</spring-cloud.version>
    <spring-cloud-alibaba.version>2023.0.3.2</spring-cloud-alibaba.version>
    <spring-ai.version>1.0.0-M6</spring-ai.version>
  </properties>

  <dependencyManagement>
    <dependencies>
    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-dependencies</artifactId><version>${spring-cloud.version}</version><type>pom</type><scope>import</scope></dependency>
    <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-alibaba-dependencies</artifactId><version>${spring-cloud-alibaba.version}</version><type>pom</type><scope>import</scope></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-bom</artifactId><version>${spring-ai.version}</version><type>pom</type><scope>import</scope></dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-jdbc</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-crypto</artifactId></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-web</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>0.12.6</version></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>0.12.6</version><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-websocket</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-mongodb</artifactId></dependency>
    <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId></dependency>
    <dependency><groupId>com.alibaba.cloud</groupId><artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId></dependency>
    <dependency><groupId>com.baomidou</groupId><artifactId>mybatis-plus-spring-boot3-starter</artifactId><version>3.5.12</version></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-core</artifactId></dependency>
    <dependency><groupId>org.springframework.ai</groupId><artifactId>spring-ai-openai-spring-boot-starter</artifactId></dependency>
    <dependency><groupId>com.yomahub</groupId><artifactId>liteflow-spring-boot-starter</artifactId><version>2.12.4</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.apache.tika</groupId><artifactId>tika-core</artifactId><version>2.9.2</version></dependency>
    <dependency><groupId>org.apache.tika</groupId><artifactId>tika-parsers-standard-package</artifactId><version>2.9.2</version></dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-core</artifactId>
    </dependency>
  </dependencies>

  <build>
    <plugins>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <version>3.11.0</version>
        <configuration>
          <annotationProcessorPaths>
            <path>
              <groupId>org.projectlombok</groupId>
              <artifactId>lombok</artifactId>
              <version>1.18.34</version>
            </path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>
```

这里出现的 `spring-cloud` / `spring-cloud-alibaba` / `spring-ai` 三个版本属性，是给 `<dependencyManagement>` 里的 BOM 用的；`spring-ai-core`、`spring-ai-openai-spring-boot-starter` 的版本由 `spring-ai-bom` 统一管理，`jjwt`、`mybatis-plus`、`liteflow`、`tika` 则各自写明版本。`maven-compiler-plugin` 里配置了 Lombok 注解处理器，实体上用的 `@Data` 才会在编译期生成 getter/setter。

保存 `pom.xml` 后，点击 IDEA 右侧 Maven 面板的刷新按钮，等待依赖下载完成。

#### 2. 执行编译命令

**操作位置：** IDEA 的 Terminal，当前目录必须是项目根目录，也就是能看到 `pom.xml` 的目录。  
**操作类型：** 执行命令。

```powershell
mvn clean compile
```

如果本机没有配置 Maven，也可以使用 IDEA Maven 面板执行 `clean` 和 `compile`。

#### 3. 验证结果

看到 `BUILD SUCCESS` 才算本小节完成。常见错误含义：

```text
Could not resolve dependency
    -> Maven 无法下载依赖，检查网络或 Maven 镜像
package ... does not exist
    -> pom.xml 依赖没有加载成功，刷新 Maven 后重试
```

编译成功后，再进入“初始化 3”。此时只证明代码依赖可以编译，不代表数据库已经启动。

### 初始化 3：创建基础设施、数据库脚本和应用配置

**完成标准：** 项目中有 Docker Compose 文件、MySQL 初始化脚本、环境变量模板和 Spring 配置文件；执行 Docker 命令后，MySQL、Redis、MongoDB 能正常运行。

本小节中的四个文件用途不同：

```text
docker-compose.yml
    -> Docker 的启动配置，docker compose 命令会读取并执行它
docker/mysql/01-schema.sql
    -> MySQL 初始化脚本，容器首次创建数据库时自动执行它
.env.example
    -> 环境变量示例，只是模板，不会自动提供真实密钥
src/main/resources/application.yml
    -> Spring Boot 启动时读取的应用配置文件
```

#### 1. 创建 Docker Compose 文件

**创建文件：** 项目根目录下的 `docker-compose.yml`。  
**作用：** 定义并启动 MySQL、Redis、MongoDB 三个开发环境服务。

```yaml
services:
  mysql:
    image: mysql:8.0
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: interview
    ports:
      - "15006:3306"
    volumes:
      - mysql-data:/var/lib/mysql
      - ./docker/mysql/01-schema.sql:/docker-entrypoint-initdb.d/01-schema.sql:ro

  redis:
    image: redis:7
    ports:
      - "16379:6379"

  mongodb:
    image: mongo:7
    ports:
      - "27017:27017"
    volumes:
      - mongo-data:/data/db

volumes:
  mysql-data:
  mongo-data:
```

#### 2. 创建 MySQL 初始化脚本

**先创建文件夹：** `docker/mysql/`  
**再创建文件：** `docker/mysql/01-schema.sql`  
**作用：** 创建项目运行需要的数据库表。它不是需要手动逐条执行的 PowerShell 文件；MySQL 容器第一次初始化时会自动执行它。

需要创建的核心表是（共 9 张）：

```text
app_user
interview_position
interview_resume
interview_session
interview_question
interview_answer
interview_follow_up
interview_report
interview_judge_compensation_log
```

除了这些表，`01-schema.sql` 里还有几处后来补上的列和唯一键，后面文档出现这些名字时要和这里对上：`interview_question.status`（预生成题目先以 `PRE_GENERATED` 占位，取用时 CAS 改成 `ACTIVE`，配套联合索引 `idx_question_session_status`）；`interview_answer.is_follow_up`（区分初次作答与追问作答）和 `retry_count`（评分补偿重试次数），唯一键由 `(question_id)` 改成 `(question_id, is_follow_up)`；`interview_follow_up` 新增唯一键 `uk_follow_up_session_question (session_id, question_id)`；`interview_report.status`（`GENERATING / COMPLETED / FAILED`）与 `updated_at`；以及补偿日志表 `interview_judge_compensation_log`。旧库升级时这些列要用 `ALTER TABLE` 手工补一次，`01-schema.sql` 末尾以注释形式给出了迁移语句。

将本文档后续各业务模块给出的建表 SQL 按顺序放入这个文件。修改 SQL 后，已有的 MySQL 数据卷不会自动重新执行脚本；开发阶段需要重新创建数据卷：

```powershell
docker compose down -v
docker compose up -d
```

`down -v` 会删除本项目 Docker Compose 创建的数据库数据，只能在确认不需要保留开发数据时执行。

#### 3. 创建环境变量模板

**创建文件：** 项目根目录下的 `.env.example`  
**作用：** 记录项目需要哪些环境变量，供开发者复制参考。不要把真实 API Key 写入这个文件。

```dotenv
INTERVIEW_DB_URL=jdbc:mysql://localhost:15006/interview
INTERVIEW_DB_USERNAME=root
INTERVIEW_DB_PASSWORD=root
INTERVIEW_REDIS_HOST=localhost
INTERVIEW_REDIS_PORT=16379
INTERVIEW_MONGO_URI=mongodb://localhost:27017/interview
INTERVIEW_JWT_SECRET=replace-with-a-long-random-secret
INTERVIEW_AI_BASE_URL=
INTERVIEW_AI_API_KEY=
INTERVIEW_AI_MODEL=
ALIYUN_NLS_APP_KEY=
ALIYUN_NLS_ACCESS_KEY_ID=
ALIYUN_NLS_ACCESS_KEY_SECRET=
```

`.env.example` 本身不会被 Spring Boot 自动读取。运行后端时，需要把这些变量配置到 IDEA 的 Run Configuration，或者配置到操作系统环境变量中。末尾三个 `ALIYUN_NLS_*` 是阿里云智能语音交互（实时语音识别）的凭据：`app-key` 用来签发识别用的临时 Token，`access-key-id` / `access-key-secret` 只在后端签名时使用，绝不能下发到浏览器，也不要写进仓库明文。

#### 4. 创建 Spring Boot 应用配置

**创建文件：** `src/main/resources/application.yml`  
**作用：** 告诉 Spring Boot 如何连接 MySQL、Redis、MongoDB，以及从哪里读取 JWT 和 AI 配置。

```yaml
server:
  port: ${INTERVIEW_SERVER_PORT:18080}

spring:
  ai:
    openai:
      base-url: ${INTERVIEW_AI_BASE_URL:https://tokenx24.com/v1}
      api-key: ${INTERVIEW_AI_API_KEY:${interview.ai.api-key}}
      http-client:
        connect-timeout: 10s
        read-timeout: 180s
        write-timeout: 30s
      chat:
        completions-path: /chat/completions
        options:
          model: ${INTERVIEW_AI_MODEL:${interview.ai.model}}
  datasource:
    url: ${INTERVIEW_DB_URL:jdbc:mysql://localhost:15006/interview}
    username: ${INTERVIEW_DB_USERNAME:root}
    password: ${INTERVIEW_DB_PASSWORD:root}

  data:
    redis:
      host: ${INTERVIEW_REDIS_HOST:localhost}
      port: ${INTERVIEW_REDIS_PORT:16379}
    mongodb:
      uri: ${INTERVIEW_MONGO_URI:mongodb://localhost:27017/interview}

  cloud:
    nacos:
      config:
        enabled: false
        import-check:
          enabled: false
      discovery:
        enabled: false

interview:
  jwt-secret: ${INTERVIEW_JWT_SECRET:change-me}
  singleflight:
    local-fallback-enabled: ${INTERVIEW_SINGLEFLIGHT_LOCAL_FALLBACK_ENABLED:true}
  ai:
    base-url: ${INTERVIEW_AI_BASE_URL:https://tokenx24.com/v1}
    api-key: ${INTERVIEW_AI_API_KEY:}
    model: ${INTERVIEW_AI_MODEL:gpt-5.5}

aliyun:
  nls:
    app-key: ${ALIYUN_NLS_APP_KEY:}
    access-key-id: ${ALIYUN_NLS_ACCESS_KEY_ID:}
    access-key-secret: ${ALIYUN_NLS_ACCESS_KEY_SECRET:}

liteflow:
  rule-source: flow/*.xml
```

这里的 `${环境变量:默认值}` 是 Spring 的占位符语法：冒号前是环境变量名，冒号后是取不到时的默认值。`spring.ai.openai.*` 是 Spring AI 的自动配置，`interview.ai.*` 是业务侧自己读的一份 AI 配置，两者指向同一个服务。`aliyun.nls.*` 三个键对应 `.env.example` 里新增的三个环境变量，`AliyunNlsTokenService` 用 `@Value("${aliyun.nls.app-key}")` 这样的方式注入。`liteflow.rule-source: flow/*.xml` 告诉 LiteFlow 去 classpath 的 `flow/` 目录扫描规则文件；不加这一段，`flow/interview.xml` 里的追问链一条都不会加载，追问会直接报 chain not found。真实的 AI API Key 和阿里云 AccessKey 只通过环境变量注入，配置文件里留空即可。

#### 5. 启动基础设施并检查状态

**操作位置：** 项目根目录。  
**操作类型：** 执行命令；命令会读取刚才创建的 `docker-compose.yml`。

```powershell
docker compose up -d
docker compose ps
```

三个服务都显示 `running` 或 `Up` 后，基础设施才算启动成功。本机当前使用以下宿主机端口：MySQL `15006`、Redis `16379`、MongoDB `27017`。其中 Redis 容器内部仍使用 `6379`，MongoDB 容器内部仍使用 `27017`。

如果出现 `open //./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified`，说明 Docker Desktop 或其 Linux 引擎尚未启动，需要先启动 Docker Desktop，再执行 Compose 命令。如果出现 `port is already allocated`，说明宿主机端口被其他服务占用，应检查占用情况或调整 Compose 的宿主机端口，并同步修改 `application.yml` 和 `.env.example`。

查看日志时执行：

```powershell
docker compose logs mysql
docker compose logs redis
docker compose logs mongodb
```

### 初始化 4：创建公共配置类

**完成标准：** 创建跨模块共用的配置类，并明确每个类在哪个阶段被使用。

#### 1. 创建异步配置

**创建文件：** `src/main/java/com/example/interview/config/AsyncConfig.java`  
**作用：** 按任务类型注册相互隔离的异步线程池，避免简历解析、AI 调用、报告生成等长耗时任务互相占用队列和工作线程。

**下面补充完整的异步线程池配置。`@EnableAsync` 负责开启 Spring 异步代理；每种任务使用独立线程池，某一类任务堆积时不会阻塞其他类型任务。线程池参数是教学版占位值，生产环境应结合 AI 服务耗时、并发量和限流策略压测后调整。**

```java
package com.example.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

// **开启 Spring 的 @Async 异步方法代理。**
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "interviewPreparationTaskExecutor")
    public Executor interviewPreparationTaskExecutor() {
        return executor("interview-preparation-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "questionGenerationTaskExecutor")
    public Executor questionGenerationTaskExecutor() {
        // **预生成任务丢了也无所谓，静默丢弃，不阻塞调用方。**
        return executor("question-generation-", 2, 4, 50,
                new ThreadPoolExecutor.DiscardPolicy());
    }

    @Bean(name = "resumeParsingTaskExecutor")
    public Executor resumeParsingTaskExecutor() {
        return executor("resume-parsing-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "aiScoringTaskExecutor")
    public Executor aiScoringTaskExecutor() {
        return executor("ai-scoring-", 4, 8, 100,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "followUpTaskExecutor")
    public Executor followUpTaskExecutor() {
        return executor("follow-up-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "reportGenerationTaskExecutor")
    public Executor reportGenerationTaskExecutor() {
        return executor("report-generation-", 2, 4, 30,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "demeanorAnalysisTaskExecutor")
    public Executor demeanorAnalysisTaskExecutor() {
        return executor("demeanor-analysis-", 2, 4, 30,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "transcriptionTaskExecutor")
    public Executor transcriptionTaskExecutor() {
        return executor("transcription-", 2, 4, 100,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = "cacheWriteTaskExecutor")
    public Executor cacheWriteTaskExecutor() {
        // **缓存回填等轻量异步操作，不依赖 @Async 注解，供 CompletableFuture 使用。**
        return executor("cache-write-", 2, 4, 50,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    private Executor executor(
            String prefix,
            int corePoolSize,
            int maxPoolSize,
            int queueCapacity,
            RejectedExecutionHandler rejectionPolicy
    ) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        // 拒绝策略：默认用 CallerRunsPolicy（谁提交谁执行，任务不丢）
        executor.setRejectedExecutionHandler(rejectionPolicy);
        executor.initialize();
        return executor;
    }
}
```

**上面代码块中的新增配置保持为完整 Java 文件；为保证复制后仍然是可编译代码，代码行的改动标记采用代码内 `// **...**` 注释，文字说明和代码块标题均使用 Markdown 粗体。**

#### 2. 创建跨域配置

**创建文件：** `src/main/java/com/example/interview/config/CorsConfig.java`  
**作用：** 允许本地前端开发地址访问后端接口。前端地址和后端端口确定后，再填写允许的来源。开发环境下前端经 Vite 代理访问后端，属于同源不需要跨域，因此该类保持为占位空类。

```java
package com.example.interview.config;

public class CorsConfig {
}
```

#### 3. 创建统一异常处理

**创建文件：** `src/main/java/com/example/interview/config/ApiExceptionHandler.java`  
**作用：** 把 Controller 抛出的业务异常转换成统一 JSON 响应，前端不需要分别处理不同格式的错误。

```java
// 从这里开始是新增的：以下整个文件都是新增的（磁盘上原是一个只有类头的空类，
// 报告接口抛出的 AccessDeniedException 没人接，会一路冒到 Spring MVC 默认处理变成 500）。
package com.example.interview.config;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(
            IllegalArgumentException error
    ) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> forbidden(
            AccessDeniedException error
    ) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("message", error.getMessage()));
    }
}
// 新增结束
```

#### **4. 创建定时任务配置**

**创建文件：** `src/main/java/com/example/interview/config/SchedulingConfig.java`  
**作用：** **启用 Spring 的定时任务能力，供 RedisSingleflight 的本地缓存清理等定时方法使用。**

```java
package com.example.interview.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

// **启用 Spring 定时任务。**
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
```

**这四个类创建完成后，才进入“注册和登录模块”。公共配置类的具体代码应在本节分别放在对应文件路径下，不能只列文件名而不说明创建位置和作用。**

### 初始化 5：启动后端并完成基础验证

**操作位置：** 项目根目录。  
**前置条件：** 初始化 1 到初始化 4 已完成，Docker 服务正在运行，`pom.xml` 已经成功编译。

#### 1. 启动后端

```powershell
mvn spring-boot:run
```

也可以在 IDEA 中运行 `InterviewPlatformApplication.main()`。

#### 2. 验证启动结果

确认控制台出现类似信息：

```text
Started InterviewPlatformApplication
```

如果启动失败，按下面顺序检查：

```text
数据库连接失败 -> 检查 docker compose ps 和 application.yml 端口
类或依赖找不到 -> 刷新 Maven 并重新执行 mvn clean compile
端口被占用 -> 修改 server.port 或关闭占用端口的程序
```

后端能启动并连接 MySQL、Redis、MongoDB，才说明项目初始化完成。

## 四、先创建后续会用到的依赖文件

前面的初始化只创建了 Spring Boot 启动类、配置类和基础设施。后面的业务代码会互相引用很多文件，所以不能直接跳到 Controller。正确做法是先建立依赖层，再从外到内写业务：

```text
数据库实体和 Mapper
    -> 仓储接口和基础设施
    -> 领域对象和状态机
    -> AI、队列、锁等应用服务
    -> Controller 和前端
```

下面这些文件如果没有先创建，后面的代码就只是“看起来完整”，实际无法编译。先按清单建好文件；后面的章节会继续在这些文件中补充完整实现。

### 4.1 先建立数据库实体和 Mapper

统一放在：

```text
src/main/java/com/example/interview/infrastructure/mysql/
```

需要提前创建的文件：

```text
PositionEntity.java
PositionMapper.java
InterviewSessionEntity.java
InterviewSessionMapper.java
QuestionEntity.java
QuestionMapper.java
AnswerEntity.java
AnswerEntityMapper.java
FollowUpEntity.java
FollowUpMapper.java
ReportEntity.java
ReportMapper.java
```

这个包 `infrastructure/mysql/` 下最终还会有 `UserEntity.java`、`UserMapper.java`（在「五、注册和登录模块」创建）和 `ResumeEntity.java`、`ResumeMapper.java`（在「六、简历上传和解析模块」创建），它们同样是数据库实体和 Mapper，只是各自在自己的模块里第一次出现。另外 `FollowUpExecution.java` 也在这个包下，但它不是表实体，而是追问 LiteFlow 链的上下文对象（承载原题、答案、评分、追问内容等），不要把它当成一张表去建。先创建上面这份清单里的文件即可。

实体负责描述一行数据库记录，Mapper 负责提供数据库访问能力。先用下面的最小结构建立文件，字段会在对应模块中补全：

```java
// 例：src/main/java/com/example/interview/infrastructure/mysql/PositionMapper.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

// Mapper 接口不写业务流程，只负责让应用层能够查询和保存 PositionEntity。
@Mapper
public interface PositionMapper
        extends BaseMapper<PositionEntity> {
}
```

每个实体和 Mapper 都遵循同一个对应关系：

```text
PositionEntity       -> PositionMapper
InterviewSessionEntity -> InterviewSessionMapper
QuestionEntity       -> QuestionMapper
AnswerEntity         -> AnswerEntityMapper
FollowUpEntity       -> FollowUpMapper
ReportEntity         -> ReportMapper
```

如果后面看到 `positions.selectById(...)`、`questions.insert(...)` 或 `answers.findJudge(...)`，先回到这里确认对应的实体和 Mapper 是否已经存在。`selectById`、`insert` 等是 `BaseMapper` 提供的通用方法；`findJudge` 这类业务方法则必须在自定义仓储或 Mapper 中另外声明和实现。

先建立其余实体文件。下面每个代码块都要单独保存到对应的文件中，不能把多个 `public class` 合并到一个文件：

```java
// src/main/java/com/example/interview/infrastructure/mysql/PositionEntity.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

@TableName("interview_position")
public class PositionEntity {
    private Long id;
    private String name;
    private String description;
    private String skills;

    // 使用 IDEA Generate 生成 getter 和 setter。
}
```

```java
// src/main/java/com/example/interview/infrastructure/mysql/InterviewSessionEntity.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@TableName("interview_session")
public class InterviewSessionEntity {
    private String id;
    private Long userId;
    private Long positionId;
    private Long resumeId;
    private String state;
    private BigDecimal matchScore;
    // 会话开始时间、结束时间和创建时间，状态机推进与报告统计都要读它们。
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;
}
```

```java
// src/main/java/com/example/interview/infrastructure/mysql/QuestionEntity.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

// 实体用 @Data 让 Lombok 生成 getter/setter，保持实体类简短。
@Data
@TableName("interview_question")
public class QuestionEntity {
    private String id;
    private String sessionId;
    private Long positionId;
    private String content;
    private String category;
    private Integer sequenceNo;
    // 题目状态：ACTIVE 可作答；预生成下一题时先写 PRE_GENERATED 占住题号，
    // 被取用时再 CAS 改成 ACTIVE。对应 interview_question.status 列。
    private String status;
}
```

```java
// src/main/java/com/example/interview/infrastructure/mysql/AnswerEntity.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("interview_answer")
public class AnswerEntity {
    private Long id;
    private String sessionId;
    private String questionId;
    private String requestId;
    private String answer;
    // 评分是小数（DECIMAL(5,2) 对应的 Double），不是整数：
    // 用 Integer 会把 78.5 这类分数截断，评分队列与报告端拿到的分数就不一致了。
    private Double score;
    private String feedback;
    private Long fencingToken;
    // 答案创建时间，补偿服务靠它判断评分是否超时（对应 created_at 列）。
    private LocalDateTime createdAt;
    // 追问作答标记：0=初次作答，1=追问作答。追问复用原题 questionId 提交，
    // 只能靠这一列区分同一道题的两次作答（对应 is_follow_up 列）。
    private Integer isFollowUp;
    // 评分补偿重投次数，超过上限就把 status 置为 JUDGE_FAILED（对应 retry_count 列）。
    private Integer retryCount;
    // 答案状态：PENDING_JUDGE / JUDGED / JUDGE_FAILED。
    private String status;
}
```

```java
// src/main/java/com/example/interview/infrastructure/mysql/FollowUpEntity.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

// 实体用 @Data 让 Lombok 统一生成 getter/setter；chain 只是纯内存字段，不在表里。
@Data
@TableName("interview_follow_up")
public class FollowUpEntity {
    private Long id;
    private String sessionId;
    private String questionId;
    private Integer score;
    private String level;
    // **追问实际执行的 LiteFlow 链名称，例如 followUpBasicChain。**
    // interview_follow_up 表里没有 chain 列，必须标 exist = false，
    // 否则 MyBatis-Plus 会把 chain 拼进 SQL，执行时报 Unknown column 'chain'。
    @TableField(exist = false)
    private String chain;
    private String content;
    // 记录创建时间，由数据库自动写入，用于排序和展示（对应 created_at 列）。
    private LocalDateTime createdAt;
}
```

```java
// src/main/java/com/example/interview/infrastructure/mysql/ReportEntity.java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

@TableName("interview_report")
public class ReportEntity {
    private Long id;
    private String sessionId;
    private String content;
    // 报告状态：GENERATING / COMPLETED / FAILED。异步生成后前端靠轮询它判断是否还要等。
    private String status;
    // 最后一次状态或内容更新时间，对应 interview_report.updated_at 列。
    private LocalDateTime updatedAt;
    // 报告的首次创建时间，由数据库自动写入（对应 created_at 列）。
    private LocalDateTime createdAt;
}
```

这些实体先把“表名、字段名和 Java 类型”固定下来。下一步再写 Mapper；如果实体字段还没有 getter/setter，`UserEntity::getUsername`、`entity.setState(...)` 这类代码会无法编译。

```java
// 以下 6 个接口分别保存为同名文件，不能放在同一个 Java 文件里。
@Mapper
public interface PositionMapper
        extends BaseMapper<PositionEntity> {
}

@Mapper
public interface InterviewSessionMapper
        extends BaseMapper<InterviewSessionEntity> {
}

@Mapper
public interface QuestionMapper
        extends BaseMapper<QuestionEntity> {
}

@Mapper
public interface AnswerEntityMapper
        extends BaseMapper<AnswerEntity> {
}

@Mapper
public interface FollowUpMapper
        extends BaseMapper<FollowUpEntity> {
}

@Mapper
public interface ReportMapper
        extends BaseMapper<ReportEntity> {
}
```

这里仍然是连续展示，实际必须拆成 `PositionMapper.java`、`InterviewSessionMapper.java` 等 6 个文件；每个文件都要补上 `package`、`BaseMapper` 和 `Mapper` 的 import。

### 4.2 依赖文件的局部创建规则

从这里开始，不再把后面所有实体、Mapper、DTO 和工具类一次性写在前面。写到某个业务步骤时，如果发现当前文件需要一个还没有教过的类，就在这个步骤前先补齐它。

```text
当前业务步骤
    -> 找出当前文件直接用到的类
    -> 说明这个类为什么必须先存在
    -> 在当前步骤前创建并讲解它
    -> 回到原文件继续编写
```

每个文件都要说清楚路径、职责、属性、构造器和方法。只在文件清单中出现名字，不算这个文件已经创建。

从后面的每一个步骤开始，固定先写下面这张“待补充文件表”，再给代码：

```text
本步骤需要补充：
1. 完整文件路径：src/main/java/com/example/interview/...
2. 文件类型：实体、Mapper、DTO、工具类、应用服务或 Controller
3. 为什么当前步骤必须先有它
4. 创建完成后，哪个文件会注入或调用它
```

路径必须写到项目根目录下的完整位置。例如不能只写 `application/PositionView.java`，要写成 `src/main/java/com/example/interview/application/PositionView.java`。如果是前端文件，就写 `frontend/src/main.jsx`；如果是资源文件，就写 `src/main/resources/...`。下面再次引用已经创建过的文件时，也要同时写完整路径，不能只写一个类名。

除非这个类型明确只服务于某个类、并且确实应该是内部类型，否则实体、DTO、命令对象、响应对象、Mapper、服务和配置类都必须创建成独立文件。不能为了让示例看起来暂时不报错，就把多个 `public` 类型塞进一个文件，或者先写一个之后不再补全的空壳。

## 五、注册和登录模块

### 模块主要目的

建立用户身份体系，让系统能够注册用户、验证密码、签发 JWT，并在后续请求中识别当前用户。

### 模块整体流程

```text
注册 -> 校验并加密密码 -> 保存用户
登录 -> 校验密码 -> 生成 JWT
请求 -> 携带 JWT -> 后端校验 -> 获取当前用户
```

注册和登录放在一个大模块中，因为它们共同使用用户表、密码加密和 JWT。开发顺序如下：

```text
登录 1：准备用户数据访问层
登录 2：准备密码加密和 JWT
登录 3：编写注册接口
登录 4：编写登录接口
登录 5：编写 JWT 校验
登录 6：前端保存 Token 并携带请求
```

### 登录 1：准备用户数据访问层

**本小节目的：** 建立用户数据的持久化能力，为注册和登录提供统一的用户查询、保存入口。

**本小节流程：** 设计用户表 -> 创建实体 -> 创建 Mapper -> 配置字段映射 -> 编译验证。

这一步要同时创建：

```text
src/main/java/com/example/interview/infrastructure/mysql/UserEntity.java
src/main/java/com/example/interview/infrastructure/mysql/UserMapper.java
```

先看数据库表：


app_user 表：先定义用户主键、唯一用户名、密码哈希和展示名称，后面的 `UserEntity` 与 `UserMapper` 都以这些字段为准。

```sql
CREATE TABLE IF NOT EXISTS app_user (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;
```

`UserEntity` 只描述一行用户数据：


UserEntity：这里只负责把表的一行记录映射成 Java 对象，不在实体类里放注册或登录逻辑。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

// 把这个 Java 类映射到 MySQL 的 app_user 表。
@TableName("app_user")
public class UserEntity {
    // 数据库主键或对象唯一标识；实体入库后通常由数据库生成。
    private Long id;
    // 登录用户名；注册时要求唯一，登录时用它查询用户。
    private String username;
    // 密码哈希值；只保存 BCrypt 结果，绝不保存明文密码。
    private String passwordHash;
    // 用户展示名称，只用于页面显示，不参与身份校验。
    private String displayName;

    // MyBatis-Plus 和业务代码通过 getter 读取数据库字段。
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }
}
```

创建文件时要注意：

```text
右键 infrastructure/mysql
    -> New
    -> Java Class
    -> UserEntity
```

先确认包名正确，再添加 `@TableName("app_user")`，最后按照数据库字段添加 Java 字段。`password_hash` 对应 `passwordHash`，MyBatis-Plus 会进行驼峰转换。

Mapper 文件：


UserMapper：这是数据访问接口，继承通用 Mapper 后即可直接使用基础查询和写入方法。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

// @Mapper 让 MyBatis 发现这个接口；BaseMapper 提供通用 CRUD 方法。
@Mapper
public interface UserMapper extends BaseMapper<UserEntity> {
}
```

`BaseMapper<UserEntity>` 已经提供 `selectOne`、`insert`、`selectById` 等基础方法，不需要重复手写。

两个文件完成后的连接关系：

```text
UserEntity
    -> 描述 app_user 表的一条记录

UserMapper
    -> 提供访问 app_user 表的能力

UserController
    -> 后续通过 UserMapper 查询和保存用户
```

### 登录 2：准备密码加密和 JWT

**本小节目的：** 建立密码不可逆存储和 Token 签发能力，为注册、登录和鉴权提供安全基础。

**本小节流程：** 配置 BCrypt -> 配置 JWT 密钥和有效期 -> 编写 Token 工具 -> 注册 Bean -> 编译验证。

这一步创建：

```text
src/main/java/com/example/interview/config/SecurityCryptoConfig.java
src/main/java/com/example/interview/config/JwtTokenService.java
```

先创建密码编码器：


SecurityCryptoConfig：这是 Spring 配置类，通过 Bean 把 BCrypt 编码器注册给注册和登录接口注入使用。

```java
package com.example.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

// @Configuration 声明配置类；@Bean 把对象交给 Spring 容器管理。
@Configuration
public class SecurityCryptoConfig
{
    @Bean
    // 创建并返回密码编码器 Bean，注册和登录都会复用这个对象。
    public PasswordEncoder passwordEncoder()
    {
        return new BCryptPasswordEncoder();
    }
}
```

注册时使用：


encode：注册流程调用它，把明文密码转换成不可逆哈希；数据库只保存返回值，不保存原密码。

```java
// 注册时只保存不可逆哈希，不保存明文密码。
encoder.encode(request.password())
```

登录时使用：


matches：登录流程把用户输入的原密码和数据库哈希交给 BCrypt 比较，返回布尔值，不会还原出原密码。

```java
// BCrypt 不能解密，matches 会用原密码和数据库哈希完成校验。
encoder.matches(
        request.password(),
        user.getPasswordHash()
)
```

不要自己解密密码，因为 BCrypt 是单向哈希。

接着创建 JWT 服务：


JwtTokenService：这个服务集中负责密钥校验、Token 签发和 Token 解析，避免鉴权逻辑散落在 Controller 中。

```java
package com.example.interview.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

// @Service 让 JWT 服务可以被 Controller 和 Filter 注入。
@Service
public class JwtTokenService {

    // 用于签发和验证 JWT 的 HMAC 密钥，服务重启后必须保持来源一致。
    private final SecretKey key;

    // 读取配置中的 JWT 密钥，并在服务创建阶段校验长度和格式。
    public JwtTokenService(
            @Value("${interview.jwt-secret:}") String secret
    ) {
        // 密钥太短会降低 HMAC 签名的安全性，配置错误时直接阻止服务启动。
        if (secret == null || secret.length() < 32) {
            throw new IllegalStateException(
                    "INTERVIEW_JWT_SECRET must be at least 32 characters"
            );
        }

        // JWT 的签发和解析必须使用同一把密钥。
        key = Keys.hmacShaKeyFor(
                secret.getBytes(StandardCharsets.UTF_8)
        );
    }

    // 根据用户 ID 和用户名签发 JWT，并设置签发时间和过期时间。
    public String issue(Long userId, String username) {
        return Jwts.builder()
                // subject 保存主要身份；业务字段用 claim 保存。
                .subject(username)
                .claim("userId", String.valueOf(userId))
                .issuedAt(new Date())
                // 当前示例把 Token 有效期设置为 24 小时。
                .expiration(new Date(
                        System.currentTimeMillis()
                                + 86400000
                ))
                // 签名让服务端能够识别 Token 是否被篡改。
                .signWith(key)
                .compact();
    }

    // 解析并验证 JWT，验证成功后取出 Token 中保存的用户名。
    public String username(String token) {
        return Jwts.parser()
                // verifyWith 会校验签名，过期或篡改都会抛异常。
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    // **解析并验证 JWT 后读取 userId，报告权限校验不得信任前端传入的用户 ID。**
    public Long userId(String token) {
        // 解析 JWT 的 userId 声明，与 issue() 中 .claim("userId", ...) 对应。
        String raw = Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .get("userId", String.class);
        return raw != null ? Long.parseLong(raw) : null;
    }
}
```

JWT 中保存用户 ID 和用户名，过期时间当前设置为 24 小时。注意 `userId()` 的返回类型是可空的 `Long`：Token 里没有这个声明时返回 `null`，由后面的过滤器写入请求属性，再由 `JwtCurrentUserIdProvider` 判断属性是否合法。完成后，注册和登录接口就具备了生成 Token 的能力。

### 登录 3：编写注册接口

**本小节目的：** 提供创建用户的 HTTP 接口，并在保存前完成参数校验、重复检查和密码加密。

**本小节流程：** 接收注册参数 -> 校验参数 -> 查询用户名是否存在 -> 加密密码 -> 保存用户 -> 返回结果。

注册步骤需要创建或完善以下真实文件：

```text
src/main/java/com/example/interview/application/RegisterRequest.java
src/main/java/com/example/interview/application/LoginRequest.java
src/main/java/com/example/interview/application/AuthResponse.java
src/main/java/com/example/interview/application/UserController.java
```

在写 `UserController.java` 之前，先创建本步骤真正需要的三个独立文件。它们不是 Controller 内部的临时类型，后续登录接口、测试代码和其他应用服务都可以直接复用。

### 文件 1：注册请求对象

保存位置：

```text
src/main/java/com/example/interview/application/RegisterRequest.java
```

这个文件只描述注册接口接收的 JSON 字段。`@NotBlank` 会在进入 `register` 方法前校验空值。

```java
package com.example.interview.application;

import jakarta.validation.constraints.NotBlank;

// 注册接口接收的请求对象；@NotBlank 会在方法执行前拦截空值。
public record RegisterRequest(
        // 用户登录名，后面会用它检查数据库中的唯一性。
        @NotBlank String username,
        // 原始密码只交给 PasswordEncoder，不直接入库。
        @NotBlank String password,
        // 页面展示用昵称，不参与密码认证。
        @NotBlank String displayName
) {
}
```

### 文件 2：登录请求对象

保存位置：

```text
src/main/java/com/example/interview/application/LoginRequest.java
```

这个文件只描述登录接口的输入。登录步骤会用 `username` 查询用户，再用 `password` 和数据库哈希比较。

```java
package com.example.interview.application;

import jakarta.validation.constraints.NotBlank;

// 登录接口接收的请求对象；登录步骤会按 username 查询用户。
public record LoginRequest(
        // 登录时使用的用户名。
        @NotBlank String username,
        // 用于和数据库密码哈希比较的原密码。
        @NotBlank String password
) {
}
```

### 文件 3：认证响应对象

保存位置：

```text
src/main/java/com/example/interview/application/AuthResponse.java
```

注册和登录都返回这个对象，保证前端只需要处理一种成功响应格式。

```java
package com.example.interview.application;

// 注册和登录共用的响应对象，避免前端处理两种返回格式。
public record AuthResponse(
        // 服务端签发的 JWT，前端后续请求会放入 Authorization。
        String token,
        // 当前用户 ID，用于关联后续业务数据。
        String userId,
        // 当前用户登录名。
        String username,
        // 当前用户展示名称。
        String displayName
) {
}
```

先写 Controller 依赖：


UserController：先把数据访问、密码编码器和 Token 服务作为构造器依赖注入，后面的接口方法只组合这些能力。

```java
@RestController
@RequestMapping("/api/auth")
public class UserController {

    // 用户数据访问接口，负责查询和保存 UserEntity。
    private final UserMapper users;
    // 密码编码器依赖，注册时哈希密码，登录时比较密码。
    private final PasswordEncoder encoder;
    // JWT 服务依赖，负责签发 Token 和解析 Token 身份。
    private final JwtTokenService tokens;

    // 接收用户模块的三个协作者，Controller 本身只负责编排请求流程。
    public UserController(
            UserMapper users,
            PasswordEncoder encoder,
            JwtTokenService tokens
    ) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
    }
}
```

注册接口：


register：这个方法按“校验用户名 -> 加密密码 -> 保存用户 -> 签发 Token”的顺序完成注册。

```java
@PostMapping("/register")
// 处理注册请求：校验用户名、加密密码、保存用户并返回登录 Token。
public AuthResponse register(
        // @Valid 触发参数校验；@RequestBody 把 JSON 转成 RegisterRequest。
        @Valid @RequestBody RegisterRequest request
) {
    var count = users.selectCount(
            // lambdaQuery 用方法引用绑定实体字段，避免手写字段名。
            Wrappers.<UserEntity>lambdaQuery()
                    .eq(
                            UserEntity::getUsername,
                            request.username()
                    )
    );

    if (count > 0) {
        throw new IllegalStateException(
                "username already exists"
        );
    }

    var user = new UserEntity();
    user.setUsername(request.username());
    user.setDisplayName(request.displayName());
    user.setPasswordHash(
            encoder.encode(request.password())
    );
    users.insert(user);

    return new AuthResponse(
            tokens.issue(user.getId(), user.getUsername()),
            String.valueOf(user.getId()),
            user.getUsername(),
            user.getDisplayName()
    );
}
```

注册流程是：

```text
查重
    -> 加密密码
    -> insert
    -> 生成 Token
    -> 返回用户信息
```

### 登录 4：编写登录接口

**本小节目的：** 校验用户身份并返回 JWT，让前端能够进入需要登录的业务流程。

**本小节流程：** 接收登录参数 -> 查询用户 -> 校验密码 -> 生成 JWT -> 返回 Token 和用户信息。

在 `src/main/java/com/example/interview/application/UserController.java` 中增加登录方法。它会直接使用前一步创建的：

```text
src/main/java/com/example/interview/application/LoginRequest.java
src/main/java/com/example/interview/application/AuthResponse.java
```

这里不再重新声明请求对象，只实现登录查询和密码校验。


login：这是 `UserController.login` 的核心查询与认证逻辑，先按用户名取用户，再用哈希校验密码，成功后统一组装认证响应。

```java
@PostMapping("/login")
// 处理登录请求：查询用户、比较密码哈希，成功后返回新的 JWT。
public AuthResponse login(
        // Spring 会先把 JSON 绑定到 LoginRequest，再执行 @NotBlank 校验。
        @Valid @RequestBody LoginRequest request
) {
    var user = users.selectOne(
            Wrappers.<UserEntity>lambdaQuery()
                    .eq(
                            UserEntity::getUsername,
                            request.username()
                    )
    );

    if (user == null
            || !encoder.matches(
                    request.password(),
                    user.getPasswordHash()
            )) {
        throw new IllegalArgumentException(
                "invalid credentials"
        );
    }

    return new AuthResponse(
            tokens.issue(user.getId(), user.getUsername()),
            String.valueOf(user.getId()),
            user.getUsername(),
            user.getDisplayName()
    );
}
```

登录时数据库只返回密码哈希，`matches` 内部完成比较。`LoginRequest.java` 和 `AuthResponse.java` 都是独立文件，Controller 这里只负责调用它们。

以上片段合并后的完整文件如下：

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.config.JwtTokenService;
import com.example.interview.infrastructure.mysql.UserEntity;
import com.example.interview.infrastructure.mysql.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class UserController {

    // 用户数据访问接口，负责查询和保存 UserEntity。
    private final UserMapper users;
    // 密码编码器依赖，注册时哈希密码，登录时比较密码。
    private final PasswordEncoder encoder;
    // JWT 服务依赖，负责签发 Token 和解析 Token 身份。
    private final JwtTokenService tokens;

    // 接收用户模块的三个协作者，Controller 本身只负责编排请求流程。
    public UserController(
            UserMapper users,
            PasswordEncoder encoder,
            JwtTokenService tokens
    ) {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
    }

    @PostMapping("/register")
    // 处理注册请求：校验用户名、加密密码、保存用户并返回登录 Token。
    public AuthResponse register(
            @Valid @RequestBody RegisterRequest request
    ) {
        var count = users.selectCount(
                Wrappers.<UserEntity>lambdaQuery()
                        .eq(
                                UserEntity::getUsername,
                                request.username()
                        )
        );

        if (count > 0) {
            throw new IllegalStateException(
                    "username already exists"
            );
        }

        var user = new UserEntity();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(
                encoder.encode(request.password())
        );
        users.insert(user);

        return new AuthResponse(
                tokens.issue(user.getId(), user.getUsername()),
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getDisplayName()
        );
    }

    @PostMapping("/login")
    // 处理登录请求：查询用户、比较密码哈希，成功后返回新的 JWT。
    public AuthResponse login(
            @Valid @RequestBody LoginRequest request
    ) {
        var user = users.selectOne(
                Wrappers.<UserEntity>lambdaQuery()
                        .eq(
                                UserEntity::getUsername,
                                request.username()
                        )
        );

        if (user == null
                || !encoder.matches(
                        request.password(),
                        user.getPasswordHash()
                )) {
            throw new IllegalArgumentException(
                    "invalid credentials"
            );
        }

        return new AuthResponse(
                tokens.issue(user.getId(), user.getUsername()),
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getDisplayName()
        );
    }

}
```

### 登录 5：编写 JWT 校验

**本小节目的：** 保护需要登录的接口，阻止无效 Token 或过期 Token 访问用户业务数据。

**本小节流程：** 读取请求头 -> 解析 JWT -> 校验签名和有效期 -> 写入当前用户上下文 -> 放行或返回未授权。

这一步创建：

```text
src/main/java/com/example/interview/config/JwtAuthenticationFilter.java
src/main/java/com/example/interview/config/SecurityConfig.java
src/main/java/com/example/interview/application/CurrentUserIdProvider.java
src/main/java/com/example/interview/application/JwtCurrentUserIdProvider.java
```

进入这一步前，`JwtTokenService.java` 已经在登录 2 中完成。这几个文件各负责一件事：`JwtAuthenticationFilter` 读取并验证每次请求的 Token，`SecurityConfig` 把过滤器注册到 `/api/*` 的请求链中，`CurrentUserIdProvider` / `JwtCurrentUserIdProvider` 则把过滤器写进请求的身份读成“当前登录用户 ID”。过滤器不直接访问数据库，只依赖已经存在的 Token 服务。

Filter：


下面先看过滤器本身。它位于 Controller 之前，读取 `Authorization: Bearer ...`，解析成功后才把请求交给后续链路。

```java
package com.example.interview.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// OncePerRequestFilter 保证一次 HTTP 请求只执行一次过滤逻辑。
public class JwtAuthenticationFilter
        extends OncePerRequestFilter {

    // JWT 服务依赖，负责签发 Token 和解析 Token 身份。
    private final JwtTokenService tokens;

    // 过滤器只依赖 Token 解析服务，不直接访问数据库。
    public JwtAuthenticationFilter(
            JwtTokenService tokens
    ) {
        this.tokens = tokens;
    }

    @Override
    // 在 Controller 前检查 Authorization 头；Token 有效则放行，无效则返回 401。
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {
        // 每个请求先从 Authorization 头读取 Bearer Token。
        var header =
                request.getHeader("Authorization");

        if (header != null
                && header.startsWith("Bearer ")) {
            try {
                // 去掉 "Bearer " 前缀后才是 JWT 本体。
                var username = tokens.username(
                        header.substring(7)
                );
                // 把身份放入当前请求，后续 Controller 可以读取。
                request.setAttribute(
                        "userName",
                        username
                );
                request.setAttribute(
                        "userId",
                        tokens.userId(
                                header.substring(7)
                        )
                );
            } catch (Exception ignored) {
                // Token 无法验证时不能继续访问受保护接口。
                response.setStatus(401);
                return;
            }
        }

        // 校验完成后交给下一个过滤器或 Controller。
        chain.doFilter(request, response);
    }
}
```

注册 Filter：


过滤器写好后，再用配置类把它注册到 Spring 的 Servlet 过滤器链；否则这个 Java 类虽然存在，运行时也不会自动执行。

```java
package com.example.interview.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

@Configuration
public class SecurityConfig {

    @Bean
    public FilterRegistrationBean<
            JwtAuthenticationFilter
            > jwtFilter(
            JwtTokenService tokens
    ) {
        var bean =
                // FilterRegistrationBean 把自定义 Filter 注册到 Servlet 过滤器链。
                new FilterRegistrationBean<>(
                        new JwtAuthenticationFilter(tokens)
                );
        bean.addUrlPatterns("/api/*");
        bean.setOrder(1);
        return bean;
    }
}
```

之后所有 `/api/*` 请求都会先经过 Token 校验。

过滤器只把身份写进“这一次请求”的属性里，业务代码还得有个统一入口把它读出来。所以这一步还要创建两个文件：

```text
src/main/java/com/example/interview/application/CurrentUserIdProvider.java
src/main/java/com/example/interview/application/JwtCurrentUserIdProvider.java
```

先看接口：


CurrentUserIdProvider：这是“当前登录用户 ID”的抽象。报告接口只关心“现在是谁”，不该知道这个 ID 是 JWT 里带的还是别的认证方式给的；抽成接口后，单元测试可以塞一个假实现，不必造 HTTP 请求。

```java
package com.example.interview.application;

/**
 * 当前登录用户 ID 的提供者。
 *
 * <p>为什么抽成接口而不是直接在 Controller 里读请求属性：报告接口只关心
 * "现在是谁"，不该知道 ID 是 JWT 里的还是别的认证方式带的；抽成接口后
 * 单测可以塞一个假实现，不必造 HTTP 请求。</p>
 */
public interface CurrentUserIdProvider {
    long currentUserId();
}
```

再看基于 JWT 的实现：


JwtCurrentUserIdProvider：它从当前线程绑定的请求里，取出过滤器验签后写入的 `userId` 属性。为什么不直接信前端传的 ID：请求体或查询参数里的用户 ID 是能随便伪造的，谁都能拿别人的 ID 去查报告，所以这里只认 `JwtAuthenticationFilter` 验签后写进 request 的属性，前端传什么一概不看。

```java
package com.example.interview.application;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

// ============================================================
// 当前登录用户 ID 提供者（基于 JWT）
// ============================================================
// 职责：从当前线程绑定的 HTTP 请求里，取出认证过滤器验签后写入的 userId。
//
// 为什么不让 Controller 直接信前端传的 userId：
//   请求体或查询参数里的用户 ID 是能随便伪造的，谁都能拿别人的 ID 去查报告。
//   所以这里只认 JwtAuthenticationFilter 验签后写进 request 的 "userId" 属性，
//   前端传什么一概不看，从根上堵住越权。
// ============================================================
@Component
public class JwtCurrentUserIdProvider
        implements CurrentUserIdProvider {

    @Override
    public long currentUserId() {
        // 拿当前线程绑定的请求上下文，Spring 用 ThreadLocal 保存它。
        RequestAttributes attributes =
                RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servlet)) {
            // 不在 HTTP 请求线程里：直接失败，绝不返回默认用户。
            throw new IllegalStateException(
                    "no HTTP request is bound to current thread"
            );
        }
        HttpServletRequest request =
                servlet.getRequest();
        // 读过滤器验签后写入的属性，而不是请求参数。
        Object value = request.getAttribute("userId");
        // 属性必须是数字：缺失说明 JWT 没验过或过滤器没接线，同样直接失败。
        if (!(value instanceof Number number)) {
            throw new IllegalStateException(
                    "validated JWT userId is missing"
            );
        }
        return number.longValue();
    }
}
```

拿不到请求、或请求属性里没有合法的 `userId` 时，这个方法都直接抛 `IllegalStateException`，绝不返回默认值——否则就变成“匿名用户能看数据”的越权漏洞。报告接口 `ReportController` 就是用它拿到 `currentUser.currentUserId()`，再和会话归属用户比对，不相等直接抛 `AccessDeniedException`。全局异常处理器 `ApiExceptionHandler` 把 `AccessDeniedException` 映射成 403、把 `IllegalArgumentException` 映射成 400；注意 `IllegalStateException` 没有被专门接管，会落成 HTTP 500。

### 登录 6：前端保存 Token 并携带请求

**本小节目的：** 让前端保存登录状态，并自动为后续 API 请求附加 JWT。

**本小节流程：** 登录成功保存 Token -> 封装请求方法 -> 自动设置 Authorization -> 处理过期状态 -> 恢复登录状态。

文件：

```text
frontend/src/main.jsx
```

注册和登录共用同一个入口 `auth`：根据当前是“注册”还是“登录”决定请求路径和请求体，成功后就地保存登录状态。


auth：`register` 为真时提交整个表单（用户名、密码、显示名称），否则只提交用户名和密码；两个接口都返回同一种 `AuthResponse`，所以保存逻辑只写一份。

```javascript
const auth = () => run(async () => {
  // 注册和登录路径不同，请求体也不同。
  const endpoint = register ? '/api/auth/register' : '/api/auth/login';
  const payload = register
    ? form
    : { username: form.username, password: form.password };
  const value = await request(endpoint, {
    method: 'POST',
    body: JSON.stringify(payload)
  });
  // localStorage 让刷新页面后仍能取回登录 Token。
  localStorage.setItem('interview_token', value.token);
  localStorage.setItem('interview_user', JSON.stringify(value));
  // setUser 更新内存状态，页面立即从登录表单切换到业务页面。
  setUser(value);
});
```

刷新页面后，靠 `restoreStoredUser` 把用户信息读回来：


restoreStoredUser：`useState(restoreStoredUser)` 只在组件首次渲染时执行一次；用户信息以 JSON 存在 `interview_user` 里，解析失败就当作未登录，避免脏数据把页面弄崩。

```javascript
const restoreStoredUser = () => {
  try {
    const raw = localStorage.getItem('interview_user');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
};
```

统一请求方法：


request：这个请求封装自动附加 Bearer Token；收到 401 时清理本地登录信息并刷新页面。它同时兼顾普通 JSON 请求和表单上传，所以用 `isForm` 区分是否设置 `Content-Type`。

```javascript
const API_ROOT = import.meta.env.VITE_API_ROOT || '';

const request = async (url, options = {}) => {
  const token = localStorage.getItem('interview_token');
  // FormData 上传时不能手写 Content-Type，交给浏览器自己带 boundary。
  const isForm = options.body instanceof FormData;
  const response = await fetch(`${API_ROOT}${url}`, {
    ...options,
    headers: {
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      // 保留调用方传入的 headers，再补充统一的 Authorization。
      ...(options.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  });
  const body = await response.text();
  if (!response.ok) {
    if (response.status === 401) {
      localStorage.removeItem('interview_token');
      localStorage.removeItem('interview_user');
      window.location.reload();
    }
    // 后端错误统一返回 {"message": ...}，取不到就退回原始响应文本。
    let message = body;
    try {
      message = JSON.parse(body).message || body;
    } catch {
      // Keep the raw response when the server did not return JSON.
    }
    throw new Error(message || `HTTP ${response.status}`);
  }
  return body ? JSON.parse(body) : null;
};
```

到这里注册和登录闭环完成：

```text
注册
    -> 数据库保存哈希
    -> 返回 JWT

登录
    -> 查询用户
    -> 校验密码
    -> 返回 JWT

后续请求
    -> 前端携带 JWT
    -> Filter 校验 JWT
```

## 六、简历上传和解析模块

### 模块主要目的

接收用户简历文件，先解析为原始文本，再调用 AI 提取技术技能 JSON，给岗位匹配、AI 出题和报告生成提供简历上下文。

### 模块整体流程

```text
上传文件 -> 校验文件 -> 文件落盘 -> 解析正文
    -> AI 提取技术技能 -> 以 PARSED 状态入库
    -> 查询最近一份已解析简历
```

### 简历 1：创建简历表、实体和 Mapper

**本小节目的：** 建立简历记录的数据结构，保存文件位置、解析状态、原始解析文本和 AI 提取的技术技能 JSON。

**本小节流程：** 设计简历表 -> 创建实体 -> 增加技能字段 -> 创建 Mapper -> 配置状态字段 -> 编译验证。

这一部一起创建：

```text
docker/mysql/01-schema.sql 中的 interview_resume
src/main/java/com/example/interview/infrastructure/mysql/ResumeEntity.java
src/main/java/com/example/interview/infrastructure/mysql/ResumeMapper.java
```

数据表：


这一段先定义简历表的字段和状态，后面的实体、解析服务都会依赖这些列。

```sql
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
```

实体：


ResumeEntity：这是简历表的一行数据，字段依次承载归属用户、原始文件、解析文本、AI 技能 JSON 和处理状态。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

// 把一条 interview_resume 数据映射成 Java 对象。
@TableName("interview_resume")
public class ResumeEntity {
    // 数据库主键或对象唯一标识；实体入库后通常由数据库生成。
    private Long id;
    // 所属用户 ID，用来把当前数据与登录用户关联起来。
    private Long userId;
    // 用户上传文件的安全文件名，用于展示和记录原始名称。
    private String fileName;
    // 文件落盘后的服务器路径，解析服务从这里读取文件。
    private String storagePath;
    // 文件解析出的纯文本，供岗位匹配、AI 出题和报告使用。
    private String parsedContent;
    // AI 提取并校验后的技术技能 JSON 数组，供岗位匹配使用。
    private String extractedSkills;
    // 数据处理状态，例如 PARSING 表示解析中，PARSED 表示解析完成。
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getStoragePath() {
        return storagePath;
    }

    public void setStoragePath(String storagePath) {
        this.storagePath = storagePath;
    }

    public String getParsedContent() {
        return parsedContent;
    }

    public void setParsedContent(String parsedContent) {
        this.parsedContent = parsedContent;
    }

    public String getExtractedSkills() {
        return extractedSkills;
    }

    public void setExtractedSkills(String extractedSkills) {
        this.extractedSkills = extractedSkills;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

Mapper：


ResumeMapper：这是简历持久化入口，继承 `BaseMapper<ResumeEntity>` 后可以直接查询和写入简历记录。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResumeMapper
        extends BaseMapper<ResumeEntity> {
}
```

简历状态先只定义两种：

```text
PARSING
PARSED
```

后续开始面试时检查状态是否为 `PARSED`。

### 简历 2：创建简历解析服务

**本小节目的：** 把 PDF、Word 等文件内容统一解析成后续业务可以使用的纯文本。

**本小节流程：** 接收文件 -> 调用 Apache Tika -> 提取文本 -> 处理解析异常 -> 返回解析结果。

**开始本节前先确认：** `src/main/java/com/example/interview/infrastructure/mysql/ResumeEntity.java` 和 `src/main/java/com/example/interview/infrastructure/mysql/ResumeMapper.java` 已经写好；本节只新增解析服务，不要在服务里重新定义简历实体。

文件：

```text
src/main/java/com/example/interview/application/ResumeParserService.java
```


ResumeParserService：这个服务只做“文件 -> 纯文本”转换；空文本和解析异常都会在这里被拦截。

```java
package com.example.interview.application;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.nio.file.Path;

@Service
public class ResumeParserService {

    // Tika 是文档解析器，负责从 PDF、Word 等文件中提取文本。
    private final Tika tika = new Tika();

    // 读取简历文件并提取纯文本；没有可读内容或解析失败时抛出异常。
    public String parse(Path path) {
        try {
            var text = tika.parseToString(path);
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException(
                        "resume contains no readable text"
                );
            }
            return text.trim();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "简历解析失败",
                    e
            );
        }
    }
}
```

这一层只负责把文件变成文本，不负责用户归属和数据库入库。

### 简历 2.5：使用 AI 提取技术技能

**本小节目的：** 接收 `ResumeParserService.parse(path)` 提取出的简历原文，调用 AI 只提取编程语言、后端框架、数据库、中间件和云原生工具。

**本小节流程：** 组装系统提示词和简历原文 -> 调用统一的 `ChatClient` -> 校验返回值是 JSON 字符串数组 -> 去重后保存。

文件：

```text
src/main/java/com/example/interview/application/ResumeAiExtractionService.java
```

```java
package com.example.interview.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class ResumeAiExtractionService {

    // 系统角色限定为资深技术招聘专家，让 AI 按招聘场景识别技术技能。
    private static final String SYSTEM_PROMPT =
            "你是一位资深技术招聘专家。";

    // **简历技能提取与其他 AI 能力统一通过 ChatClient 调用。**
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public ResumeAiExtractionService(
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    public String extractSkills(String parsedResumeText) {
        if (parsedResumeText == null || parsedResumeText.isBlank()) {
            throw new IllegalArgumentException(
                    "parsed resume text is empty"
            );
        }

        String raw = chatClient.prompt()
                .system(SYSTEM_PROMPT
                        + "\n只提取编程语言、后端框架、数据库、中间件和云原生工具。"
                        + "\n只返回 JSON 字符串数组，不要返回解释。")
                .user("""
                        请从以下候选人的简历中提取技术技能。
                        简历文本：
                        %s
                        """.formatted(parsedResumeText))
                .call()
                .content();
        if (raw == null || raw.isBlank()) {
            throw new IllegalStateException(
                    "AI returned empty resume skills"
            );
        }

        // 入库前校验 JSON，防止 AI 返回解释文字或 Markdown。
        return normalizeSkills(
                raw
        );
    }

    private String normalizeSkills(String content) {
        try {
            JsonNode root = objectMapper.readTree(content.trim());
            if (!root.isArray()) {
                throw new IllegalArgumentException(
                        "AI resume skills must be a JSON array"
                );
            }

            List<String> skills = new ArrayList<>();
            for (JsonNode skill : root) {
                if (!skill.isTextual() || skill.asText().isBlank()) {
                    throw new IllegalArgumentException(
                            "AI resume skills must contain only non-empty strings"
                    );
                }
                String value = skill.asText().trim();
                // 去重，避免同一技能重复参与匹配。
                if (!skills.contains(value)) {
                    skills.add(value);
                }
            }
            return objectMapper.writeValueAsString(skills);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "AI resume skills must be a valid JSON array",
                    e
            );
        }
    }
}
```

### 简历 3：编写上传接口

**本小节目的：** 提供简历上传入口，并把文件保存和文本解析串成一个业务流程。

**本小节流程：** 接收 Multipart 文件 -> 校验文件 -> 文件落盘 -> 解析正文 -> AI 提取技能 -> 以 PARSED 状态入库 -> 返回结果。

**开始本节前先确认：** `src/main/java/com/example/interview/infrastructure/mysql/ResumeEntity.java`、`src/main/java/com/example/interview/infrastructure/mysql/ResumeMapper.java`、`src/main/java/com/example/interview/application/ResumeParserService.java` 和 `src/main/java/com/example/interview/application/ResumeAiExtractionService.java` 都已经存在。

上传接口返回前端需要的 `ResumeView`，所以先在当前步骤补上这个响应对象。它只负责传输简历信息，不负责读取文件或更新数据库。

```text
src/main/java/com/example/interview/application/ResumeView.java
```

```java
package com.example.interview.application;

// 简历接口返回的只读对象，避免把 ResumeEntity 直接暴露给前端。
public record ResumeView(
        // 简历记录 ID。
        String id,
        // 简历所属用户 ID。
        String userId,
        // 用户上传时的文件名。
        String fileName,
        // 当前解析状态，例如 PARSING 或 PARSED。
        String status,
        // 解析出的正文，供准备页面和匹配模块使用。
        String parsedContent,
        // **AI 提取出的技术技能 JSON，供岗位匹配和前端展示使用。**
        String extractedSkills
) {
}
```

文件：

```text
src/main/java/com/example/interview/application/ResumeController.java
```

接口声明：


ResumeController：这里先声明上传接口需要的三个协作者和文件根目录，后面的 `upload`、`latest` 方法都从这些属性取能力。

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController {

    // 简历数据访问接口，负责读取解析后的简历。
    private final ResumeMapper resumes;
    // 简历解析服务依赖，把磁盘文件转换成纯文本。
    private final ResumeParserService parser;
    // AI 技能提取服务，把 parser.parse(path) 得到的原文转换成技能 JSON。
    private final ResumeAiExtractionService aiExtraction;
    // 上传文件保存目录；解析完成后数据库只记录这个路径。
    private final Path root = Path.of("data/resumes");

    public ResumeController(
            ResumeMapper resumes,
            ResumeParserService parser,
            ResumeAiExtractionService aiExtraction
    ) {
        this.resumes = resumes;
        this.parser = parser;
        this.aiExtraction = aiExtraction;
    }

    @PostMapping(
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    // 保存文件、解析正文、提取技能并把最终状态写入数据库。
    public ResumeView upload(
            @RequestParam("userId") long userId,
            @RequestPart("file") MultipartFile file
    ) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException(
                    "resume file is empty"
            );
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            throw new IllegalArgumentException(
                    "resume exceeds 10MB"
            );
        }

        Files.createDirectories(root);
        String original = file.getOriginalFilename() == null
                ? "resume"
                : Path.of(file.getOriginalFilename())
                        .getFileName()
                        .toString();
        Path path = root.resolve(
                System.currentTimeMillis() + "-" + original
        );

        try {
            file.transferTo(path);

            ResumeEntity entity = new ResumeEntity();
            entity.setUserId(userId);
            entity.setFileName(original);
            entity.setStoragePath(path.toString());
            entity.setStatus("PARSING");

            // 先保留 Tika 提取出的原始简历文本。
            String parsedResumeText = parser.parse(path);
            entity.setParsedContent(parsedResumeText);
            // 再把同一份原文交给 AI，只保存技术技能 JSON。
            entity.setExtractedSkills(
                    aiExtraction.extractSkills(parsedResumeText)
            );
            entity.setStatus("PARSED");
            resumes.insert(entity);

            return view(entity);
        } catch (Exception e) {
            Files.deleteIfExists(path);
            throw e;
        }
    }

    @GetMapping
    // 查询当前用户最新一份已经解析完成的简历。
    public ResponseEntity<ResumeView> latest(
            @RequestParam("userId") long userId
    ) {
        ResumeEntity entity = resumes.selectOne(
                Wrappers.<ResumeEntity>query()
                        .eq("user_id", userId)
                        .eq("status", "PARSED")
                        .isNotNull("parsed_content")
                        .ne("parsed_content", "")
                        .orderByDesc("id")
                        .last("limit 1")
        );

        return entity == null
                ? ResponseEntity.noContent().build()
                : ResponseEntity.ok(view(entity));
    }

    // 把数据库实体转换成接口响应对象。
    private ResumeView view(ResumeEntity entity) {
        return new ResumeView(
                String.valueOf(entity.getId()),
                String.valueOf(entity.getUserId()),
                entity.getFileName(),
                entity.getStatus(),
                entity.getParsedContent(),
                entity.getExtractedSkills()
        );
    }
}
```

文件名使用 `getFileName()` 去掉用户上传路径，文件大小限制避免过大文件拖垮解析器，失败时清理磁盘文件。上面的代码就是 `ResumeController.java` 的完整文件，不要再把 `upload()` 单独复制到类外。

### 简历 4：查询用户最近一份已解析简历

**本小节目的：** 为匹配和面试准备页面提供当前用户可用的最新简历。

**本小节流程：** 从查询参数取 userId -> 按用户和解析状态查询 -> 按时间倒序取一条 -> 返回简历文本和元数据。

`latest()` 已经和 `upload()` 合并在同一个 `ResumeController.java` 完整文件中。前端进入准备页面时调用 `GET /api/resumes?userId=...`，恢复用户最近一份简历。

### 简历 5：前端上传简历

**本小节目的：** 在准备面试页面完成文件选择、上传、解析状态展示和错误提示。

**本小节流程：** 选择文件 -> 组装 FormData -> 携带 Token 上传 -> 展示解析状态 -> 刷新简历数据。


uploadResume：这个方法负责选择文件、组装 FormData、调用上传接口，并把返回的简历写入页面状态。

```javascript
const uploadResume = file => run(async () => {
  if (!file) return;

  // FormData 同时承载普通字段和文件，字段名要和后端 upload 的
  // @RequestParam("userId")、@RequestPart("file") 对齐。
  const data = new FormData();
  data.append('userId', userId);
  data.append('file', file);

  // request 检测到 body 是 FormData 时不设置 Content-Type，
  // 交给浏览器补 boundary，并自动带上 Authorization 头。
  const value = await request('/api/resumes', {
    method: 'POST',
    body: data
  });

  setResume(normalizeResume(value));
  setNotice('简历解析完成，可以开始面试。');
});
```

`request` 内部遇到 FormData 不会手动设置 `Content-Type`，浏览器会自动添加 boundary，所以这里只传 `body` 即可。

## 七、岗位列表和简历匹配模块

### 模块主要目的

提供岗位选择能力，并将岗位要求与简历中的 AI 技能 JSON 进行比较，为面试会话和 AI 出题准备目标岗位上下文。

### 模块整体流程

```text
查询岗位列表 -> 用户选择岗位 -> 获取已解析简历
    -> 读取岗位技能和简历技能 JSON -> 集合匹配
    -> 计算匹配结果 -> 返回匹配信息
```

### 岗位 1：创建岗位表、实体和 Mapper

**本小节目的：** 建立岗位数据的持久化结构，保存岗位名称、描述和技能要求。

**本小节流程：** 设计岗位表 -> 创建实体 -> 创建 Mapper -> 准备初始化数据 -> 查询验证。

文件：

```text
docker/mysql/01-schema.sql 中的 interview_position
src/main/java/com/example/interview/infrastructure/mysql/PositionEntity.java
src/main/java/com/example/interview/infrastructure/mysql/PositionMapper.java
```

数据表：


这一段先定义岗位表的字段，技能要求用 JSON 列保存，后面的实体和匹配逻辑都会依赖这些列。

```sql
CREATE TABLE IF NOT EXISTS interview_position (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(128) NOT NULL,
    description TEXT NOT NULL,
    skills JSON NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB;
```

实体：


这里直接把 `PositionEntity.java` 写成可保存的完整文件。只展示几个字段而不补 getter/setter，会导致 `PositionController` 中的 `getName()`、`getSkills()` 无法编译。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

// 岗位表的一行记录，Controller 和匹配服务通过它读取岗位信息。
@TableName("interview_position")
public class PositionEntity {
    // 数据库主键或对象唯一标识。
    private Long id;
    // 岗位名称。
    private String name;
    // 岗位详细说明。
    private String description;
    // 岗位要求的技能集合，数据库中按 JSON 字符串保存。
    private String skills;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSkills() {
        return skills;
    }

    public void setSkills(String skills) {
        this.skills = skills;
    }
}
```

`PositionMapper.java` 也要单独创建，不能只在文件清单里写名字：

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

// BaseMapper 提供岗位的基础查询和保存能力。
@Mapper
public interface PositionMapper
        extends BaseMapper<PositionEntity> {
}
```

`skills` 在数据库中保存 JSON 数组，当前教学实现先用字符串接收，再转换成列表。

### 岗位 2：编写岗位查询接口

**本小节目的：** 向前端提供可选岗位列表，作为准备面试的第一项业务数据。

**本小节流程：** 查询岗位 -> 转换响应对象 -> 编写 Controller -> 返回岗位列表 -> 前端调用验证。

进入 Controller 前先补齐它直接返回的 `PositionView`。`PositionEntity` 是数据库对象，不能原样暴露给前端；`PositionView` 只保留页面需要的岗位字段，所以它属于本步骤新增的响应对象。

```text
src/main/java/com/example/interview/application/PositionView.java
```

```java
package com.example.interview.application;

// 岗位接口返回给前端的只读数据对象，不承载查询或匹配逻辑。
public record PositionView(
        // 岗位数据库 ID，前端选择岗位时提交它。
        Long id,
        // 岗位名称。
        String name,
        // 岗位详细描述。
        String description,
        // 岗位技能要求，当前版本仍按字符串返回。
        String skills
) {
}
```

匹配接口 `POST /api/positions/{id}/match` 还会返回 `MatchView`，这个响应对象同样只承载数据，不包含匹配逻辑：

```text
src/main/java/com/example/interview/application/MatchView.java
```

```java
package com.example.interview.application;

// 岗位匹配接口 /api/positions/{id}/match 返回的响应对象
public record MatchView(
        // 简历 ID
        Long resumeId,
        // 岗位 ID
        Long positionId,
        // 匹配分数（百分比，如 85.5）
        Double matchScore,
        // 匹配详情（例如："匹配技能：Java、Spring、MySQL"）
        String detail
) {
}
```

`PositionEntity`、`PositionMapper` 和这两个响应对象都准备好后，才开始写 `PositionController`；`list()` 使用的 `view(PositionEntity)` 辅助方法也放在同一个 Controller 中。

文件：

```text
src/main/java/com/example/interview/application/PositionController.java
```


PositionController：这个 Controller 持有岗位和简历两个数据入口，既能返回岗位列表，也能执行岗位与简历的基础匹配。

```java
package com.example.interview.application;

import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

@RestController
@RequestMapping("/api/positions")
public class PositionController {

    // 岗位数据访问接口，负责读取岗位及其技能要求。
    private final PositionMapper positions;
    // 简历数据访问接口，负责读取解析后的简历。
    private final ResumeMapper resumes;
    // Jackson 用于解析岗位和 AI 返回的 JSON 技能数组。
    private final ObjectMapper objectMapper;

    // Controller 创建时由 Spring 注入两个 Mapper。
    public PositionController(
            PositionMapper positions,
            ResumeMapper resumes,
            ObjectMapper objectMapper
    ) {
        this.positions = positions;
        this.resumes = resumes;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    // 查询岗位列表，并把数据库实体转换成前端需要的视图对象。
    public List<PositionView> list() {
        return positions.selectList(null)
                .stream()
                .map(this::view)
                .toList();
    }

    @GetMapping("/{id}")
    // 按岗位 ID 查询单个岗位，不存在时返回业务错误。
    public PositionView detail(
            @PathVariable("id") long id
    ) {
        var position =
                positions.selectById(id);
        if (position == null) {
            throw new IllegalArgumentException(
                    "position not found"
            );
        }
        return view(position);
    }

    // 把数据库实体转换成不暴露内部字段的前端响应对象。
    private PositionView view(PositionEntity position) {
        return new PositionView(
                position.getId(),
                position.getName(),
                position.getDescription(),
                position.getSkills()
        );
    }
    
    @PostMapping("/{id}/match")
    // 按岗位技能和 AI 提取出的简历技能计算匹配分数。
    public MatchView match(
            @PathVariable("id") Long id,
            @RequestParam("resumeId") Long resumeId
    ) {
        PositionEntity position = positions.selectById(id);
        ResumeEntity resume = resumes.selectById(resumeId);

        if (position == null || resume == null) {
            throw new IllegalArgumentException(
                    "position or resume not found"
            );
        }

        // 岗位技能和候选人技能都转换为列表；候选人技能来自数据库字段 extracted_skills。
        List<String> required = parseSkills(position.getSkills());
        Set<String> candidateSkills = new HashSet<>(
                parseSkills(resume.getExtractedSkills()).stream()
                        .map(this::normalize)
                        .toList()
        );

        // 只匹配技能集合，不再对整段简历正文执行 contains。
        List<String> matched = required.stream()
                .filter(skill -> candidateSkills.contains(normalize(skill)))
                .toList();

        // 匹配度 = 已匹配岗位技能数 / 岗位要求技能总数 * 100。
        double score = required.isEmpty()
                ? 0
                : Math.round(
                        matched.size() * 10000.0
                                / required.size()
                ) / 100.0;

        return new MatchView(
                resumeId,
                id,
                score,
                "匹配技能：" + String.join("、", matched)
        );
    }

    // 岗位技能和 AI 技能都按 JSON 数组解析，避免手动替换字符串导致误解析。
    private List<String> parseSkills(String skills) {
        if (skills == null || skills.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(skills);
            if (!root.isArray()) {
                throw new IllegalArgumentException(
                        "skills must be a JSON array"
                );
            }
            return StreamSupport.stream(
                            root.spliterator(),
                            false
                    )
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .distinct()
                    .toList();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "invalid skills JSON",
                    e
            );
        }
    }

    // 忽略大小写和首尾空格，避免 Java/java 被判定为不同技能。
    private String normalize(String skill) {
        return skill.trim().toLowerCase(Locale.ROOT);
    }
}
```

这里的匹配输入已经不是整段简历正文，而是上传时由 AI 提取并保存的 `extracted_skills` JSON 数组。岗位的 `skills` 字段也按 JSON 数组解析，最后使用忽略大小写的技能集合计算匹配度。整条匹配链路是纯代码的集合比对，不调用 AI；`resources/skills/resume-matching.md` 这个 Skill 已经在 `SkillRegistry` 中注册，但代码里没有 `skillPrompt("resume-matching")` 调用，该 Skill 已注册但当前未接入匹配链路。

## 八、创建面试会话模块

### 模块主要目的

创建一次面试的会话边界，统一管理面试状态、领域事件和快照；快照以 Redis 热缓存 + MongoDB 冷存储分层保存，并用版本号 CAS 原子更新，保证流程能按规则推进、并发下不丢状态并支持恢复。

### 模块整体流程

```text
选择岗位和简历 -> 创建 sessionId -> 保存会话
    -> 加载/初始化快照（Redis 热缓存 -> MongoDB 冷存储 -> INIT）
    -> 开始面试：提交 START 推进到 MATCHING
    -> 异步准备任务（注册心跳 -> 匹配 -> 出题 -> 注销心跳）
    -> 根据事件推进状态并用 CAS 原子写回快照
    -> 前端轮询 /state 感知进度，结束时触发表现分析
```

### 面试 1：创建会话表、实体和 Mapper <span style="color:green">✅</span>

**本小节目的：** 保存面试会话与用户、岗位、简历之间的关系，以及当前面试状态。

**本小节流程：** 设计会话表 -> 创建实体 -> 创建 Mapper -> 配置状态字段 -> 完成基础增删查。

文件：

```text
src/main/java/com/example/interview/infrastructure/mysql/InterviewSessionEntity.java
src/main/java/com/example/interview/infrastructure/mysql/InterviewSessionMapper.java
```

表结构：


这一段先保存面试会话的核心关联和生命周期字段，`state`、`started_at`、`ended_at` 会被后续接口更新。

```sql
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
```

实体文件不能只写字段片段，否则 `InterviewController` 中的 `setId()`、`setState()` 等调用没有实现。现在直接创建完整文件：

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

import java.math.BigDecimal;
import java.time.LocalDateTime;

// 一条面试会话记录，保存用户、岗位、简历和生命周期状态。
@TableName("interview_session")
public class InterviewSessionEntity {
    private String id;
    private Long userId;
    private Long positionId;
    private Long resumeId;
    private String state;
    private BigDecimal matchScore;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private LocalDateTime createdAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(LocalDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
```

Mapper 文件：

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewSessionMapper
        extends BaseMapper<InterviewSessionEntity> {
}
```

会话 ID 使用 UUID 字符串，便于前后端传递和跨服务关联。

### 面试 2：创建状态、事件和快照 <span style="color:green">✅</span>

**本小节目的：** 用明确的数据对象表达面试生命周期中的状态变化和恢复所需上下文。

**本小节流程：** 定义状态枚举 -> 定义领域事件 -> 设计快照对象 -> 约定状态转换输入输出。

这一步一起创建：

```text
src/main/java/com/example/interview/domain/InterviewState.java
src/main/java/com/example/interview/domain/InterviewEvent.java
src/main/java/com/example/interview/domain/InterviewSessionSnapshot.java
```

状态：


InterviewState：枚举只列出面试生命周期允许出现的状态，状态机后面会用它限制流转。

```java
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
```

事件：


InterviewEvent：事件表示推动状态变化的业务动作，例如提交答案、评分完成或面试超时。

```java
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
```

快照：


InterviewSessionSnapshot：快照保存某一时刻的状态、版本和已处理请求 ID，便于恢复状态并实现幂等。

```java
package com.example.interview.domain;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

// 创建不可变的面试会话快照，集中保存状态、版本和幂等信息。
public record InterviewSessionSnapshot(
        // 面试会话 ID，用来贯穿题目、答案、评分和报告。
        UUID sessionId,
        // 面试当前生命周期状态，状态机依据它决定允许的下一步。
        InterviewState state,
        // 快照版本号；每次状态变化递增，用于并发更新控制。
        long version,
        // 已处理请求 ID；客户端重试同一请求时可以识别为幂等操作。
        Set<String> processedRequestIds
) {
    // Set.copyOf 生成不可变集合，避免快照创建后被外部代码偷偷修改。
    public InterviewSessionSnapshot {
        processedRequestIds =
                Set.copyOf(processedRequestIds);
    }

    // 检查请求 ID 是否已经处理过，用于阻止重复推进同一个事件。
    public boolean hasProcessed(
            String requestId
    ) {
        return processedRequestIds.contains(
                requestId
        );
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
```

`version` 负责并发控制，`processedRequestIds` 负责请求幂等。

### 面试 3：创建状态机 <span style="color:green">✅</span>

**本小节目的：** 集中约束哪些事件可以在当前状态执行，避免面试流程被非法请求推进。

**本小节流程：** 定义状态转换 -> 编写状态机 -> 校验事件合法性 -> 返回新状态 -> 编写测试场景。

文件：

```text
src/main/java/com/example/interview/domain/InterviewSessionStateMachine.java
```

转移规则：

```text
INIT + START = MATCHING                                 初始 + 开始 = 匹配中
MATCHING + POSITION_MATCHED = QUESTIONING               匹配中 + 匹配完 = 出题中
QUESTIONING + QUESTION_READY = ANSWERING                出题中 + 题目好 = 答题中
ANSWERING + ANSWER_SUBMITTED = JUDGING                 答题中 + 提交答 = 评分中
JUDGING + JUDGE_DONE = ASKING                          评分中 + 评分完 = 追问中
ASKING + ASK_COMPLETED = QUESTIONING                   追问中 + 追问完 = 出题中
ANSWERING + TIMEOUT = JUDGING                          答题中 + 超时 = 评分中
任意状态 + END = ENDED                                  任意状态 + 结束 = 已结束
```

代码：


InterviewSessionStateMachine：这个类把状态转移规则集中保存，`advance` 不直接修改业务对象，而是返回新的状态快照。

```java
package com.example.interview.domain;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public class InterviewSessionStateMachine {

    private final Map<
            InterviewState,
            Map<InterviewEvent, InterviewState>
    > transitions = new EnumMap<>(
            // EnumMap 专门用于枚举键，适合保存状态机的转移表。
            InterviewState.class
    );

    // 构造状态机并注册整个面试生命周期的合法转移。
    public InterviewSessionStateMachine() {
        // 构造时一次性注册所有合法的“当前状态 + 事件 -> 下一状态”。
        register(
                InterviewState.INIT,
                InterviewEvent.START,
                InterviewState.MATCHING
        );
        register(
                InterviewState.MATCHING,
                InterviewEvent.POSITION_MATCHED,
                InterviewState.QUESTIONING
        );
        register(
                InterviewState.QUESTIONING,
                InterviewEvent.QUESTION_READY,
                InterviewState.ANSWERING
        );
        // 从这里开始是新增的：容许"题目已展示但状态仍停在 QUESTIONING"时提交答案。
        // 为什么必须加这一条：出题流程里 QUESTION_READY（QUESTIONING→ANSWERING）那一步的
        // CAS 推进失败会被静默忽略（见 InterviewPreparationAsyncService.advance 的调用处），
        // 于是前端已经把题目展示给用户了，会话快照却还停在 QUESTIONING。
        // 此时用户提交答案触发 ANSWER_SUBMITTED，原状态机里 QUESTIONING + ANSWER_SUBMITTED
        // 没有转移规则，直接抛 invalid transition，前端就收到 500。
        // 题目既然已经展示，用户的作答就应当被接受并正常进入评分（JUDGING），
        // 评分后续的 JUDGE_DONE→ASKING 流转不受影响。
        register(
                InterviewState.QUESTIONING,
                InterviewEvent.ANSWER_SUBMITTED,
                InterviewState.JUDGING
        );
        // 新增结束
        register(
                InterviewState.ANSWERING,
                InterviewEvent.ANSWER_SUBMITTED,
                InterviewState.JUDGING
        );
        register(
                InterviewState.JUDGING,
                InterviewEvent.JUDGE_DONE,
                InterviewState.ASKING
        );
        register(
                InterviewState.ASKING,
                InterviewEvent.ASK_COMPLETED,
                InterviewState.QUESTIONING
        );
        register(
                InterviewState.ANSWERING,
                InterviewEvent.TIMEOUT,
                InterviewState.JUDGING
        );

        for (var state : InterviewState.values()) {
            // END 是全局事件，任意状态都允许结束。
            register(
                    state,
                    InterviewEvent.END,
                    InterviewState.ENDED
            );
        }
    }

    // 把一条合法的状态转移写入转移表，供 advance 查询下一状态。
    private void register(
            InterviewState from,
            InterviewEvent event,
            InterviewState to
    ) {
        // 一个状态可能对应多个事件，因此使用两级 Map 保存转移表。
        transitions
                .computeIfAbsent(
                        from,
                        // 没有当前状态的子 Map 时才创建一次。
                        ignored -> new EnumMap<>(
                                InterviewEvent.class
                        )
                )
                .put(event, to);
    }

    // 校验事件是否能在当前状态执行，并处理重复请求或生成新快照。
    public TransitionResult advance(
            InterviewSessionSnapshot snapshot,
            InterviewEvent event,
            String requestId
    ) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(event);
        Objects.requireNonNull(requestId);

        // 同一个 requestId 重试时直接返回旧快照，保证事件幂等。
        if (snapshot.hasProcessed(requestId)) {
            return TransitionResult.idempotent(
                    snapshot
            );
        }

        var eventMap = transitions.getOrDefault(
                snapshot.state(),
                Map.of()
        );
        var nextState = eventMap.get(event);

        // 找不到转移规则说明调用方违反了面试生命周期。
        if (nextState == null) {
            throw new IllegalStateException(
                    "invalid transition: "
                            + snapshot.state()
                            + " + "
                            + event
            );
        }

        // 目标状态与当前状态相同（例如已经 ENDED 的会话再次收到 END），
        // 说明这是一次重复推进，直接返回"未变更"结果，保证终态幂等。
        // 如果不做这一步判断，重复调用结束接口会再写一次快照，破坏终态幂等。
        if (nextState == snapshot.state()) {
            return TransitionResult.idempotent(snapshot);
        }

        return TransitionResult.changed(
                snapshot.moveTo(
                        nextState,
                        requestId
                )
        );
    }

    // 状态机的统一返回对象，同时告诉调用方快照是否发生变化。
public record TransitionResult(
        // 状态机处理后的新快照；幂等请求时仍然返回旧快照。
        InterviewSessionSnapshot snapshot,
        // true 表示发生了状态变化，false 表示重复请求被忽略。
        boolean changed
    ) {
        // 为重复请求创建“不变更状态”的结果，调用方仍可拿到原快照。
        static TransitionResult idempotent(
                InterviewSessionSnapshot snapshot
        ) {
            return new TransitionResult(
                    snapshot,
                    false
            );
        }

        // 为成功推进状态创建“已变更”的结果对象。
        static TransitionResult changed(
                InterviewSessionSnapshot snapshot
        ) {
            return new TransitionResult(
                    snapshot,
                    true
            );
        }
    }
}
```

状态机只负责判断业务事件是否合法，不负责数据库、Redis 或 AI。

### 面试 4：创建 MongoDB 快照仓储 <span style="color:green">✅</span>

**本小节目的：** 用 Redis 热缓存 + MongoDB 冷存储分层保存面试过程的完整上下文快照，既支持高频读取，又能在缓存不可用时兜底，并通过版本号 CAS 保证并发写入不丢状态。

**本小节流程：** 定义快照文档 -> 创建 MongoDB 仓储 -> 分层读取快照（Redis -> MongoDB -> INIT）-> 用 findAndModify 按版本原子写回 -> 接入会话服务。

**开始本节前先确认：** `src/main/java/com/example/interview/domain/InterviewSessionSnapshot.java`、`src/main/java/com/example/interview/domain/InterviewState.java` 已经存在；本节负责把领域快照映射到 MongoDB 文档，并加上 Redis 热缓存与 CAS 更新。

这一步创建：

```text
src/main/java/com/example/interview/infrastructure/snapshot/SnapshotDocument.java
src/main/java/com/example/interview/infrastructure/snapshot/SnapshotMongoRepository.java
src/main/java/com/example/interview/infrastructure/snapshot/SessionSnapshotRepository.java
```

仓储负责：

```text
load：先查 Redis 热缓存
    -> 未命中再查 MongoDB 冷存储（真正的数据源）
    -> 都没有则创建 INIT 快照并同步写入 MongoDB
    -> MongoDB 命中后异步回填 Redis
compareAndSet：用 findAndModify 按 version 原子比对更新
    -> 成功后淘汰 Redis，下一次读取重新懒加载最新版本
```

MongoDB 文档保存 `sessionId`、`state`、`version` 和已处理请求。领域快照和 MongoDB 文档分开，是为了不让领域对象依赖数据库注解；Redis 里存的只是序列化后的同一份快照，是加速层而不是数据源。

`SnapshotDocument.java`：

```java
package com.example.interview.infrastructure.snapshot;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.HashSet;
import java.util.Set;

// MongoDB 中保存的持久化文档，不把 Mongo 注解带进领域快照。
@Document("interview_session_snapshot")
public class SnapshotDocument {
    @Id
    private String id;
    private String sessionId;
    private String state;
    private long version;
    private Set<String> processedRequestIds = new HashSet<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getState() {
        return state;
    }

    public void setState(String state) {
        this.state = state;
    }

    public long getVersion() {
        return version;
    }

    public void setVersion(long version) {
        this.version = version;
    }

    public Set<String> getProcessedRequestIds() {
        return processedRequestIds;
    }

    public void setProcessedRequestIds(
            Set<String> processedRequestIds
    ) {
        this.processedRequestIds = processedRequestIds;
    }
}
```

`SessionSnapshotRepository.java`：

```java
package com.example.interview.infrastructure.snapshot;

import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewState;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.HashSet;
import java.util.UUID;

// 把领域快照转换为 Mongo 文档，并提供版本比较更新。
@Repository
public class SessionSnapshotRepository {
    private final SnapshotMongoRepository mongo;

    public SessionSnapshotRepository(
            SnapshotMongoRepository mongo
    ) {
        this.mongo = mongo;
    }

    public InterviewSessionSnapshot load(UUID sessionId) {
        SnapshotDocument document =
                mongo.findBySessionId(sessionId.toString())
                        .orElseGet(() -> createInitial(sessionId));
        return toDomain(document);
    }

    public boolean compareAndSet(
            InterviewSessionSnapshot oldValue,
            InterviewSessionSnapshot newValue
    ) {
        SnapshotDocument current =
                mongo.findBySessionId(
                        oldValue.sessionId().toString()
                ).orElse(null);
        if (current == null
                || current.getVersion() != oldValue.version()) {
            return false;
        }
        mongo.delete(current);
        mongo.save(toDocument(newValue));
        return true;
    }

    private SnapshotDocument createInitial(UUID sessionId) {
        SnapshotDocument document = new SnapshotDocument();
        document.setId(sessionId.toString());
        document.setSessionId(sessionId.toString());
        document.setState(InterviewState.INIT.name());
        document.setVersion(0);
        document.setProcessedRequestIds(new HashSet<>());
        return mongo.save(document);
    }

    private InterviewSessionSnapshot toDomain(
            SnapshotDocument document
    ) {
        return new InterviewSessionSnapshot(
                UUID.fromString(document.getSessionId()),
                InterviewState.valueOf(document.getState()),
                document.getVersion(),
                document.getProcessedRequestIds()
        );
    }

    private SnapshotDocument toDocument(
            InterviewSessionSnapshot snapshot
    ) {
        SnapshotDocument document = new SnapshotDocument();
        document.setId(snapshot.sessionId().toString());
        document.setSessionId(snapshot.sessionId().toString());
        document.setState(snapshot.state().name());
        document.setVersion(snapshot.version());
        document.setProcessedRequestIds(
                new HashSet<>(snapshot.processedRequestIds())
        );
        return document;
    }
}

interface SnapshotMongoRepository
        extends MongoRepository<SnapshotDocument, String> {
    java.util.Optional<SnapshotDocument> findBySessionId(
            String sessionId
    );
}
```

**整改说明：以上旧实现保留用于对比。它的问题在于每次更新都要走"读版本 -> 比对 -> delete -> save"三段独立的网络调用，两个并发请求会同时读到同一版本、同时通过校验，最终互相覆盖且都返回成功，乐观锁退化成"没有锁"；同时它把 Redis 当成必选项，Redis 一抖动接口就直接报错。下面给出本节最终采用的完整实现。**

**快照访问顺序必须固定为：Redis 热缓存 -> MongoDB 冷存储 -> 创建 INIT。Redis key 为 `snapshot:{sessionId}`，热数据 TTL 为 30 分钟。MongoDB 命中后异步回填 Redis；MongoDB 未命中时同步写入 MongoDB。任何 Redis 读写异常都只告警、不上抛，统一降级为"缓存未命中"继续走 Mongo 冷路径，保证 Redis 故障期间会话仍能推进。`compareAndSet` 改用 `MongoTemplate.findAndModify` 把"过滤 + 更新"合成一条命令，由 MongoDB 保证单文档原子性，成功后淘汰 Redis。**

**`SnapshotDocument.java` 无需改变。`SnapshotMongoRepository` 在真实项目里是独立文件，一并给出；`SessionSnapshotRepository.java` 替换为下面的完整实现：**

`SnapshotMongoRepository.java`：

```java
package com.example.interview.infrastructure.snapshot;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface SnapshotMongoRepository
        extends MongoRepository<SnapshotDocument, String> {

    Optional<SnapshotDocument> findBySessionId(String sessionId);
}
```

`SessionSnapshotRepository.java`：

```java
package com.example.interview.infrastructure.snapshot;

import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewState;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

// **Redis 保存热快照，MongoDB 保存冷快照；领域对象不依赖任何基础设施注解。**
@Repository
public class SessionSnapshotRepository {
    // **Redis 热缓存前缀，实际 key 为 snapshot:{sessionId}。**
    private static final String CACHE_PREFIX = "snapshot:";
    // **热数据保留 30 分钟，过期后由下一次访问懒加载。**
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    private final SnapshotMongoRepository mongo;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    // 异步回填 Redis 用的线程池，避免占用 ForkJoinPool 的公共线程。
    private final Executor cacheWriter;

    // 能发"带条件原子更新"命令的依赖：Spring Data 的 MongoRepository 只暴露 CRUD，
    // 给不出 findAndModify，而 CAS 的原子性正是靠这条命令拿到的。
    private final MongoTemplate mongoTemplate;

    // Redis 读写失败只告警、不上抛，所以先备好 log 对象。
    private static final Logger log =
            LoggerFactory.getLogger(SessionSnapshotRepository.class);

    public SessionSnapshotRepository(
            SnapshotMongoRepository mongo,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            // 给 cacheWriter 指定 Bean 名字：AsyncConfig 里注册了 9 个 Executor，
            // 只写类型会报"expected single matching bean but found 10"启动失败；
            // cacheWriteTaskExecutor 的注释写明"供 CompletableFuture 使用"，正合回填场景。
            @Qualifier("cacheWriteTaskExecutor") Executor cacheWriter,
            MongoTemplate mongoTemplate
    ) {
        this.mongo = mongo;
        this.redis = redis;
        this.mapper = mapper;
        this.cacheWriter = cacheWriter;
        this.mongoTemplate = mongoTemplate;
    }

    /**
     * 读取会话快照：Redis 热缓存优先，未命中回源 Mongo 并异步回填。
     *
     * <p>为什么 Redis 异常必须被吞掉而不是上抛：Redis 在本项目里只是"加速读取的热缓存"，
     * 真正的数据源是 MongoDB。缓存不可用时最差的结果是"慢一点"，而不是"面试做不下去"。
     * 所以这里把所有 Redis 异常统一降级为"缓存未命中"，继续走 Mongo 冷路径，保证
     * Redis 故障期间会话仍可推进。</p>
     *
     * @param sessionId 面试会话 ID
     * @return 该会话的快照；Mongo 也没有时返回新创建的 INIT 快照
     */
    public InterviewSessionSnapshot load(UUID sessionId) {
        // 拼出 Redis 热缓存的 key。
        String key = cacheKey(sessionId);

        // 第一步：尝试读热缓存。命中就直接返回，省掉一次 Mongo 查询。
        // 任何 Redis 异常都降级为"未命中"，继续往下走冷路径。
        try {
            String cached = redis.opsForValue().get(key);
            if (cached != null && !cached.isBlank()) {
                return fromJson(cached);   // 反序列化缓存里的 JSON 快照
            }
        } catch (RuntimeException error) {
            // 只告警、不抛出：Redis 挂了最多是"慢一点"，不能让面试做不下去。
            log.warn(
                    "redis read failed, fall back to mongo. sessionId={}",
                    sessionId,
                    error
            );
        }

        // 第二步：冷路径读 Mongo。这是真正的数据源，Redis 挂了也必须走这里。
        SnapshotDocument document =
                mongo.findBySessionId(sessionId.toString())
                        .orElse(null);
        if (document != null) {
            InterviewSessionSnapshot snapshot =
                    toDomain(document);
            // **MongoDB 命中后异步回填 Redis，不阻塞当前读取请求。**
            CompletableFuture.runAsync(
                    () -> writeCache(key, snapshot),
                    cacheWriter                        // 使用自定义线程池，不走 ForkJoinPool
            );
            return snapshot;
        }

        // 第三步：Mongo 也没有时，创建 INIT 快照并同步写入 MongoDB（数据源必须写成功），
        // 再写一次热缓存——写缓存失败只告警，不影响本次返回。
        SnapshotDocument initial = createInitialDocument(sessionId);
        SnapshotDocument saved = mongo.save(initial);
        InterviewSessionSnapshot snapshot = toDomain(saved);
        writeCache(key, snapshot);
        return snapshot;
    }

    /**
     * 淘汰热缓存。
     *
     * <p>为什么淘汰失败不算错：淘汰只是让下一次读回源 Mongo。如果因为 Redis 异常把
     * CAS 流程打断，就变成"缓存故障引发数据写不进去"，主次颠倒。</p>
     */
    private void evictCache(UUID sessionId) {
        try {
            // 淘汰热缓存：删掉之后，下一次 load 会从 Mongo 重新加载最新版本。
            redis.delete(cacheKey(sessionId));
        } catch (RuntimeException error) {
            // 淘汰失败不算错：缓存到期会自动失效，不能因此把 CAS 流程打断。
            log.warn(
                    "redis evict failed, cache will expire by ttl. sessionId={}",
                    sessionId,
                    error
            );
        }
    }

    /**
     * 写入热缓存。
     *
     * <p>为什么写失败只告警：缓存写失败会让下一次读多走一次 Mongo，属于性能问题
     * 而非正确性问题；序列化失败同理——只要 Mongo 里数据是好的，会话状态就不会丢。</p>
     */
    private void writeCache(String key, InterviewSessionSnapshot snapshot) {
        try {
            // 把快照序列化成 JSON 写进 Redis，并带上 TTL（本项目是 30 分钟）。
            redis.opsForValue().set(
                    key,
                    mapper.writeValueAsString(snapshot),
                    CACHE_TTL
            );
        } catch (JsonProcessingException error) {
            // 序列化失败：只要 Mongo 里数据是好的，会话状态就不会丢，只告警。
            log.warn("cannot serialize session snapshot. key={}", key, error);
        } catch (RuntimeException error) {
            // Redis 写失败：缓存只是加速手段，跳过即可。
            log.warn("redis write failed, skip cache. key={}", key, error);
        }
    }

    // 以下是与 Redis 无关的纯转换方法，不需要降级处理。
    private String cacheKey(UUID sessionId) {
        return CACHE_PREFIX + sessionId;
    }

    // **将 Redis JSON 反序列化为领域快照。**
    private InterviewSessionSnapshot fromJson(String json) {
        try {
            return mapper.readValue(
                    json,
                    InterviewSessionSnapshot.class
            );
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "cannot deserialize session snapshot",
                    e
            );
        }
    }

    /**
     * 期望版本 + 1 的原子写回。
     *
     * <p>为什么不能继续用"先查版本、再 delete + save"：那是三段独立的网络调用，
     * 两个并发请求会同时读到同一版本、同时通过校验，最终互相覆盖且都返回成功，
     * 乐观锁就退化成了"没有锁"。而提交答案、结束面试、评分工作流、追问生成、
     * 面试准备这五处调用方，全都指望它只放行一个。</p>
     *
     * <p>这里改用 findAndModify 一条命令完成"过滤 + 更新"：过滤条件带上 sessionId 和
     * 期望的 version，MongoDB 保证单文档更新的原子性，只有当版本仍等于期望值时才会
     * 命中并返回更新后的文档；别人先改过就会返回 null，调用方据此判定 CAS 失败。</p>
     *
     * @param oldValue 调用方读到的旧快照，其中的 version 是期望版本
     * @param newValue 状态机推进后的新快照
     * @return 更新成功返回 true；版本已被其他请求推进返回 false
     */
    public boolean compareAndSet(
            InterviewSessionSnapshot oldValue,
            InterviewSessionSnapshot newValue
    ) {
        // 期望版本来自调用方读到的旧快照，会被写进过滤条件里。
        UUID sessionId = oldValue.sessionId();

        // 过滤条件：sessionId 匹配，并且 version 仍等于期望版本。
        // 别人先推进过 version，这条过滤就会落空，findAndModify 直接返回 null。
        Query query = Query.query(
                Criteria.where("sessionId").is(sessionId.toString())
                        .and("version").is(oldValue.version())
        );

        // 要写入的新值：新状态、新版本号、累积的幂等请求 ID 集合。
        Update update = new Update()
                .set("state", newValue.state().name())
                .set("version", newValue.version())
                .set(
                        "processedRequestIds",
                        new HashSet<>(newValue.processedRequestIds())
                );

        // findAndModify 把"过滤 + 更新"合成一条命令，由 MongoDB 保证单文档原子性；
        // returnNew(true) 表示返回更新后的文档（而不是更新前的）。
        SnapshotDocument saved = mongoTemplate.findAndModify(
                query,
                update,
                FindAndModifyOptions.options().returnNew(true),
                SnapshotDocument.class
        );

        // 返回 null = 过滤没命中（版本已被别人推进，或文档不存在）→ CAS 失败。
        if (saved == null) {
            return false;
        }

        // 写回成功后淘汰热缓存，下一次 load 会从 Mongo 重新懒加载最新版本，
        // 避免缓存里留着旧 state，导致后续判断基于过期数据。
        evictCache(sessionId);
        return true;
    }

    private SnapshotDocument createInitialDocument(
            UUID sessionId
    ) {
        SnapshotDocument document = new SnapshotDocument();
        document.setId(sessionId.toString());
        document.setSessionId(sessionId.toString());
        document.setState(InterviewState.INIT.name());
        document.setVersion(0);
        document.setProcessedRequestIds(new HashSet<>());
        return document;
    }

    private InterviewSessionSnapshot toDomain(
            SnapshotDocument document
    ) {
        return new InterviewSessionSnapshot(
                UUID.fromString(document.getSessionId()),
                InterviewState.valueOf(document.getState()),
                document.getVersion(),
                document.getProcessedRequestIds()
        );
    }

    private SnapshotDocument toDocument(
            InterviewSessionSnapshot snapshot
    ) {
        SnapshotDocument document = new SnapshotDocument();
        document.setId(snapshot.sessionId().toString());
        document.setSessionId(snapshot.sessionId().toString());
        document.setState(snapshot.state().name());
        document.setVersion(snapshot.version());
        document.setProcessedRequestIds(
                new HashSet<>(snapshot.processedRequestIds())
        );
        return document;
    }
}
```

### 面试 5：编写创建和开始接口 <span style="color:green">✅</span>

**本小节目的：** 提供创建面试、开始面试、状态查询、追问查询和结束面试的接口，连接准备页面与面试主流程。

**本小节流程：** 接收岗位和简历 -> 创建会话 -> 初始化状态 -> 保存快照 -> 提交 START 进入匹配中 -> 异步准备（注册心跳 -> 匹配 -> 出题 -> 注销心跳）-> 前端轮询 /state 感知进度 -> 取追问 -> 结束时触发表现分析。

---

**第一步：创建 `src/main/java/com/example/interview/application/SessionView.java` 文件，这个文件主要用于定义面试会话接口的响应对象，将会话 ID、当前状态和版本号组合成前端需要的返回结构，而不是直接暴露数据库实体。**

在进入 Controller 之前，先创建它要返回的 `SessionView`。它不是数据库实体，而是把会话 ID、当前状态和版本号组合成前端需要的响应对象。

```java
package com.example.interview.application;

import com.example.interview.domain.InterviewState;

import java.util.UUID;

/**
 * 面试会话接口的响应对象，只表达当前会话进度。
 * 前端根据 state 字段决定显示哪个页面（等待中、匹配中、答题中等）。
 */
public record SessionView(
        // 面试会话 ID，后续答题和查询都使用它来定位具体会话。
        UUID sessionId,
        // 当前状态机状态，例如 INIT / MATCHING / QUESTIONING / ANSWERING 等，前端据此渲染对应界面。
        InterviewState state,
        // 快照版本号，用于乐观锁检测状态是否已经被其他请求推进，防止并发覆盖。
        long version
) {
}
```
#### 前置依赖：预留的空壳定义（后续章节才会完整实现）

`InterviewPreparationAsyncService` 使用了几个尚未定义的类。为了让本小节的代码能够编译和理解，这里给出它们的最小空壳定义——只保留被用到的方法签名和字段，不包含真实逻辑。完整实现在后续对应章节中给出。

> **补充：** `prepare()` 还依赖心跳服务 `HeartbeatService`。它是一个自包含的小文件，直接在下面的「第二步」给出完整实现，不再作为空壳。

> **为什么需要这些空壳：** 这些类分别涉及 AI 调用、题目持久化和预生成，如果在面试5不提供占位，`InterviewPreparationAsyncService` 的 import 和构造器注入就找不到来源，读者也无法理解每个依赖的角色。

---

##### 空壳 1：`src/main/java/com/example/interview/ai/GeneratedQuestion.java`

**为什么先放这：** `InterviewPreparationAsyncService` 需要调用 `ai.generateQuestion()`，这个方法返回 `GeneratedQuestion` 对象。如果不定义这个 record，`prepare()` 方法中就无法声明变量接收返回值。完整实现在「九、AI出题模块 - 出题2」中给出。

```java
package com.example.interview.ai;

/**
 * AI 出题方法返回的结果对象，仅包含题目正文和分类。
 * 空壳定义，只保留 InterviewPreparationAsyncService 用到的最小结构。
 * 完整实现（包含非空校验）在「九、AI出题模块 - 出题2」中给出。
 */
public record GeneratedQuestion(
        // 题目正文——AI 返回的 JSON 中的 content 字段。
        String content,
        // 题目分类——AI 返回的 JSON 中的 category 字段。
        String category
) {
}
```

---

##### 空壳 2：`src/main/java/com/example/interview/ai/InterviewAiFacade.java`

**为什么先放这：** `InterviewPreparationAsyncService` 的构造器需要注入 `InterviewAiFacade`，并在 `prepare()` 方法中调用 `ai.generateQuestion(...)`。如果这个类不存在，构造器参数 `InterviewAiFacade ai` 就找不到类型定义。完整实现在「九、AI出题模块 - 出题2」中给出。

```java
package com.example.interview.ai;

import java.util.UUID;

/**
 * AI 交互的统一门面。空壳定义，只保留 prepare() 方法用到的方法签名。
 * 完整实现（包含 ChatClient 调用、JSON 解析校验、Singleflight 合并）在「九、AI出题模块 - 出题2」中给出。
 */
public class InterviewAiFacade {

    /**
     * 根据岗位和简历上下文调用 AI 生成面试题。
     *
     * @param sessionId          面试会话 ID，用于日志追踪
     * @param positionName       目标岗位名称
     * @param positionDescription 岗位描述
     * @param requiredSkills     岗位要求的技能列表
     * @param resumeContent      候选人简历解析内容
     * @param sequenceNo         当前是第几题
     * @return 生成的题目对象，包含正文和分类
     */
    public GeneratedQuestion generateQuestion(
            UUID sessionId,
            String positionName,
            String positionDescription,
            String requiredSkills,
            String resumeContent,
            int sequenceNo
    ) {
        // 空壳：完整实现在「九、AI出题模块 - 出题2」中给出
        return null;
    }
}
```

---

##### 空壳 3：`src/main/java/com/example/interview/infrastructure/mysql/QuestionEntity.java`

**为什么先放这：** `prepare()` 方法中需要创建 `QuestionEntity` 对象并调用其 setter 方法设置字段值（`setId()`、`setSessionId()`、`setContent()` 等）。没有这个实体类就无法编译。完整实现在「九、AI出题模块 - 出题1」中给出。

```java
package com.example.interview.infrastructure.mysql;

/**
 * 题目表的数据库实体。空壳定义，只保留 prepare() 中用到的 setter 方法和字段。
 * 完整实现（包含所有字段、getter、setter 和 MyBatis-Plus 注解）在「九、AI出题模块 - 出题1」中给出。
 */
public class QuestionEntity {
    private String id;
    private String sessionId;
    private Long positionId;
    private String content;
    private String category;
    private Integer sequenceNo;
    private String status;

    public void setId(String id) { this.id = id; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public void setPositionId(Long positionId) { this.positionId = positionId; }
    public void setContent(String content) { this.content = content; }
    public void setCategory(String category) { this.category = category; }
    public void setSequenceNo(Integer sequenceNo) { this.sequenceNo = sequenceNo; }
    public void setStatus(String status) { this.status = status; }

    public String getContent() { return content; }
    public String getCategory() { return category; }
}
```

---

##### 空壳 4：`src/main/java/com/example/interview/infrastructure/mysql/QuestionMapper.java`

**为什么先放这：** `prepare()` 方法中调用了 `questions.insert(firstEntity)` 将首题持久化到数据库。没有 `QuestionMapper`，`questions.insert()` 就没有来源。完整实现在「九、AI出题模块 - 出题1」中给出。

```java
package com.example.interview.infrastructure.mysql;

/**
 * 题目表的 MyBatis-Plus Mapper。空壳定义，只保留 prepare() 中用到的 insert() 方法。
 * 完整实现（继承 BaseMapper 获得标准 CRUD）在「九、AI出题模块 - 出题1」中给出。
 */
public class QuestionMapper {
    public void insert(QuestionEntity entity) {
        // 空壳：完整实现在「九、AI出题模块 - 出题1」中给出
    }
}
```

---

##### 空壳 5：`src/main/java/com/example/interview/application/NextQuestionPreGeneratorService.java`

**为什么先放这：** `prepare()` 方法的最后一步调用了 `preGenerator.preGenerate(sessionId, 2)` 来预生成第二题。如果没有这个服务，构造器参数和调用语句都无法编译。完整实现在「九、AI 出题模块 - 出题 4：前端加载和预取题目」中给出。

```java
package com.example.interview.application;

import java.util.UUID;

/**
 * 下一题预生成器。空壳定义，只保留 prepare() 中用到的 preGenerate() 方法。
 * 完整实现（包含异步生成、缓存管理、队列保护）在「二十一、异步化改造」中给出。
 */
public class NextQuestionPreGeneratorService {
    /**
     * 异步预生成指定序号的下一个题目，提前缓存后续题目以提升用户体验。
     *
     * @param sessionId  面试会话 ID
     * @param nextSeq    下一题序号
     */
    public void preGenerate(UUID sessionId, int nextSeq) {
        // 空壳：完整实现在「二十一、异步化改造」中给出
    }
}
```

---


---

**第二步：创建 `src/main/java/com/example/interview/application/HeartbeatService.java` 文件，这个文件主要用于把"后台异步任务（评分 / 追问生成 / 预生成）还在不在跑"变成一个可查询的信号，供补偿器判断处理线程是否已经死亡。**

它由四个动作组成：任务开始时 `register(sessionId)` 放进进程内活跃表并立刻写一次心跳；运行期间由 `@Scheduled` 每 10 秒给活跃表里的每个会话续一次心跳（TTL 30 秒）；任务结束时 `unregister(sessionId)` 摘掉活跃项并删掉心跳 key；补偿器用 `exists(sessionId)` 反查"处理这条任务的线程是否还活着"。

**为什么用"定时器续期"而不是"任务每次干完活自己写一次心跳"：** 线程崩溃时，写在业务代码里的心跳代码根本不会被执行；而 `@Scheduled` 跑在独立的调度线程上，业务线程死了它照样在续期，30 秒 TTL 一到心跳自然消失——这正好就是"线程已死"的信号。

```java
// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================
// 后台任务心跳服务
// ============================================================
// 职责：把"后台异步任务（评分 / 追问生成 / 预生成）还在不在跑"变成一个可查询的信号。
//   1) 任务开始：register(sessionId) —— 放进进程内活跃表，并立刻写一次心跳
//   2) 运行期间：@Scheduled 每 10 秒给活跃表里的每个会话续一次心跳（TTL 30 秒）
//   3) 任务结束：unregister(sessionId) —— 摘掉活跃项 + 删掉心跳 key
//   4) 补偿器：exists(sessionId) —— 反查"处理这条任务的线程是否还活着"
//
// 为什么需要它：补偿器原来只能靠"队列里还有没有这条任务"判断，而这个判断对
//   "任务已被消费者取走、线程随后崩溃"完全失效——记录会永远卡在处理中状态。
//
// 为什么用"定时器续期"而不是"任务每次干完活自己写一次心跳"：
//   线程崩溃时，那段写在业务代码里的心跳代码根本不会被执行；
//   @Scheduled 跑在独立的调度线程上，业务线程死了它照样在续期，
//   30 秒 TTL 一到心跳自然消失——这正好就是"线程已死"的信号。
// ============================================================
@Service
public class HeartbeatService {

    // 心跳就是一个带 TTL 的字符串 key，StringRedisTemplate 足够用，
    // 不需要自定义序列化器，也不会把一个对象图塞进 Redis。
    private final StringRedisTemplate redis;

    // 活跃会话表：定时器靠它知道该给哪些会话续心跳。
    // 为什么用 ConcurrentHashMap：写入的是业务线程（register / unregister），
    // 遍历的是调度线程（heartbeat），两边天然并发。
    // 为什么不放进 Redis：活跃会话是"本进程正在跑哪些任务"，是本地概念；
    // 放 Redis 反而需要在进程退出时清理，做不好会留下永远续期的僵尸 key。
    private final ConcurrentHashMap<UUID, Boolean> activeSessions =
            new ConcurrentHashMap<>();

    // 构造器只注入 Redis：心跳服务没有别的协作者，依赖越少越不容易出错。
    public HeartbeatService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 注册会话心跳（由后台任务在开始时调用）。
     *
     * <p>为什么注册时就写一次：如果只等下一次定时器触发才第一次写，
     * "注册完成 → 定时器下次触发"这段空窗期内 exists() 会返回 false，
     * 补偿器可能把刚启动的正常任务误判成已死线程。
     *
     * @param sessionId 面试会话 ID
     */
    public void register(UUID sessionId) {
        // 记入活跃表：定时器靠它才知道要给哪些会话续心跳。
        activeSessions.put(sessionId, Boolean.TRUE);
        // 立刻手写一次，消除"注册完到下一次定时器触发"之间的心跳空窗。
        write(sessionId);
    }

    /**
     * 注销会话心跳（由后台任务在 finally 块中调用）。
     *
     * <p>为什么必须放在 finally 里：任务可能抛异常提前退出，
     * 写在正常路径上的注销根本走不到，心跳会一直续到进程结束，
     * 让补偿器永远认为"还有人在处理这条任务"。
     *
     * @param sessionId 面试会话 ID
     */
    public void unregister(UUID sessionId) {
        // 先从活跃表摘掉：定时器不再为它续期，心跳不会再被写活。
        activeSessions.remove(sessionId);
        // 再主动删掉 key，让补偿器马上就能判定"处理线程已退出"，
        // 而不是干等 30 秒 TTL 自然过期。
        redis.delete(key(sessionId));
    }

    /**
     * 定时续期：每 10 秒为所有活跃会话写入一次 Redis 心跳。
     *
     * <p>为什么用 fixedDelay 而不是 fixedRate：fixedDelay 是"上一轮执行完再等 10 秒"，
     * fixedRate 是"固定频率触发"。若某轮写入卡住（Redis 抖动），fixedRate 会把
     * 后续轮次不断堆叠进来；fixedDelay 天然串行，不会堆积。
     *
     * <p>为什么 TTL 取 30 秒（= 3 个续期周期）：留出 20 秒的容错窗口。
     * 心跳 key 在 30 秒后消失，说明这个线程至少连续 20 秒没能完成写入，
     * 基本可以判定已经崩溃或卡死。
     */
    @Scheduled(fixedDelay = 10_000)
    public void heartbeat() {
        // 遍历活跃表逐个续期：keySet() 是弱一致视图，遍历期间有会话被
        // remove 掉也不会抛 ConcurrentModificationException。
        activeSessions.keySet().forEach(this::write);
    }

    /**
     * 检查会话心跳是否存在（由 JudgeTaskCompensationService 调用）。
     *
     * @param sessionId 面试会话 ID
     * @return true = 心跳还在（处理线程存活）；false = 心跳已消失（处理线程死亡）
     */
    public boolean exists(UUID sessionId) {
        // 包一层 Boolean.TRUE.equals：hasKey 的返回值是包装类型 Boolean，
        // 在连接异常等情况下可能为 null，直接拆箱会抛 NPE；
        // 这里把 null 统一当成"心跳不存在"，宁可多补偿一次也不要让调用方崩掉。
        return Boolean.TRUE.equals(
                redis.hasKey(key(sessionId))
        );
    }

    /**
     * 写入心跳到 Redis。
     *
     * <p>为什么值是当前时间戳：业务上不读这个值，但存时间戳让排查变简单——
     * 线上直接 GET heartbeat:{sessionId} 就能看到最后一次续期时间，
     * 从而算出线程大概死了多久。
     */
    private void write(UUID sessionId) {
        // SET heartbeat:{sessionId} <当前毫秒时间戳> EX 30：
        // 真正的信号是"这个 key 还在不在"，值只是给人看的。
        redis.opsForValue().set(
                key(sessionId),
                Instant.now().toEpochMilli() + "",
                Duration.ofSeconds(30)
        );
    }

    // 心跳 key 的拼装集中在一处：写、查、删都调它，避免前缀写歪对不上。
    private String key(UUID sessionId) {
        // 形如 heartbeat:{sessionId}，出问题时可以直接 GET 这个 key 看最后心跳时间。
        return "heartbeat:" + sessionId;
    }
}
```

---

**第三步：创建 `src/main/java/com/example/interview/application/InterviewPreparationAsyncService.java` 文件，这个文件主要用于异步执行面试开始后的匹配和首题准备，避免让 HTTP 请求线程等待 AI 响应而长时间阻塞 Tomcat 线程。**

> **前置依赖说明：** 本节涉及的前置依赖包括 `InterviewSessionEntity`、`InterviewSessionMapper`、`SessionSnapshotRepository`、`InterviewSessionStateMachine`、刚创建的 `SessionView`、`HeartbeatService` 和 `AsyncConfig`。本步新增的 `InterviewPreparationAsyncService` 由它承接**匹配**与**出题**两个异步阶段。

**这个服务演示两个彼此衔接的后台阶段，并在整个任务期间用心跳标记"线程还活着"：**
1. **任务开始**：先 `heartbeat.register(sessionId)` 注册心跳（写在 `try` 外面，保证"任务正在跑"这个事实先被记录）。
2. **第一阶段**：读取会话关联的简历和岗位，调用占位的匹配方法，成功后提交 `POSITION_MATCHED`，把状态从 `MATCHING` 推进到 `QUESTIONING`。
3. **第二阶段**：调用占位的出题方法，成功后提交 `QUESTION_READY`，把状态从 `QUESTIONING` 推进到 `ANSWERING`。
4. **任务结束**：在 `finally` 中 `heartbeat.unregister(sessionId)`，无论正常还是异常退出都注销心跳。

每一次推进都会**重新加载快照**并使用 **`compareAndSet`**，CAS 失败只记录日志并停止当前任务，不能直接覆盖其他请求已经写入的新版本。

```java
package com.example.interview.application;

import com.example.interview.ai.GeneratedQuestion;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 异步执行面试开始后的匹配和首题准备，避免让 HTTP 请求线程等待 AI。
 * 使用 @Async 注解在专用线程池中运行，Controller 调用后可以立即返回响应给前端。
 */
@Service
public class InterviewPreparationAsyncService {

    // 日志记录器，用于记录异步任务中的关键步骤和异常，因为异步线程的异常不会直接返回给已结束的 HTTP 请求。
    private static final Logger log =
            LoggerFactory.getLogger(
                    InterviewPreparationAsyncService.class
            );

    // 面试会话的数据库 Mapper，用于按 ID 查询会话记录，获取 userId、positionId、resumeId 等关联信息。
    private final InterviewSessionMapper sessionMapper;
    // 简历 Mapper，用于查询简历的解析内容（parsedContent），供 AI 出题时作为上下文。
    private final ResumeMapper resumes;
    // 岗位 Mapper，用于查询岗位的名称、描述和技能要求，供 AI 出题时作为输入。
    private final PositionMapper positions;
    // 快照仓库，用于加载和 CAS 更新状态快照，保证并发安全。
    private final SessionSnapshotRepository snapshots;
    // 面试状态机，负责校验事件是否合法并生成新的快照状态。
    private final InterviewSessionStateMachine machine;
    // 第一题生成后，立即异步生成第二题的预生成服务，提前缓存后续题目以提升用户体验。
    private final NextQuestionPreGeneratorService preGenerator;
    // 统一 AI 出题入口，封装调用大模型生成题目的逻辑，避免首题继续使用固定字符串占位。
    private final InterviewAiFacade ai;
    // 题目 Mapper，用于将 AI 生成的第一道题持久化到数据库的 question 表中。
    private final QuestionMapper questions;
    // 心跳服务：prepare 是准备任务的唯一入口，只有它知道任务开始与结束的时刻，
    // register / unregister 才能严格配对。
    private final HeartbeatService heartbeat;

    /**
     * Spring 通过构造器注入所有依赖。
     *
     * @param sessionMapper  面试会话 Mapper，查询会话基本信息
     * @param resumes        简历 Mapper，查询简历解析内容
     * @param positions      岗位 Mapper，查询岗位描述和技能要求
     * @param snapshots      快照仓库，加载和 CAS 更新状态
     * @param machine        状态机，校验事件合法性并推进状态
     * @param preGenerator   下一题预生成器，提前生成后续题目
     * @param ai             AI 门面，统一调用大模型出题
     * @param questions      题目 Mapper，持久化生成的题目
     * @param heartbeat      心跳服务，任务开始时注册、结束时注销
     */
    public InterviewPreparationAsyncService(
            InterviewSessionMapper sessionMapper,
            ResumeMapper resumes,
            PositionMapper positions,
            SessionSnapshotRepository snapshots,
            InterviewSessionStateMachine machine,
            NextQuestionPreGeneratorService preGenerator,
            InterviewAiFacade ai,
            QuestionMapper questions,
            // 心跳服务追加在末尾，Spring 按类型注入，不会打乱既有参数顺序。
            HeartbeatService heartbeat
    ) {
        this.sessionMapper = sessionMapper;
        this.resumes = resumes;
        this.positions = positions;
        this.snapshots = snapshots;
        this.machine = machine;
        this.preGenerator = preGenerator;
        this.ai = ai;
        this.questions = questions;
        this.heartbeat = heartbeat;
    }

    /**
     * 使用专用线程池异步执行，Controller 调用后可以立即返回响应，不会阻塞 Tomcat 的 HTTP 处理线程。
     * 该方法依次执行：查询会话 -> 查询简历和岗位 -> 提交匹配事件 -> AI 生成首题 -> 提交出题事件 -> 预生成第二题。
     *
     * @param sessionId 面试会话 ID，用于定位需要准备的面试会话
     */
    @Async("interviewPreparationTaskExecutor")
    public void prepare(UUID sessionId) {
        // 任务开始，先注册心跳：写在 try 外面，保证"任务正在跑"这个事实先被记录，
        // 否则前面任何一行抛异常都等于"从来没注册过"，补偿器会误判线程已死。
        heartbeat.register(sessionId);
        try {
            // 第一步：根据 sessionId 查询面试会话实体，获取 userId、positionId、resumeId 等关联信息。
            InterviewSessionEntity session =
                    sessionMapper.selectById(
                            sessionId.toString()
                    );
            // 如果会话不存在，直接抛出异常，由 catch 块记录日志。
            if (session == null) {
                throw new IllegalArgumentException(
                        "interview session not found"
                );
            }

            // 第二步：查询该会话关联的简历和岗位信息。
            // 简历的 parsedContent 和岗位的名称/描述/技能将作为 AI 出题的上下文输入。
            ResumeEntity resume =
                    resumes.selectById(session.getResumeId());
            PositionEntity position =
                    positions.selectById(session.getPositionId());
            // 如果简历或岗位不存在，无法继续准备流程，直接抛出异常。
            if (resume == null || position == null) {
                throw new IllegalArgumentException(
                        "resume or position not found"
                );
            }

            // 第三步：匹配阶段 —— 提交 POSITION_MATCHED 事件，将状态从 MATCHING 推进到 QUESTIONING。
            // 这里使用占位匹配逻辑，真实生产环境会在此处计算岗位匹配度分数。
            if (!advance(
                    sessionId,
                    InterviewEvent.POSITION_MATCHED,
                    "position-matched"
            )) {
                // 如果 CAS 更新失败（说明其他线程已经推进了版本），直接返回，不继续后续出题。
                return;
            }

            // 第四步：首题生成阶段 —— 使用真实 AI Facade 生成第一道面试题，并写入题目表。
            // 传入岗位名称、描述、技能要求和简历解析内容作为 AI 出题的上下文。
            GeneratedQuestion firstQuestion =
                    ai.generateQuestion(
                            sessionId,                      // 会话 ID，用于关联题目
                            position.getName(),              // 岗位名称，供 AI 了解职位方向
                            position.getDescription(),       // 岗位描述，供 AI 了解职责细节
                            position.getSkills(),            // 技能要求，供 AI 针对性地出题
                            resume.getParsedContent(),       // 简历解析内容，供 AI 结合候选人背景出题
                            1                                // 题目序号，标记为第一题
                    );
            // 将 AI 生成的首题持久化到数据库。
            QuestionEntity firstEntity = new QuestionEntity();
            firstEntity.setId(UUID.randomUUID().toString());   // 生成全局唯一 ID
            firstEntity.setSessionId(sessionId.toString());    // 关联面试会话
            firstEntity.setPositionId(session.getPositionId());// 关联岗位
            firstEntity.setContent(firstQuestion.content());   // 题目内容
            firstEntity.setCategory(firstQuestion.category()); // 题目分类（如技术、项目经验等）
            firstEntity.setSequenceNo(1);                      // 第一题，序号为 1
            firstEntity.setStatus("ACTIVE");                   // 状态为激活，表示该题正在使用
            questions.insert(firstEntity);
            // 记录日志，方便追踪题目生成情况（因为异步异常无法返回给前端）。
            log.info(
                    "first question generated, sessionId={}, question={}",
                    sessionId,
                    firstQuestion.content()
            );

            // 第五步：出题完成阶段 —— 提交 QUESTION_READY 事件，将状态从 QUESTIONING 推进到 ANSWERING。
            // 此时前端可以感知到状态变为 ANSWERING，从而展示答题界面。
            advance(
                    sessionId,
                    InterviewEvent.QUESTION_READY,
                    "question-ready"
            );

            // 第六步：首题已激活，立即启动第二题的预生成，提前准备下一道题目以提升用户体验。
            // 序号为 2，表示生成第二题。
            preGenerator.preGenerate(sessionId, 2);

        } catch (Exception e) {
            // 异步线程中的异常不会直接返回给已结束的 HTTP 请求，因此必须记录完整上下文以便排查问题。
            log.error(
                    "interview preparation failed, sessionId={}",
                    sessionId,
                    e
            );
        } finally {
            // 任务结束（含异常退出），注销心跳：用 finally 盖住所有退出路径，
            // 线程一旦退出就删掉心跳，不留"看起来还活着"的假信号。
            heartbeat.unregister(sessionId);
        }
    }

    /**
     * 每一个异步阶段都重新加载快照，并通过 CAS（Compare-And-Swap）防止旧快照覆盖新快照。
     * 这是保证并发安全的关键：如果其他线程已经推进了状态版本，当前线程不应覆盖。
     *
     * @param sessionId  面试会话 ID，用于加载当前快照
     * @param event      要提交的事件，例如 POSITION_MATCHED 或 QUESTION_READY
     * @param requestName 请求名称，用于生成唯一的请求 ID，便于追踪
     * @return true 表示 CAS 成功，状态已推进；false 表示 CAS 失败，有其他线程已更新版本
     */
    private boolean advance(
            UUID sessionId,
            InterviewEvent event,
            String requestName
    ) {
        // 重新加载当前快照，确保基于最新版本推进。
        InterviewSessionSnapshot oldValue =
                snapshots.load(sessionId);
        // 调用状态机，校验事件在当前状态下是否合法，并生成新的快照。
        var result = machine.advance(
                oldValue,
                event,
                requestName + "-" + UUID.randomUUID()
        );
        // 使用 CAS 乐观锁更新快照：只有当数据库中版本号与 oldValue 一致时才写入新快照。
        boolean updated = snapshots.compareAndSet(
                oldValue,
                result.snapshot()
        );
        if (!updated) {
            // CAS 失败说明其他线程已经推进了版本，记录警告日志后停止当前链路，不做重试。
            log.warn(
                    "snapshot compareAndSet failed, sessionId={}, event={}, version={}",
                    sessionId,
                    event,
                    oldValue.version()
            );
            return false;
        }
        // CAS 成功，记录推进信息以便追踪状态流转。
        log.info(
                "snapshot advanced, sessionId={}, event={}, state={}",
                sessionId,
                event,
                result.snapshot().state()
        );
        return true;
    }
}
```

---

**第四步：创建 `src/main/java/com/example/interview/application/InterviewController.java` 文件，这个文件主要用于提供面试会话的 RESTful 接口，包括创建会话、开始面试、状态查询、追问查询和结束面试五个核心端点。**

**教学版改造说明：** 教学版为了快速演示状态机流转，曾经在 `start()` 方法中同步连续推进 `START`、`POSITION_MATCHED` 和 `QUESTION_READY` 三步。这里改为更贴近生产环境的**异步版本**：`start()` 只提交 `START`，把状态从 `INIT` 推进到 `MATCHING`，调用异步服务后立即返回；剩余状态由异步任务在匹配和出题完成后分别触发，避免 HTTP 请求长时间阻塞、Tomcat 线程被无效占用。

**完整真实生产环境流程：**
1. 用户点击"开始面试" -> `start()` 返回"匹配中"
2. 异步任务计算匹配分数 -> 推送到"出题中"
3. 异步任务生成第一道题 -> 推送到"答题中"
4. 前端通过 `GET /{sessionId}/state` 轮询感知状态变化
5. 提交原题答案后，前端先 `GET /{sessionId}/follow-up?questionId=` 看有没有追问
6. 用户结束面试 -> `end()` 推进到终态，并把整场转写文本交给表现分析

```java
package com.example.interview.application;

import com.example.interview.ai.DemeanorAnalysisService;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

/**
 * 面试会话的 RESTful Controller。
 * 提供创建面试、开始面试、状态查询、追问查询和结束面试五个核心接口。
 * create / start / state / end 返回 SessionView，前端根据其中的 state 字段决定显示哪个页面；
 * follow-up 返回 FollowUpView。
 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    // ============ 依赖注入 ============

    // 简历 Mapper，用于校验简历是否存在且已解析完成。
    private final ResumeMapper resumes;
    // 面试会话 Mapper，用于将会话记录持久化到数据库。
    private final InterviewSessionMapper sessionMapper;
    // 快照仓库，用于加载和 CAS 更新状态快照，保证状态推进的并发安全。
    private final SessionSnapshotRepository snapshots;
    // 面试状态机，负责校验事件合法性并生成推进后的新快照。
    private final InterviewSessionStateMachine machine;
    // 异步执行匹配和首题准备的服务，start() 调用后不等待任务完成，立即返回响应。
    private final InterviewPreparationAsyncService preparationTasks;
    // 追问仓储：回答"刚才这道题有没有追问要答"，是追问链路在前端的唯一出口。
    private final FollowUpRepository followUps;
    // 会话恢复服务：重连时把中断的异步步骤幂等补跑，兑现"主动恢复 + 幂等补偿"。
    private final SessionRecoveryService recovery;

    /**
     * Spring 通过构造器注入所有依赖。
     *
     * @param resumes            简历 Mapper，用于校验简历
     * @param sessionMapper      会话 Mapper，用于持久化会话
     * @param snapshots          快照仓库，用于状态快照的加载和 CAS 更新
     * @param machine            状态机，校验和推进状态
     * @param preparationTasks   异步准备服务，后台执行匹配和出题
     * @param followUps          追问仓储，查询某道原题上的追问
     * @param recovery           恢复服务，重连时幂等补跑中断的异步步骤
     */
    public InterviewController(
            ResumeMapper resumes,
            InterviewSessionMapper sessionMapper,
            SessionSnapshotRepository snapshots,
            InterviewSessionStateMachine machine,
            InterviewPreparationAsyncService preparationTasks,
            // 从这里开始是新增的：构造器末尾追加追问仓储与会话恢复服务（Spring 按类型注入）。
            FollowUpRepository followUps,
            SessionRecoveryService recovery
            // 新增结束
    ) {
        this.resumes = resumes;
        this.sessionMapper = sessionMapper;
        this.snapshots = snapshots;
        this.machine = machine;
        this.preparationTasks = preparationTasks;
        this.followUps = followUps;
        this.recovery = recovery;
    }

    // ============ 接口定义 ============

    /**
     * 创建面试会话。
     * 该接口接收用户 ID、岗位 ID 和简历 ID，校验简历存在且已解析后，
     * 创建会话记录建立用户、岗位、简历之间的关联，初始状态从 INIT 开始。
     *
     * @param userId     用户 ID，标识面试者
     * @param positionId 岗位 ID，标识面试职位
     * @param resumeId   简历 ID，标识候选人简历
     * @return SessionView 包含会话 ID、初始状态（INIT）和版本号
     * @throws IllegalArgumentException 如果简历不存在、不属于该用户或尚未解析完成
     */
    @PostMapping
    public SessionView create(
            @RequestParam("userId") Long userId,
            @RequestParam("positionId") Long positionId,
            @RequestParam("resumeId") Long resumeId
    ) {
        // 第一步：根据 resumeId 查询简历记录，校验简历是否存在。
        var resume = resumes.selectById(resumeId);

        // 第二步：校验合法性 —— 简历不能为空、必须属于当前用户、且状态必须为 "PARSED"（已解析完成）。
        // 这三个条件任何一个不满足都说明简历不可用，抛出异常提示前端引导用户先上传并解析简历。
        if (resume == null
                || !Objects.equals(userId, resume.getUserId())
                || !"PARSED".equalsIgnoreCase(
                        resume.getStatus()
                )) {
            throw new IllegalArgumentException(
                    "请先上传并完成简历解析"
            );
        }

        // 第三步：生成全局唯一的会话 ID，使用 UUID 避免 ID 冲突和序列问题。
        UUID id = UUID.randomUUID();

        // 第四步：加载该会话 ID 的初始快照，快照中包含了初始状态（INIT）和初始版本号。
        var snapshot = snapshots.load(id);

        // 第五步：创建会话实体，设置基本属性并持久化到数据库。
        var entity = new InterviewSessionEntity();
        entity.setId(id.toString());                        // 设置会话 ID
        entity.setUserId(userId);                           // 设置用户 ID
        entity.setPositionId(positionId);                   // 设置岗位 ID
        entity.setResumeId(resumeId);                       // 设置简历 ID
        entity.setState(snapshot.state().name());           // 设置初始状态（从快照中获取）
        sessionMapper.insert(entity);                       // 插入数据库

        // 第六步：返回 SessionView，包含会话 ID、初始状态和版本号。
        return new SessionView(
                id,
                snapshot.state(),
                snapshot.version()
        );
    }

    /**
     * 开始面试（异步版本）。
     * 该接口只向状态机提交 START 事件，将状态从 INIT 推进到 MATCHING，
     * 然后立即调度异步准备任务返回响应，不在本次 HTTP 请求中等待匹配和出题完成。
     *
     * @param sessionId 要启动的面试会话 ID
     * @return SessionView 包含会话 ID、当前状态（MATCHING）和版本号
     */
    @PostMapping("/{sessionId}/start")
    public SessionView start(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 第一步：加载当前会话的最新快照，获取当前状态和版本号。
        var oldValue = snapshots.load(sessionId);

        // 第二步：调用面试状态机，提交 START 事件，把面试会话从 INIT 推进到 MATCHING。
        // machine.advance() 会校验当前状态是否允许 START 事件，并生成新的快照。
        var result = machine.advance(
                oldValue,
                InterviewEvent.START,
                UUID.randomUUID().toString()
        );

        // 第三步：使用 CAS 乐观锁保存新快照，如果版本冲突则立即抛出异常。
        save(oldValue, result.snapshot());

        // 第四步：START 成功后立即提交后台异步任务，当前 HTTP 请求不执行匹配和出题。
        // preparationTasks.prepare() 使用 @Async 专用线程池异步执行。
        preparationTasks.prepare(sessionId);

        // 第五步：立即返回 MATCHING 状态，让前端可以展示"匹配中"页面。
        // POSITION_MATCHED 和 QUESTION_READY 由异步任务分别提交。
        return view(sessionId, result.snapshot());
    }

    /**
     * 状态查询接口（新增）：返回会话当前快照状态，供前端轮询。
     *
     * <p>为什么直接返回状态机里的原始状态（如 ENDED）：前端需要区分"面试结束"和
     * "报告生成完成"两件事，任何一侧改名都会让这个判断失真。</p>
     *
     * <p>为什么复用 view(UUID, snapshot)：本接口返回的字段与 create / start / end
     * 完全一致（sessionId / state / version），复用同一个转换方法保证前端在任何入口
     * 拿到的会话视图形状都一样。这是只读接口，Redis 未命中时会懒加载 MongoDB，
     * 不会改任何状态，因此不需要加锁或 CAS。</p>
     *
     * @param sessionId 面试会话 ID
     * @return 含 state 的会话视图
     */
    @GetMapping("/{sessionId}/state")
    public SessionView state(@PathVariable("sessionId") UUID sessionId) {
        // 直接读快照当前状态：Redis 未命中时会懒加载 MongoDB，不会改任何状态。
        return view(sessionId, snapshots.load(sessionId));
    }

    /**
     * 追问查询接口（新增）：查询某一道原题上已经生成的追问。
     *
     * <p>为什么必须有这个接口：追问存在 interview_follow_up 表，不作为新题插入
     * interview_question。前端取"下一题"走的是 POST /{sessionId}/questions，那里只会
     * 返回 AI 新出的题，永远看不到追问。少了这一问，候选人根本不知道还有追问要答。</p>
     *
     * <p>调用时机：前端提交完原题答案、轮询到状态回到 QUESTIONING 之后。
     * 此时追问生成链（judge → ASK_COMPLETED）已经跑完并落库，能稳定查到。</p>
     *
     * <p>为什么返回 null 而不是 404：没有追问是正常业务分支（分数段决定不追问），
     * 返回空响应体让前端用一次判空处理两种情况，不需要把"正常"写成"异常"。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 刚答完的那道原题 ID
     * @return 追问视图；这道题没有追问时返回 null（HTTP 200 + 空 body）
     */
    @GetMapping("/{sessionId}/follow-up")
    public FollowUpView followUp(@PathVariable("sessionId") UUID sessionId,
                                 @RequestParam("questionId") UUID questionId) {
        return followUps.find(sessionId, questionId);
    }

    /**
     * 结束面试会话。
     * 向状态机提交 END 事件，将会话推进到终态，并同步更新数据库中的会话状态。
     *
     * @param sessionId 要结束的面试会话 ID
     * @return SessionView 包含会话 ID、结束状态和版本号
     */
    @PostMapping("/{sessionId}/end")
    public SessionView end(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 第一步：加载当前会话的最新快照。
        var oldValue = snapshots.load(sessionId);

        // 第二步：调用状态机提交 END 事件，生成结束状态的快照。
        var result = machine.advance(
                oldValue,
                InterviewEvent.END,
                UUID.randomUUID().toString()
        );

        // 第三步：使用 CAS 乐观锁保存新快照。
        save(oldValue, result.snapshot());

        // 第四步：同步更新数据库中的会话实体状态字段，保证数据表与快照一致。
        InterviewSessionEntity entity = sessionMapper.selectById(
                sessionId.toString()
        );
        if (entity != null) {
            entity.setState(result.snapshot().state().name());
            sessionMapper.updateById(entity);
        }

        // 第五步：返回更新后的 SessionView。
        return view(sessionId, result.snapshot());
    }

    // ============ 私有方法 ============

    /**
     * 使用 CAS 乐观锁保存快照，如果版本冲突则抛出 IllegalStateException。
     * 这样可以防止并发操作中旧快照覆盖新快照导致状态丢失。
     *
     * @param oldValue 基于此版本进行 CAS 比较
     * @param newValue 要写入的新快照
     * @throws IllegalStateException 如果 CAS 失败，说明有其他请求已经推进了版本
     */
    private void save(
            com.example.interview.domain.InterviewSessionSnapshot oldValue,
            com.example.interview.domain.InterviewSessionSnapshot newValue
    ) {
        if (!snapshots.compareAndSet(oldValue, newValue)) {
            throw new IllegalStateException(
                    "session version conflict"
            );
        }
    }

    /**
     * 将会话 ID 和快照包装成前端需要的 SessionView 响应对象。
     *
     * @param sessionId 面试会话 ID
     * @param snapshot  当前状态快照，包含 state 和 version
     * @return SessionView 响应对象
     */
    private SessionView view(
            UUID sessionId,
            com.example.interview.domain.InterviewSessionSnapshot snapshot
    ) {
        return new SessionView(
                sessionId,
                snapshot.state(),
                snapshot.version()
        );
    }
}
```

---

**面试 5 自检清单：**

- **✅ `InterviewController.start()` 是否只提交 `START`，并从 `INIT` 推进到 `MATCHING` 后立即返回：已完成。**
- **✅ 是否新增异步任务服务，先完成占位匹配，再提交 `POSITION_MATCHED`，再完成占位出题，最后提交 `QUESTION_READY`：已完成。**
- **✅ 每一步是否重新读取快照并使用 `compareAndSet`，失败时记录日志：已完成。**
- **✅ 是否使用 Spring `@Async`，并在 `AsyncConfig` 中补充专用线程池：已完成。**
- **✅ `prepare()` 是否在任务开始时 `heartbeat.register(sessionId)`、在 `finally` 中 `heartbeat.unregister(sessionId)`：已完成。**
- **✅ 是否补齐 `GET /{sessionId}/state`（只读、返回 SessionView）和 `GET /{sessionId}/follow-up?questionId=`（返回 FollowUpView，无追问返回 null）：已完成。**
- **✅ `end()` 是否在推进 `END` 事件、同步 `interview_session.state` 后，从答案表兜底取整场转写文本交给 `DemeanorAnalysisService.analyze(...)`（文本为空则跳过）：已完成。**
- **✅ 是否补充教学版改造原因、生产环境完整流程，并保留面试 5 标题绿色 `✅`：已完成。**

## 九、AI 出题模块

### 模块主要目的

根据岗位、简历、历史题目和面试状态生成当前题目，并避免并发请求导致重复出题。出题有两条路径：后台预生成服务提前把"下一题"以 `PRE_GENERATED` 状态写进题目表、占住题号；取题接口优先把这道预生成题用 CAS 改成 `ACTIVE` 返回，只有没命中预生成题时才回退到请求合并（Singleflight）现场调用 AI。

### 模块整体流程

```text
读取面试上下文 -> 组装提示词 -> 调用 AI
    -> 校验返回结果 -> 保存题目 -> 请求合并 -> 返回题目

取题时（两条路径）：
后台预生成（@Async）-> 落库 status = PRE_GENERATED（先占住题号）
    -> 取题接口按 max(sequence_no) + 1 算出目标题号
    -> 命中 PRE_GENERATED：CAS 改成 ACTIVE 后直接返回
    -> 未命中：Singleflight 现场生成，落库 status = ACTIVE
```

### 出题前：实现 RedisSingleflight 和本地降级

**本小节目的：** 在 Redis 正常时使用分布式请求合并；Redis 不可用时自动切换到 JVM 内存中的本地请求合并，避免单点 Redis 故障直接放大 AI 调用。

**本小节流程：** 检查 Redis 可用性 -> Redis 可用时走分布式缓存 -> Redis 不可用且开关开启时走 `ConcurrentHashMap` -> 相同 key 共用同一个 `CompletableFuture` -> 5 分钟后过期 -> 每 10 分钟定时清理。

---

**第一步：在配置文件 `src/main/resources/application.yml` 中增加本地降级开关配置。**

这个配置项控制当 Redis 不可用时，是否启用 JVM 内存级别的请求合并降级策略，避免 Redis 故障直接导致 AI 调用雪崩。

```yaml
interview:
  singleflight:
    local-fallback-enabled: ${INTERVIEW_SINGLEFLIGHT_LOCAL_FALLBACK_ENABLED:true}
```

---

**第二步：创建 `src/main/java/com/example/interview/infrastructure/singleflight/RedisSingleflight.java` 文件。**

这个文件是整个**请求合并模块**的核心实现。它的职责是：在 Redis 正常工作时，通过 **Lua 脚本** 实现分布式环境下的原子性"查缓存 + 抢锁"操作，保证多个服务节点不会同时调用 AI 出同一道题；在 Redis 不可用时，自动降级到 **JVM 内存级别** 的本地请求合并（基于 `ConcurrentHashMap` + `CompletableFuture`），确保系统不因 Redis 单点故障而崩溃。

```java
package com.example.interview.infrastructure.singleflight;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;
// 声明为 Spring 组件：RedisSingleflight 被 InterviewAiFacade、QuestionController、
// FollowUpGenerationService 等多处构造器注入，必须注册为 Bean 才能启动；
// 同时类内的 clearExpiredLocalCache 依赖 @Scheduled，也需要 Bean 化后才会被定时调用。
@Component
public class RedisSingleflight {
    // Redis 中缓存 AI 结果的前缀，用于快速定位已生成的内容。
    private static final String VALUE_PREFIX =
            "singleflight:value:";
    // Redis 中分布式锁的前缀，用于防止多个节点同时执行 producer。
    private static final String LOCK_PREFIX =
            "singleflight:lock:";
    // Redis 中围栏令牌的前缀，用于单调递增地标记每次评分结果的新旧顺序。
    private static final String FENCE_PREFIX =
            "singleflight:fence:";

    
    // 本地缓存ai出题结果
    private final ConcurrentHashMap<
            String,
            CompletableFuture<String>
            > localCache = new ConcurrentHashMap<>();
    // 缓存结果过期时间
    private final ConcurrentHashMap<String, Long> localExpiry =
            new ConcurrentHashMap<>();

    // 存结果的版本号，防止乱修改
    private final ConcurrentHashMap<String, Long> localFenceTokens =
            new ConcurrentHashMap<>();

    // Spring Data Redis 的操作模板，用于执行 Redis 命令和 Lua 脚本。
    private final StringRedisTemplate redis;
    private final FailureClassifier failureClassifier;
    // 超时接管服务：可重试的失败登记给它，TTL 过期后由它统一重跑。
    private final TimeoutTakeoverService takeover;
    // 是否启用本地降级开关，从配置文件中读取，默认为 true。
    private final boolean localFallbackEnabled;
    private final SingleflightStateMachine stateMachine;
    public RedisSingleflight(
            // Spring Data Redis 的操作模板，由 Spring 自动注入。
            StringRedisTemplate redis,
            // 本地降级开关，从配置项 interview.singleflight.local-fallback-enabled 读取，默认 true。
            @Value(
                    "${interview.singleflight.local-fallback-enabled:true}"
            )
            boolean localFallbackEnabled,
            FailureClassifier failureClassifier,
            TimeoutTakeoverService takeover,
            SingleflightStateMachine stateMachine
    ) {
        this.redis = redis;
        this.localFallbackEnabled = localFallbackEnabled;
        this.failureClassifier = failureClassifier;
        this.takeover = takeover;
        this.stateMachine = stateMachine;
    }
    // 先探测 Redis；探测失败时才进入本地降级分支。
    // 参数 key：业务唯一标识（如 question:sessionId:sequence），用于合并相同请求。
    // 参数 ttl：结果的过期时间，超过此时间后需要重新生成。
    // 参数 producer：实际执行 AI 调用的生产函数，只有第一个请求才会执行它。
    // 参数 converter：将 producer 返回的字符串转换为业务需要的类型 T。
    // 返回值：类型 T 的业务结果。
    public <T> T execute(
            String key,
            Duration ttl,
            Supplier<String> producer,
            Function<String, T> converter
    ) {
        stateMachine.register(key);
        // 第一步：检查 Redis 是否可用。
        if (!redisAvailable()) {
            // 第二步：Redis 不可用时，判断是否允许本地降级。
            if (!localFallbackEnabled) {
                // 降级被禁用则抛出异常，防止无限制地穿透到 AI。
                throw new IllegalStateException(
                        "Redis is unavailable and local fallback is disabled"
                );
            }
            // 第三步：执行本地请求合并，默认过期时间为 5 分钟。
            return converter.apply(
                    executeLocal(
                            key,
                            Duration.ofMinutes(5),
                            producer
                    )
            );
        }

        // Redis 可用时走分布式请求合并路径。
        return converter.apply(
                executeRedis(key, ttl, producer)
        );
    }
    public FencedValue executeFenced(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        // 先通过统一的 execute 方法获取执行结果。
        String value = execute(
                key,
                ttl,
                producer,
                Function.identity()
        );
        String tokenKey = FENCE_PREFIX + key;
        Long token;
        // 根据 Redis 是否可用，选择分布式递增或本地递增令牌。
        if (redisAvailable()) {
            // Redis 可用时使用 Redis 的 INCR 命令保证全局单调递增。
            token = redis.opsForValue().increment(tokenKey);
        } else {
            // Redis 不可用时使用本地 ConcurrentHashMap 保证单调递增。
            // compute 对同一个 key 是原子操作，避免并发请求拿到相同的本地令牌。
            token = localFenceTokens.compute(
                    key,
                    (ignored, current) -> (current == null ? 0L : current) + 1L
            );
        }
        return new FencedValue(value, token == null ? 0L : token);
    }

    

    public boolean acceptFence(String key, long token) {
        if (!redisAvailable()) {
            // Redis 不可用时，从本地令牌映射中读取当前最新令牌进行比较。
            Long current = localFenceTokens.get(key);
            return current == null || token >= current;
        }
        // Redis 可用时，从 Redis 中读取最新令牌进行比较。
        String raw = redis.opsForValue().get(FENCE_PREFIX + key);
        return raw != null && Long.parseLong(raw) == token;
    }

    // executeRedis 是 Redis 可用时的分布式请求合并实现。它把“检查缓存是否命中”和
    // “尝试获取分布式锁”这两步用一个 Lua 脚本打包成一次原子操作提交给 Redis，从而彻底
    // 避免两个请求同时拿到“未命中”的结论、进而出现两个节点同时调用 AI 的竞态问题。
    // 脚本只会返回三种结果：CACHED（缓存命中，直接读缓存）；OWNER（成功抢到锁，是这一组
    // 请求的第一个，应当执行 producer 并把结果写回缓存）；WAIT（没命中也没抢到锁，需要轮询
    // 等待持有锁的那个请求把结果写进缓存）。对于 WAIT 分支，最多轮询 30 秒，若超时仍未拿到
    // 结果，则视为持锁方异常，由当前请求自己兜底调用 producer，避免系统被锁问题永久卡死。
    // 参数 key：业务唯一标识。
    // 参数 ttl：结果在 Redis 中的过期时间。
    // 参数 producer：实际执行 AI 调用的生产函数。
    // 返回值：AI 生成的字符串结果；可能来自缓存命中，也可能来自当前请求自己执行 producer。
    private String executeRedis(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        String valueKey = VALUE_PREFIX + key;
        String lockKey = LOCK_PREFIX + key;
        // 使用系统纳秒时间作为锁所有者标识，具备足够随机性。
        String owner = Long.toHexString(
                System.nanoTime()
        );
        // Lua 脚本：原子地执行"查缓存 → 抢锁"两步操作。
        // KEYS[1] = valueKey（缓存键）, KEYS[2] = lockKey（锁键）
        // ARGV[1] = owner（锁所有者）, ARGV[2] = "30"（锁过期秒数）
        // 返回 {"CACHED", value} 表示命中缓存
        // 返回 {"OWNER", ""} 表示抢锁成功，需要执行 producer
        // 返回 {"WAIT", ""} 表示未命中缓存也没抢到锁，需要轮询等待
        DefaultRedisScript<List> script = new DefaultRedisScript<>(
                """
                local cached = redis.call('get', KEYS[1])
                if cached then
                    return {'CACHED', cached}
                end
                local acquired = redis.call(
                    'set', KEYS[2], ARGV[1], 'NX', 'EX', ARGV[2]
                )
                if acquired then
                    return {'OWNER', ''}
                end
                return {'WAIT', ''}
                """,
                List.class
        );
        List<?> decision = redis.execute(
                script,
                List.of(valueKey, lockKey),
                owner,
                "30"
        );
        String decisionType = String.valueOf(decision.get(0));
        // 情况一：缓存命中，直接返回缓存中的结果。
        if ("CACHED".equals(decisionType)) {
            return String.valueOf(decision.get(1));
        }
        // 情况二：抢到锁，执行 producer 并将结果写入缓存。
        if ("OWNER".equals(decisionType)) {
            try {
                stateMachine.transit(key, SingleflightStateMachine.State.RUNNING);
                String result = runProducer(key, ttl, producer);
                redis.opsForValue().set(
                        valueKey,
                        result,
                        ttl
                );
                return result;
            } finally {
                // 无论 producer 成功还是抛出异常，都必须释放锁。
                redis.delete(lockKey);
            }
        }

        // 情况三：未抢到锁时轮询等待结果，超时后自行调用，保持原有兜底逻辑。
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String result = redis.opsForValue().get(valueKey);
            if (result != null) {
                return result;
            }
            // 每次轮询间隔 50 毫秒，避免高频空转消耗 CPU。
            // 这里把“线程休眠”的小操作直接内联进轮询循环，因为它只在本处使用一次，
            // 没必要单独拆出一个方法，内联后读代码时能顺着循环看到完整的等待节奏。
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                // 线程被中断说明外部希望停止等待，需要恢复中断标志并立即抛异常，
                // 避免循环被异常打断后线程状态丢失，也避免把中断信号悄悄吞掉。
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "singleflight wait interrupted",
                        e
                );
            }
        }
        takeover.register(key, ttl, producer);
        throw new RetryableSingleflightException(
                "wait timeout, producer registered for timeout takeover",
                null
        );
    }
    private String executeLocal(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        long expiresAt = System.currentTimeMillis()
                + ttl.toMillis();
        localExpiry.putIfAbsent(key, expiresAt);
        // computeIfAbsent：只有 key 第一次出现时才创建新的 CompletableFuture，
        // 后续的并发请求会复用同一个 future，从而实现请求合并。
        CompletableFuture<String> future =
                localCache.computeIfAbsent(
                        key,
                        ignored -> CompletableFuture.supplyAsync(
                                producer
                        )
                );
        try {
            // join() 会阻塞等待第一个请求的 producer 执行完成。
            return future.join();
        } finally {
            localExpiry.putIfAbsent(key, expiresAt);
        }
    }
    private String runProducer(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        try {
            // 抢到锁的这一次真实执行生产者，成功结果由 Singleflight 分发给等待方。
            String value = producer.get();
            // 从这里开始是新增的：执行成功 → 终态 SUCCEEDED，等待方读到后直接回放结果缓存。
            stateMachine.transit(key, SingleflightStateMachine.State.SUCCEEDED);
            // 新增结束
            return value;
        } catch (RuntimeException error) {
            // 失败先分类，再决定是"直接抛错"还是"登记重试"。
            FailureClassifier.FailureType type =
                    failureClassifier.classify(error);
            // 从这里开始是新增的：无论哪类失败都先记下原因，等待方与排障都能读到。
            stateMachine.markReason(key, error.getClass().getSimpleName());
            // 新增结束
            if (type
                    == FailureClassifier.FailureType.NON_RETRYABLE) {
                // 从这里开始是新增的：不可重试 → 终态 FAILED，等待方读到后立刻失败，不再空等。
                stateMachine.transit(key, SingleflightStateMachine.State.FAILED);
                // 新增结束
                throw error;
            }
            // 从这里开始是新增的：可重试 → 先转到 TAKEN_OVER，再由接管服务在 TTL 过期后重试。
            stateMachine.transit(key, SingleflightStateMachine.State.TAKEN_OVER);
            // 新增结束
            takeover.register(
                    key,
                    ttl,
                    producer
            );
            throw new RetryableSingleflightException(
                    "producer registered for timeout takeover",
                    error
            );
        }
    }
    @Scheduled(fixedDelay = 600_000)
    public void clearExpiredLocalCache() {
        long now = System.currentTimeMillis();
        // 遍历过期时间表，找出所有已过期的 key。
        for (Map.Entry<String, Long> entry
                : localExpiry.entrySet()) {
            if (entry.getValue() <= now) {
                String key = entry.getKey();
                CompletableFuture<String> future =
                        localCache.get(key);
                // 只有当 future 已完成或不存在时，才安全地清理过期条目。
                if (future == null || future.isDone()) {
                    localExpiry.remove(key, entry.getValue());
                    localCache.remove(key, future);
                }
            }
        }
    }
    private boolean redisAvailable() {
        try {
            String pong = redis.execute(
        (RedisCallback<String>) connection -> connection.ping()
);
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception ignored) {
            // 任何异常（网络超时、连接拒绝等）都视为 Redis 不可用。
            return false;
        }
    }
    public record FencedValue(
            // AI 生成的结果文本。
            String value,
            // 单调递增令牌，用于防止旧结果覆盖新结果。
            long token
    ) {
    }
}
```

**SchedulingConfig** 已在初始化 4 中创建，无需重复定义。`@Scheduled` 注解依赖 Spring 的 `@EnableScheduling` 配置类。

---

### 出题前 1.1：为 Singleflight 补齐失败分类、超时接管与请求生命周期状态机

**本小节目的：** 上一步的 `RedisSingleflight` 只解决了「同一个 key 只真正调一次 AI」，真跑起来还漏了三个洞：生产者失败后直接上抛，没有区分「等一会儿重试可能就好了」和「重试多少次都一样」；可重试的失败当场重试，多个超时任务会在同一时刻集体打爆 AI；一次请求走到哪一步、为什么失败，Redis 里没有任何痕迹，排障只能翻日志。本小节把这三个洞补上。

**本小节流程：** 新建 `FailureClassifier`（判断这次失败值不值得重试）-> 新建 `TimeoutTakeoverService`（登记待接管任务，TTL 到期后错峰重跑）-> 新建 `RetryableSingleflightException`（把「已登记、稍后重试」表达成一种异常）-> 新建 `SingleflightStateMachine`（用 EnumMap 静态转移表 + Redis Hash 记录 PENDING / RUNNING / SUCCEEDED / FAILED / TAKEN_OVER）-> 回到 `RedisSingleflight`，构造器注入这三个协作者，并在 `execute` / `executeRedis` / `runProducer` 三处接上状态流转与失败登记。

**为什么紧接着做：** 这三件事都挂在 `RedisSingleflight` 上，越晚接入改动面越大；而评分、追问、抽题三条链路都已经依赖它，先把可靠性补齐，后面三条链路才能共用同一套失败语义。

---

**第一步：新建 `src/main/java/com/example/interview/infrastructure/singleflight/FailureClassifier.java`。**

这个类只回答一个问题：这次失败还值不值得重试。它沿异常的 `cause` 链一路往下找，只要链上出现超时、连接被拒、限流这类「等一会儿可能就好了」的异常，就判定为 `RETRYABLE`；反过来，JSON 解析失败、参数非法这类异常，重试多少次结果都一样，判定为 `NON_RETRYABLE`。`RedisSingleflight.runProducer` 靠它的结论决定是直接上抛还是登记重试。

```java
// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

import org.springframework.stereotype.Component;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

// ====================================================================
// FailureClassifier - 失败分类器
// 把异常分成"可重试 / 不可重试"两类，供 Singleflight 决定下一步：
// 可重试的登记给 TimeoutTakeoverService，等 TTL 过期后接管重跑；
// 不可重试的（JSON 解析失败、业务校验不过）直接抛出，重试也没意义。
// 判断时沿 cause 链一路往下找，因为网络异常常被上层包装成自定义异常。
// ====================================================================
@Component
public class FailureClassifier {

    // 失败类型：只有 RETRYABLE 才会触发超时接管重试。
    public enum FailureType {
        // 暂时性故障（网络超时、5xx 等），换一次执行就可能成功
        RETRYABLE,
        // 确定性失败（JSON 解析失败、业务校验失败等），重试多少次都一样
        NON_RETRYABLE
    }

    /**
     * 判断一次失败是否值得重试。
     *
     * <p>为什么要遍历整条 cause 链：AI 调用失败常被上层包装成自定义异常，
     * 真正的 SocketTimeoutException 藏在 cause 里；只看最外层会把它误判成
     * 不可重试，白白丢掉一次补救机会。
     *
     * @param error 生产者抛出的异常
     * @return RETRYABLE 表示可以交给超时接管重试，否则为 NON_RETRYABLE
     */
    public FailureType classify(Throwable error) {
        // 从最外层异常开始，逐层往 cause 里面找。
        Throwable current = error;
        while (current != null) {
            // 网络超时 / IO 异常都属于"等一下再试就好"的暂时性故障。
            if (current instanceof SocketTimeoutException
                    || current instanceof TimeoutException
                    || current instanceof java.io.IOException) {
                return FailureType.RETRYABLE;
            }
            // 有些 HTTP 客户端不抛专用异常，只在 message 里写状态码；
            // 5xx 说明是服务端临时故障，同样算可重试。
            String message = current.getMessage();
            if (message != null
                    && (message.contains("HTTP 5")
                    || message.contains("status=5"))) {
                return FailureType.RETRYABLE;
            }
            // 这一层没命中，继续往下一层 cause 找。
            current = current.getCause();
        }
        // 整条链都找不到可重试特征：按不可重试处理，不再浪费重试资源。
        return FailureType.NON_RETRYABLE;
    }
}
// 新增结束
```

---

**第二步：新建 `src/main/java/com/example/interview/infrastructure/singleflight/TimeoutTakeoverService.java`。**

这个类承接「可重试但当场重试没意义」的失败：把 key、租约时长和生产者原样登记到内存 Map，`@Scheduled(fixedDelay = 5000)` 每 5 秒扫一遍，只有过了截止时间的任务才重新执行。重跑走的是 `singleflight.execute` 而不是裸调 `producer`，所以即使别的消费者已经抢先完成，这次重跑也只会拿到缓存结果，不会真的再调一次 AI。

**注意构造器上的 `@Lazy`：** `RedisSingleflight` 持锁失败要登记到本类，本类重跑又要复用 `RedisSingleflight` 的去重，两个类互相构造器注入会形成循环依赖。Spring Boot 2.6 之后默认禁止循环依赖，所以这里给参数加 `@Lazy`，先拿到代理对象，真正调用 `execute` 时才解析真身，循环即被打破。

```java
// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

// ====================================================================
// TimeoutTakeoverService - AI 调用超时接管
// Singleflight 里的生产者失败且属于"可重试"时，不立刻重试，而是把任务
// 登记到这里；由 @Scheduled 定时扫描，等 TTL 过期后再统一重跑，
// 让重试压力错峰，避免多个超时任务在同一时刻集体打爆 AI 服务。
// ====================================================================
@Service
public class TimeoutTakeoverService {

    // 一条待接管任务：key 用于复用 Singleflight 去重，deadline 是允许重试的时间点。
    private record TakeoverTask(
            String key,
            Duration ttl,
            Supplier<String> producer,
            long deadline
    ) {
    }

    // 用 Singleflight 重跑任务而不是裸调 producer：重试也要走去重，避免重复调 AI。
    private final RedisSingleflight singleflight;
    // 内存任务表：key -> 待接管任务；会被扫描线程和注册线程并发访问，用 ConcurrentHashMap。
    private final Map<String, TakeoverTask> tasks =
            new ConcurrentHashMap<>();

    // 构造器注入 Singleflight；任务表是服务自己的状态，不需要外部提供。
    // 加 @Lazy：RedisSingleflight 与 TimeoutTakeoverService 互相依赖（前者持锁失败要登记接管，
    // 后者重跑又要复用前者的去重），构造器注入会形成循环依赖，Spring Boot 2.6 之后默认禁止，
    // 加 @Lazy 后这里先拿到一个代理对象，真正调用 execute 时才解析真身，循环即被打破。
    public TimeoutTakeoverService(
            @Lazy RedisSingleflight singleflight
    ) {
        this.singleflight = singleflight;
    }

    /**
     * 注册一个待接管任务：在给定 TTL 内如果没人完成该 key 的执行，
     * 调度器会在超时后主动重新执行 producer。
     *
     * <p>为什么注册而不是立即重试：AI 调用超时往往是因为网络抖动或服务
     * 瞬时过载，立刻重试大概率还是失败。先登记到内存 Map 并记录截止时间，
     * 由 @Scheduled 的扫描任务在 TTL 过期后统一重试，把重试压力错峰分散，
     * 避免多个超时任务在同一时刻集体打爆 AI 服务。
     *
     * @param key      与 RedisSingleflight 相同的业务 key，重试时复用其去重能力
     * @param ttl      接管期限，超过该时长仍无结果才允许重试
     * @param producer 真正执行 AI 调用的生产函数
     */
    public void register(
            String key,
            Duration ttl,
            Supplier<String> producer
    ) {
        // 同一 key 重复注册会覆盖旧任务：后登记的时间更近，以它为准。
        tasks.put(
                key,
                new TakeoverTask(
                        key,
                        ttl,
                        producer,
                        // 截止时间 = 当前时间 + TTL，扫描时用它判断有没有到期。
                        System.currentTimeMillis()
                                + ttl.toMillis()
                )
        );
    }

    /**
     * 每 5 秒扫描一次所有已注册任务，把已经到期的任务重新投递执行。
     *
     * <p>为什么用 removeIf 而不是 for 循环：removeIf 在遍历的同时删除满足
     * 条件的元素，语义与"执行完就移除"完全吻合，且 ConcurrentHashMap 的
     * removeIf 是线程安全的，不会在扫描过程中与 register 并发写入冲突。
     *
     * <p>重试本身走 singleflight.execute：即使重试时已有其他消费者抢先
     * 完成了该 key 的执行，execute 的去重逻辑会让这次重试直接拿到缓存结果，
     * 不会真的重复调用 AI。执行失败的任务保留在 Map 中等待下一个扫描周期。
     */
    @Scheduled(fixedDelay = 5000)
    public void takeOverExpired() {
        // 整轮扫描用同一个"现在"，判断到期的口径保持一致。
        long now = System.currentTimeMillis();
        tasks.entrySet().removeIf(entry -> {
            TakeoverTask task = entry.getValue();
            // 未到截止时间就暂不处理，留给后面的扫描周期。
            if (task.deadline() > now) {
                return false;
            }
            try {
                // 重跑走 singleflight.execute：别的消费者已完成也能直接拿结果，
                // value -> value 表示结果原样返回，不会再真的重复调一次 AI。
                singleflight.execute(
                        task.key(),
                        task.ttl(),
                        task.producer(),
                        value -> value
                );
            } catch (RuntimeException ignored) {
                // 重试仍然失败（例如 AI 持续不可用），保留任务等待下轮再试。
                return false;
            }
            // 执行成功：从任务表里移除，本轮不用再管它。
            return true;
        });
    }
}
// 新增结束
```

---

**第三步：新建 `src/main/java/com/example/interview/infrastructure/singleflight/RetryableSingleflightException.java`。**

这是一个很薄的异常类型，作用是给调用方一个明确的信号：这次请求没有失败到底，任务已经被 `TimeoutTakeoverService` 登记，TTL 过期后会被重新执行。调用方拿到它时应当把它当成「暂时没结果」，而不是当成「请求挂了」。

```java
// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

/**
 * 可重试的 Singleflight 生产者异常。
 *
 * <p>当生产者抛出此异常时，表示失败是暂时性的（如网络超时），
 * 调用方应将任务注册到 TimeoutTakeoverService 等待重试。
 *
 * <p>为什么单独定义一个异常类型而不是复用 RuntimeException：
 * 等待方需要把"被接管的失败"和"真正的业务失败"区分开——前者应该稍后重查，
 * 后者应该立刻报错；靠一个专用类型判断，比在消息字符串里找关键字可靠。
 */
public class RetryableSingleflightException
        extends RuntimeException {
    /**
     * @param message 说明信息，通常写明"已注册到超时接管"
     * @param cause   原始失败原因，保留堆栈便于排查根因
     */
    public RetryableSingleflightException(
            String message,
            Throwable cause
    ) {
        super(message, cause);
    }
}
// 新增结束
```

---

**第四步：新建 `src/main/java/com/example/interview/infrastructure/singleflight/SingleflightStateMachine.java`。**

前面三步解决的是「失败之后怎么办」，这一步解决的是「现在到底卡在哪」。它把一次请求的生命周期显式建模成五个状态：`PENDING`（已登记，还没轮到执行）、`RUNNING`（已抢到锁，正在执行生产者）、`SUCCEEDED` / `FAILED`（两个终态，等待方读到后分别回放结果或立刻失败）、`TAKEN_OVER`（可重试失败，已交出去等待接管重试）。状态转移规则写在一张 `EnumMap` 静态表里，`transit` 时先查表、非法转移直接拒绝，避免状态被乱改；同时把当前状态与失败原因写进 Redis Hash，排障时直接 `HGETALL` 就能看到这次请求卡在哪一步、为什么失败。

```java
// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.infrastructure.singleflight;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

// ============================================================
// Single-flight 请求生命周期状态机
// ============================================================
// 职责：把"同一个 key 的这次 AI 调用现在走到哪一步"显式建模出来，
// 落库到 Redis Hash，供跨节点的其他请求查询与决策。
//
// 为什么 Single-flight 需要一个状态机：
//   只有"锁 + 结果缓存"两个设施时，等待方无法区分下面三种情况，
//   只能靠轮询猜测：
//     1. 执行方正在跑（应该继续等）；
//     2. 执行方跑挂了且失败不可重试（应该立刻报错，不要让用户白等）；
//     3. 执行方跑挂了但失败可重试（应该交给接管服务，而不是所有人一起重试）。
//   把生命周期显式建模后，等待方读一次状态就能做出正确决策，
//   同时每一次转移都留下痕迹，排障时能直接看到"卡在哪一步"。
//
// 与 FencingToken 的分工：
//   FencingToken 解决"旧结果不许覆盖新结果"；
//   本状态机解决"当前这次请求处于哪个阶段"。
//   前者管数据新旧，后者管流程阶段，两者互补。
// ============================================================
@Component
public class SingleflightStateMachine {

    // 状态在 Redis 中的 key 前缀，实际 key 为 sfs:state:{业务key}。
    private static final String STATE_PREFIX = "sfs:state:";
    // 状态记录的存活时间：略长于生产者 TTL，保证接管重试期间状态仍可查。
    private static final Duration STATE_TTL = Duration.ofMinutes(10);
    // Hash 中的字段名。
    private static final String FIELD_STATE = "state";
    private static final String FIELD_FENCE = "fence";
    private static final String FIELD_REASON = "reason";

    /**
     * 请求生命周期状态。
     *
     * <p>为什么把终态单独标注出来：终态不允许再被转移到 RUNNING，
     * 防止"已经失败的任务被另一个节点重新拉起来执行两遍"。
     */
    public enum State {
        // 等待中：请求已登记，但还没人抢到执行权。
        PENDING(false),
        // 执行中：某个节点已抢到锁，正在跑 producer。
        RUNNING(false),
        // 成功：结果已写入结果缓存，后来者可直接回放（终态）。
        SUCCEEDED(true),
        // 不可重试失败，终态，不再重试。
        FAILED(true),
        // 可重试失败：已交给接管服务，等 TTL 过期后重试。
        TAKEN_OVER(true);

        // 是否为终态：终态不允许再被转移回 RUNNING。
        private final boolean terminal;

        State(boolean terminal) {
            this.terminal = terminal;
        }

        public boolean terminal() {
            return terminal;
        }
    }

    /**
     * 合法转移表。
     *
     * <p>为什么用 EnumMap 静态表而不是一串 if：转移规则是这个类的核心契约，
     * 用"状态 → 允许的下一批状态"表达出来，新增状态时只改这张表，
     * 校验逻辑一行都不用动；同时也天然拒绝了自环和回退。
     */
    private static final Map<State, Set<State>> TRANSITIONS =
            new EnumMap<>(State.class);

    static {
        // PENDING 只能被推进成 RUNNING，或直接被判定为 FAILED。
        TRANSITIONS.put(State.PENDING, Set.of(State.RUNNING, State.FAILED));
        // RUNNING 有三种出口：执行成功、不可重试失败、可重试（转交接管）。
        TRANSITIONS.put(State.RUNNING, Set.of(
                State.SUCCEEDED,
                State.FAILED,
                State.TAKEN_OVER
        ));
        // 下面两个写成空集合：SUCCEEDED / FAILED 是终态，不允许再被推进。
        TRANSITIONS.put(State.SUCCEEDED, Set.of());
        TRANSITIONS.put(State.FAILED, Set.of());
        // TAKEN_OVER 是被接管的中转态，接管重试后仍可能成功或失败。
        TRANSITIONS.put(State.TAKEN_OVER, Set.of(State.SUCCEEDED, State.FAILED));
    }

    // Redis 操作模板：状态存在 Redis Hash 里，多个实例都能读到同一份。
    private final StringRedisTemplate redis;

    // 构造器注入 Redis 模板。
    public SingleflightStateMachine(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 登记一次请求：不存在的状态置为 PENDING，已存在则保持不变。
     *
     * @param key 业务 key（与 Singleflight 的 key 一致）
     * @return 登记后的当前状态
     */
    public State register(String key) {
        // 拼出状态在 Redis 里的 key：sfs:state:{业务key}。
        String redisKey = STATE_PREFIX + key;
        // putIfAbsent：只有还不存在时才写 PENDING，已存在就保持原状态（重复登记无副作用）。
        redis.opsForHash().putIfAbsent(redisKey, FIELD_STATE, State.PENDING.name());
        // 续期 TTL，避免某个 key 一直没人推进却永久占着内存。
        redis.expire(redisKey, STATE_TTL);
        // 返回登记后的真实状态（可能刚被另一个节点推进过）。
        return current(key);
    }

    /**
     * 尝试把状态从当前值推进到目标值。
     *
     * <p>为什么返回 boolean 而不是抛异常：状态转移失败在 Single-flight 里是
     * "正常竞争结果"（例如另一个节点已经写成功），调用方据此走回放分支即可，
     * 用异常表达会污染控制流。
     *
     * @param key    业务 key
     * @param target 目标状态
     * @return 转移成功返回 true；当前状态不存在、已是终态或不是合法后继返回 false
     */
    public boolean transit(String key, State target) {
        // 先读当前状态；从没登记过（null）就谈不上转移。
        State from = current(key);
        if (from == null) {
            return false;
        }
        // 查转移表：目标必须是当前状态的合法后继，否则拒绝（挡掉回退和自环）。
        Set<State> allowed = TRANSITIONS.getOrDefault(from, Set.of());
        if (!allowed.contains(target)) {
            return false;
        }
        // 合法则写入新状态，并续期 TTL。
        String redisKey = STATE_PREFIX + key;
        redis.opsForHash().put(redisKey, FIELD_STATE, target.name());
        redis.expire(redisKey, STATE_TTL);
        return true;
    }

    /**
     * 读取当前状态。
     *
     * @param key 业务 key
     * @return 状态；从未登记过或已过期返回 null
     */
    public State current(String key) {
        // 从 Redis Hash 里取 state 字段。
        Object raw = redis.opsForHash().get(STATE_PREFIX + key, FIELD_STATE);
        // 没登记过、或者 key 已过期 → 返回 null，调用方按"无状态"处理。
        if (raw == null) {
            return null;
        }
        // 存进去的是枚举名字符串，这里再还原回枚举对象。
        return State.valueOf(raw.toString());
    }

    /**
     * 记录这次请求绑定的围栏令牌，便于排障时对照"结果属于哪一次执行"。
     *
     * @param key   业务 key
     * @param fence 由 FencingToken 递增得到的令牌
     */
    public void bindFence(String key, long fence) {
        // 把本次执行的围栏令牌写进同一个 Hash，排障时能对照"结果属于哪一次执行"。
        redis.opsForHash().put(STATE_PREFIX + key, FIELD_FENCE, Long.toString(fence));
    }

    /**
     * 记录失败原因，供等待方与排障使用。
     *
     * @param key    业务 key
     * @param reason 失败原因摘要（不要放入完整堆栈，避免占用 Redis）
     */
    public void markReason(String key, String reason) {
        // 只存失败原因摘要（类名或简短说明），不放完整堆栈，避免撑大 Redis value。
        redis.opsForHash().put(STATE_PREFIX + key, FIELD_REASON, reason);
    }
}
// 新增结束
```

---

**第五步：回到 `RedisSingleflight`，把上面三个协作者接进去。**

上一步给出的 `RedisSingleflight` 已经是接线后的完整版本，这里只说清三处接线点，读代码时对照着看：

1. **构造器**新增 `FailureClassifier`、`TimeoutTakeoverService`、`SingleflightStateMachine` 三个参数，`execute` 进来的第一件事就是 `stateMachine.register(key)`，把这次请求登记成 `PENDING`。
2. **`executeRedis` 的 OWNER 分支**：抢到锁后先 `transit(key, RUNNING)`，再把执行动作交给 `runProducer`；`WAIT` 分支轮询 30 秒仍拿不到结果时，不再自己兜底调一次 AI，而是 `takeover.register` 后抛 `RetryableSingleflightException`，把这次调用交给接管服务错峰重跑。
3. **`runProducer`** 是生产者唯一的执行口：成功 `transit(key, SUCCEEDED)`；失败先 `markReason` 记下异常类型，再用 `FailureClassifier` 分类——不可重试的直接 `transit(key, FAILED)` 并上抛，可重试的先 `transit(key, TAKEN_OVER)` 再登记接管。

**验证方式：** 把 AI 端点临时指到一个不通的地址，调用抽题接口，预期：第一次请求抛 `RetryableSingleflightException`；`TimeoutTakeoverService` 里的任务数 +1；Redis 里 `HGETALL sfs:state:{key}` 能看到 `PENDING -> RUNNING -> TAKEN_OVER` 的流转和失败原因；TTL 过后接管服务重新执行并写回缓存。再把 AI 端点改成一个返回非法 JSON 的地址，预期直接抛异常、状态停在 `FAILED`，不会被登记重试。

---
### 出题 1：创建题目表、实体和 Mapper

**本小节目的：** 创建题目表及其对应的实体和 Mapper，用于持久化每道面试题，保存其所属会话、关联岗位、正文内容、考察分类、顺序号和状态（`PRE_GENERATED` / `ACTIVE`）。

**本小节流程：** 设计题目表 -> 创建实体和 Mapper -> 配置题目状态 -> 查询和保存验证。

---

**第一步：在 `docker/mysql/01-schema.sql` 中创建 `interview_question` 表。**

这张表存每一道面试题。**关键在 `status` 列**：后台预生成下一题时，题目先以 `PRE_GENERATED` 落库，先把"某场面试的第几题"这个题号占住，避免两个并发请求各生成一道、撞上 `uk_question_session_sequence` 唯一键；等取题接口用 CAS 把它改成 `ACTIVE`，才允许展示给候选人作答。为了让"按会话 + 题号找最大号、再按状态过滤"这条取题查询走索引，额外建了 `(session_id, sequence_no, status)` 联合索引。

```sql
CREATE TABLE IF NOT EXISTS interview_question (
    id VARCHAR(36) PRIMARY KEY,
    session_id VARCHAR(36) NOT NULL,
    position_id BIGINT NOT NULL,
    content LONGTEXT NOT NULL,
    category VARCHAR(64) NOT NULL,
    sequence_no INT NOT NULL,
    -- 题目状态：PRE_GENERATED 表示后台预生成、已占住题号但还没展示；
    -- ACTIVE 表示可用、可展示（CAS 激活或同步兜底直接写入）。
    status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_question_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id),
    CONSTRAINT fk_question_position
        FOREIGN KEY (position_id) REFERENCES interview_position (id),
    UNIQUE KEY uk_question_session_sequence (session_id, sequence_no),
    INDEX idx_question_session (session_id),
    -- 取题时按 session_id + sequence_no 找最大号、并按 status 过滤，这个联合索引正好覆盖。
    INDEX idx_question_session_status (session_id, sequence_no, status)
) ENGINE = InnoDB;
```

---

**第二步：创建 `src/main/java/com/example/interview/infrastructure/mysql/QuestionEntity.java` 文件。**

这个文件是**题目表的数据实体**，对应数据库中的 `interview_question` 表。它的职责是将数据库中的每一行题目记录映射为 Java 对象，包含题目的所属会话、关联岗位、正文内容、考察分类、顺序号和状态等字段。**`sessionId` 和 `sequenceNo` 共同帮助系统定位某场面试中的第几题；`status` 决定这道题能不能被取出来作答。**

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;    // 实体用 @Data 让 Lombok 生成 getter/setter

// QuestionEntity 是一道面试题的数据库记录，对应 interview_question 表，每条记录代表一次
// AI 生成的面试题。它承载了题目的全部落库字段：主键 id、所属会话 sessionId、关联岗位
// positionId、题目正文 content、考察分类 category、顺序号 sequenceNo，以及题目状态 status。
// 其中 sessionId 与 sequenceNo 共同标定“某场面试会里的第几题”，status 记录题目状态
// （PRE_GENERATED / ACTIVE），前端只展示 ACTIVE 的题目。实体通过 MyBatis-Plus 的 @TableName 注解与表对应，
// 由 QuestionMapper 驱动增删改查。
@Data
@TableName("interview_question")
public class QuestionEntity {
    // 数据库主键或对象唯一标识；实体入库后通常由数据库生成。
    // 使用 UUID 字符串格式确保全局唯一性。
    private String id;
    // 面试会话 ID，用来贯穿题目、答案、评分和报告。
    // 通过这个字段将同一场面试的所有题目关联在一起。
    private String sessionId;
    // 关联的岗位 ID，用来确定本次匹配或面试目标。
    // 引用 position 表的主键，用于在生成题目时获取岗位要求。
    private Long positionId;
    // 题目、答案、追问或报告的正文内容。
    // AI 生成的题目文本存储在这个字段中。
    private String content;
    // 题目所属的考察分类，例如 Java、数据库或系统设计。
    // 用于前端按类别展示和能力维度分析。
    private String category;
    // 题目在当前面试会话中的顺序号，从 1 开始递增。
    // 决定题目在面试中的展示顺序。
    private Integer sequenceNo;
    // 题目当前的状态，对应 interview_question.status 列（VARCHAR(30)，默认 'ACTIVE'）。
    // 取值只有两个：PRE_GENERATED 表示后台预生成、已占住题号但还没展示给候选人；
    // ACTIVE 表示可用、可展示（取题时 CAS 从 PRE_GENERATED 改过来，或同步兜底直接写 ACTIVE）。
    // 前端只把 ACTIVE 的题当成本题展示，避免拿到还没准备完的预生成题。
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getPositionId() {
        return positionId;
    }

    public void setPositionId(Long positionId) {
        this.positionId = positionId;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Integer getSequenceNo() {
        return sequenceNo;
    }

    public void setSequenceNo(Integer sequenceNo) {
        this.sequenceNo = sequenceNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

---

**第三步：创建 `src/main/java/com/example/interview/infrastructure/mysql/QuestionMapper.java` 文件。**

这个文件是**题目表的 MyBatis-Plus Mapper 接口**。它继承 `BaseMapper<QuestionEntity>`，自动获得对 `interview_question` 表的标准 CRUD 操作能力（增删改查、分页、批量操作等），无需手写任何 SQL。后续的 `QuestionController`、`NextQuestionPreGeneratorService` 和 `AnswerRepository` 都直接通过这个 Mapper 操作题目数据。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface QuestionMapper
        extends BaseMapper<QuestionEntity> {
}
```

---

### 出题 2：创建 AI Facade

**本小节目的：** 封装 AI 调用细节，让出题、评分、追问和报告使用统一的 AI 访问入口。通过这个 Facade 层，上层业务代码不需要关心 AI 模型的调用方式、提示词的组装细节和 JSON 结果的解析过程。

**本小节流程：** 定义 AI 请求模型 -> 配置模型客户端 -> 编写 Facade -> 处理返回和异常 -> 暴露业务方法。

---

**第一步：创建 `src/main/java/com/example/interview/ai/GeneratedQuestion.java` 文件。**

这个文件是 **AI 出题结果的纯数据载体**，在 Facade 返回题目之前使用。它只保存 AI 生成的题目正文和分类两个核心字段，避免 Facade 直接返回一大段未校验的 JSON 字符串。`record` 类型使其不可变，保证了数据的线程安全性和语义清晰性。这个对象不等同于数据库中的 `QuestionEntity`，它是应用层用于传递 AI 生成结果的中转对象。

```java
package com.example.interview.ai;

// GeneratedQuestion 是 AI 出题方法落库之前的纯数据载体，只在 InterviewAiFacade.generateQuestion
// 与上层调用方（如 QuestionController）之间传递。它用 record 定义、天然不可变且线程安全，
// 只承载两个经过非空校验的字段：题目正文 content 和考察分类 category。需要特别注意的是，它
// 并不是数据库里的 QuestionEntity——后者还要额外带上 sessionId、positionId、sequenceNo、status
// 等落库字段；这里的对象只管“AI 生成出来、还没有持久化”这一小段生命周期。
public record GeneratedQuestion(
        // 经过非空校验的题目正文。
        // AI 返回的 JSON 中的 content 字段，确保不为空后才封装到此记录中。
        String content,
        // 题目所属分类，例如 Java、数据库或系统设计。
        // AI 返回的 JSON 中的 category 字段，用于后续按类别分析和展示。
        String category
) {
}
```

---

**第二步：创建 `src/main/java/com/example/interview/ai/InterviewAiFacade.java` 文件。**

这个文件是整个**AI 交互的核心门面类**。它封装了对 Spring AI `ChatClient` 的调用细节，提供统一的 `generateQuestion()` 方法供出题模块调用。另外，`ChatClient` 的 Spring AI 依赖必须已经加入项目，`RedisSingleflight` 已在「出题前」创建，`SkillRegistry` 则在「十五、Skill 模块」中定义；只有这些依赖存在，下面的 Facade 构造器才有真实来源。

依赖说明：
- **`ChatClient`（Spring AI）**：由 Spring AI 提供，负责向大语言模型发送提示词并接收返回结果。
- **`RedisSingleflight`（已在出题前创建）**：用于合并相同请求，避免同一题目重复调用 AI。
- **`SkillRegistry`（Skill 模块中定义，见「十五、Skill 模块」）**：启动时加载 `resources/skills` 下的 Markdown 规则，出题时通过 `skillPrompt("question-generation")` 把出题规则拼到提示词最前面。

```java
package com.example.interview.ai;

import com.example.interview.infrastructure.singleflight.RedisSingleflight;
import com.example.interview.skill.SkillDefinition;
import com.example.interview.skill.SkillRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

// InterviewAiFacade 是整个项目里所有 AI 交互的统一门面（Facade），后续的出题、评分、追问、
// 报告、表现分析等方法都会挂在这个类下，避免业务代码四处散落对大模型的调用细节。它内部
// 注入三个核心依赖：ChatClient（Spring AI，负责真正向大模型发送提示词并接收文本响应）、
// RedisSingleflight（负责把相同 key 的请求合并成一次 AI 调用）、SkillRegistry（出题 / 评分 /
// 追问 / 报告各自的业务规则，以 Markdown 形式统一加载）。构造器通过 ChatClient.Builder
// 读取 application.yml 里的模型端点、API Key 等配置后 build 出可用的 ChatClient。
@Service
public class InterviewAiFacade {
    // Spring AI 的聊天客户端，用来向模型发送提示词并接收结果。
    // 通过 ChatClient.Builder 构建，读取 application.yml 中的 AI 模型配置。
    private final ChatClient chatClient;
    // 合并相同请求，避免同一题重复调用模型。
    // 使用 RedisSingleflight 的 execute 方法包装 AI 调用，保证相同 key 的请求只执行一次 producer。
    private final RedisSingleflight singleflight;
    // 业务 Skill 注册表，启动时把 resources/skills 下的 Markdown 规则文件统一加载进来。
    // 注入后，出题 / 评分 / 追问 / 报告四条链路才能通过 skillPrompt(name) 把规则拼进提示词。
    private final SkillRegistry skills;

    public InterviewAiFacade(
        // 请求合并器，由 Spring 自动注入已配置好的 RedisSingleflight 实例。
        RedisSingleflight singleflight,
        // ChatClient 的构建器，负责读取 Spring AI 配置并创建可执行的 ChatClient。
        ChatClient.Builder chatClientBuilder,
        // 业务 Skill 注册表，由 Spring 自动注入；追加在参数末尾不影响既有调用方。
        SkillRegistry skills
    ) {
        this.singleflight = singleflight;
        // Builder 负责读取 Spring AI 配置（模型端点、API Key、超时等）并创建可执行的 ChatClient。
        this.chatClient =
                chatClientBuilder.build();
        this.skills = skills;
    }
```

---

**第三步：在 `InterviewAiFacade` 中实现题目生成方法 `generateQuestion()`。**

这个方法在提示词最前面拼上 `question-generation` Skill，再把岗位和简历拼成业务指令，限制模型只返回题目 JSON，然后检查 `content` 是否为空。它是出题流程中调用 AI 的核心方法，接收面试上下文参数，调用模型并解析和校验返回结果。

```java
    // generateQuestion 是 AI 出题流程的核心方法，负责“把岗位和简历拼装成提示词 → 调用大模型 →
    // 解析并校验返回的 JSON → 产出结构化的题目”。它把一排上下文参数（会话、岗位名称、岗位描述、
    // 技能要求、简历正文、题目序号）组合成一段面向中文面试官角色的提示词，限定模型只返回
    // {"content","category"} 这样的小 JSON；拿到响应后先转成 Jackson 节点，再安全地提取 content
    // 和 category 字段：若 AI 返回的题目正文为空，直接抛出 IllegalStateException，防止空题目
    // 被保存进数据库，保证入库的每一道题都真实可作答。
    // 参数 sessionId：当前面试会话 ID，用于日志追踪和定位某一场面试。
    // 参数 positionName：目标岗位名称，如“Java 后端工程师”。
    // 参数 positionDescription：岗位描述，包含职责和任职要求。
    // 参数 requiredSkills：岗位要求的技能列表。
    // 参数 resumeContent：候选人的简历正文，用于严格基于真实经历定制题目。
    // 参数 sequenceNo：当前是第几道题，从 1 开始递增。
    // 返回值：校验后的 GeneratedQuestion 对象，包含题目正文 content 和分类 category。
    public GeneratedQuestion generateQuestion(
            UUID sessionId,
            String positionName,
            String positionDescription,
            String requiredSkills,
            String resumeContent,
            int sequenceNo
    ) {
        // 每次出题都先把 question-generation Skill 拼在提示词最前面，再追加业务指令。
        var prompt = skillPrompt("question-generation")
                + String.format(
                "你是一位中文技术面试官。"
                        + "请根据候选人的真实简历和目标岗位，"
                        + "为第 %d 题生成一道技术面试题。\n"
                        + "目标岗位：%s\n"
                        + "岗位说明：%s\n"
                        + "岗位技能要求：%s\n"
                        + "候选人简历：\n%s\n"
                        + "要求：题目必须围绕简历中明确出现的"
                        + "项目、职责或技术栈，不要编造经历。"
                        + "只返回 JSON："
                        + "{\"content\":\"题目正文\","
                        + "\"category\":\"考察分类\"}",
                sequenceNo,
                positionName,
                positionDescription,
                requiredSkills,
                excerpt(resumeContent, 6000)
        );

        // 把"出题"接入 RedisSingleflight，凑齐 评分/追问/出题 三条去重链路。评分（judge）和追问
        // （FollowUpGenerationService）都走了 singleflight.executeFenced，出题也必须这样：并发触发
        // 相同 sessionId+题号的请求会各自打一次 AI，浪费且可能产出不一致的题。key 用
        // "question:{sessionId}:{sequenceNo}" 把重复请求合并成一个。注意取题接口走的是独立的
        // "question-view:" key（缓存完整 QuestionView JSON），与这里的 "question:" key（缓存原始
        // 出题 JSON）不同级，互不占用，见 QuestionController 的说明。
        String questionKey = "question:" + sessionId + ":" + sequenceNo;
        var result = singleflight.executeFenced(
                questionKey,
                Duration.ofMinutes(5),
                // 生产函数只负责真正调 AI 出题，返回"未解析的原始 JSON 正文"；
                // 去重、幂等、围栏令牌都由单飞框架负责，这里不重复实现。
                () -> chat(prompt)
        );
        // Fencing Token 校验：出题结果缓存有 5 分钟窗口，同 key 在这期间又被重触发时旧结果会被
        // 新结果顶掉。本轮 token 若已不是"当前最新"，说明读到的是过期缓存，直接拒绝，避免旧题
        // 覆盖新题的边界竞态。
        if (!singleflight.acceptFence(questionKey, result.token())) {
            throw new IllegalStateException(
                    "拒绝过期出题结果: sessionId=" + sessionId
                            + ", sequenceNo=" + sequenceNo
            );
        }

        // 调用 AI 模型并解析返回的 JSON 字符串为 Jackson 树节点。
        var node = parseJson(result.value());
        // 从 JSON 节点中提取 content 字段的值。
        var content = node.path("content")
                .asText()
                .trim();
        // 从 JSON 节点中提取 category 字段的值，默认为"简历定制"。
        var category = node.path("category")
                .asText("简历定制")
                .trim();

        // 校验：如果 AI 返回的题目正文为空，则抛出异常，防止保存空题目。
        if (content.isBlank()) {
            throw new IllegalStateException(
                    "AI returned an empty interview question"
            );
        }

        return new GeneratedQuestion(
                content,
                category
        );
    }
    
    // skillPrompt 按业务名称取出对应 Skill 的 Markdown 正文，用「【业务 Skill：名称】…【业务 Skill 结束】」
    // 包起来，拼在提示词最前面，AI 才会优先遵守这些规则。出题、评分、追问、报告四条链路复用同一段
    // 包裹逻辑，因此提取成独立方法，而不是在四个地方各写一遍。名称拼错时 skills.get(name) 会抛
    // IllegalStateException("skill not found: " + name)，第一次调用就暴露，不会静默退化成"没有规则的提示词"。
    // 注入的内容直接读取结构化 SkillDefinition（见 SkillLoader 解析的元数据），把"用途 + 输出契约 + 规则正文"
    // 一次性拼进提示词，因此 Skill 有任何改动，四条链路下次调用自动生效，无需改动任一调用代码。
    // 参数 name：SkillRegistry 中的技能名称，例如 "question-generation"。
    // 返回值：包裹了技能定义、可直接拼入提示词的文本。
    private String skillPrompt(String name) {
        SkillDefinition definition = skills.get(name);
        // 元数据可能为空（兼容没有 front matter 的旧文件），为空的字段不拼进提示词。
        String purpose = blank(definition.purpose())
                ? ""
                : "用途：" + definition.purpose() + "\n";
        String output = blank(definition.outputContract())
                ? ""
                : "必须的输出契约：" + definition.outputContract() + "\n";
        return "【业务 Skill：" + name + "】\n"
                + purpose
                + output
                + definition.content()
                + "\n【业务 Skill 结束】\n";
    }

    // 判断文本是否为空或仅空白，用于决定某段元数据要不要拼进提示词。
    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
```

---

**第四步：在 `InterviewAiFacade` 中补全三个私有辅助方法。**

这些方法被 `generateQuestion()` 和后续的其他 AI 调用方法共用，不能把它们留成未定义调用。三个方法各自承担 AI 调用链中的一个关键环节：**调用模型**、**解析 JSON**、**截断文本**。

```java
    // chat 是通往大模型的最底层封装，把“用 ChatClient 发起一次对话并取出文本响应”这一动作收口，
    // 让上层的生成题目、评分、追问、报告等方法只需要关心提示词，而不用关心模型调用的差异细节。
    // 它通过 chatClient.prompt().user(prompt).call().content() 发请求并取回纯文本。它给 AI 调用加
    // 了“瞬时失败自动重试”：出题 / 评分 / 报告 / 追问四条链路最终都走这一个方法，而它之前只调用一次、
    // 失败即抛。AI 上游偶发超时或返回空串时，就会直接变成 500（表现就是“刚发起取第一题那次请求 500”，
    // 把整场面试开场打断）。这里只对两种“失败后重试大概率能自愈”的情况重试 3 次：
    //   1) TransientAiException —— Spring AI 给“网络抖动 / 上游 5xx”这类瞬时错误打的标签；
    //   2) AI 返回了空串。
    // 鉴权、请求非法这类 NonTransientAiException 不在此列，重试也是白费，放出去让上层处理。
    // 参数 prompt：组装好的提示词字符串。
    // 返回值：AI 模型返回的非空文本内容。
    // 异常：重试后仍失败时抛出最后一次失败原因。
    private String chat(String prompt) {
        RuntimeException lastFailure = null;
        for (int attempt = 0; attempt < 3; attempt += 1) {
            try {
                String content = chatClient.prompt()
                        .user(prompt)
                        .call()
                        .content();
                if (content != null && !content.isBlank()) {
                    return content;
                }
                // 空返回视为瞬时失败：记下原因，短暂退避后重试下一次。
                lastFailure = new IllegalStateException(
                        "AI returned empty content"
                );
            } catch (TransientAiException transientEx) {
                // 只重试瞬时异常；其它异常在 try 块外原样向上抛。
                lastFailure = transientEx;
            }
            if (attempt < 2) {
                try {
                    // 退避递增：1/2 次后分别等 600ms / 1200ms，给上游留出恢复时间。
                    Thread.sleep(600L * (attempt + 1));
                } catch (InterruptedException interrupted) {
                    // 线程被中断：不再重试，把站到的最新失败原因抛回去。
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        throw lastFailure == null
                ? new IllegalStateException("AI call failed unexpectedly")
                : lastFailure;
    }

    // parseJson 负责把模型返回的 JSON 文本解析成 Jackson 的 JsonNode 树节点。之所以统一收敛到
    // 这一个方法，是因为对模型返回的 JSON 做解析、并对解析失败的场景做统一处理，是出题、评分、
    // 追问、报告等所有 AI 方法都要走的一步。解析成功后返回的 JsonNode 可以继续用 path() 方法
    // 做“字段不存在也不抛异常”的安全取值，配合默认值使用起来很顺手。一旦 JSON 格式非法，
    // 就会把底层 JsonProcessingException 包装成 IllegalStateException 抛出，让上层不用再关心
    // 底层 JSON 库的异常类型，错误信息也统一成对业务更友好的文案。
    // 参数 content：AI 返回的 JSON 格式字符串。
    // 返回值：Jackson 的 JsonNode，便于后续用 path() 安全地按路径提取字段。
    // 异常：当 JSON 非法时抛出 IllegalStateException 并保留原始异常链。
    private JsonNode parseJson(String content) {
        try {
            return new ObjectMapper().readTree(content);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "AI returned invalid JSON",
                    e
            );
        }
    }

    // excerpt 是一个文本长度保护小工具，用来控制传入模型的简历正文长度，防止提示词无限膨胀。
    // 生成题目时会把整个简历塞进提示词，而简历可能很长；无限变长的提示词一是会显著增加调用
    // 成本，二是超出模型上下文窗口后反而会干扰模型生成质量。因此这里统一按 maxLength 截断：
    // 若传入文本是 null 直接返回空串兜底，否则超长就只保留前 maxLength 个字符。由于它在出题
    // 等多个 AI 调用里都会用到，保留为独立方法以便复用。
    // 参数 text：原始简历文本。
    // 参数 maxLength：允许保留的最大字符数。
    // 返回值：截断后的文本；当 text 为 null 时返回空字符串。
    private String excerpt(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength
                ? text
                : text.substring(0, maxLength);
    }
}
```

因此 `InterviewAiFacade.java` 至少要补充 `ObjectMapper`、`JsonNode` 和 `JsonProcessingException` 的 import，并把以上方法放进同一个类。后续评分和报告方法是在这个真实 Facade 文件中继续增加的方法，不是重新创建新的 AI 客户端。

---

### 出题 3：创建题目接口并接入请求合并

**本小节目的：** 提供获取当前题目的 HTTP 接口（`POST /api/interviews/{sessionId}/questions`），并保证同一会话的并发请求不会重复调用 AI。取题优先命中后台预生成的题目（用 CAS 改成 `ACTIVE` 后直接返回），没命中才通过 `RedisSingleflight` 合并请求、现场调用 AI。

**本小节流程：** 接收会话请求 -> 校验会话/岗位/简历 -> 按 max(sequence_no) + 1 算目标题号 -> 先 `takePreGenerated` 取预生成题（CAS 激活） -> 未命中走 Singleflight 现场生成 -> 保存并返回题目。

---

**第一步：创建 `src/main/java/com/example/interview/application/QuestionView.java` 文件。**

这个文件是**题目接口返回给前端的只读 DTO（Data Transfer Object）**。它把数据库中的 `QuestionEntity` 转换成前端需要的四个核心字段（ID、正文、分类、顺序号），再加上题目状态字段。使用 `record` 类型使其不可变，不把数据库实体直接暴露给前端，遵循分层架构的隔离原则。

```java
package com.example.interview.application;

import java.util.UUID;

// QuestionView 是题目接口返回给前端的只读 DTO（Data Transfer Object），本质是数据库实体
// QuestionEntity 的“瘦身版 + 面向展示版”。它用 record 定义，天然不可变且线程安全，只暴露
// 前端真正需要的五个字段：题目 ID、正文、分类、顺序号和状态，从而遵循分层隔离原则，避免
// 把数据库实体内部的冗余字段直接透传给前端。前端拿到后可直接渲染正文、按分类分组，并只
// 展示 status 为 ACTIVE 的题目。
public record QuestionView(
        // 题目 ID，提交答案时用于定位题目。
        // 前端在提交答案时需要将此 ID 回传，后端据此查找对应的题目记录。
        UUID id,
        // AI 生成并校验后的题目正文。
        // 前端展示给候选人的面试题目文本。
        String content,
        // 题目考察分类。
        // 如 Java、数据库、系统设计等，用于分类展示和能力分析。
        String category,
        // 当前会话中的题目顺序。
        // 从 1 开始递增，决定题目的展示顺序。
        Integer sequenceNo,
        // 题目状态，前端只展示 ACTIVE 题目。
        // PRE_GENERATED 表示后台预生成、还没被取用；ACTIVE 表示这道题就是当前题、可以作答。
        String status
) {
}
```

---

**第二步：创建 `src/main/java/com/example/interview/application/QuestionController.java` 文件。**

这个文件是**题目获取的 HTTP Controller**。它对外提供 `POST /api/interviews/{sessionId}/questions` 接口（前端取题只有这一个入口，没有 `GET .../questions/current`）。取题时先尝试把后台预生成好的 `PRE_GENERATED` 题目 CAS 激活返回，没命中才通过 `RedisSingleflight` 的请求合并机制现场生成，保证同一会话的并发请求不会重复调用 AI。在调用 `QuestionController` 之前，需要先确认 `InterviewSessionMapper`、`PositionMapper`、`ResumeMapper`、`QuestionMapper`、`InterviewAiFacade`、`RedisSingleflight` 和 `ObjectMapper` 已经准备好并可注入。

下面直接给出 `QuestionController.java` 的完整文件。属性、查询、序号计算、预生成题 CAS（`takePreGenerated`）和 Singleflight 必须全部放在同一个类中，不能把方法片段放在类外。

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.ai.GeneratedQuestion;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.example.interview.infrastructure.singleflight.RedisSingleflight;
// 识别"单飞等待超时"的可重试信号：取题接口在 question-view key 上等门面生成结果，
// 等待窗口耗尽时底层抛 RetryableSingleflightException，捕获后本地短暂重试而非直接 500。
import com.example.interview.infrastructure.singleflight.RetryableSingleflightException;
// 捕获唯一键冲突（可能被 Spring/MyBatis 包装成 DataIntegrityViolationException）：
// 取题的同步兜底与 /start 异步的 prepare 都可能插入同一道首题，撞上 uk_question_session_sequence。
import org.springframework.dao.DataIntegrityViolationException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
// Objects.nonNull 用于过滤掉 max() 在无数据时返回的 NULL。
import java.util.Objects;
import java.util.UUID;

// QuestionController 负责向外暴露“获取当前会话下一道题”的 HTTP 接口，是出题模块的入口层。
// 它只负责组织并串联五件事：校验会话/岗位/简历是否存在、按 max(sequence_no) + 1 计算下一题的
// 序号、先调 takePreGenerated 尝试取后台预生成好的题（命中就直接返回，一个 AI 调用都不发）、
// 未命中才构造 Singleflight 的 key 交给 RedisSingleflight 做请求合并（保证同一会话的并发请求
// 只调用一次 AI）、最后把结果反序列化成前端友好的 QuestionView 返回。Controller 本身不直接
// 接触大模型，AI 调用细节全部收敛在 InterviewAiFacade 里，符合“Controller 只做编排、不写业务
// 规则”的分层约定。
@RestController
@RequestMapping("/api/interviews")
public class QuestionController {
    // 面试会话 Mapper，用于查询会话是否存在以及获取会话详情。
    private final InterviewSessionMapper sessions;
    // 岗位 Mapper，用于查询岗位名称、描述和技能要求。
    private final PositionMapper positions;
    // 简历 Mapper，用于查询候选人的简历内容。
    private final ResumeMapper resumes;
    // 题目 Mapper，用于查询已生成的题目和保存新生成的题目。
    private final QuestionMapper questions;
    // AI Facade，封装了调用 AI 生成题目的业务逻辑。
    private final InterviewAiFacade ai;
    // 请求合并器，保证相同 key 的并发请求只执行一次 AI 调用。
    private final RedisSingleflight singleflight;
    // Jackson JSON 处理器，用于将对象序列化为 JSON 或反序列化 JSON 为对象。
    private final ObjectMapper mapper;
    // 岗位 Controller 实例，与其他协作者一并由 Spring 装配（本类未直接使用，保留依赖关系）。
    private final PositionController positionController;

    public QuestionController(
            // 面试会话 Mapper，由 Spring 自动注入。
            InterviewSessionMapper sessions,
            // 岗位 Mapper，由 Spring 自动注入。
            PositionMapper positions,
            // 简历 Mapper，由 Spring 自动注入。
            ResumeMapper resumes,
            // 题目 Mapper，由 Spring 自动注入。
            QuestionMapper questions,
            // AI Facade 实例，由 Spring 自动注入。
            InterviewAiFacade ai,
            // 请求合并器实例，由 Spring 自动注入。
            RedisSingleflight singleflight,
            // JSON 处理器实例，由 Spring 自动注入。
            ObjectMapper mapper,
            // 岗位 Controller 实例，由 Spring 自动注入（与其他协作者一并保留在装配清单中）。
            PositionController positionController
    ) {
        this.sessions = sessions;
        this.positions = positions;
        this.resumes = resumes;
        this.questions = questions;
        this.ai = ai;
        this.singleflight = singleflight;
        this.mapper = mapper;
        this.positionController = positionController;
    }

    // nextQuestion 处理 POST /api/interviews/{sessionId}/questions，返回当前会话的下一道题。
    // 处理过程分为几个明确的步骤：先用 sessionId 查询会话，不存在就直接抛异常；再根据会话里的
    // positionId 和 resumeId 分别查岗位、简历，两个都缺一不可；接着在同一会话内按 max(sequence_no)+1
    // 推算下一题的序号（注意必须用最大值而不是题目总数，因为后续预生成的题也会占用序号）；然后先调
    // takePreGenerated 尝试取后台预生成好的这道题，命中就立即返回、全程不发 AI 请求；没命中才以
    // “question:{sessionId}:{sequence}” 为 key 交给 RedisSingleflight 做请求合并，相同 key 的并发
    // 请求在此时被收敛成一次真正的 AI 调用；最后把拿到的 JSON 反序列化成 QuestionView。
    // 参数 sessionId：面试会话的唯一标识。
    // 返回值 QuestionView：包含题目 ID、正文、分类、顺序号和状态的只读 DTO。
    // 异常：会话、岗位或简历任一不存在时抛出 IllegalArgumentException。
    @PostMapping("/{sessionId}/questions")
    public QuestionView nextQuestion(
            @PathVariable("sessionId") UUID sessionId
    ) throws JsonProcessingException {
        // 第一步：根据 sessionId 查询面试会话是否存在。
        InterviewSessionEntity session =
                sessions.selectById(sessionId.toString());
        if (session == null) {
            throw new IllegalArgumentException(
                    "interview session not found"
            );
        }

        // 第二步：查询会话关联的岗位信息和简历信息。
        PositionEntity position =
                positions.selectById(session.getPositionId());
        ResumeEntity resume =
                resumes.selectById(session.getResumeId());
        if (position == null || resume == null) {
            throw new IllegalArgumentException(
                    "position or resume not found"
            );
        }

        // 第三步：查询当前会话中已有的最大题目序号。
        Integer maxSequence = questions.selectObjs(
                Wrappers.<QuestionEntity>query()
                        .select("max(sequence_no)")
                        .eq(
                                "session_id",
                                sessionId.toString()
                        )
        ).stream()
                // 过滤掉 max() 返回的 NULL：会话里一题都没有时，SQL 的 max(sequence_no) 是
                // "一行、值为 NULL"，selectObjs 返回 [null]。findFirst() 内部用 Optional.of(null)
                // 会抛 NPE，触发场景正是"第一题还没生成出来时前端来取题"。过滤后走 orElse(0)。
                .filter(Objects::nonNull)
                .findFirst()
                .map(value -> ((Number) value).intValue())
                .orElse(0);
        // 必须按最大 sequence_no 计算，不能按题目总数计算；PRE_GENERATED 题也已经占用序号。
        int sequence = maxSequence + 1;

        // 第四步：先尝试取后台预生成的题。NextQuestionPreGeneratorService 已经把这道题以
        // PRE_GENERATED 落库、占住了题号，这里只需把它 CAS 激活就能返回，全程不调用 AI。
        // 为什么放在 Singleflight 之前：命中预生成时这一趟没有任何 AI 调用，走请求合并纯属多余；
        // 只有没命中（后台还没生成完，或已被别的线程取走）才落到下面的同步兜底。
        QuestionView preGenerated = takePreGenerated(sessionId, sequence);
        if (preGenerated != null) {
            // 命中即返回，下面的同步生成不会执行。
            return preGenerated;
        }

        // 第五步：构造 RedisSingleflight 的 key，用于请求合并。
        // key 命名空间用独立的 "question-view:" 前缀：InterviewAiFacade.generateQuestion 自己也用
        // singleflight 缓存 "question:{sessionId}:{seq}"（缓存的是原始出题 JSON，只有 content/category）。
        // 若取题接口沿用同一个 key：① 回放出错类型的缓存，得到 id/sequenceNo/status 全是 null 的题，
        // 前端 status==='ACTIVE' 校验必失败；② 取题成为该 key 的持有者后，其 producer 内部又会调
        // 门面单飞（同 key），等于在自己已持有的锁上等待，30s 超时抛 500。换用 "question-view:" 前缀后，
        // 取题缓存只存完整的 QuestionView JSON，也不与门面的锁互相占用，两个症状一起消除。
        String key = "question-view:"
                + sessionId
                + ":"
                + sequence;

        // 第六步：通过 RedisSingleflight 执行请求合并。
        // 相同 key 的并发请求只有一个会真正执行 createQuestionJson，其余复用结果。
        // 捕获 RetryableSingleflightException 做本地有限重试：异步 prepare 还在用门面单飞生成首题
        // （生成可能 >30s）时，取题这趟在 question-view key 上等不到结果、底层抛可重试信号。
        // 此时生成方仍在推进，稍后 createQuestionJson 的幂等先查就能直接从库里返回，所以重试
        // 而不是立刻失败。上限 4 次、间隔 1.5 秒，超过仍抛给前端兜底。
        int attempts = 0;
        while (true) {
            try {
                String json = singleflight.execute(
                        key,
                        Duration.ofMinutes(5),
                        () -> createQuestionJson(
                                sessionId,
                                session,
                                position,
                                resume,
                                sequence
                        ),
                        value -> value
                );
                // 第七步：将 JSON 字符串反序列化为 QuestionView 返回给前端。
                return mapper.readValue(json, QuestionView.class);
            } catch (RetryableSingleflightException retryable) {
                if (++attempts >= 4) {
                    throw retryable;
                }
                try {
                    Thread.sleep(1500L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw retryable;
                }
            }
        }
    }

    // createQuestionJson 是作为 RedisSingleflight 的 producer 回调被执行的“真正出题动作”：
    // 只有当某个 key 的第一个请求抢到执行权时，它才会运行，其余并发请求都只等待并复用它的结果。
    // 方法内部做三件事：先用 InterviewAiFacade.generateQuestion 调用大模型拿到经过校验的题目；
    // 再把结果封装成 QuestionEntity 并插入数据库（同步兜底生成的题目直接标记为 ACTIVE，前端可以
    // 马上展示）；最后把实体转成前端友好的 QuestionView 并用 ObjectMapper 序列化成 JSON 字符串。
    // 这样 Singleflight 的缓存层拿到并缓存的就是这个 JSON，后续命中缓存时无需再访问数据库。
    // 参数 sessionId：面试会话 ID。
    // 参数 session：面试会话实体，用于拿 positionId。
    // 参数 position：岗位实体，用于提供岗位名称、描述和技能要求。
    // 参数 resume：简历实体，用于提供候选人的简历解析内容。
    // 参数 sequence：题目序号。
    // 返回值：包含 QuestionView 全部字段的 JSON 字符串。
    private String createQuestionJson(
            UUID sessionId,
            InterviewSessionEntity session,
            PositionEntity position,
            ResumeEntity resume,
            int sequence
    ) {
        // 幂等先查再插：首题有两个生成入口——/start 异步触发的 prepare 会插一道 sequence=1 的
        // ACTIVE 题，取题接口的同步兜底也可能插 sequence=1。两者都在"该题号还没有题"时各插一次，
        // 后插的那个会撞上唯一键 uk_question_session_sequence（接口直接 500）。所以插入前先按
        // (sessionId, sequence) 查一次，prepare 已经生成好就直接复用它，不再新插一道。
        QuestionEntity existing = questions.selectOne(
                Wrappers.<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getSessionId,
                                sessionId.toString()
                        )
                        .eq(
                                QuestionEntity::getSequenceNo,
                                sequence
                        )
                        .in(
                                QuestionEntity::getStatus,
                                "ACTIVE",
                                "PRE_GENERATED"
                        )
        );
        if (existing != null) {
            return toViewJson(existing);
        }

        // 调用 AI Facade 生成题目。
        GeneratedQuestion generated =
                ai.generateQuestion(
                        sessionId,
                        position.getName(),
                        position.getDescription(),
                        position.getSkills(),
                        resume.getParsedContent(),
                        sequence
                );

        // 将 AI 生成的结果封装为数据库实体。
        QuestionEntity question = new QuestionEntity();
        question.setId(UUID.randomUUID().toString());
        question.setSessionId(sessionId.toString());
        question.setPositionId(position.getId());
        question.setContent(generated.content());
        question.setCategory(generated.category());
        question.setSequenceNo(sequence);
        // 同步兜底生成的题目直接进入 ACTIVE 状态，前端可立即展示。
        question.setStatus("ACTIVE");
        // 先查后插挡得住"prepare 已生成"的顺序冲突，但挡不住"两侧恰好同一毫秒插入"的并发竞态。
        // 一旦撞上唯一键，就重新读一次已存在的那一行返回，而不是把异常抛给前端变成 500。
        try {
            questions.insert(question);
        } catch (DataIntegrityViolationException conflict) {
            QuestionEntity winner = questions.selectOne(
                    Wrappers.<QuestionEntity>lambdaQuery()
                            .eq(
                                    QuestionEntity::getSessionId,
                                    sessionId.toString()
                            )
                            .eq(
                                    QuestionEntity::getSequenceNo,
                                    sequence
                            )
                            .in(
                                    QuestionEntity::getStatus,
                                    "ACTIVE",
                                    "PRE_GENERATED"
                            )
            );
            if (winner == null) {
                throw conflict;
            }
            return toViewJson(winner);
        }
        return toViewJson(question);
    }

    // 把已落库（或刚插入）的题目实体转成返回给前端的视图 JSON。
    // 为什么单独成方法：幂等先查与冲突兜底两条路径都要"实体 -> JSON"，共用一份转换逻辑避免重复，
    // 同时把 createQuestionJson 的注意力留在"生成 + 落库"上。序列化失败说明视图对象不可用，
    // 包装为运行时异常向外抛出，避免被吞。
    private String toViewJson(QuestionEntity entity) {
        QuestionView view = new QuestionView(
                UUID.fromString(entity.getId()),
                entity.getContent(),
                entity.getCategory(),
                entity.getSequenceNo(),
                entity.getStatus()
        );
        try {
            return mapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "failed to serialize question view",
                    e
            );
        }
    }

    // takePreGenerated 负责“取出并激活”后台预生成好的下一题，是取题流程里排在 Singleflight 之前
    // 的一条快路径。它的核心是三步“原子取货”：先按 sessionId + sequenceNo 查一行 PRE_GENERATED 的题，
    // 再把它的 status 用 CAS 改成 ACTIVE，最后把这一行转成 QuestionView 返回。任何一步没命中或没抢到，
    // 都返回 null，让调用方回落到 Singleflight 现场生成——功能正确性不依赖预生成一定成功。
    //
    // 为什么先查再更新，而不是直接 update 后返回：MyBatis-Plus 的 update 只返回影响行数，不返回实体
    // 内容；而题目正文要回给前端，所以必须先把这一行查出来。
    //
    // 为什么 update 的 where 里还要带 status = 'PRE_GENERATED'：这就是 CAS。只有“当前仍是
    // PRE_GENERATED”时才允许改成 ACTIVE；一旦已被别的线程改过，影响行数就是 0，两个并发请求不会把
    // 同一道题各返回一次。
    // 参数 sessionId：面试会话 ID。
    // 参数 sequence：目标题号（调用方已按最大题号 + 1 算好）。
    // 返回值：激活后的题目视图；没有可用的预生成题时返回 null。
    private QuestionView takePreGenerated(
            UUID sessionId,
            int sequence
    ) {
        // 第一步：只找 PRE_GENERATED。
        // 为什么不连 ACTIVE 一起找：ACTIVE 表示这道题用户已经看过，再返回一次等于重复出题。
        QuestionEntity entity = questions.selectOne(
                Wrappers.<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getSessionId,
                                sessionId.toString()
                        )
                        .eq(
                                QuestionEntity::getSequenceNo,
                                sequence
                        )
                        .eq(
                                QuestionEntity::getStatus,
                                "PRE_GENERATED"
                        )
        );
        if (entity == null) {
            // 后台还没生成完，或者已经被别的线程取走：返回 null 走同步兜底，顶多多等几秒。
            return null;
        }

        // 第二步：CAS 更新成 ACTIVE。set 是“要改成什么”，eq 里的 status 是“当前必须还是什么”。
        int updated = questions.update(
                // 第一个参数传 null：本次只靠 lambdaUpdate 里的 set 改字段。
                null,
                Wrappers.<QuestionEntity>lambdaUpdate()
                        .set(
                                QuestionEntity::getStatus,
                                "ACTIVE"
                        )
                        .eq(
                                QuestionEntity::getId,
                                entity.getId()
                        )
                        .eq(
                                QuestionEntity::getStatus,
                                "PRE_GENERATED"
                        )
        );
        if (updated != 1) {
            // 影响行数不是 1：这条题已经被别人激活，本次空手而归，交给同步兜底。
            return null;
        }

        // 第三步：更新成功，把内存里的状态同步过来，再转成前端视图返回。
        entity.setStatus("ACTIVE");
        return new QuestionView(
                UUID.fromString(entity.getId()),
                entity.getContent(),
                entity.getCategory(),
                entity.getSequenceNo(),
                entity.getStatus()
        );
    }
}
```

---

### 出题 4：前端加载和预取题目

**本小节目的：** 让前端进入面试后及时展示题目，并让"下一题"提前准备好以减少等待。真正提前准备下一题的是后端 `NextQuestionPreGeneratorService`（`@Async` 落库 `PRE_GENERATED`），前端只负责在会话状态推进后调用 `POST /api/interviews/{sessionId}/questions` 取题（没有 `GET .../questions/current`）。

**本小节流程：** 后端预生成下一题 -> 创建面试并 /start -> POST 取第一题 -> 提交答案 -> 轮询会话状态 -> POST 取下一题 -> 校验 status === 'ACTIVE'。

---

**第一步：在后端用 `NextQuestionPreGeneratorService` 预生成下一题。**

`AnswerCommandService`（提交答案后）、`FollowUpGenerationService`（追问落库后）和 `InterviewPreparationAsyncService`（生成首题后）都会在合适时机调一次 `preGenerate`：它在后台线程里把"下一题"提前生成好、以 `PRE_GENERATED` 落库占住题号，用户切题时 `QuestionController.takePreGenerated` 就能直接命中，不必等 AI。它要守住三条性质：**幂等**（先查本会话本题号是否已有 `PRE_GENERATED` / `ACTIVE` 行，重复触发不会多出一行题）、**容错**（预生成是锦上添花，失败只记日志，绝不能让提交答案、追问生成这些主流程跟着失败）、**不阻塞**（方法标 `@Async` 扔进独立线程池，调用方调完立即返回）。

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.ai.GeneratedQuestion;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import com.example.interview.infrastructure.mysql.PositionEntity;
import com.example.interview.infrastructure.mysql.PositionMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

// NextQuestionPreGeneratorService 负责"把下一题提前生成好"，是出题模块里的一条后台支线。
// 它在链路里的位置：提交答案之后、追问落库之后、准备首题之后都会调一次 preGenerate；生成好的
// 题目以 PRE_GENERATED 落库，QuestionController 取题时优先拿它，用户切题就不用等 AI。
@Service
public class NextQuestionPreGeneratorService {
    // 预生成失败只记日志、不往外抛，出错痕迹全靠这个 logger 留下来。
    private static final Logger log =
            LoggerFactory.getLogger(NextQuestionPreGeneratorService.class);

    private final InterviewSessionMapper sessions;   // 按会话 ID 取会话，顺带拿到职位 ID 与简历 ID
    private final PositionMapper positions;          // 岗位三要素（名称/描述/技能），决定出题方向
    private final ResumeMapper resumes;              // 简历解析正文，让题目贴着候选人经历出
    private final QuestionMapper questions;          // 开头的幂等查询与结尾的插入都走它
    private final InterviewAiFacade ai;              // 统一 AI 出题入口；本类只负责把上下文拼好

    public NextQuestionPreGeneratorService(
            InterviewSessionMapper sessions,
            PositionMapper positions,
            ResumeMapper resumes,
            QuestionMapper questions,
            InterviewAiFacade ai
    ) {
        this.sessions = sessions;
        this.positions = positions;
        this.resumes = resumes;
        this.questions = questions;
        this.ai = ai;
    }

    // preGenerate 预生成第 nextSequenceNo 题。@Async 靠 Spring 代理生效，必须由容器注入后调用，
    // 自己 new 出来的实例上调用就是普通同步方法。为什么不返回 Future：调用方不需要结果，预生成
    // 是"尽力而为"的优化，失败了 QuestionController 的同步兜底仍能保证功能正确。
    // 参数 sessionId：面试会话 ID。
    // 参数 nextSequenceNo：下一题的序号（当前题序号 + 1，由调用方算好）。
    @Async("questionGenerationTaskExecutor")
    public void preGenerate(
            UUID sessionId,
            int nextSequenceNo
    ) {
        // 整个方法体被 try 包住：@Async 方法抛出的异常回不到调用方（调用方早已返回），只能在这里
        // 自己接住，否则会被 AsyncUncaughtExceptionHandler 悄悄吃掉，连日志都没有。
        try {
            // ---- 第一步：幂等检查 ----
            // 为什么查 PRE_GENERATED 和 ACTIVE 两个状态：前者说明别的线程已经预生成过这道题；
            // 后者说明用户已经切到这道题，或同步兜底已经建过这道题。两种情况都不能再生成，
            // 否则会撞上 uk_question_session_sequence 唯一键。
            QuestionEntity existing = questions.selectOne(
                    Wrappers.<QuestionEntity>lambdaQuery()
                            .eq(
                                    QuestionEntity::getSessionId,
                                    sessionId.toString()
                            )
                            .eq(
                                    QuestionEntity::getSequenceNo,
                                    nextSequenceNo
                            )
                            .in(
                                    QuestionEntity::getStatus,
                                    "PRE_GENERATED",
                                    "ACTIVE"
                            )
            );
            if (existing != null) {
                // 已经有人生成过同一道题：直接收工。这就是幂等——重复触发不会多出一行题。
                return;
            }

            // ---- 第二步：组装 AI 出题上下文 ----
            // 需要哪些数据是本服务的实现细节，所以自己查，不要求调用方传进来。
            InterviewSessionEntity session =
                    sessions.selectById(sessionId.toString());
            if (session == null) {
                throw new IllegalArgumentException(
                        "interview session not found"
                );
            }
            PositionEntity position =
                    positions.selectById(session.getPositionId());
            ResumeEntity resume =
                    resumes.selectById(session.getResumeId());
            if (position == null || resume == null) {
                // 上下文缺一块就不出题：宁可这次预生成失败，也别凭空造一道没有依据的题。
                throw new IllegalArgumentException(
                        "position or resume not found"
                );
            }

            // ---- 第三步：调 AI 出题 ----
            GeneratedQuestion generated = ai.generateQuestion(
                    sessionId,
                    position.getName(),
                    position.getDescription(),
                    position.getSkills(),
                    resume.getParsedContent(),
                    nextSequenceNo
            );

            // ---- 第四步：落库，状态标 PRE_GENERATED ----
            // 不写库这次预生成等于没做；标成 ACTIVE 则等于"用户已看过"，切题时会被判成已消费。
            QuestionEntity entity = new QuestionEntity();
            entity.setId(UUID.randomUUID().toString());
            entity.setSessionId(sessionId.toString());
            entity.setPositionId(session.getPositionId());
            entity.setContent(generated.content());
            entity.setCategory(generated.category());
            entity.setSequenceNo(nextSequenceNo);
            entity.setStatus("PRE_GENERATED");
            questions.insert(entity);

        } catch (Exception e) {
            // 只记日志不重抛：异步方法抛出去没人接，而且预生成失败不该让任何主流程请求失败。
            // 把 sessionId 和题号一起记下来，排查时能直接定位是哪一场哪一题没生成出来。
            log.error(
                    "pre-generate question failed, sessionId={}, sequenceNo={}",
                    sessionId,
                    nextSequenceNo,
                    e
            );
        }
    }
}
```

---

**第二步：在 `frontend/src/main.jsx` 中实现首次请求题目逻辑。**

创建面试（注意是查询参数形式）之后，先调 `/start` 启动状态机，再取第一题，把返回结果保存为页面当前题目。取题只有 `POST .../questions` 这一个入口，没有 `GET .../questions/current`。

```javascript
// 创建面试：后端 InterviewController.create 用的是 @RequestParam（查询参数），不是 JSON 请求体。
const value = await request(`/api/interviews?${query}`, { method: 'POST' });
setSession(value);
setScreen('ANSWERING');
// 启动面试：状态机 INIT → ...，同时异步准备第一题。
await request(`/api/interviews/${value.sessionId}/start`, { method: 'POST' });
// 取第一题：后端只有 POST .../questions 这个入口（没有 GET .../questions/current）。
const first = await request(
  `/api/interviews/${value.sessionId}/questions`,
  { method: 'POST' }
);
// 将获取到的题目设置为当前展示的题目，并清掉上一场残留的追问。
setQuestion(first);
setFollowUp(null);
```

---

**第三步：在 `frontend/src/main.jsx` 中实现提交答案后取下一题逻辑。**

提交答案后不能立刻取下一题——后端还在异步评分、裁决追问、预生成下一题。前端先用 `waitForNextQuestionState` 轮询会话状态（每秒一次 `GET /api/interviews/{sessionId}/state`），等到 `QUESTIONING` / `ANSWERING` 再取题；状态是 `ENDED` 就直接进报告页。取到的题还要再确认一次 `status === 'ACTIVE'`，因为预生成题和已淘汰题都不能展示给候选人。

```javascript
// 先等后端把状态推进到位（评分 → 追问裁决 → 预生成下一题），再去取下一题。
// 旧写法提交返回就立刻要下一题，此时后端还在评分，拿到的还是旧题。
const state = await waitForNextQuestionState(session.sessionId);
// 状态已经 ENDED：这场面试到此为止，直接进报告页。
if (state.state === 'ENDED') {
  setFollowUp(null);
  setScreen('REPORT');
  await loadReport();
  return;
}

// 状态已推进：这时候才去取下一题（后端只有 POST .../questions）。
const next = await request(
  `/api/interviews/${session.sessionId}/questions`,
  { method: 'POST' }
);
// 只有 ACTIVE 的题才允许作答：预生成题和已淘汰题都不能展示。
if (!next || next.status !== 'ACTIVE') {
  throw new Error('下一题尚未就绪');
}
setQuestion(next);
```

对应的 `waitForNextQuestionState` 只做一件事：循环拉取会话状态，遇到 `QUESTIONING` / `ANSWERING` 或 `ENDED` 就返回，否则等 1 秒再查，避免打满后端。

后端预生成 + 请求合并共同保证了"切题不卡"：预生成是**体验优化**（命中就零等待），后端 Singleflight 才是真正的**重复调用保护**（即使前端因轮询或重试发出重复请求，后端也会合并为一次 AI 调用）。

---

## 十、文本答题模块

### 模块主要目的

接收当前题目的文本答案，区分「初次作答」和「追问作答」两种提交，保证答案幂等提交，把有效答案交给异步评分流程，并在初次作答成功后顺手预生成下一题。

### 模块整体流程

```text
输入答案 -> 校验会话和题目 -> 获取题级锁
    -> 是追问作答：保存答案 -> 发布追问评分任务 -> 直接返回（不推进状态机）
    -> 否则：防重复提交 -> 保存答案 -> 推进状态 -> 发布评分任务
    -> 仍在锁内算出下一题序号并预生成下一题
```

### 答题 1：创建答案表、实体和 Mapper

**本小节目的：** 创建答案表对应的实体和 Mapper，用于保存用户针对每道题提交的答案及其提交状态。`request_id` 用于提交幂等，`fencing_token` 用于防止旧的评分结果覆盖新的评分结果。同一道题现在会有「初次作答」和「追问作答」两行，所以表里新增了 `is_follow_up` 标记，唯一键也从 `(question_id)` 改成了 `(question_id, is_follow_up)`。

**本小节流程：** 设计答案表 -> 创建实体 -> 创建 Mapper -> 配置唯一约束 -> 查询和保存验证。

---

**第一步：准备数据库表结构，执行 SQL 迁移脚本。**

`request_id` 用于提交幂等——相同的 `request_id` 不会重复插入记录。为了支持卡住任务的定时补偿，`interview_answer` 还必须保存创建时间（`created_at`）、补偿次数（`retry_count`）和评分状态（`status`）。追问作答复用原题的 `question_id` 提交，光靠 `question_id` 无法把「同一道题的两次作答」区分开，所以表里新增了 `is_follow_up` 列，唯一键也从 `(question_id)` 改成了 `(question_id, is_follow_up)`——同一道题最多两行，一行初次作答、一行追问作答。已有数据库升级时执行下面的迁移；新建数据库时把这些字段直接合并进建表语句。

答案表至少保存以下字段：

```text
session_id       -- 面试会话 ID，关联到面试会话
question_id      -- 题目 ID，关联到题目表
request_id       -- 客户端生成的请求 ID，用于幂等去重
answer           -- 候选人提交的答案正文
score            -- AI 评分结果
feedback         -- AI 评分反馈
fencing_token    -- 围栏令牌，防止旧评分覆盖新评分
is_follow_up     -- 追问作答标记：0=初次作答，1=追问作答
retry_count      -- 异步补偿重投评分任务的次数
```

```sql
-- interview_answer 新增追问标记与补偿重试计数
ALTER TABLE interview_answer
    ADD COLUMN is_follow_up TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN retry_count INT NOT NULL DEFAULT 0;

-- 唯一键由 (question_id) 改成 (question_id, is_follow_up)：
-- 同一道题最多两行，一行初次作答、一行追问作答
ALTER TABLE interview_answer
    DROP INDEX uk_answer_question,
    ADD UNIQUE KEY uk_answer_question_round (question_id, is_follow_up);

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
```

---

**第二步：创建 `src/main/java/com/example/interview/infrastructure/mysql/AnswerEntity.java` 文件（完整补偿版）。**

这个文件是**答案表的数据实体**，对应数据库中的 `interview_answer` 表。它保存候选人每次提交的答案正文、评分结果和评分反馈。补偿版 `AnswerEntity.java` 需要把补偿服务使用的字段（`createdAt`、`retryCount`、`status`）也映射出来，以便定时补偿任务能够扫描到处于 `PENDING_JUDGE` 状态但长时间未被评分的记录。另外，为了区分同一道题的初次作答与追问作答，还要把新增的 `is_follow_up` 列映射成 `isFollowUp` 字段。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

// 一次面试题答案及其异步评分状态。
// 对应 interview_answer 表，包含答案正文、评分结果和补偿控制字段。
@TableName("interview_answer")
public class AnswerEntity {
    // 数据库自增主键。
    private Long id;
    // 面试会话 ID，关联到具体的面试场次。
    private String sessionId;
    // 题目 ID，关联到面试题表。
    private String questionId;
    // 客户端生成的请求 ID，用于幂等去重，相同的 requestId 不会重复插入。
    private String requestId;
    // 候选人提交的答案正文。
    private String answer;
    // AI 评分结果，null 表示尚未评分。
    private Double score;
    // AI 评分反馈，包含对答案的评语和改进建议。
    private String feedback;
    // 围栏令牌，用于防止旧评分结果覆盖新评分结果。
    private Long fencingToken;
    // 答案创建时间，用于补偿服务判断评分是否超时。
    private LocalDateTime createdAt;
    // 补偿重试次数，每次补偿服务扫描处理时递增。
    private Integer retryCount;
    // 追问作答标记，对应 interview_answer.is_follow_up 列。
    // 用 Integer 而不是 boolean 承接 0/1：MyBatis-Plus 把基本类型 boolean 映射到 TINYINT 时
    // 存在类型处理器兼容问题（false 有时被写成 null 或读取报错），包装类型 Integer 最稳妥。
    // 语义：0=初次作答（候选人第一次回答原题），1=追问作答（候选人回答的是原题派生出的追问）。
    // 追问不作为新题插入 interview_question，前端提交的 questionId 仍是原题 ID，
    // 所以必须靠这个标记把「同一道题的初次评分」和「追问评分」区分成两行，避免互相覆盖。
    private Integer isFollowUp;
    // 答案状态：PENDING_JUDGE（待评分）、JUDGED（已评分）、JUDGE_FAILED（评分失败）。
    private String status;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getQuestionId() {
        return questionId;
    }

    public void setQuestionId(String questionId) {
        this.questionId = questionId;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public Double getScore() {
        return score;
    }

    public void setScore(Double score) {
        this.score = score;
    }

    public String getFeedback() {
        return feedback;
    }

    public void setFeedback(String feedback) {
        this.feedback = feedback;
    }

    public Long getFencingToken() {
        return fencingToken;
    }

    public void setFencingToken(Long fencingToken) {
        this.fencingToken = fencingToken;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    // isFollowUp 的读写方法，供仓储层写入、评分模块与补偿服务读取。
    public Integer getIsFollowUp() {
        return isFollowUp;
    }

    public void setIsFollowUp(Integer isFollowUp) {
        this.isFollowUp = isFollowUp;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
```

---

**第三步：创建 `src/main/java/com/example/interview/infrastructure/mysql/AnswerEntityMapper.java` 文件。**

这个文件是**答案表的 MyBatis-Plus Mapper 接口**。它继承 `BaseMapper<AnswerEntity>`，自动获得对 `interview_answer` 表的标准 CRUD 操作能力。后面的 `AnswerRepository` 通过这个 Mapper 实现对答案数据的增删改查。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnswerEntityMapper
        extends BaseMapper<AnswerEntity> {
}
```

---

### 答题 2：创建命令对象和答案仓储

**本小节目的：** 隔离接口参数与持久化模型，并为提交答案提供清晰的仓储操作。通过命令对象（`AnswerCommand`）明确一次提交需要哪些参数（含追问作答标记 `followUp`），通过仓储（`AnswerRepository`）封装对数据库的访问逻辑。

**本小节流程：** 定义提交命令 -> 定义答案仓储接口 -> 实现数据库操作 -> 编排保存和查询。

---

**第一步：创建 `src/main/java/com/example/interview/application/AnswerCommand.java` 文件。**

这个文件是**应用层提交答案的命令对象**，明确一次提交答案必须带有哪场面试、哪道题、什么内容和客户端的请求 ID。使用 `record` 类型使其不可变，确保参数在传输过程中不会被意外修改。`requestId` 由客户端生成，用于在网络重试场景下实现幂等提交。末位的 `followUp` 用来区分这次提交是「初次作答」还是「追问作答」，因为追问复用原题的 `questionId`，只能靠它把两种作答分开。

```java
package com.example.interview.application;

import java.util.UUID;

public record AnswerCommand(
        // 面试会话 ID，用来贯穿题目、答案、评分和报告。
        // 标识答案属于哪一场面试。
        UUID sessionId,
        // 当前题目 ID，提交答案和评分时用它定位题目。
        // 标识答案对应哪一道面试题。
        UUID questionId,
        // 客户端生成的请求 ID，用来识别网络重试。
        // 每次提交使用唯一 ID，相同 requestId 的重复提交会被幂等处理。
        String requestId,
        // 候选人本次提交的答案正文。
        // 用户输入或语音转写的文本内容。
        String answer,
        // 追问作答标记：false=初次作答（候选人第一次回答原题），
        // true=追问作答（候选人回答的是原题派生出的追问）。
        // 为什么放在命令对象里：追问不作为新题插入 interview_question，
        // 前端提交的 questionId 仍是原题 ID，只能靠这个标记在落库、评分、状态机各环节区分两种作答。
        boolean followUp
) {
}
```

---

**第二步：创建 `src/main/java/com/example/interview/application/AnswerRepository.java` 文件。**

这个文件是**应用层使用的答案仓储**，隐藏 MyBatis-Plus 的数据库细节。它提供 `insertIfAbsent()` 方法（用"如果不存在才插入"的语义保护答案写入，处理网络重试带来的重复提交，并把 `is_follow_up` 标记一并落库）、`findJudge()` 与 `recordJudge()` 方法（都带 `boolean followUp` 参数，按 `is_follow_up` 定位"初次评分"或"追问评分"那一行，避免两种评分互相覆盖）、`findSequenceNo()` 方法（按题目 ID 查题号，供提交成功后算"下一题序号"）和 `loadSessionTranscript()` 方法（汇总整场面试的答案文本，供表现分析使用）。在创建此文件之前，需先确保 `AnswerEntityMapper` 和 `QuestionMapper` 已经就绪。

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.infrastructure.mysql.AnswerEntity;
import com.example.interview.infrastructure.mysql.AnswerEntityMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.stream.Collectors;

// 应用层使用的答案仓储，隐藏 MyBatis-Plus 的数据库细节。
@Repository
public class AnswerRepository {
    // 答案表的 MyBatis-Plus Mapper，用于执行数据库操作。
    private final AnswerEntityMapper mapper;
    // 题目表的 MyBatis-Plus Mapper，用于查询题目是否存在。
    private final QuestionMapper questions;

    public AnswerRepository(
            // 答案表的 Mapper 实例，由 Spring 自动注入。
            AnswerEntityMapper mapper,
            // 题目表的 Mapper 实例，由 Spring 自动注入。
            QuestionMapper questions
    ) {
        this.mapper = mapper;
        this.questions = questions;
    }

    // 仅在同一请求不存在时写入答案，网络重试不会产生重复记录。
    // 参数 command：前端提交的答案命令对象。
    // 返回值：true 表示插入成功，false 表示已存在相同记录（幂等拦截）。
    public boolean insertIfAbsent(AnswerCommand command) {
        AnswerEntity entity = new AnswerEntity();
        entity.setSessionId(command.sessionId().toString());
        entity.setQuestionId(command.questionId().toString());
        entity.setRequestId(command.requestId());
        entity.setAnswer(command.answer());
        // 把追问标记落到 is_follow_up 列（0=初次作答，1=追问作答）。
        // 唯一键是 (question_id, is_follow_up)，所以同一道题的初次作答与追问作答会各占一行；
        // 不写这个标记的话，追问答案会和初次答案撞唯一键，被当成重复提交静默丢弃。
        entity.setIsFollowUp(command.followUp() ? 1 : 0);

        try {
            return mapper.insert(entity) == 1;
        } catch (DuplicateKeyException ignored) {
            // 唯一键冲突（request_id 重复，或同一题同一作答类型重复）时，视为重复提交，静默忽略。
            return false;
        }
    }

    // 查询已经保存的评分，重复消费任务时直接回放这个结果。
    // 参数 sessionId：面试会话 ID。
    // 参数 questionId：题目 ID。
    // 参数 followUp：true 查追问作答那一行，false 查初次作答那一行。
    // 返回值：如果找到评分结果则返回 StoredJudge，否则返回 null。
    public StoredJudge findJudge(
            UUID sessionId,
            UUID questionId,
            boolean followUp
    ) {
        AnswerEntity entity = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        AnswerEntity
                        >()
                        .eq("session_id", sessionId.toString())
                        .eq("question_id", questionId.toString())
                        // 同一道题现在有两行，必须按 is_follow_up 过滤，否则会读到错误的一行。
                        .eq("is_follow_up", followUp ? 1 : 0)
                        .isNotNull("score")
                        .last("limit 1")
        );
        return entity == null
                ? null
                : new StoredJudge(
                        entity.getScore(),
                        entity.getFeedback(),
                        entity.getFencingToken()
                );
    }

    // 保存评分结果，由评分模块调用（JudgeResult 在评分模块中定义）。
    // 参数 followUp：决定评分写回哪一行——追问评分写回 is_follow_up=1 那一行，
    // 绝不能覆盖初次评分，否则候选人的首次得分会被追问得分冲掉。
    public void recordJudge(
            UUID sessionId,
            UUID questionId,
            JudgeResult result,
            boolean followUp
    ) {
        AnswerEntity entity = mapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<
                        AnswerEntity
                        >()
                        .eq("session_id", sessionId.toString())
                        .eq("question_id", questionId.toString())
                        // 按追问标记过滤，保证只更新对应类型的作答行。
                        .eq("is_follow_up", followUp ? 1 : 0)
                        .last("limit 1")
        );
        if (entity == null) {
            throw new IllegalStateException(
                    "answer not found"
            );
        }
        entity.setScore(result.score());
        entity.setFeedback(result.feedback());
        entity.setFencingToken(result.fencingToken());
        // 评分成功后更新状态，避免下一轮补偿继续扫描该记录。
        entity.setStatus("JUDGED");
        mapper.updateById(entity);
    }

    // 按题目 UUID 查出它的 sequence_no，供"下一题序号 = 当前题序号 + 1"使用。
    // 为什么查不到直接抛异常，而不是返回 0：题号从 1 开始，0 会被上层当成合法题号继续 +1，
    // 数据不一致会被静默放大成"题号整体错位"，不如当场抛出，早失败早暴露。
    public int findSequenceNo(UUID questionId) {
        QuestionEntity question = questions.selectOne(
                Wrappers.<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getId,
                                questionId.toString()
                        )
        );
        if (question == null
                || question.getSequenceNo() == null) {
            throw new IllegalArgumentException(
                    "question sequence not found: " + questionId
            );
        }
        return question.getSequenceNo();
    }

    // 加载某场面试的全部答案文本，拼接成一段完整转写文本，供表现分析使用。
    // 按答案自增主键升序取出该会话的全部答案，过滤掉空白内容后，用换行符拼接。
    public String loadSessionTranscript(UUID sessionId) {
        return mapper.selectList(
                        Wrappers.<AnswerEntity>lambdaQuery()
                                .eq(AnswerEntity::getSessionId,
                                        sessionId.toString())
                                .orderByAsc(AnswerEntity::getId)
                )
                .stream()
                .map(AnswerEntity::getAnswer)
                .filter(answer -> answer != null && !answer.isBlank())
                .collect(Collectors.joining("\n"));
    }

}
```

---

**第三步：创建 `src/main/java/com/example/interview/application/StoredJudge.java` 文件。**

这个文件是**已评分答案的查询结果载体**，负责把评分数据从仓储层干净地返回给上层逻辑。它只带三样东西：AI 评出的分数、反馈评语和写入这次评分时使用的围栏令牌（`fencingToken` 用于在写回时判断「这次结果是不是最新的」，防止旧评分覆盖新评分）。它是独立的顶层类而不是嵌套在 `AnswerRepository` 里，因为评分模块（`InterviewWorkflowService`）也要引用它，做成嵌套类型会迫使评分模块多写一层 `AnswerRepository.StoredJudge`。

```java
package com.example.interview.application;

// 一次已评分答案的查询结果，用于把评分数据从仓库层返回给上层逻辑。
// 记录 AI 给出的分数、反馈以及用于防止旧结果覆盖新结果的围栏令牌。
public class StoredJudge {
    // AI 评分结果，可能为 null 表示尚未评分。
    // 与 AnswerEntity 的 score 列（Double）保持一致，支持小数评分。
    private final Double score;
    // AI 评分反馈，包含评语和改进建议。
    private final String feedback;
    // 围栏令牌，用于防止旧评分结果覆盖新评分结果。
    private final Long fencingToken;

    public StoredJudge(Double score, String feedback, Long fencingToken) {
        this.score = score;
        this.feedback = feedback;
        this.fencingToken = fencingToken;
    }

    public Double score() {
        return score;
    }

    public String feedback() {
        return feedback;
    }

    public Long fencingToken() {
        return fencingToken;
    }
}
```

---

### 答题 3：创建题级锁

**本小节目的：** 防止同一用户或客户端并发提交同一道题，保证答案只被处理一次。锁的粒度精确到"同一场面试的同一道题"，不会阻塞其他面试或其他题目。

**本小节流程：** 设计锁 Key -> 获取 Redis 锁 -> 执行幂等检查和保存 -> 释放锁 -> 处理超时和失败。

---

**第一步：创建 `src/main/java/com/example/interview/infrastructure/lock/QuestionLockManager.java` 文件。**

这个文件是**基于 Redis 的分布式题级锁管理器**。它使用 Redis 的 `SET NX` 命令实现一个带过期时间的排他锁。锁键的格式为 `interview:answer:{sessionId}:{questionId}`，粒度精确到同一场面试的同一道题。`LockHandle` 实现了 `AutoCloseable` 接口，可以配合 `try-with-resources` 语法使用，确保锁在异常时也能被正常释放。

```java
package com.example.interview.infrastructure.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

// 使用 Redis SET NX 实现一个带过期时间的题级锁。
// 锁粒度精确到"会话 + 题目"，不会阻塞其他面试或其他题目。
@Component
public class QuestionLockManager {
    // Spring Data Redis 的操作模板，用于执行 SET NX 命令。
    private final StringRedisTemplate redis;

    public QuestionLockManager(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 尝试获取指定 key 的分布式锁。
    // 参数 key：锁键，格式为 "interview:answer:{sessionId}:{questionId}"。
    // 返回值 LockHandle：包含锁的获取状态、持有 token 和释放锁的能力。
    public LockHandle tryLock(
            String key
    ) {
        // 生成唯一的锁持有者标识，用于安全释放锁。
        String token = UUID.randomUUID().toString();
        // 使用 SET NX 命令尝试获取锁，过期时间为 30 秒，防止死锁。
        Boolean acquired = redis.opsForValue().setIfAbsent(
                key,
                token,
                Duration.ofSeconds(30)
        );
        return new LockHandle(
                redis,
                key,
                token,
                Boolean.TRUE.equals(acquired)
        );
    }

    // try-with-resources 退出时释放锁，避免异常导致锁一直占用。
    public record LockHandle(
            // Redis 操作模板，用于释放锁时执行 delete 命令。
            StringRedisTemplate redis,
            // 锁的 Redis key。
            String key,
            // 锁持有者的唯一标识，只有持有者才能释放自己的锁。
            String token,
            // 是否成功获取锁。
            boolean acquired
    ) implements AutoCloseable {
        @Override
        public void close() {
            // 只有成功获取锁的持有者才需要释放锁。
            if (acquired) {
                // 先读取 Redis 中当前锁的持有者。
                String current = redis.opsForValue().get(key);
                // 只有当前持有者是自己时才删除锁，防止误删其他线程的锁。
                if (token.equals(current)) {
                    redis.delete(key);
                }
            }
        }
    }
}
```

---

### 答题 4：让应用服务推进状态并发布任务

**本小节目的：** 将保存答案、推进面试状态和投递评分任务组织成连续的应用服务流程。通过 `AnswerCommandService` 将加锁、保存答案、推进快照、发布评分任务串成一个完整用例，并在追问作答时走早返回分支、在初次作答成功后预生成下一题。

**本小节流程：** 校验命令 -> 获取题级锁 -> 追问作答则保存并发布追问评分任务后返回 -> 否则保存答案 -> 推进状态 -> 发布评分任务 -> 预生成下一题。

---

**第一步：确认前置依赖文件都已就绪。**

开始本节前先确认以下文件已经存在，并且方法签名与前置章节一致：

- `src/main/java/com/example/interview/application/AnswerCommand.java`
- `src/main/java/com/example/interview/application/AnswerRepository.java`
- `src/main/java/com/example/interview/infrastructure/snapshot/SessionSnapshotRepository.java`
- `src/main/java/com/example/interview/infrastructure/lock/QuestionLockManager.java`
- `src/main/java/com/example/interview/application/JudgeTaskPublisher.java`（评分模块中定义，`publish` 方法末位带 `boolean followUp` 参数）
- `src/main/java/com/example/interview/application/NextQuestionPreGeneratorService.java`（下一题预生成服务，`preGenerate` 标 `@Async`）
- `src/main/java/com/example/interview/domain/InterviewSessionStateMachine.java`

---

**第二步：创建 `src/main/java/com/example/interview/application/AnswerCommandService.java` 文件。**

这个文件是**答案提交的应用服务编排类**。它把加锁、保存答案、推进快照和发布评分任务串成一个完整的业务流程。执行顺序不可随意调换，必须严格遵守以下流程：

```text
锁
    -> 追问作答？是则：写答案 -> 发布追问评分任务（followUp=true）-> 返回
    -> 否则：读取旧快照 -> 状态机校验 -> 写答案 -> CAS 更新快照
    -> 发布评分任务（followUp=false）
    -> 仍在锁内算出下一题序号 -> 预生成下一题
```

```java
package com.example.interview.application;

import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.infrastructure.lock.QuestionLockManager;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import org.springframework.stereotype.Service;

@Service
public class AnswerCommandService {
    // 面试状态机依赖，统一校验状态和事件是否合法，并据此推进面试状态。
    private final InterviewSessionStateMachine machine;
    // 答案仓储，保存候选人的文本或转写答案，并提供 insertIfAbsent() 幂等写入。
    private final AnswerRepository answers;
    // 快照仓储，读取和保存面试当前状态，提供 load() 和 compareAndSet() 乐观锁控制。
    private final SessionSnapshotRepository snapshots;
    // 题级分布式锁，防止同一题被并发提交多次，锁粒度精确到"会话 + 题目"。
    private final QuestionLockManager locks;
    // 评分任务发布器，把答案交给异步评分队列，末位 followUp 决定评分写回哪一行。
    private final JudgeTaskPublisher publisher;
    // 下一题预生成服务：提交成功后顺手把下一题提前生成好，用户切题时就不用等 AI。
    private final NextQuestionPreGeneratorService preGenerator;

    public AnswerCommandService(
            InterviewSessionStateMachine machine,
            AnswerRepository answers,
            SessionSnapshotRepository snapshots,
            QuestionLockManager locks,
            JudgeTaskPublisher publisher,
            NextQuestionPreGeneratorService preGenerator
    ) {
        this.machine = machine;
        this.answers = answers;
        this.snapshots = snapshots;
        this.locks = locks;
        this.publisher = publisher;
        this.preGenerator = preGenerator;
    }

    // 接收答案提交，区分初次作答与追问作答，保存答案并发布异步评分任务。
    // 参数 command：前端提交的答案命令对象，含 sessionId、questionId、requestId、answer 和 followUp。
    public void submit(
            AnswerCommand command
    ) {
        // 锁粒度精确到"会话 + 题目"，不会阻塞其他题目。
        var lockKey = "interview:answer:"
                + command.sessionId()
                + ":"
                + command.questionId();

        try (var lock =
                     locks.tryLock(lockKey)) {
            if (!lock.acquired()) {
                throw new IllegalStateException(
                        "answer is being processed"
                );
            }

            // 追问作答早返回分支。为什么必须放在 machine.advance 之前：
            // 追问不作为新题插入 interview_question，前端提交的 questionId 仍是原题 ID。
            // 若追问作答也走 ANSWER_SUBMITTED 推进状态机，同一道题会被推进两次、被算成两道题，
            // 题序与进度整体错乱。所以追问作答在这里就返回，只做"答案落库 + 投递追问评分任务"，
            // 既不推进主线状态机，也不触发下一题预生成（追问之后不再预生成新题，避免题号被带偏）。
            // 仍然保留锁与 insertIfAbsent：锁防止同一道题的追问被并发重复处理，
            // insertIfAbsent 靠唯一键 (question_id, is_follow_up) 兜底幂等。
            if (command.followUp()) {
                answers.insertIfAbsent(command);
                // 末位传 true：评分队列据此把分数写回 is_follow_up=1 那一行，且不推进主线。
                publisher.publish(
                        command.sessionId(),
                        command.questionId(),
                        command.answer(),
                        true
                );
                return;
            }

            // 先读取版本快照，后面的 CAS 会用它检测并发修改。
            var oldSnapshot =
                    snapshots.load(
                            command.sessionId()
                    );

            // 状态机校验：检查当前状态是否允许 ANSWER_SUBMITTED 事件。
            var result = machine.advance(
                    oldSnapshot,
                    InterviewEvent.ANSWER_SUBMITTED,
                    command.requestId()
            );

            // 重复请求在状态机层面直接结束，不重复写答案或发布任务。
            // 第一道防线是状态机对重复 requestId 的校验。
            if (!result.changed()) {
                return;
            }

            // 数据库层面的唯一约束是第二道幂等防线。
            answers.insertIfAbsent(command);

            // 只有快照版本仍等于 oldSnapshot 时才允许推进状态。
            // 使用 CAS（Compare-And-Swap）乐观锁机制防止并发冲突。
            if (!snapshots.compareAndSet(
                    oldSnapshot,
                    result.snapshot()
            )) {
                throw new IllegalStateException(
                        "snapshot version conflict"
                );
            }

            // 状态和答案成功落库后，再投递异步评分任务。
            // 末位传 false：评分写回 is_follow_up=0 那一行，并推进主线状态机。
            publisher.publish(
                    command.sessionId(),
                    command.questionId(),
                    command.answer(),
                    false
            );

            // 触发下一题预生成。为什么写在 publish 之后：到这一步答案确实已落库、
            // 快照确实已推进，题号才有意义。
            // 为什么必须还在锁里面（也就是 try-with-resources 的花括号内）：
            // 题号按"当前题序号 + 1"算，锁一放开，同一会话的下一题可能立刻被另一个请求接手，
            // 两边算出同一个题号，撞上 uk_question_session_sequence。
            int nextSequenceNo = answers.findSequenceNo(
                    command.questionId()
            ) + 1;
            // @Async 方法，调完立即返回：这里不等待 AI，用户提交答案的响应不会因此变慢。
            preGenerator.preGenerate(
                    command.sessionId(),
                    nextSequenceNo
            );
        }
    }
}
```

---

### 答题 5：创建提交接口

**本小节目的：** 对外提供统一的答案提交入口，承接前端文本答题和语音转写结果。通过 RESTful API 接收提交请求，请求体里带上追问作答标记 `followUp`，经过参数校验后转发给应用服务处理。

**本小节流程：** 接收请求 -> 获取当前用户 -> 调用应用服务 -> 返回提交状态 -> 前端等待评分结果。

---

**第一步：创建 `src/main/java/com/example/interview/application/SubmitAnswerRequest.java` 文件。**

这个文件是**HTTP 层接收的答案提交请求参数**。它包含题目 ID（`questionId`）、客户端请求 ID（`requestId`）、答案正文（`answer`）和追问作答标记（`followUp`）。使用 `record` 类型和 Jakarta Validation 注解（`@NotBlank`）确保请求参数的有效性。转换后才交给 `AnswerCommand` 进行业务处理。注意 `followUp` 用的是包装类型 `Boolean` 而不是基本类型 `boolean`：这样前端老版本不传该字段时 Jackson 会给 `null`，控制器里再用 `Boolean.TRUE.equals(...)` 兜底成 `false`（按初次作答处理），既不会因为缺字段直接 400，也不会把未传字段误判成追问。

```java
package com.example.interview.application;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

// HTTP 层接收的答案提交参数，转换后才交给 AnswerCommand。
public record SubmitAnswerRequest(
        // 当前题目的 ID，后端据此查找对应的题目记录。
        UUID questionId,
        // 客户端生成的请求 ID，用于幂等去重，不能为空。
        @NotBlank String requestId,
        // 候选人提交的答案正文，不能为空。
        @NotBlank String answer,
        // 追问作答标记，由前端提交。
        // 用包装类型 Boolean 而不是基本类型 boolean，是为了允许前端老版本不传该字段——
        // 缺省时 Jackson 给 null，控制器里用 Boolean.TRUE.equals(...) 兜底成 false（初次作答），
        // 这样新老前端都能正常提交，不会因为缺字段直接 400。
        Boolean followUp
) {
}
```

---

**第二步：在 `AnswerController.java` 中实现答案提交接口。**

这个 **AnswerController** 只负责 HTTP 参数的接收和校验，真正的业务顺序交给 `AnswerCommandService`。类级 `@RequestMapping("/api/answers")` 加上方法级 `@PostMapping("/{sessionId}/answers")`，对外暴露的完整路径是 `POST /api/answers/{sessionId}/answers`；它把 `SubmitAnswerRequest` 转换为 `AnswerCommand` 后调用应用服务处理。

```java
package com.example.interview.application;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// 答案 Controller 只负责 HTTP 参数转换，业务顺序交给 AnswerCommandService。
@RestController
@RequestMapping("/api/answers")
public class AnswerController {
    // 答案提交的应用服务，负责执行业务编排。
    private final AnswerCommandService answers;

    public AnswerController(
            // 答案提交应用服务实例，由 Spring 自动注入。
            AnswerCommandService answers
    ) {
        this.answers = answers;
    }

    // 提交答案接口。
    // 参数 sessionId：面试会话 ID，来自 URL 路径。
    // 参数 request：HTTP 请求体，包含 questionId、requestId、answer 和 followUp。
    @PostMapping("/{sessionId}/answers")
    public void submit(
            @PathVariable UUID sessionId,
            @Valid @RequestBody SubmitAnswerRequest request
    ) {
        answers.submit(
                new AnswerCommand(
                        sessionId,
                        request.questionId(),
                        request.requestId(),
                        request.answer(),
                        // 末位传入追问标记。前端可能不传 followUp（老版本），
                        // 此时 request.followUp() 为 null，Boolean.TRUE.equals(...) 会安全地兜底成 false，
                        // 既不会抛空指针，也不会把未传字段误判成追问。
                        Boolean.TRUE.equals(request.followUp())
                )
        );
    }
}
```

Controller 只接收请求，业务编排放在 `AnswerCommandService`。

## 十一、AI 异步评分模块

### 模块主要目的

**为什么需要这个模块？** 候选人在面试中提交答案后，需要 AI 对答案进行评分（正确性、完整性、深度、表达等维度）。如果采用同步方式——用户提交答案后直接等待 AI 返回评分再响应——存在两个严重问题：

1. **阻塞用户请求线程**：AI 调用通常耗时 3～10 秒，同步等待会占满 Web 服务器的线程池，导致其他请求排队。
2. **缺乏容错能力**：如果 AI 调用超时或返回异常（JSON 解析失败、网络闪断），同步模式下用户请求会直接看到 500 错误。

因此，本模块的核心目的是：**把耗时的 AI 评分从用户提交请求中解耦出来，通过 Redis List 队列异步投递评分任务，由 `@Scheduled` 驱动的消费者循环取任务、调用 AI、保存结果，并驱动后续的追问流程或下一题。** 队列用「待处理 / 处理中 / 死信」三个 key 保证任务不丢，另外还有一个补偿线程定时扫描数据库，兜底那些既不在队列里、处理线程又已经死掉的漏网任务。

### 模块整体流程

```text
用户提交答案（AnswerCommandService.submit）
    │
    ▼
答案落库 + 快照 CAS 成功后，JudgeTaskPublisher.publish(...)
把 JudgeTask 序列化成 JSON，rightPush 到主队列
interview:task:judge（立即返回，不阻塞用户）
    │
    ▼
JudgeTaskConsumerLoop 的 @Scheduled(fixedDelay = 200) 循环
每 200ms 调一次 pullOnce()：用 move 把队首原子搬移到
processing 队列 interview:task:judge:processing
    │
    ▼
反序列化 JudgeTask（sessionId / questionId / answer / followUp / retryCount）
    │
    ▼
调用 InterviewWorkflowService.judge(...) 执行评分工作流
    │
    ▼
InterviewAiFacade.judge(...)：Single-flight 合并 + FencingToken 防过期
    │
    ▼
chatJudge(...) 组装提示词（拼评分 Skill）→ 调 AI → 校验 JSON
    │
    ├─ 缺 score / feedback 为空 → 把问题反馈给 AI 重新生成（最多 2 次）
    └─ 2 次仍不合格 → 抛异常，交给重试/死信
    │
    ▼
得到结构化 JudgeResult（score + feedback + fencingToken）
    │
    ▼
保存评分到数据库（interview_answer 表，按 is_follow_up 区分写哪一行）
    │
    ▼
初次作答：推进面试状态机（JUDGE_DONE）并异步发布追问任务
追问作答：早返回，只评分落库，不推进主线、不发布追问
    │
    ▼
成功 → 从 processing 队列 remove 掉这条消息
失败 → retryCount+1 重投主队列；超过 3 次 → 移入死信队列
        interview:task:judge:dead
    │
    ▼
补偿线程（JudgeTaskCompensationService，每 30 秒）兜底：
数据库里超 5 分钟未评分、队列里又查不到、心跳也没了的记录
→ 重投；超过补偿上限 → 置 JUDGE_FAILED 并写补偿日志
```

---

### 评分 1：创建 Redis 评分队列

**本小节目的：** 建立答案评分任务的可靠投递通道。当候选人在面试中提交答案后，立即将评分任务写入 Redis 队列（不阻塞 HTTP 请求），由后台消费者异步处理。同时通过 processing 队列和死信队列机制，确保任务不会因消费者崩溃而丢失。

**本小节流程：**
```text
定义任务对象（JudgeTask，带 followUp 标记）
    → 发布者序列化后 rightPush 到主队列 interview:task:judge
    → JudgeTaskConsumerLoop 每 200ms 调一次 pullOnce()，
      用 move 把队首原子搬移到 processing 队列
    → 处理成功则按「值 + 数量 1」精确 remove 掉 processing 里的这条消息
    → 失败则判断重试次数
        → 未超限：原样保留 followUp，retryCount+1 重新投递到主队列
        → 超限（或反序列化失败）：移入 dead 死信队列
```

**为什么出队用 `move` 而不是 `leftPopAndLeftPush`：** `move(source, LEFT, target, LEFT)` 是 Redis 6.2 的 `LMOVE`，把队首元素从主队列原子地搬到处理中队列，中间没有「取出来了但还没登记」的窗口——消费者此刻崩溃，任务也还留在处理中队列里，补偿服务才查得到。处理成功后按「值 + 数量 1」精确 `remove`，而不是再 `leftPop` 一次：处理中队列里可能同时躺着别人的消息，`leftPop` 会摘错。

**为什么消费由 `@Scheduled` 循环驱动：** `pullOnce()` 用的是非阻塞搬运（拿不到就返回 null），不会挂起线程，所以必须由调用方反复拉取；用 `@Scheduled(fixedDelay = 200)` 而不是在方法里写死循环，单次异常只跳过这一轮，不会让整个消费者永久退出。

---

#### 文件 1：`src/main/java/com/example/interview/application/JudgeTask.java`

**创建这个文件干什么：** 这是 Redis 队列中传输的任务消息体。记录类（Java 16+ `record`）是不可变的，可以被安全地序列化/反序列化。`JudgeTaskPublisher` 会把它序列化成 JSON 放入 Redis 队列，消费者读取后再反序列化回这个对象。**如果没有这个 record 类，publish 方法就没有可以序列化的消息载体。**

**为什么比最初多了 `followUp` 字段：** 追问作答与初次作答现在共用同一条评分队列。消费者拿到任务后要据此决定两件事——评分写回 `interview_answer` 的哪一行（`is_follow_up=0` 还是 `1`）、要不要推进主线状态机。所以队列载荷必须带上这个标记，且它被特意插在 `retryCount` **之前**，让「重试次数永远是最后一个分量」的约定保持不变，重投、补偿构造 `JudgeTask` 时不容易把参数错位。

```java
package com.example.interview.application;

import java.util.UUID;

/**
 * 评分任务在 Redis 队列里的载荷对象。
 * <p>
 * 为什么用 record：
 * 1. 任务是只读的，投递进队列之后不会再被修改；
 * 2. 只装「重新执行一次评分」所必需的最小上下文，
 *    消费者与补偿线程拿到后据此重建评分调用；
 * 3. 字段都是基本类型 + String + UUID，Jackson 序列化 / 反序列化不会出意外。
 * </p>
 * <p>
 * 注意：这个类只描述「队列里传什么」。AI 返回后解析出来的结果对象是
 * {@link JudgeResult}（score / feedback / fencingToken / stale），
 * 两者字段不同，不要混用。
 * </p>
 *
 * @param sessionId  面试会话 ID，消费者靠它取会话快照、推进状态机
 * @param questionId 题目 ID
 * @param answer     候选人答案原文，评分时直接送给 AI
 * @param followUp   是否追问作答：false=初次作答、true=追问作答。
 *                   消费者据此决定评分写回哪一行（is_follow_up），
 *                   以及是否推进主线状态机；
 *                   重试 / 补偿重投时必须原样保留，
 *                   否则追问任务会被当成初次作答错误处理。
 * @param retryCount 已重试次数：投递时为 0，重试与补偿各 +1，超过上限转死信队列
 */
public record JudgeTask(
        UUID sessionId,
        UUID questionId,
        String answer,
        // 追问标记插在 retryCount 之前，retryCount 仍保持末位，
        // 这样「重试次数永远是最后一个分量」的约定不变，阅读和调用时更不容易错位。
        boolean followUp,
        int retryCount
) {
}
```

---

#### 文件 2：`src/main/java/com/example/interview/application/JudgeTaskPublisher.java`

**创建这个文件干什么：** 这是评分队列的发布者和消费者合为一体的服务类。

**发布职责**：当用户提交答案后，`AnswerCommandService.submit()`（在其他章节定义）在「答案落库 + 快照 CAS 成功」之后调用 `publish(sessionId, questionId, answer, followUp)`，把 `JudgeTask` 序列化为 JSON 后 `rightPush` 进 `interview:task:judge` 主队列。

**消费职责**：真正被 `JudgeTaskConsumerLoop` 反复调用的是同步方法 `pullOnce()`。它用 `move`（Redis `LMOVE`）把队首任务从主队列原子性地搬到 processing 队列，执行工作流，成功后再按「值 + 数量 1」精确 `remove` 掉——这是 Redis 实现可靠消费的核心模式：即使消费者在处理过程中崩溃，任务也不会丢失，仍留在 processing 队列里等待补偿服务兜底。类里还保留了一个带 `@Async("aiScoringTaskExecutor")` 的 `consumeOnce()`，用带 200ms 超时的 `rightPopAndLeftPush` 拉一条，作为「单次异步消费」的备用入口；日常消费走的是 `pullOnce()` 这条路。

**重试与死信机制**：如果消费失败（AI 超时、解析异常等），调用 `retryOrDeadLetter()` 判断：`retryCount < MAX_RETRY(3)` 则**原样保留 `followUp`**、只把重试次数 +1 重新入队；否则（或反序列化失败、`task == null`）推入 `interview:task:judge:dead` 死信队列，供运维人工排查。

**为什么需要三个队列键（QUEUE / PROCESSING / DEAD）？**
- **QUEUE**（待处理）：消息的入口，`rightPush` 写入尾部，消费者从头部取任务。
- **PROCESSING**（处理中）：`move` 把任务从 QUEUE 原子性地移到 PROCESSING，表示这个任务正在被某个消费者处理。如果消费者崩溃，任务会保留在 PROCESSING 中，不会丢失，但也不会被其他消费者取走——因此需要「评分 1.1 补偿服务」来兜底。
- **DEAD**（死信）：超过最大重试次数的任务移到这里，避免无限循环。

**依赖什么：**
- `StringRedisTemplate`：Spring Data Redis 提供，操作 Redis 列表。
- `ObjectMapper`：Jackson 提供的 JSON 序列化/反序列化工具。
- `InterviewWorkflowService`（在下一节「评分 3」中定义）：真正执行业务逻辑的编排服务，由构造器注入。

**被谁调用：** 由答案提交流程（上一章节）调用 `publish()` 方法触发；由 `JudgeTaskConsumerLoop` 的定时循环调用 `pullOnce()` 完成消费。

```java
package com.example.interview.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.data.redis.connection.RedisListCommands.Direction;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

/**
 * Redis 评分队列的发布者 + 消费者（合并在同一个 Service 中）。
 * <p>
 * 为什么合并在一个类中：发布（publish）与消费（pullOnce）共享相同的队列键、
 * 重试策略、序列化配置。拆成两个类会导致重复的常量和配置代码，
 * 且事务一致性更难保证（例如消费失败后重投时，依赖同一个 retryOrDeadLetter 方法）。
 * </p>
 */
@Service
public class JudgeTaskPublisher {

    // ================ Redis 队列键 ================

    /**
     * 待处理任务队列（List 类型）。
     * rightPush 写入尾部(右侧)，消费时用 move（Redis 6.2 的 LMOVE）从头部(左侧)取出
     * 并原子性转移到 processing 队列。右侧进左侧出，构成 FIFO（先进先出）队列——
     * 先提交的答案先被评分。
     * 命名规范：interview:{模块}:{用途}，便于在 Redis CLI 中按前缀批量管理。
     */
    private static final String QUEUE = "interview:task:judge";

    /**
     * 已取出但尚未确认完成的任务队列（List 类型）。
     * 为什么需要这个队列：普通的 leftPop 直接从 QUEUE 删除任务，
     * 如果消费者在处理过程中崩溃，任务永远丢失。
     * 而 move 把任务从 QUEUE 原子性地移动到 PROCESSING，
     * 消费者确认完成后才按「值 + 数量 1」精确 remove 掉。
     * 即使消费者崩溃，任务仍保留在 PROCESSING 中，由补偿服务兜底。
     */
    private static final String PROCESSING = "interview:task:judge:processing";

    /**
     * 超过最大重试次数的死信队列（List 类型）。
     * 为什么需要死信队列：不直接丢弃失败任务，而是保留现场供运维排查。
     * 运维可以查看死信队列中的任务，分析失败原因（AI 接口异常？格式变化？），
     * 修复后手动重新投递到 QUEUE。
     */
    private static final String DEAD = "interview:task:judge:dead";

    /**
     * 单个任务的最大重试次数。
     * 为什么是 3 次：<br>
     * - 第 1 次失败：可能是瞬时的网络抖动，重试大概率成功。<br>
     * - 第 2 次失败：可能是 AI 服务短暂不可用，等待几秒后重试。<br>
     * - 第 3 次失败：大概率是永久性异常（如 prompt 格式错误、AI 模型不存在等），
     *   不再重试，进入死信队列。<br>
     * 3 次是经典的重试次数上限。太多浪费队列资源，太少则对临时故障不够友好。
     */
    private static final int MAX_RETRY = 3;

    // ================ 依赖注入 ================

    /**
     * Spring Data Redis 提供的 Redis 操作模板。
     * 为什么用 StringRedisTemplate 而不是 RedisTemplate<String, Object>：
     * 因为队列中存储的是 JSON 字符串，手动序列化/反序列化更可控。
     * StringRedisTemplate 的序列化器默认就是 StringRedisSerializer，
     * 对字符串操作不需要额外的类型转换，性能更好。
     */
    private final StringRedisTemplate redis;

    /**
     * Jackson JSON 序列化/反序列化工具。
     * 为什么需要：JudgeTask record 需要序列化为 JSON 字符串存入 Redis 列表，
     * 读取时又需要从 JSON 反序列化为 JudgeTask 对象。
     * 使用 ObjectMapper 而不是手动拼接 JSON，可以保证：
     * 1. 字段名和值类型一致（不会出现字符串值被当作数字的问题）；
     * 2. 特殊字符被正确转义（答案文本可能包含引号、换行符等）；
     * 3. 后续如果 record 增加字段，反序列化自动兼容（JSON 中有多余字段则忽略）。
     */
    private final ObjectMapper mapper;

    /**
     * 评分工作流：真正执行「AI 评分 + 落库 + 推进状态 + 触发追问」的那一环。
     * 全类只声明这一次，构造器注入一次，避免重复字段导致编译报错。
     */
    private final InterviewWorkflowService workflow;

    /**
     * 构造函数注入所有依赖。
     * 
     * 为什么不使用 @Autowired 字段注入：
     * 1. 构造函数注入让依赖在对象创建时就被固定下来，运行期间不可变；
     * 2. 在单元测试时可以直接 new 并传入 mock 对象，不需要反射或 Spring 容器；
     * 3. 如果依赖过多，构造函数参数列表会「提醒」你该类承担了过多职责（单一职责原则）。
     */
    public JudgeTaskPublisher(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            InterviewWorkflowService workflow
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.workflow = workflow;
    }

    // ================ 发布方法 ================

    /**
     * 把答案包装成 JudgeTask 任务消息，序列化为 JSON 后写入 Redis 待处理队列。
     * 
     * 为什么这个方法不阻塞：
     * 1. 写入 Redis 队列是内存级操作，通常在微秒级完成；
     * 2. 即使 Redis 暂时不可用，rightPush 抛出异常时，
     *    调用方可以捕获异常并返回用户一个友好的提示，
     *    而不是让用户等待 AI 评分完成（AI 评分可能要好几秒）。
     * 
     * @param sessionId  面试会话 ID，必填且不能为 null
     * @param questionId 题目 ID，必填且不能为 null
     * @param answer     候选人提交的答案文本，可以为空字符串但不能为 null
     * @param followUp   是否追问作答：false=初次作答、true=追问作答。
     *                   追问与初次共用同一条队列，载荷必须带上这个标记，
     *                   否则消费者无法区分「该写哪一行、该不该推进状态机」。
     * @throws IllegalStateException 如果序列化 JudgeTask 失败
     */
    public void publish(
            UUID sessionId,
            UUID questionId,
            String answer,
            boolean followUp
    ) {
        try {
            // rightPush：向列表尾部（右侧）插入一个元素。
            // 为什么用 rightPush 而不是 leftPush：
            // 写入走右侧（尾部），消费者从左侧（头部）取，
            // 右侧进左侧出，构成真正的 FIFO（先进先出）队列，
            // 保证先提交的答案先被评分（先来后到）。
            redis.opsForList().rightPush(
                    QUEUE,
                    // 将 JudgeTask 对象序列化为 JSON 字符串。
                    // 为什么存 JSON 而不是 Java 原生序列化：
                    // 1. JSON 是跨语言的，如果以后有 Node.js/Python 消费者也能读取；
                    // 2. JSON 可读性好，运维可以直接在 Redis CLI 中查看消息内容；
                    // 3. Jackson 序列化性能优于 Java 原生序列化。
                    mapper.writeValueAsString(
                            new JudgeTask(
                                    sessionId,
                                    questionId,
                                    answer,
                                    followUp,   // 追问标记，透传进队列载荷
                                    0           // 初始重试次数为 0，末位仍表示首次投递
                            )
                    )
            );
        } catch (JsonProcessingException e) {
            // 什么情况下会走到这里：ObjectMapper 无法序列化 JudgeTask。
            // 实际上 JudgeTask 是一个 record，字段都是基本类型+String+UUID，
            // 几乎不可能序列化失败。
            // 但是 Jackson 声明抛出 JsonProcessingException，我们必须处理。
            // 这里包装为 IllegalStateException（非受检异常），
            // 让调用方不需要显式 try-catch，简化 controller 层的代码。
            throw new IllegalStateException(
                    "无法序列化评分任务: sessionId=" + sessionId
                            + ", questionId=" + questionId,
                    e
            );
        }
    }

    // ================ 消费方法 ================

    /**
     * 从 Redis 队列中取出一条评分任务并处理（带 200ms 阻塞的备用入口）。
     *
     * 为什么加 @Async("aiScoringTaskExecutor")：
     * 1. 这个方法由 Spring 的异步线程池执行，不阻塞主线程；
     * 2. "aiScoringTaskExecutor" 是自定义线程池 Bean 的名称（见 AsyncConfig），
     *    核心 4 / 最大 8 / 队列 100，专门用于 AI 评分任务。
     *    为什么不用默认的 SimpleAsyncTaskExecutor：AI 任务可能很多，
     *    需要控制并发数，避免线程暴涨导致系统负载过高或 Redis 连接被打满。
     *
     * 注意：日常消费走的是下面同步的 pullOnce()，由 JudgeTaskConsumerLoop 定时循环调用。
     * 本方法保留的是「单次异步消费」能力，适合被外部一次性触发。
     */
    @Async("aiScoringTaskExecutor")
    public void consumeOnce() {
        // rightPopAndLeftPush（对应 Redis 命令 RPOPLPUSH）：
        // 原子性地从 QUEUE 尾部取出一个元素，推入 PROCESSING 队列。
        // 为什么用带超时的版本：队列为空时等待 200ms 后返回 null，避免无限期阻塞占用线程。
        String raw = redis.opsForList().rightPopAndLeftPush(QUEUE, PROCESSING, Duration.ofMillis(200));
        // 队列为空时直接返回（Redis 列表为空时返回 null，而非空字符串）。
        if (raw == null) {
            return;
        }
        JudgeTask task = null;
        try {
            // 反序列化后立刻执行评分工作流，并把载荷里的 followUp 透传给工作流，
            // 追问作答才不会推进主线状态机。
            task = mapper.readValue(raw, JudgeTask.class);
            workflow.judge(task.sessionId(), task.questionId(), task.answer(), task.followUp());
            // 处理成功后，从 PROCESSING 队列中移除这条消息。
            // remove 参数：键名 / 最多删除 1 个匹配元素 / 要删除的 JSON 字符串。
            redis.opsForList().remove(PROCESSING, 1, raw);
        } catch (Exception e) {
            // 任何异常（反序列化失败、AI 调用超时、数据库写入失败等）都走重试或死信逻辑。
            retryOrDeadLetter(raw, task);
        }
    }

    /**
     * 同步拉取并处理一条评分任务（日常消费入口）。
     *
     * <p>判定依据是队列搬运是否返回 null——返回 null 就说明待处理队列此刻没有任务，
     * 本轮无事可做。之所以在 consumeOnce() 之外再开一个方法：consumeOnce() 上有 @Async，
     * 调用后会立刻返回，不能用来「循环拉取」；本方法保持同步语义，处理完才返回，
     * 正好放进 JudgeTaskConsumerLoop 的定时循环里。</p>
     *
     * @return 本次是否消费到任务：true 表示确实拿到并处理了一条，false 表示队列已空
     */
    public boolean pullOnce() {
        // 把队首元素从 QUEUE 原子地搬到 PROCESSING：中间没有「取出来了但还没登记」的窗口，
        // 消费者此刻崩溃任务也还留在处理中队列里，补偿服务才能查得到并重投。
        // 从左边取是因为 publish 用的是 rightPush——右侧进、左侧出，先到先处理。
        // 这里是非阻塞版本：拿不到就返回 null，由外层 @Scheduled 反复调用，不会挂起线程。
        String raw = redis.opsForList().move(QUEUE, Direction.LEFT, PROCESSING, Direction.LEFT);
        // 返回 null 说明 QUEUE 已经空了，本轮没有任务可处理。
        if (raw == null) {
            return false;
        }
        JudgeTask task = null;
        try {
            // 反序列化后立刻执行评分工作流：AI 评分 → 落库 → 推进状态 → 触发追问。
            task = mapper.readValue(raw, JudgeTask.class);
            // pullOnce 同样要把 followUp 透传下去（追问任务重投后仍保持追问语义）。
            workflow.judge(task.sessionId(), task.questionId(), task.answer(), task.followUp());
            // 评分成功，按「值 + 数量 1」精确 remove 掉处理中队列里的这条消息。
            // 不能用 leftPop：处理中队列里可能同时躺着别人的消息，leftPop 会摘错。
            redis.opsForList().remove(PROCESSING, 1, raw);
        } catch (Exception e) {
            // 任何环节失败（反序列化 / AI 评分 / 落库）都交给重投或死信，
            // 避免任务永远留在处理中队列里没人管。
            retryOrDeadLetter(raw, task);
        }
        return true;
    }

    // ================ 重试 / 死信逻辑 ================

    /**
     * 判断任务应该重新入队重试还是进入死信队列。
     * 
     * @param raw  原始的 JSON 字符串，如果 task 解析失败（task==null），
     *             则用 raw 直接入死信队列，因为重试也无法反序列化。
     * @param task 解析后的 JudgeTask 对象，可能为 null（解析失败时）
     */
    public void retryOrDeadLetter(
            String raw,
            JudgeTask task
    ) {
        // 进入死信队列的条件：
        // 条件 1：task == null（反序列化失败）
        //     -> 这说明消息的 JSON 格式损坏或结构不匹配，
        //        重试多少次都不可能成功，必须人工介入。
        // 条件 2：task.retryCount() >= MAX_RETRY（超过最大重试次数）
        //     -> 已经重试过 3 次仍然失败，大概率是永久性异常
        //        （如 AI 模型接口地址错误、prompt 格式不兼容等），
        //        继续重试只会浪费资源。
        if (task == null || task.retryCount() >= MAX_RETRY) {
            // 推入死信队列，供运维人工排查。
            redis.opsForList().rightPush(DEAD, raw);
            // 从 PROCESSING 队列中删除这条消息。
            // 如果不删除，它永远留在 PROCESSING 中，
            // 补偿服务会不断扫描到它（评分 1.1），产生不必要的日志。
            redis.opsForList().remove(
                    PROCESSING,
                    1,
                    raw
            );
            return;
        }

        try {
            // 构建重试任务：retryCount 比原来 +1。
            // 为什么创建新的 JudgeTask 而不是修改原对象：
            // JudgeTask 是 Java record，所有字段都是 final 的，
            // 不可修改。这是 record 的设计意图——不可变性。
            // 创建新对象也不会影响已存在的旧对象引用，线程安全。
            JudgeTask retry = new JudgeTask(
                    task.sessionId(),
                    task.questionId(),
                    task.answer(),
                    task.followUp(),        // 原样保留追问标记，重投后仍走追问分支
                    task.retryCount() + 1   // 重试次数 +1
            );

            // 重新投递到待处理队列，等待下一次消费。
            redis.opsForList().rightPush(
                    QUEUE,
                    mapper.writeValueAsString(retry)
            );

            // 从 PROCESSING 队列中删除旧消息。
            // 删除操作在 rightPush 之后执行：
            // 即使删除失败（如 Redis 宕机），旧消息还留在 PROCESSING 中，
            // 由补偿服务兜底扫描处理（评分 1.1）。
            // 但 rightPush 已经成功了，所以下一轮消费不会丢失任务。
            redis.opsForList().remove(
                    PROCESSING,
                    1,
                    raw
            );
        } catch (JsonProcessingException serializationError) {
            // 什么情况下会走到这里：ObjectMapper 无法序列化重试的 JudgeTask。
            // 与 publish 方法一样，JudgeTask 的字段都很简单，几乎不可能失败。
            // 但为了完整性，这里声明了异常处理。
            // 注意：这里抛出 IllegalStateException 会导致当前任务没有被正确处理，
            // 它仍然在 PROCESSING 队列中，由补偿服务兜底。
            throw new IllegalStateException(
                    "无法序列化重试任务: sessionId="
                            + task.sessionId()
                            + ", questionId=" + task.questionId(),
                    serializationError
            );
        }
    }
}
```

---

#### 文件 3：`src/main/java/com/example/interview/application/JudgeTaskConsumerLoop.java`

**创建这个文件干什么：** 它是评分队列的「节拍器」。`pullOnce()` 用的是非阻塞搬运（拿不到就返回 null），不会挂起线程，所以必须有人反复拉取——否则任务会一直躺在队列里，补偿服务查库时看不到任何「已评分」的记录，只会一遍遍重复补偿，等于空转。

**为什么用 `@Scheduled` 而不是把循环写在方法里：** 写在方法里就是常驻线程 + 忙等，一旦抛异常整个消费者永久退出；`@Scheduled(fixedDelay)` 由 Spring 调度，单次异常只会跳过这一轮，下一轮照常拉取，健壮性更好。间隔取 200ms：队列为空时最多带来 5 次/秒的空查询，成本可忽略。

**为什么用 `fixedDelay` 而不是 `fixedRate`：** 必须等上一轮处理完再开始计时，否则队列积压时会不断叠加新的拉取轮次。

```java
package com.example.interview.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ============================================================
// 评分任务消费者循环
// ============================================================
// 职责：定时从评分队列拉取任务，交给 JudgeTaskPublisher.pullOnce() 处理。
//
// 为什么需要一个循环：pullOnce 用的是非阻塞的队列搬运（拿不到就返回 null），
// 不会像 consumeOnce 里带超时的阻塞弹出那样挂起等待。所以必须由调用方反复拉取，
// 否则任务会一直躺在队列里，补偿服务查库时看不到任何"已评分"的记录，
// 只会一遍遍重复补偿，等于空转。
//
// 为什么用 @Scheduled 而不是把循环写在方法里：
//   写在方法里就是常驻线程 + 忙等，一旦抛异常整个消费者永久退出；
//   @Scheduled(fixedDelay) 由 Spring 调度，单次异常只会跳过这一轮，
//   下一轮照常拉取，健壮性更好。
//   间隔取 200ms：队列为空时最多带来 5 次/秒的空查询，成本可忽略。
// ============================================================
@Component
public class JudgeTaskConsumerLoop {

    // 只依赖发布者：取任务、处理、失败重试都在 JudgeTaskPublisher 里完成，
    // 本类只负责"按节奏调用"，职责保持单一。
    private final JudgeTaskPublisher publisher;

    // 构造器注入，方便测试时替换成 mock。
    public JudgeTaskConsumerLoop(JudgeTaskPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 定时拉取一条评分任务。
     *
     * <p>fixedDelay 而不是 fixedRate：必须等上一轮处理完再开始计时，
     * 否则队列积压时会不断叠加新的拉取轮次。</p>
     */
    // 每轮处理完再等 200ms 触发下一次；队列为空时不会占着 CPU 空转。
    @Scheduled(fixedDelay = 200)
    public void poll() {
        // 一次只取一条：单个消费者不长时间占用线程；
        // 返回 false（队列已空）也直接结束本轮，等下一次调度即可。
        publisher.pullOnce();
    }
}
```

---

### 评分 1.1：创建答题轮次异步补偿服务

**本小节目的：** 处理「边界异常情况」的兜底方案。在理想情况下，消费者正常处理所有任务。但在生产环境中可能会出现：
1. 消费者进程崩溃（OOM、宕机、部署重启），导致已从 QUEUE 取出但尚未处理完的任务卡在 PROCESSING 队列中。
2. Redis 集群主从切换导致队列中个别消息丢失。
3. 消息被误删除等运维操作。

这些异常的共同结果是：`interview_answer` 表中某些答案的 `score IS NULL` 且长时间未被评分。补偿服务的职责就是定期扫描这些「卡住」的记录，检查 Redis 队列中是否还有对应的任务，如果没有则重新投递。

**本小节流程：**
```text
每 30 秒执行一次
    │
    ▼
数据库扫描：创建时间超过 5 分钟且 score IS NULL 且 status != 'JUDGE_FAILED' 的答案
（SQL 额外读出 is_follow_up，供重投时原样带回）
    │
    ▼
对每一条卡住的答案：
    │
    ├─→ 用当前字段构造 JudgeTask 并序列化为 JSON
    │   ├─→ LPOS 检查主队列里是否已有这条任务
    │   ├─→ heartbeat.exists(sessionId) 检查处理线程是否还活着
    │   │   ├─→ 队列里有 或 心跳还在：跳过（等它自己跑完）
    │   │   └─→ 两者都没有：进入下一步
    │   │
    │   └─→ 如果 retryCount+1 > 3
    │       ├─→ 是：置 status='JUDGE_FAILED'，
    │       │      往 interview_judge_compensation_log 插一条记录
    │       └─→ 否：原样保留 is_follow_up，重投到 QUEUE，
    │                更新 retry_count 和 status='PENDING_JUDGE'
    │
    └─→ 处理下一条
```

#### 文件 1：`src/main/java/com/example/interview/application/JudgeTaskCompensationService.java`

**创建这个文件干什么：** 这是一个定时调度任务（`@Scheduled`），周期性扫描数据库中「超过 5 分钟仍未评分」的答案记录，先通过 Redis 的 `LPOS` 命令检查待处理队列中是否仍然存在对应的任务消息，再用 `HeartbeatService.exists(sessionId)` 反查「处理这条答案的线程是否还活着」，两者都没有才判定为真的卡住并重新投递。

**它的角色：** 兜底策略的最后一道防线。正常流程中，publish → 消费者循环 → judge 已经能处理绝大多数任务。补偿服务只解决那部分边界异常场景，确保没有评分被永远遗漏。

**为什么只查 LPOS 不够：** 任务一旦被消费者取走，`LPOS` 立刻查不到。若线程随后崩溃，这条答案会一直被当成「正在评分」，永远等不到重投。所以必须再补一个心跳判断：队列里还有任务 = 还没被人取走，正常；心跳还在 = 取走它的线程还活着，只是 AI 长尾比较慢，重投会造成同题双评。

**依赖什么：**
- `JdbcTemplate`：Spring JDBC 模板，用于扫描和更新 `interview_answer` 表、写补偿日志。
- `StringRedisTemplate`：Redis 操作（LPOS 检查 + 重新投递）。
- `ObjectMapper`：序列化 JudgeTask 为 JSON。
- `QuestionMapper`（MyBatis-Plus）：用于 `findSequenceNo()` 方法按主键查询题号。
- `SessionSnapshotRepository`：会话快照仓库（构造器注入）。
- `HeartbeatService`：后台任务心跳服务，用 `exists(sessionId)` 回答「这条卡住的答案，处理线程还活着吗」。

**被谁调用：** Spring 的 `@Scheduled` 注解自动触发，无需手动调用。

```java
package com.example.interview.application;

import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;

import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

/**
 * 定时扫描评分卡住记录的补偿服务。
 * <p>
 * 为什么需要这个类：即使有 processing 队列保护，消费者崩溃时任务可能永远卡在
 * PROCESSING 队列中（没有其他人来消费它）。这个服务作为「兜底」策略，
 * 通过定期检查数据库中未评分的记录来判断是否需要重新投递。
 * </p>
 */
@Service
public class JudgeTaskCompensationService {

    /**
     * Redis 待处理队列的键名，与 JudgeTaskPublisher 中的 QUEUE 必须完全一致。
     * 为什么不复用 JudgeTaskPublisher.QUEUE：两个类职责不同，
     * 如果耦合在一起（JudgeTaskPublisher 不存在时补偿服务也无法编译），
     * 违背了「补偿服务独立运行」的设计意图——即使发布者类出了问题，
     * 补偿服务也应该能独立工作。
     */
    private static final String QUEUE = "interview:task:judge";

    /**
     * 补偿重试的最大次数。
     * 注意与 JudgeTaskPublisher.MAX_RETRY 区分：
     * - MAX_RETRY（3）是同一个任务在正常消费流程中的重试次数上限；
     * - MAX_COMPENSATION_COUNT（3）是补偿服务重新投递的次数上限。
     * 如果一个任务在正常消费中失败 3 次进入死信，补偿服务扫描到后，
     * 会发现 retry_count 已经是 3（或 4），从而不再重新投递，标记为失败。
     * 两个上限共同保证了任务不会无限循环。
     */
    private static final int MAX_COMPENSATION_COUNT = 3;

    // ================ 依赖注入 ================

    /**
     * Spring JDBC 模板，用于直接操作 interview_answer 表。
     * 为什么用 JdbcTemplate 而不是 MyBatis-Plus：
     * 补偿服务需要执行特定的 SQL（如条件扫描、更新特定字段），
     * JdbcTemplate 更直接，不需要为了一次查询创建 Mapper 接口。
     */
    private final JdbcTemplate jdbc;

    /**
     * Redis 操作模板，与 JudgeTaskPublisher 共享同一个 Bean。
     * 用于 LPOS 检查队列中是否存在任务，以及重新投递任务。
     */
    private final StringRedisTemplate redis;

    /**
     * JSON 序列化工具，与 JudgeTaskPublisher 共享同一个 Bean。
     */
    private final ObjectMapper mapper;

    /**
     * 题目表的 MyBatis-Plus Mapper，供 findSequenceNo 查询题目序号。
     * 原始版本缺失该字段注入，导致 findSequenceNo 无法通过编译，这里一并补上。
     */
    private final QuestionMapper questions;

    /**
     * 会话快照仓库（构造器注入）。
     */
    private final SessionSnapshotRepository sessionSnapshotRepository;

    /**
     * 心跳服务：用来回答「这条卡住的答案，处理线程还活着吗」。
     * 任务被消费者取走后 LPOS 就查不到了，只有心跳还在，
     * 才能说明线程只是 AI 长尾跑得慢、并没有死。
     */
    private final HeartbeatService heartbeat;

    /**
     * 构造函数注入所有依赖。
     */
    public JudgeTaskCompensationService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper mapper,
            // 题目 Mapper，用于 findSequenceNo 查询题号。
            QuestionMapper questions,
            SessionSnapshotRepository sessionSnapshotRepository,
            // 心跳服务，按类型注入；追加在参数末尾，不打乱既有参数顺序。
            HeartbeatService heartbeat
    ) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.mapper = mapper;
        this.questions = questions;
        this.sessionSnapshotRepository = sessionSnapshotRepository;
        this.heartbeat = heartbeat;
    }

    // ================ 主逻辑：定时补偿扫描 ================

    /**
     * 每 30 秒执行一次，扫描创建超过 5 分钟且仍未评分的答案。
     * 
     * 为什么是 30 秒间隔：
     * 太短（如 5 秒）会频繁扫描数据库，对 DB 造成压力；
     * 太长（如 5 分钟）则卡住的任务迟迟得不到补偿。
     * 30 秒是实践中较好的平衡点。
     * 
     * 为什么是 5 分钟阈值：
     * 正常评分流程通常在几秒内完成（AI 调用 3～10 秒 + 数据库写入 <100ms）。
     * 如果超过 5 分钟还没有评分，说明一定出了异常（消费者崩溃、队列卡住等）。
     * 设置 5 分钟的缓冲，避免误伤正常但稍慢的流程。
     * 
     * @Scheduled(fixedDelay = 30_000) 的含义：
     * fixedDelay 指上一次执行完成后延迟 30 秒再执行下一次。
     * 区别于 fixedRate（固定频率），fixedDelay 保证
     * 两次执行不会重叠——即使某次扫描因数据库慢查询耗时 10 秒，
     * 下一次也会在 10+30=40 秒后开始，不会出现并发执行。
     */
    @Scheduled(fixedDelay = 30_000)
    public void compensateStuckAnswers() {
        // 执行 SQL 查询，查找需要补偿的答案记录。
        // SQL 条件说明：
        // 1. score is null：还未评分
        // 2. status <> 'JUDGE_FAILED'：未标记为"评分失败"
        //    （标记为失败的记录不再重复补偿，避免死循环）
        // 3. created_at < DATE_SUB(NOW(), INTERVAL 5 MINUTE)：
        //    创建时间超过 5 分钟，确保不会扫到刚提交的正常答案
        // 4. order by created_at：先处理最老的卡住记录
        // 5. limit 100：每次最多处理 100 条，避免一次扫描过多导致数据库抖动
        List<PendingAnswer> pending =
                jdbc.query(
                        """
                        select id, session_id, question_id,
                               answer, retry_count, is_follow_up
                        from interview_answer
                        where score is null
                          and status <> 'JUDGE_FAILED'
                          and created_at <
                              DATE_SUB(NOW(), INTERVAL 5 MINUTE)
                        order by created_at
                        limit 100
                        """,
                        // ResultSetExtractor：将 ResultSet 的每一行映射为
                        // PendingAnswer record 对象。
                        // 为什么用 lambda 而不是 RowMapper 实现类：
                        // 因为这个查询只在这里使用，单独创建一个 PendingAnswerRowMapper
                        // 类过于冗余。lambda 更简洁。
                        (ResultSet resultSet, int rowNum) ->
                                new PendingAnswer(
                                        resultSet.getLong("id"),
                                        UUID.fromString(
                                                resultSet.getString("session_id")
                                        ),
                                        UUID.fromString(
                                                resultSet.getString("question_id")
                                        ),
                                        resultSet.getString("answer"),
                                        resultSet.getInt("retry_count"),
                                        // 读出追问标记，补偿重投时必须原样带回去，
                                        // 否则追问任务会被当成初次作答，错误地推进主线状态机。
                                        resultSet.getInt("is_follow_up")
                                )
                );

        // 逐条处理卡住的答案。
        // 为什么不批量处理：每条答案的补偿条件可能不同
        // （有的需要重试，有的需要标记失败），
        // 逐条处理逻辑更清晰，且 limit 100 控制了一次处理的总量。
        for (PendingAnswer answer : pending) {
            compensate(answer);
        }
    }

    // ================ 单条补偿逻辑 ================

    /**
     * 对一条卡住的答案执行补偿操作。
     * 
     * @param answer 从数据库扫描到的卡住答案记录
     */
    private void compensate(PendingAnswer answer) {
        try {
            // 构建 JudgeTask 对象，使用数据库中当前的 retryCount；
            // 把读出的 is_follow_up 转成 boolean 带到队列载荷里。
            JudgeTask task = new JudgeTask(
                    answer.sessionId(),
                    answer.questionId(),
                    answer.answer(),
                    answer.isFollowUp() == 1,
                    answer.retryCount()
            );
            String raw = mapper.writeValueAsString(task);

            // 关键检查：LPOS 判断待处理队列中是否已经有这条任务。
            // 为什么用 LPOS（Redis 6.0+ 命令）：它是一个 O(N) 的查找命令，
            // 在队列中从头搜索匹配元素。替代方案是 LRANGE 取出全部元素再在
            // Java 端检查，但那会把整个队列传输到客户端、浪费带宽；LPOS 直接
            // 在 Redis 后端查找，更省。如果队列很长（数十万条）LPOS 会变慢，
            // 但正常待处理队列不会有太多积压，可以接受。
            // 这段 LPOS 检查只在本处使用一次，因此直接内联，不再单独抽方法。
            byte[] queueKey =
                    QUEUE.getBytes(StandardCharsets.UTF_8);
            byte[] taskValue =
                    raw.getBytes(StandardCharsets.UTF_8);
            // 执行 LPOS 命令。connection.execute(Object... cmd) 的第一个参数是
            // 命令名，后面的参数是该命令的参数，相当于 CLI 里执行 LPOS key "<JSON>"。
            Object queuePosition = redis.execute(
                    (RedisCallback<Object>) connection -> connection.execute(
                            "LPOS",
                            queueKey,
                            taskValue
                    )
            );
            // 待处理队列里还有任务 或 处理线程心跳还在，才跳过。
            // 为什么必须补上心跳这一半：任务一旦被消费者取走，LPOS 立刻查不到；
            // 若线程随后崩溃，这条答案会一直被当成"正在评分"，永远等不到重投。
            boolean alive = heartbeat.exists(answer.sessionId());
            if (queuePosition != null || alive) {
                // 队列里还有任务 = 还没被人取走，正常；
                // 心跳还在 = 取走它的线程还活着，只是 AI 长尾比较慢，重投会造成同题双评。
                return;
            }

            // 检查累计重试次数是否超过上限。
            int nextRetry = answer.retryCount() + 1;
            if (nextRetry > MAX_COMPENSATION_COUNT) {
                // 超过 3 次补偿上限，标记为评分失败后不再重试。以下“标记失败”逻辑
                // 只在此处使用一次，因此直接内联，不再单独抽取方法。
                // 更新答案表：标记为评分失败。WHERE 中的 score is null 是为了防并发——
                // 若补偿期间正常流程已评完分写入 score，这里就不应覆盖已评分的记录。
                jdbc.update(
                        """
                        update interview_answer
                        set retry_count = ?,
                            status = 'JUDGE_FAILED'
                        where id = ? and score is null
                        """,
                        nextRetry,
                        answer.id()
                );
                // 写入补偿日志，供运维排查失败原因；日志保留 retry_count，
                // 便于分析重试分布、优化补偿上限配置，日后重算也能追溯原始数据。
                jdbc.update(
                        """
                        insert into interview_judge_compensation_log
                            (answer_id, retry_count, reason)
                        values (?, ?, ?)
                        """,
                        answer.id(),
                        nextRetry,
                        "Redis 队列中找不到对应的评分任务，补偿多次后放弃"
                );
                return;
            }

            // 构建补偿后的任务对象，retryCount +1，追问标记原样保留。
            JudgeTask compensated = new JudgeTask(
                    answer.sessionId(),
                    answer.questionId(),
                    answer.answer(),
                    answer.isFollowUp() == 1,
                    nextRetry
            );
            String compensatedRaw =
                    mapper.writeValueAsString(compensated);

            // 重新投递到 Redis 待处理队列。
            // 为什么这里只用 rightPush（而不需要先 remove PROCESSING）：
            // 补偿任务的场景是"任务从未被消费"（卡在 PROCESSING 或已经丢失），
            // 而不是"消费者处理过程中失败"。所以直接向 QUEUE 投递即可。
            redis.opsForList().rightPush(
                    QUEUE,
                    compensatedRaw
            );

            // 同步更新数据库中的 retry_count 和 status。
            // 为什么要在同一个事务中更新数据库：
            // 防止补偿服务投递成功但数据库状态未更新，
            // 导致下一轮扫描时再次补偿同一个答案（重复投递）。
            // 注意：这里是自动提交模式，每个 update 独立事务。
            // 如果 Redis 投递成功但数据库更新失败：
            // 下一轮会再次扫描到这条记录，届时 queueContains 会返回 true
            // （因为 Redis 中已有任务），不会重复投递，但也无法更新状态。
            // 这是最终一致性模型的一个小缺口，但对于补偿场景可以接受。
            jdbc.update(
                    """
                    update interview_answer
                    set retry_count = ?,
                        status = 'PENDING_JUDGE'
                    where id = ? and score is null
                    """,
                    nextRetry,
                    answer.id()
            );
        } catch (JsonProcessingException e) {
            // 序列化失败的情况（理论上几乎不会发生）。
            throw new IllegalStateException(
                    "无法序列化补偿任务: answerId=" + answer.id(),
                    e
            );
        }
    }

    // ================ 工具方法：查询题号 ================

    /**
     * 根据题目 ID 查询该题目在面试中的题号（sequence_no）。
     * <p>
     * 这个方法不属于定时补偿主链路，是供外部其他流程使用的轻量查询辅助方法，
     * 因此放在主逻辑之后、内部 record 之前。它只做一次按主键的查询和取值校验，
     * 本身不抽取更多小方法。
     * </p>
     *
     * @param questionId 题目 ID
     * @return 题号（从 1 开始排序）
     * @throws IllegalArgumentException 如果找不到题目或题号为 null
     */
    public int findSequenceNo(UUID questionId) {
        // 通过题目表的 MyBatis-Plus Mapper 按主键查询实体。
        // 原始样品遗漏了 questions 字段的注入，会导致这里无法编译，
        // 现已在字段声明与构造器补上 QuestionMapper 的注入。
        QuestionEntity question = questions.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getId,
                                questionId.toString()
                        )
        );
        // 查不到题目或题号为空视为异常，直接抛出 IllegalArgumentException。
        if (question == null
                || question.getSequenceNo() == null) {
            throw new IllegalArgumentException(
                    "未找到题目序号: questionId=" + questionId
            );
        }
        return question.getSequenceNo();
    }

    // ================ 内部 record ================

    /**
     * 数据库扫描结果的一行映射。
     * 为什么用 record 而不是 class：
     * 这个数据只在补偿服务内部使用，不对外暴露。
     * record 自动生成构造器、getter，代码最简洁。
     * 如果用 class + @Data 需要额外依赖 Lombok，且不易被其他模块误引用。
     */
    private record PendingAnswer(
            long id,              // interview_answer 表主键
            UUID sessionId,       // 面试会话 ID
            UUID questionId,      // 题目 ID
            String answer,        // 答案文本
            int retryCount,       // 当前重试次数
            // 追问标记（0=初次作答，1=追问作答）。用 int 承接数据库 TINYINT，
            // 构造 JudgeTask 时再转成 boolean，避免直接读 boolean 的映射问题。
            int isFollowUp
    ) {
    }
}
```

---

### 评分 2：创建评分 AI 调用

**本小节目的：** 在任务从 Redis 队列取出后，真正调用 AI 接口进行评分。本小节封装了 AI 调用的完整流程：组装评分提示词（拼上评分 Skill，追问作答额外加一句区分说明）→ 调用 AI（带 SingleFlight 去重和 Fencing Token 防过期）→ 校验 JSON 格式（不合格就把问题反馈给 AI 重新生成，最多 2 次）→ 返回结构化的 `JudgeResult`。

**为什么需要单独的「AI 调用」层而不是在工作流中直接调用 AI？**
1. **关注点分离**：AI 调用涉及 prompt 组装、格式自纠错、JSON 解析、超时控制等复杂逻辑，不应该和工作流编排混在一起。
2. **可测试性**：单独抽取后，`InterviewWorkflowService` 可以 mock `InterviewAiFacade.judge()`，不需要真的调用 AI。
3. **可替换性**：如果需要换 AI 模型（如从 GPT-4 切换到 Claude），只需要修改 `InterviewAiFacade`，工作流代码不受影响。

**本小节流程：**
```text
JudgeTask 传递进来（sessionId, questionId, answer, followUp）
    │
    ▼
InterviewAiFacade.judge(sessionId, questionId, answer, followUp) 接收参数
    │
    ├─→ 生成去重 Key：
    │     "judge:" + sessionId + ":" + questionId + ":" + answer.hashCode()
    │     + ":" + followUp
    │     （带上 followUp，避免"初次评分"与"追问评分"被合并成一次）
    │
    ├─→ singleflight.executeFenced(key, 2 分钟, () -> chatJudge(...)):
    │     只有一个请求真正调用 AI，其余相同请求等待结果
    │     │
    │     └─→ chatJudge()：
    │           拼 skillPrompt("interview-scoring") + 评分指令
    │           （followUp=true 时追加"这是对同一道题的追问回答…"）
    │           → chat(prompt) → judgeFormatIssue 检查
    │           ├─ 合法 → 返回内容
    │           ├─ 缺 score / feedback 为空 / 非法 JSON
    │           │   → 把具体问题反馈给 AI 重新生成（最多 2 次）
    │           └─ 2 次仍不合格 → 抛 IllegalStateException
    │
    ├─→ acceptFence(key, token) 校验：
    │     检查返回结果的 token 是否是最新的一代，
    │     防止较早返回的旧结果覆盖较新的结果；
    │     失败则抛 IllegalStateException（拒绝过期评分结果）
    │
    ├─→ parseJudge(result.value(), result.token())：
    │     到这里内容已通过格式校验，这里只做纯解析：
    │     取 score（asDouble）、feedback（asText("")）
    │
    └─→ 返回 JudgeResult(score, feedback, fencingToken, stale=false)
```

#### 文件 1：`src/main/java/com/example/interview/application/JudgeResult.java`

**创建这个文件干什么：** 这是 AI 评分经过解析和业务校验后的结果对象。它携带了四个关键信息：分数、反馈文本、Fencing Token（防过期机制的核心）、以及过期标志位。

**为什么需要这个类：** AI 返回的是原始 JSON 字符串，不能直接透传到业务层。需要经过 JSON 解析 → 字段提取 → 分数范围校验 → 过期判断后，才能得到一个业务层可安全使用的结构化对象。

```java
package com.example.interview.application;

/**
 * AI 评分经过解析和业务校验后的结果对象。
 * <p>
 * 为什么用 record：
 * 1. 评分结果是不可变的，创建后不会修改；
 * 2. record 自动实现了 equals/hashCode，可以安全地作为 Map 的 Key；
 * 3. 在多线程环境下不需要额外的同步措施。
 * </p>
 */
public record JudgeResult(

        /**
         * AI 给出的分数，范围 0.0 ~ 100.0。
         * 为什么是 double 而不是 int：
         * AI 可能会返回 85.5 这样的带小数分数，
         * 用 double 可以保留精度。
         * 注意：字段是否"存在"由 chatJudge 的格式校验负责——
         * 缺 score 字段会反馈给 AI 重新生成；到这里只是纯解析，
         * path("score").asDouble() 取不到值时返回 0。
         */
        double score,

        /**
         * AI 生成的评价反馈，自然语言文本。
         * 可能包含：正确性判断、遗漏要点、改进建议、表达评价等。
         * 会直接展示给候选人看到，所以不应该包含内部 prompt 信息或
         * 面试官内部的评分标准细节。
         * 同样地，feedback 为空会由 chatJudge 的格式校验拦下并要求 AI 重写，
         * 这里解析时对缺省值兜底为空字符串。
         */
        String feedback,

        /**
         * 题目锁或围栏令牌（Fencing Token）。
         * 为什么需要这个字段：<br>
         * 在分布式系统中，同一个题目可能被多次评分
         * （如 AI 返回超时后重试导致的重复评分）。
         * Fencing Token 是一个单调递增的令牌：
         * - 每次对同一条答案开始评分时，生成一个新的更大的 token；
         * - 评分完成后，检查保存的 token 是否大于等于当前 token；
         * - 如果已存在的 token 更大，说明这是一次「迟到」的旧评分结果，
         *   应当丢弃。<br>
         * 这个机制由 SingleFlight 框架自动生成和管理，
         * 业务流程只需要传递和保存这个值即可。
         */
        long fencingToken,

        /**
         * true 表示这个结果已经过期，不能用于覆盖当前状态。
         * 
         * 什么情况下 stale 为 true：
         * 当 SingleFlight 的 acceptFence() 校验失败时，
         * 说明已经有更新的评分结果被保存了，当前结果是「旧」的。
         * 
         * 注意：在 InterviewAiFacade.judge() 中，如果 Fencing Token 校验失败，
         * 会直接抛出 IllegalStateException（"stale judge result rejected"），
         * 不会构造 stale=true 的 JudgeResult。
         * 所以业务流程中可以信任：拿到的 JudgeResult 一定是新鲜的（stale=false）。
         * stale 字段保留在这里供未来扩展（如延迟校验场景）。
         */
        boolean stale

) {
}
```

#### 文件 2：`src/main/java/com/example/interview/ai/InterviewAiFacade.java`（新增 `judge` 方法）

**创建这个文件干什么：** `InterviewAiFacade` 是 AI 调用的门面（Facade）类，封装了与 AI 模型交互的所有细节。本小节在其中新增/完善了评分相关的四个私有方法：`judge()`（对外入口）、`chatJudge()`（拼提示词 + 格式自纠错）、`judgeFormatIssue()`（检查 AI 输出格式）、`parseJudge()`（纯解析）。

**这个 judge 方法在本模块中的角色：** 它是「评分 1」队列消费者 → 「评分 3」工作流 之间的桥梁。消费者从队列取出任务后，通过工作流调用 `InterviewAiFacade.judge()` 来真正执行 AI 评分。它不关心数据持久化或状态推进，只负责调用 AI 并返回结构化结果。

**依赖什么（已在其他章节定义）：**
- `RedisSingleflight`：分布式单飞（请求合并 + 围栏令牌）组件，在 AI 基础设施章节定义。
  - `executeFenced(key, timeout, producer)`：相同 key 的请求共享同一个 AI 调用，其余请求等待结果，返回带 token 的 `FencedValue`。
  - `acceptFence(key, token)`：检查 token 是否是最新的一代。
- `SkillRegistry`：业务 Skill 注册表，`skillPrompt("interview-scoring")` 把评分规则文件拼进提示词最前面。
- `chat(prompt)`：底层 AI 调用方法，空返回会抛异常。

**被谁调用：** 由 `InterviewWorkflowService.judge()`（评分 3 中定义）调用。

**格式自纠错（chatJudge）：** AI 返回的内容先过 `judgeFormatIssue()`：缺 `score` 字段、`feedback` 为空、或根本不是合法 JSON，都不会直接抛异常，而是把具体问题拼进纠正提示词让 AI 重新生成，**最多纠正 2 次**；2 次仍不合格才抛 `IllegalStateException`。`parseJudge()` 因此只做纯解析，不再校验。

```java
// 以下代码片段位于 src/main/java/com/example/interview/ai/InterviewAiFacade.java 文件中，
// 在原有类体中新增/完善评分相关方法。为了保持文档完整性，这里展示的是完整的上下文。
// 类级别的注解和构造函数等其他字段已在 AI 基础设施章节定义，此处省略。

/**
 * 组织评分上下文并调用 AI，返回结构化的分数、优缺点和建议。
 * <p>
 * 这个方法的核心设计思想：<br>
 * 1. 请求合并（Singleflight）：同一道题同一份答案的多次评分请求合并为一次 AI 调用；<br>
 * 2. Fencing Token：防止较早启动的 AI 调用返回的结果覆盖较新启动的调用结果；<br>
 * 3. 格式自纠错：AI 输出不合规时，把问题反馈给它重新生成，而不是直接抛异常。
 * </p>
 *
 * @param sessionId  面试会话 ID，用于生成去重 Key 和 Fencing Token
 * @param questionId 题目 ID，用于组装评分的 prompt
 * @param answer     候选人提交的答案正文
 * @param followUp   是否追问作答：透传给 chatJudge，决定提示词里要不要加区分说明
 * @return 经过校验的结构化评分结果，永不为 null
 * @throws IllegalStateException 如果 Fencing Token 校验失败，或 AI 多次输出仍不合格式
 */
public JudgeResult judge(UUID sessionId, UUID questionId, String answer,
                         // 新增 followUp 参数，透传给 chatJudge 决定评分提示词。
                         boolean followUp)
{
    // ---- 第一步：生成去重 Key ----
    // 为什么 key 里要带上 followUp：
    // "初次评分"与"追问评分"的 sessionId + questionId + answer 完全可能相同，
    // 不带这个标记，两个请求会被 Singleflight 合并成同一次结果，追问评分就拿错了分。
    var key = "judge:" + sessionId + ":" + questionId
            + ":" + answer.hashCode()
            + ":" + followUp;

    // ---- 第二步：通过 Singleflight 执行带围栏的 AI 调用 ----
    // executeFenced(key, ttl, producer)：相同 key 的请求共享同一次 AI 调用，
    // 只有一个请求真正执行 producer，其余等待复用；返回带 token 的 FencedValue。
    // 生产者里内置格式自纠错：AI 输出不合规时会把问题反馈给它重新生成。
    var result = singleflight.executeFenced(
            key,
            Duration.ofMinutes(2),
            () -> chatJudge(sessionId, questionId, answer, followUp)
    );

    // ---- 第三步：Fencing Token 过期校验 ----
    // 场景：用户提交答案 A（token=1），很快修改答案重新提交 B（token=2）。
    // 如果 A 的 AI 调用先返回，此时 acceptFence(key, 1) 返回 false（最新 token 已是 2），
    // 说明 A 是旧结果，必须丢弃，否则会用旧答案的评分覆盖新答案的评分。
    if (!singleflight.acceptFence(key, result.token())) {
        throw new IllegalStateException(
                "拒绝过期评分结果: sessionId=" + sessionId
                        + ", questionId=" + questionId
        );
    }

    // ---- 第四步：纯解析 AI 返回的 JSON ----
    // 到这里 content 已经过 chatJudge 的格式校验，parseJudge 只负责取值，不再校验。
    return parseJudge(result.value(), result.token());
}

/**
 * 调用 AI 生成评分并做格式自纠错的入口。
 * <p>
 * 若 AI 返回的内容缺 score / feedback 或不是合法 JSON，不直接抛异常，
 * 而是把具体问题反馈给 AI，让它按正确格式重新生成，最多纠正 2 次；
 * 反复不满足时才抛出异常，避免无限循环。
 * </p>
 * <p>
 * 提示词起点先拼上评分 Skill（interview-scoring），让 AI 先读到
 * "评分维度、分数档位、JSON 结构"这些业务规则，再执行评分指令。
 * followUp=true 时额外追加一句区分说明，初次作答保持原提示词不变。
 * </p>
 */
private String chatJudge(UUID sessionId, UUID questionId, String answer,
                         boolean followUp) {
    String prompt = skillPrompt("interview-scoring")
            + "你是严格的技术面试官。"
            + "请用 JSON 返回 score(0-100) 和 feedback。"
            + "按正确性、完整性、深度、表达综合评分。"
            + (followUp
                    ? "这是对同一道题的追问回答，请结合原题评分与追问目标综合评估。"
                    : "")
            + "题目ID=" + questionId
            + "，候选人回答=" + answer;
    String content = chat(prompt);
    // 最多纠正 2 次：把"哪里不合格"写进纠正提示词，让 AI 只返回规定格式的 JSON。
    for (int attempt = 0; attempt < 2; attempt++) {
        String issue = judgeFormatIssue(content);
        if (issue == null) {
            return content;
        }
        content = chat("上次输出不满足要求：" + issue
                + "\n请仅返回 JSON："
                + "{\"score\":0-100,\"feedback\":\"评语\"}"
                + "，不要输出其他内容。\nAI 上次输出：\n"
                + content);
    }
    throw new IllegalStateException(
            "AI 多次输出仍不符合评分格式: sessionId="
                    + sessionId + ", questionId=" + questionId
    );
}

/**
 * 检查 AI 输出是否为合法评分 JSON。合法返回 null；
 * 否则返回能够指导 AI 修正的具体问题描述，供 chatJudge 拼进纠正提示词。
 */
private String judgeFormatIssue(String content) {
    try {
        JsonNode node = new ObjectMapper().readTree(content);
        if (node.path("score").isMissingNode()) {
            return "缺少 score 字段（0-100 的数值）";
        }
        if (node.path("feedback").asText("").isBlank()) {
            return "缺少 feedback 字段（非空评语）";
        }
        return null;
    } catch (JsonProcessingException e) {
        return "输出不是合法 JSON";
    }
}

/**
 * 纯解析：把已被校验为合法结构的评分 JSON 转成 JudgeResult。
 * 格式校验统一在 chatJudge 的重试循环里完成，这里不再抛异常。
 */
private JudgeResult parseJudge(String content, long fencingToken) {
    JsonNode node = parseJson(content);
    return new JudgeResult(
            node.path("score").asDouble(),
            node.path("feedback").asText(""),
            fencingToken,
            false
    );
}
```

---

### 评分 3：创建评分工作流

**本小节目的：** 串联任务消费、AI 评分、结果保存、状态推进和后续流程触发。这是整个评分模块的「大脑」——它不负责具体的 Redis 队列操作（那是评分 1 的事），也不负责具体的 AI 调用（那是评分 2 的事），而是编排这些组件完成一次完整的评分业务流程。

**本小节流程：**
```text
JudgeTaskPublisher.pullOnce() 调用 InterviewWorkflowService.judge(..., followUp)
    │
    ├─→ followUp == true（追问作答）：走早返回分支
    │     ├─→ ai.judge(sessionId, questionId, answer, true) 执行 AI 评分
    │     ├─→ answers.findJudge(sessionId, questionId, true) 幂等检查
    │     │     └─→ 已有分数：回放已保存结果（只读快照状态，不做 CAS）
    │     ├─→ answers.recordJudge(..., true) 写回 is_follow_up=1 那一行
    │     └─→ 返回结果：不推进状态机、不写快照、不发布追问任务
    │
    └─→ followUp == false（初次作答）：走主线
          ├─→ 加载会话快照（当前状态、版本号、CAS 令牌）
          ├─→ 调用 InterviewAiFacade.judge(..., false) 执行 AI 评分
          ├─→ answers.findJudge(..., false) 幂等检查
          │   ├─→ 已评分：直接回放已保存的结果（不重复保存、不推进、不再追问）
          │   └─→ 未评分：进入下一步
          ├─→ answers.recordJudge(..., false) 写回 is_follow_up=0 那一行
          ├─→ machine.advance() 推进面试状态机（JUDGING → JUDGE_DONE → ASKING）
          ├─→ snapshots.compareAndSet() CAS 乐观锁更新快照
          ├─→ followUps.decide(score) 选出追问策略
          └─→ followUpPublisher.publish(...) 把追问任务丢进队列（异步，不等结果）
                → 返回 WorkflowResult（分数、链名、状态、feedback 等）
```

#### 文件 1：`src/main/java/com/example/interview/application/InterviewWorkflowService.java`

**创建这个文件干什么：** 编排一次完整的评分流程。它扮演「编排者」角色，不关心具体的队列或 AI 细节。

**依赖什么：**
- `SessionSnapshotRepository`：加载和保存面试会话快照（用 CAS 乐观锁更新）。
- `InterviewAiFacade`：AI 评分门面。
- `AnswerRepository`：答案持久化，查询和保存评分结果。
- `InterviewSessionStateMachine`：面试状态机，推进会话状态。
- `FollowUpDecisionService`：追问决策服务（只做「分数 → 追问策略（链名/等级/目标）」的映射）。
- `FollowUpTaskPublisher`：追问任务发布器。judge 只负责「把任务发出去」，一旦在这里直接调生成服务，就又变回同步执行，异步化就白做了。

**被谁调用：** 由 `JudgeTaskPublisher.pullOnce()` / `consumeOnce()` 中的 `workflow.judge()` 调用。

```java
package com.example.interview.application;

import com.example.interview.ai.FollowUpDecisionService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 评分工作流服务。
 * <p>
 * 角色的核心定位：编排者（Orchestrator）。
 * 它把 AI 评分、持久化、状态推进和追问策略串成一个完整的用例。
 * 不包含任何具体的"怎么做"（如序列化、JSON 解析、CAS 重试），
 * 只负责"做什么"和"什么顺序做"。
 * </p>
 *
 * <p>
 * 为什么叫 Workflow 而不是 Service：
 * 传统的 Service 通常只处理单一领域逻辑（如 AnswerService.save()），
 * 而 Workflow 横跨多个领域（AI、持久化、状态机、追问），
 * 命名 Workflow 更准确地表达了它的编排本质。
 * </p>
 */
@Service
public class InterviewWorkflowService {

    // ================ 依赖注入 ================

    /**
     * 会话快照仓库。
     * 用于加载面试当前的快照（包含状态、版本号、CAS 令牌），
     * 在评分完成后推进状态并通过 CAS 乐观锁保存新快照。
     * 为什么需要快照而不是直接操作 session 实体：
     * 快照是面试状态机专用的、不可变的版本化数据，
     * 可以安全地在多线程/多实例环境下做 CAS 更新。
     */
    private final SessionSnapshotRepository snapshots;

    /**
     * AI 评分门面，封装了 prompt 组装、SingleFlight 合并、
     * Fencing Token 校验、JSON 解析等细节。
     */
    private final InterviewAiFacade ai;

    /**
     * 答案仓库，负责 interview_answer 表的查询和持久化。
     * 为什么不用 JdbcTemplate 直接操作：
     * AnswerRepository 封装了评分结果的写入逻辑
     * （包括幂等性检查、锁版本验证等），
     * 比直接执行 SQL 更安全。
     */
    private final AnswerRepository answers;

    /**
     * 面试会话状态机，负责状态转换的合法性校验。
     * 例如：从 ANSWERING 状态只能通过 JUDGE_DONE 事件 → JUDGED 状态，
     * 不能直接从 ANSWERING → FOLLOW_UP。
     */
    private final InterviewSessionStateMachine machine;

    /**
     * 追问决策服务（在下一章节「十二、追问模块」中创建的 ai 包服务）。
     * 只负责「分数 → 追问策略（链名/等级/目标）」的映射，不负责真正的追问生成。
     * 为什么评分工作流要用它：评分完成后必须立刻知道"该不该追问、走哪条追问链"，
     * 而这个判断只依赖分数，做成一个纯映射服务后既能复用、也便于单独测试。
     */
    private final FollowUpDecisionService followUps;

    /**
     * 追问任务发布器。
     * 为什么注入的是"发布器"而不是"追问生成服务"：judge 只需要把任务丢进队列就返回，
     * 一旦在这里直接调生成服务，评分线程就会当场跑完整条 LiteFlow 追问链，
     * 追问的 AI 耗时会把评分队列的吞吐拖死，异步化等于白做。
     */
    private final FollowUpTaskPublisher followUpPublisher;

    /**
     * 下一题预生成服务。评分失败降级推进时复用，让前端能立刻取下一题而不是一直等在轮询里。
     */
    private final NextQuestionPreGeneratorService preGenerator;

    // 日志对象：评分失败降级、追问失败都要留痕——异步线程没有调用方接住异常。
    private static final Logger log =
            LoggerFactory.getLogger(InterviewWorkflowService.class);

    /**
     * 构造函数注入所有依赖。
     */
    public InterviewWorkflowService(
            SessionSnapshotRepository snapshots,
            InterviewAiFacade ai,
            AnswerRepository answers,
            InterviewSessionStateMachine machine,
            FollowUpDecisionService followUps,
            FollowUpTaskPublisher followUpPublisher,
            NextQuestionPreGeneratorService preGenerator
    ) {
        this.snapshots = snapshots;
        this.ai = ai;
        this.answers = answers;
        this.machine = machine;
        this.followUps = followUps;
        this.followUpPublisher = followUpPublisher;
        this.preGenerator = preGenerator;
    }

    // ================ 核心编排方法 ================

    /**
     * 组织评分上下文并调用 AI，返回结构化的分数、优缺点和建议。
     * <p>
     * 这个方法是评分工作流的入口，被 JudgeTaskPublisher.pullOnce() /
     * consumeOnce() 中的 workflow.judge() 调用。
     * </p>
     *
     * <p>
     * 幂等性保证：在保存评分之前先查询是否已经评分过。
     * 如果已经评分（persisted != null），直接返回已保存的结果，
     * 不再重复调用 AI 或重复写入数据库。
     * 这防止了以下场景中的重复评分：
     * - 消费者处理任务后崩溃，但数据已保存；任务重回队列后被再次消费。
     * - 补偿服务重新投递了同一个任务。
     * </p>
     *
     * <p>
     * 追问作答（followUp == true）的处理：追问内容存在 interview_follow_up 表，
     * 不作为新题插入 interview_question，前端提交的 questionId 仍是原题 ID。
     * 因此这里必须走一个独立的早返回分支：只做「AI 评分 → 落库 → 返回结果」，
     * 绝不推进主线状态机、不 CAS 写回快照、不发布追问任务。
     * 原因：主线状态机（ANSWER_SUBMITTED / JUDGE_DONE）表达的是"原题的一次完整作答"，
     * 若追问作答也推一次，同一道题会被算成两道题，题序与进度整体错乱；
     * 而且"追问的追问"不生成，所以追问分支也不会再往追问队列投任务。
     * </p>
     *
     * <p>
     * 追问已异步化：主线走到最后只调 followUpPublisher.publish() 把任务丢进队列就返回，
     * 所以两个分支返回的 hasFollowUp 都固定是 false——judge 返回的这一刻追问必然还没生成。
     * </p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param answer     候选人提交的答案文本
     * @param followUp   是否追问作答：false=初次作答、true=追问作答
     * @return 工作流执行结果，包含分数、链信息、状态、feedback、过期标记等
     */
    public WorkflowResult judge(
            UUID sessionId,
            UUID questionId,
            String answer,
            // 从这里开始是修改的：新增 followUp 参数，用于区分初次作答与追问作答。
            boolean followUp
            // 修改结束
    ) {
        // 从这里开始是新增的：追问作答早返回分支。
        // 为什么必须在最前面判掉：一旦往下走到 machine.advance(ANSWER_SUBMITTED/JUDGE_DONE)，
        // 同一道题就会被推进两次，题序错乱且会被计入两道题。所以追问作答在这里就返回，
        // 完全不碰主线状态机、快照 CAS 和追问任务发布。
        if (followUp) {
            // 追问评分复用同一条 judge 链路，只是提示词里标明"这是追问回答"
            // （见 InterviewAiFacade.judge 的 followUp 分支）。
            // 追问作答的 AI 瞬时失败也给兜底分数继续，避免前端无限等待。
            JudgeResult score;
            try {
                score = ai.judge(sessionId, questionId, answer, true);
            } catch (Exception e) {
                log.error("judge follow-up failed, fallback score, sessionId={}", sessionId, e);
                score = fallbackScore();
            }
            // 幂等回放：追问评分任务可能被重复投递（消费者崩溃后补偿重投），
            // 若 is_follow_up=1 那一行已有分数，直接回放，避免重复调用 AI、重复写库。
            var persisted = answers.findJudge(sessionId, questionId, true);
            if (persisted != null) {
                return new WorkflowResult(
                        persisted.score(),                        // 已保存的分数
                        // 追问链名用已保存的分数反推，保证回放结果和首次评分一致。
                        followUps.chooseChain(persisted.score().intValue()),
                        // 只读当前快照状态用于返回，不做任何 CAS 写回。
                        snapshots.load(sessionId).state(),
                        persisted.feedback(),                     // 已保存的反馈
                        false,                                    // 不是过期结果
                        persisted.fencingToken(),                 // 已保存的令牌
                        false                                     // 追问异步生成，这里必然还没有
                );
            }
            // 追问评分写回 is_follow_up=1 那一行，绝不覆盖初次评分。
            answers.recordJudge(sessionId, questionId, score, true);
            // 追问作答到此结束：不推进状态机、不写快照、不发布追问任务，直接返回本次评分。
            return new WorkflowResult(
                    score.score(),
                    followUps.chooseChain((int) score.score()),
                    snapshots.load(sessionId).state(),
                    score.feedback(),
                    score.stale(),
                    score.fencingToken(),
                    false
            );
        }
        // 新增结束

        // ---- 第一步：加载当前会话快照 ----
        // 快照中包含了会话的当前状态（如 ANSWERING）、版本号、
        // 以及 CAS 乐观锁所需的令牌。
        // 为什么在调用 AI 之前加载快照：
        // 1. 需要快照中的当前状态来校验是否可以执行评分；
        // 2. 如果快照加载失败（会话已被删除），可以直接报错，
        //    避免浪费一次 AI 调用。
        var old = snapshots.load(sessionId);

        // ---- 第二步：调用 AI 评分 ----
        // 这里委托给 InterviewAiFacade.judge()，它内部处理了：
        // - SingleFlight 请求合并
        // - Fencing Token 过期检测
        // - 评分结果的格式自纠错（缺 score / feedback 为空时反馈给 AI 重试，最多 2 次）
        // - JSON 解析和校验
        // - 返回 JudgeResult 结构化结果
        // 末位传 false：明确告诉 AI 这是"初次作答"，提示词里不加追问作答的区分说明。
        // 评分 AI 一次调用可能瞬时失败。失败绝不能把状态永远停在 JUDGING
        //（否则前端轮询下一题会无限转圈），所以这里捕获后给一个兜底评分，降级继续推进面试。
        JudgeResult score;
        boolean degraded = false;
        try {
            score = ai.judge(
                    sessionId,
                    questionId,
                    answer,
                    // 从这里开始是修改的：初次作答路径显式传 false。
                    false
                    // 修改结束
            );
        } catch (Exception e) {
            log.error("judge failed, degraded to continue, sessionId={}", sessionId, e);
            score = fallbackScore();
            degraded = true;
        }

        // ---- 第三步：幂等性检查 ----
        // 查询该会话+该题目是否已经评分过。
        // findJudge 方法在 AnswerRepository 中定义（其他章节），
        // 如果返回非空，说明之前已经完成了评分。
        // 末位传 false：只看 is_follow_up=0 那一行，避免把追问评分误当成初次评分回放。
        var persisted = answers.findJudge(
                sessionId,
                questionId,
                // 从这里开始是修改的：初次作答路径查询 is_follow_up=0 那一行。
                false
                // 修改结束
        );

        if (persisted != null) {
            // ---- 已经评分过：直接回放已有结果 ----
            // 什么情况下会走到这里：
            // 1. 任务被重复消费（消费者在 recordJudge 之后、remove PROCESSING 之前崩溃）；
            // 2. 补偿服务重新投递已评分的任务；
            // 3. 其他并发消费者抢先完成了评分。
            //
            // 为什么不再次调用 AI：
            // 1. AI 调用有成本（按 token 计费），重复调用浪费钱；
            // 2. 同一题同一答案的多次 AI 评分可能不同（AI 非确定性输出），
            //    导致相同答案出现不同分数，引起候选人投诉；
            // 3. 已经保存的结果是权威的。

            // 计算追问链：用已保存的分数反推，保证回放结果和首次评分返回的链名一致。
            // 这里调的是 chooseChain（只映射链名），而不是 decide（会给出完整追问计划）——
            // 这一条没有真正生成过追问，所以无需再调用追问生成服务，也不需要完整计划。

            // 返回「已回放」的结果。
            // 末位字段已由 replayed 改为 hasFollowUp：追问已异步，judge 返回时追问必然还没生成，
            // 所以这里固定给 false（"是否重放"不再由这个字段表达）。
            return new WorkflowResult(
                    persisted.score(),           // 已保存的分数
                    followUps.chooseChain(
                            persisted.score().intValue()
                    ),                           // 用同一份分数反推追问链名
                    snapshots.load(sessionId)    // 当前快照状态
                            .state(),
                    persisted.feedback(),        // 已保存的反馈
                    false,                       // 不是过期结果
                    persisted.fencingToken(),    // 已保存的令牌
                    false                        // 追问异步生成，这里必然还没有
            );
        }

        // ---- 第四步：保存评分结果 ----
        // recordJudge 方法在 AnswerRepository 中定义，
        // 负责将 score、feedback、fencingToken 写入 interview_answer 表。
        // 注意：这里是在 AI 调用完成且通过幂等性检查后才写入，
        // 确保不会保存无意义的空评分。
        answers.recordJudge(
                sessionId,
                questionId,
                score,
                // 从这里开始是修改的：初次作答写回 is_follow_up=0 那一行，明确不覆盖追问评分。
                false
                // 修改结束
        );

        // ---- 第五步：推进状态机 ----
        // 发送 JUDGE_DONE 事件，驱动状态机从当前状态转变为下一个状态。
        // 典型转换：ANSWERING → JUDGED（单题评分完成）。
        // advance 方法返回一个 Movement 对象，包含：
        // - changed()：状态是否真的发生了变更
        //   （如果当前状态不允许 JUDGE_DONE 事件，changed() 返回 false）
        // - snapshot()：状态变更后的新快照
        var moved = machine.advance(
                old,
                InterviewEvent.JUDGE_DONE,
                UUID.randomUUID().toString()
        );

        // ---- 第六步：CAS 乐观锁更新快照 ----
        // compareAndSet(old, newSnapshot)：
        // 仅在数据库中快照的版本号与 old 的版本号一致时更新。
        // 为什么需要 CAS：多线程/多实例环境下，可能会有
        // 多个消费者同时处理同一个会话的不同题目。
        // CAS 保证只有一个线程能成功更新快照，其他线程重试。
        if (moved.changed()) {
            snapshots.compareAndSet(
                    old,
                    moved.snapshot()
            );
        }

        // 从这里开始是新增的：评分降级（AI 失败）时不再生成追问，直接把 ASKING→QUESTIONING
        // 推进到位并预生成下一题，让前端能立刻取下一题而不是一直等在轮询里。
        if (degraded) {
            var askDone = machine.advance(
                    moved.snapshot(),
                    InterviewEvent.ASK_COMPLETED,
                    UUID.randomUUID().toString()
            );
            if (askDone.changed()) {
                snapshots.compareAndSet(
                        moved.snapshot(),
                        askDone.snapshot()
                );
            }
            preGenerator.preGenerate(
                    sessionId,
                    answers.findSequenceNo(questionId) + 1
            );
            return new WorkflowResult(
                    score.score(),
                    "followUpBasicChain",
                    askDone.changed()
                            ? askDone.snapshot().state()
                            : moved.snapshot().state(),
                    score.feedback(),
                    score.stale(),
                    score.fencingToken(),
                    false
            );
        }
        // 新增结束

        // ---- 第七步：决策追问策略 ----
        // 评分已落库、状态已推进到 ASKING，接下来按分数选出追问策略。
        // decide 返回完整的追问计划（链名 + 等级 + 追问目标），
        // 其中链名既用于返回给调用方，也用于驱动下游的 LiteFlow 追问链。
        var plan = followUps.decide((int) score.score());

        // 从这里开始是修改的：原来这里是同步调 followUpGeneration.generate(new FollowUpTask(...))，
        // 会当场跑完整条 LiteFlow 追问链才返回，评分队列吞吐被追问 AI 拖死。
        // 现在只把任务丢进 Redis 队列就返回，追问由独立消费者生成并落库。
        // 任务只带三样东西：哪个会话、哪道题、多少分（走哪条链由消费端的 decide 再算一次，
        // 任务体保持最小）。顺序不能反：先发任务的话，消费者可能比评分落库更快到达，
        // 链上就会读到"还没有分数"的上下文。
        followUpPublisher.publish(
                sessionId,
                questionId,
                score.score()
        );
        // 修改结束

        // ---- 第八步：返回评分结果 ----
        // 返回全新的评分结果。
        // 注意 state 取的是 JUDGE_DONE 之后、追问生成之前的快照状态（ASKING），
        // 它描述的是"评分这一步完成时的状态"，继续往下走由追问模块负责。
        // 末位 hasFollowUp=false：追问已异步，judge 返回的这一刻必然还没生成。
        return new WorkflowResult(
                score.score(),                   // AI 给出的分数
                plan.chain(),                    // 追问策略链名（真实值，不再是 false 占位）
                moved.snapshot().state(),         // 推进后的新状态
                score.feedback(),                 // AI 生成的反馈
                score.stale(),                    // 是否过期
                score.fencingToken(),             // Fencing 令牌
                false                             // 追问异步生成，这里必然还没有
        );
    }

    // ================ 内部类型 ================

    /**
     * 评分工作流的执行结果。
     * 这是一个嵌套 record，作为 InterviewWorkflowService 的内部类型，
     * 因为它的使用范围仅限于工作流调用方和消费者之间，
     * 不需要暴露给外部模块。
     *
     * @param score        AI 给出的分数
     * @param chain        追问策略链名（由 FollowUpDecisionService 根据分数选出：
     *                     &lt;60 基础追问、60-79 常规追问、&gt;=80 深度追问）
     * @param state        评分完成后的面试状态
     * @param feedback     AI 生成的评价反馈
     * @param stale        true 表示此结果已过期（不应该被后续流程使用）
     * @param fencingToken 围栏令牌，用于拒绝过期结果
     * @param hasFollowUp  是否已经生成了追问。末位字段由原来的 replayed 改为 hasFollowUp：
     *                     追问改成异步之后，judge 返回的这一刻追问必然还没生成，
     *                     所以两个分支都固定传 false（"是否重放"不再由这个字段表达）
     */
    public record WorkflowResult(
            double score,
            // 追问策略链名：追问模块创建前这里只是 boolean 占位，现在已经是真实的链名。
            String chain,
            com.example.interview.domain.InterviewState state,
            String feedback,
            boolean stale,
            long fencingToken,
            boolean hasFollowUp
    ) {
    }

    // 从这里开始是新增的：评分 AI 瞬时失败时的兜底评分。
    // 用稳定的及格线下分值（50），反馈写清楚"已自动继续"，让用户知道这题没拿到点评但面试没断。
    private JudgeResult fallbackScore() {
        return new JudgeResult(
                50,
                "评分服务暂时繁忙，本条回答已自动进入下一题。",
                0L,
                false
        );
    }
    // 新增结束
}
```

---

> **实际项目中**，评分完成后还要触发追问：`judge` 里用 `followUps.decide(score)` 按分数选出追问策略链（`<60` 基础追问、`60-79` 常规追问、`>=80` 深度追问），但**不在这里生成追问**——它只调 `followUpPublisher.publish(sessionId, questionId, score)` 把一条任务丢进 Redis 队列就返回，真正跑 LiteFlow 追问链的是下一章节的独立消费者循环。追问决策与生成的具体逻辑将在下一章节「十二、追问模块」中详细展开。本章节的重点是理解：评分结果需要持久化（`answers.recordJudge`），状态需要推进（`machine.advance`），重复任务需要回放已有结果（`persisted != null` 分支），追问作答需要走早返回分支（`followUp == true`，不推进主线）——这几个核心关注点共同构成了异步评分工作流的可靠性基础。

## 十二、追问模块

### 模块主要目的

根据评分结果动态决定是否追问，并围绕薄弱点生成下一道问题，让面试内容具有针对性。追问模块是整个 AI 面试系统中实现"自适应"的关键环节——它让面试官（AI）不是机械地按固定题库出题，而是根据候选人上一题的回答质量，实时调整追问方向和深度。

追问模块**已经异步化**：评分完成后，`InterviewWorkflowService.judge` 只把一条追问任务丢进 Redis 队列就返回，真正跑 LiteFlow 链的是独立的消费者循环（`FollowUpTaskConsumerLoop` → `FollowUpTaskPublisher.consumeOnce` → `FollowUpGenerationService.generate`）。追问内容存在 `interview_follow_up` 表，**不作为新题插入 `interview_question`**；候选人回答追问时复用原题的 `questionId` 提交，用 `interview_answer.is_follow_up` 区分"初次作答 / 追问作答"，因此追问不会打乱主线的题序与进度。

### 模块整体流程

```text
  【评分侧 · 同步】InterviewWorkflowService.judge
    评分落库 → 推进 JUDGE_DONE → decide 选出追问链名
    → followUpPublisher.publish(sessionId, questionId, score)   ← 只投递，立即返回
       │
       ▼
  【队列】Redis List 三 key：QUEUE / PROCESSING / DEAD
       │
       ▼
  【消费侧 · 异步】FollowUpTaskConsumerLoop 定时（fixedDelay = 200ms）触发
    → consumeOnce 用 move 原子出队 → FollowUpGenerationService.generate(task)
       │
       ▼
  【规则链】decide → Singleflight 保护下 execute2Resp(chainName, ...)
    loadQuestionContext → 分数段生成节点 → persistFollowUp（幂等落库）
       │
       ▼
  completeAndPreGenerate：推进 ASK_COMPLETED → 预生成下一题

  【作答侧】候选人回答追问：前端复用原题 questionId 提交（is_follow_up = 1）
    → judge 走追问早返回分支：只评分落库，不推进主线状态机
    → 前端用 GET /api/interviews/{sessionId}/follow-up?questionId= 取回追问内容
```

---

### 追问 1：根据分数选择追问策略

**本小节目的：** 将评分结果转换成明确的追问决策——例如是深入追问技术细节、要求澄清回答、还是直接进入下一题。本小节只做"决策"，不执行 LiteFlow 节点。

**本小节流程：** 接收评分 → 匹配分数区间（< 60 / 60~79 / >= 80）→ 选择策略链名称 → 构造完整追问计划 → 返回给调用方。

#### 文件 1：`src/main/java/com/example/interview/ai/FollowUpDecisionService.java`

**创建这个文件干什么：** 这个服务是整个追问模块的"入口决策器"。它根据面试评分输出一个 `FollowUpPlan`（追问计划），包含要执行的 LiteFlow 规则链名、追问等级和追问目标。这个计划会被上游调用方（如 Controller 或编排服务）拿去驱动 LiteFlow 规则链执行。它不依赖任何其他业务服务，只根据分数做纯逻辑决策，因此可以独立测试。

```java
package com.example.interview.ai;

import org.springframework.stereotype.Service;

/**
 * 追问决策服务：根据面试评分选择追问策略。
 *
 * <p>职责单一：只做"分数→策略"的映射，不负责执行 LiteFlow 节点。
 * 上游调用方拿到 FollowUpPlan 后，再驱动规则链执行具体的追问流程。</p>
 */
@Service
public class FollowUpDecisionService {

    /**
     * 根据分数选择 LiteFlow 规则链的名称。
     *
     * <p>为什么需要这个方法？上游可能只需要链名（例如直接传给 LiteFlow 的
     * FlowExecutor），而不需要完整的 FollowUpPlan 对象。这个方法提供轻量选择。</p>
     *
     * @param score 本题的评分（0-100），来自评分模块的输出
     * @return LiteFlow 规则链名称，对应 interview.xml 中定义的 chain name
     */
    public String chooseChain(int score) {
        // 分数低于 60：基础薄弱，需要先补概念和核心原理
        if (score < 60) {
            return "followUpBasicChain";
        }
        // 分数 >= 80：表现优秀，可以深入考察边界条件和架构决策
        if (score >= 80) {
            return "followUpDeepChain";
        }
        // 60 <= score < 80：中等水平，补充实现细节和排障过程
        return "followUpNormalChain";
    }

    /**
     * 根据评分生成完整的追问计划，供后续规则链执行。
     *
     * <p>为什么比 chooseChain 更推荐？因为它同时返回了链名、等级和追问目标，
     * 调用方（如 ReportController 或编排服务）可以据此记录到数据库或传给前端。</p>
     *
     * @param score 本题的评分（0-100）
     * @return 包含链名、等级、追问目标的完整计划
     */
    public FollowUpPlan decide(int score) {
        // 分数低于 60：候选人概念不清或回答严重偏离，需要从基础开始追问
        if (score < 60) {
            return new FollowUpPlan(
                    "followUpBasicChain",   // 对应 LiteFlow 的基础追问链
                    "基础追问",              // 追问等级标签，用于前端展示或数据库记录
                    "补齐概念定义、核心原理和最小可运行例子"  // 追问的具体目标
            );
        }
        // 分数 >= 80：候选人回答很好，可以深入挖掘其知识深度的边界
        if (score >= 80) {
            return new FollowUpPlan(
                    "followUpDeepChain",    // 对应 LiteFlow 的深度追问链
                    "深度追问",
                    "验证边界条件、性能取舍、故障恢复与架构决策"
            );
        }
        // 60 <= score < 80：回答基本正确但不够深入，需要补充细节
        return new FollowUpPlan(
                "followUpNormalChain",      // 对应 LiteFlow 的常规追问链
                "常规追问",
                "补充实现细节、排障过程和验证方式"
        );
    }

    /**
     * 追问计划的不可变值对象。
     * <p>使用 Java 16+ record 自动生成构造器、getter、equals、hashCode。
     * 这三个字段共同定义了"追问什么"和"怎么追问"。</p>
     *
     * @param chain     LiteFlow 规则链名称，决定本轮追问执行哪套策略流程
     * @param level     追问深度或策略等级（基础追问/常规追问/深度追问），用于数据库记录和前端展示
     * @param objective 追问要补充确认的具体目标说明，AI 生成追问内容时用它作为指引
     */
    public record FollowUpPlan(
            String chain,
            String level,
            String objective
    ) {
    }
}
```

---

### 追问 2：创建追问表、实体和执行上下文

**本小节目的：** 建立追问记录的持久化存储，同时定义 LiteFlow 各节点之间共享的可变上下文。持久化是为了让报告模块能追溯每道题的追问历史；共享上下文则是为了在规则链的多个节点之间传递数据。

**本小节流程：** 设计追问表结构 → 创建 MyBatis-Plus 实体 → 创建 Mapper 接口 → 定义规则链执行上下文 → 组装上下文数据 → 新增追问查询（`FollowUpView` + 仓储 `find`）。

#### 文件 1：`interview_follow_up` 表（SQL）

**创建这个表干什么：** 这张表记录每道题对应的追问内容。之所以需要独立的追问表而不是把追问塞进题目表，是因为一次面试中可能对某些题目追问多轮，而其他题目不问，独立表更能灵活支持一对多关系。特别注意：追问记录用 `session_id + question_id` 关联到**原题**（追问不作为新题插进 `interview_question`），并用唯一键 `uk_follow_up_session_question` 保证同一道题只产生一条追问。

```sql
-- interview_follow_up：每道题每次追问对应一条记录。
-- 联合唯一索引 uk_follow_up_session_question 保证同一场面试的同一道题不会被重复追问。
CREATE TABLE IF NOT EXISTS interview_follow_up (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,           -- 自增主键，无业务含义
    session_id VARCHAR(36) NOT NULL,                -- 面试会话 UUID，关联 interview_session
    question_id VARCHAR(36) NOT NULL,               -- 原题 UUID，关联 interview_question（追问挂在这道原题上）
    score DECIMAL(5, 2),                            -- 触发本次追问时的评分（冗余存储，方便报告模块直接查询，无需联表）
    level VARCHAR(32) NOT NULL,                     -- 追问等级：基础追问 / 常规追问 / 深度追问
    content LONGTEXT NOT NULL,                      -- AI 生成的追问正文（Markdown 格式）
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,  -- 记录生成时间，用于排序
    CONSTRAINT fk_follow_up_session
        FOREIGN KEY (session_id) REFERENCES interview_session (id),
    CONSTRAINT fk_follow_up_question
        FOREIGN KEY (question_id) REFERENCES interview_question (id),
    -- 同一场面试的同一道题只允许一条追问。仓储层是"先查后插"，并发下仍可能插进两条，
    -- 这个唯一键是最后一道防线。
    UNIQUE KEY uk_follow_up_session_question (session_id, question_id),
    INDEX idx_follow_up_session (session_id),
    INDEX idx_follow_up_question (question_id)
) ENGINE = InnoDB;
```

**为什么表里没有 `chain` 列：** 早期设计曾把命中的 LiteFlow 链名（followUpBasicChain 等）落进 `chain` 列，但追问等级（`level`）已经能表达"基础 / 常规 / 深度"，链名只是等级在 LiteFlow 里的对应物，重复存一份没有价值。最终表结构只有 `id / session_id / question_id / score / level / content / created_at`，链名只留在 LiteFlow 上下文里（`FollowUpExecution.chain`）供日志和排查使用。实体类里若还留着 `chain` 字段，必须标 `@TableField(exist = false)`，否则 MyBatis-Plus 会把它当成一列拼进 SQL 直接报 `Unknown column 'chain'`（详见下面的实体文件）。

#### 文件 2：`src/main/java/com/example/interview/infrastructure/mysql/FollowUpEntity.java`

**创建这个文件干什么：** 这是 `interview_follow_up` 表对应的 MyBatis-Plus 实体类。实体类的每个字段对应表的一列，通过 `@TableName` 注解建立映射。它被 `FollowUpMapper` 使用，也可能会被报告模块中的服务直接查询。注意 `chain` 字段在表里**并不存在**（表只有 `id / session_id / question_id / score / level / content / created_at`），因此必须标 `@TableField(exist = false)`，否则 MyBatis-Plus 会把它当成一列拼进 SQL 直接报错。

```java
package com.example.interview.infrastructure.mysql;

import java.time.LocalDateTime;

// 从这里开始是修改的：补 TableField 注解的 import。
// 为什么需要它：下面的 chain 字段在表里并不存在，必须显式告诉 MyBatis-Plus"这个字段不映射任何列"。
import com.baomidou.mybatisplus.annotation.TableField;
// 修改结束
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("interview_follow_up")
public class FollowUpEntity {
    private Long id;

    /** 面试会话 UUID，与 interview_session 表的 session_id 对应。 */
    private String sessionId;

    /** 题目 UUID，与 interview_question 表的 question_id 对应。 */
    private String questionId;

    /** 触发本次追问时的评分（0-100）。冗余字段，避免联表查询。 */
    private Integer score;

    /** 追问等级：基础追问 / 常规追问 / 深度追问。用于前端区分展示样式。 */
    private String level;

    // 从这里开始是修改的：链名只留在 LiteFlow 上下文里，不落库。
    // interview_follow_up 表里没有 chain 列，而 MyBatis-Plus 默认会把实体的每个字段
    // 都当成一列拼进 SQL。不标 exist = false 的话，selectOne / selectList 会生成
    // "SELECT ..., chain, ... FROM interview_follow_up"，执行时直接报 Unknown column 'chain'。
    @TableField(exist = false)
    private String chain;
    // 修改结束

    /** AI 生成的追问正文（Markdown 格式），前端直接渲染。 */
    private String content;

    /** 记录创建时间，由数据库自动写入，用于排序和展示。 */
    private LocalDateTime createdAt;

}
```

> **为什么这个类用 Lombok `@Data` 而别的实体是手写 getter/setter：** 本项目里两种风格并存。`FollowUpEntity` 用 `@Data` 自动生成 getter / setter / equals / hashCode，字段少、没有自定义逻辑，写出来最省事；只要保证 Lombok 依赖在编译期生效即可。阅读时把 `@Data` 展开成各字段的读写方法，语义和其他实体完全一样。

#### 文件 3：`src/main/java/com/example/interview/infrastructure/mysql/FollowUpMapper.java`

**创建这个文件干什么：** MyBatis-Plus 的 Mapper 接口，继承 `BaseMapper<FollowUpEntity>` 后自动获得 CRUD 方法，无需编写任何 SQL 实现。被所有需要读写追问记录的服务调用。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * interview_follow_up 表的 MyBatis-Plus Mapper。
 *
 * <p>继承 BaseMapper 后自动获得 insert / deleteById / selectById / updateById
 * 等常用方法。如果后续需要复杂查询（如按 sessionId 查询所有追问），
 * 直接使用 QueryWrapper 即可，无需在此接口中额外定义。</p>
 */
@Mapper
public interface FollowUpMapper extends BaseMapper<FollowUpEntity> {
}
```

#### 文件 4：`src/main/java/com/example/interview/infrastructure/mysql/FollowUpExecution.java`

**创建这个文件干什么：** 这是 LiteFlow 规则链节点之间共享的可变上下文对象。LiteFlow 在执行一条链时，会把一个上下文对象依次传给链上的每个节点组件。前面的节点（如"加载上下文节点"）向这个对象写入数据，后面的节点（如"生成追问内容节点"）从中读取数据。之所以不把字段拆成多个参数，是为了让规则链的节点方法签名保持简单统一——每个节点只接收一个上下文参数。注意它和 `FollowUpEntity` 一样放在 `infrastructure.mysql` 包下：它只承载"链上流动的数据"，不承担业务逻辑。

```java
package com.example.interview.infrastructure.mysql;

import java.util.UUID;

public class FollowUpExecution {

    /** 面试会话 ID，贯穿题目、答案、评分和报告的全局标识。规则链各节点用它来关联数据。 */
    private UUID sessionId;

    /** 当前正在追问的题目 ID。提交答案和评分时用它定位题目，追问节点据此判断补问方向。 */
    private UUID questionId;

    /** 候选人刚刚提交的答案文本。追问节点根据答案中的薄弱点决定从哪个方向追问。 */
    private String answer;

    /** 原题正文。由链首公共节点 loadQuestionContext 从题目表加载，供 AI 生成追问时围绕原题发问。 */
    private String questionTitle;

    /** 本次追问命中的规则链名称（followUpBasicChain 等）。由入口方法从 FollowUpPlan 填充，供日志和排查使用（不入库）。 */
    private String chain;

    /** 本题的评分（0-100）。策略选择节点根据它决定选择哪条追问链。 */
    private int score;

    /** 追问深度或策略等级（基础追问 / 常规追问 / 深度追问）。用于 AI 提示词中的语气和深度控制。 */
    private String level;

    /** 追问的具体目标描述。AI 生成追问内容时用这个字段作为指引，保证追问不偏离方向。 */
    private String objective;

    /** 题目原文、答案、追问内容或报告正文的通用容器。链上最后一个节点将其持久化到数据库。 */
    private String content;

    /**
     * 无参构造器：供「上下文构建节点」使用。
     * 节点从外部请求中拿到数据后，通过各字段的 setter 依次填充。
     */
    public FollowUpExecution() {
    }

    /**
     * 便捷构造器：直接传入本次追问最核心的四个参数。
     * 适用于调用方已经确定会话、题目、评分和追问目标的场景
     * （例如 FollowUpGenerationService.generateByFlow），
     * 无需先 new 再逐个 set。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param score      本题评分（0-100）
     * @param objective  追问目标说明
     */
    public FollowUpExecution(
            UUID sessionId,
            UUID questionId,
            int score,
            String objective
    ) {
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.score = score;
        this.objective = objective;
    }

    public UUID getSessionId() { return sessionId; }

    public void setSessionId(UUID sessionId) { this.sessionId = sessionId; }

    public UUID getQuestionId() { return questionId; }

    public void setQuestionId(UUID questionId) { this.questionId = questionId; }

    public String getAnswer() { return answer; }

    public void setAnswer(String answer) { this.answer = answer; }

    public String getQuestionTitle() { return questionTitle; }

    public void setQuestionTitle(String questionTitle) { this.questionTitle = questionTitle; }

    public String getChain() { return chain; }

    public void setChain(String chain) { this.chain = chain; }

    public int getScore() { return score; }

    public void setScore(int score) { this.score = score; }

    public String getLevel() { return level; }

    public void setLevel(String level) { this.level = level; }

    public String getObjective() { return objective; }

    public void setObjective(String objective) { this.objective = objective; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }
}
```

> **`chain` 字段为什么不落库：** 它是"这次追问走的是哪条链"的调试信息，只写进日志；`persistFollowUp` 节点写库时用的是 `level`（对应 `interview_follow_up.level` 列）。真正的等级语义由 `level` 表达，链名只是等级在 LiteFlow 里的映射，重复存没有价值。

#### 文件 5：`src/main/java/com/example/interview/application/FollowUpView.java`

**创建这个文件干什么：** 追问查询的视图对象，回答"候选人答完原题之后，这道题有没有追问要答"。追问内容存在 `interview_follow_up` 表、不作为新题插进 `interview_question`，所以前端在提交完原题答案、拿到下一题之前必须先问一次"刚才这道题有没有追问"。只暴露前端渲染需要的三个字段（questionId / level / content），不把整行实体丢出去。

```java
package com.example.interview.application;

import java.util.UUID;

// 从这里开始是新增的：追问视图对象，回答"候选人答完原题之后，这道题有没有追问要答"。
// 为什么需要它：追问内容存在 interview_follow_up 表，不作为新题插入 interview_question，
// 所以前端在提交完原题答案、拿到下一题之前，必须先问一次"刚才这道题有没有追问"。
// 没有这个视图，前端就无从知道该不该用 followUp=true 再提交一次，is_follow_up 链路
// 在后端虽然通了，界面上却永远走不到。
// 只暴露前端渲染需要的三个字段，不把整行实体丢出去（level 用来区分展示样式）。
public record FollowUpView(
        // 追问挂在哪一道原题上。前端拿它当 questionId 提交追问答案，与原题保持一致。
        UUID questionId,
        // 追问等级：基础追问 / 常规追问 / 深度追问，前端据此调整标题文案。
        String level,
        // AI 生成的追问正文（Markdown），前端直接渲染成题目。
        String content
) {
}
```

#### 文件 6：`src/main/java/com/example/interview/application/FollowUpRepository.java`（追问仓储接口，完全体）

**这个文件干什么：** 它是 `application` 层定义的追问仓储接口，只声明「追问要提供哪些操作」，具体实现交给 `infrastructure` 层，遵循依赖倒置原则。本次在原有 `insertIfAbsent` 的基础上新增了一个 `find` 查询方法：追问是异步生成的，前端提交完原题答案、等到状态回到 `QUESTIONING` 之后，必须先确认「刚才这道题到底有没有追问」，才能决定是继续答追问（提交时带 `followUp=true`）还是直接取下一题。没有这个查询，追问在界面上永远走不到。返回值用视图对象 `FollowUpView` 而不是实体，避免 `application` 层把 `infrastructure` 的实体类型暴露出去。

```java
package com.example.interview.application;

import java.util.UUID;

/**
 * 追问表仓储接口。
 *
 * 为什么定义在 application 层而不是 infrastructure 层：
 * 这是业务层的接口定义，具体实现在 infrastructure 层。
 * 遵循依赖倒置原则。
 */
public interface FollowUpRepository {

    /**
     * 如果不存在则插入追问记录。
     *
     * 幂等性保证：同一个 sessionId + questionId 只插入一次。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 题目 ID
     * @param content    追问内容
     * @param level      追问等级（基础追问 / 常规追问 / 深度追问），
     *                   对应 interview_follow_up 表的 level 列（NOT NULL），
     *                   必须传值，否则插入会被数据库的非空约束拒绝
     */
    void insertIfAbsent(
            UUID sessionId,
            UUID questionId,
            String content,
            String level
    );

    // 从这里开始是新增的：按会话 + 原题查一条追问。
    /**
     * 查询某一道原题上已经生成的追问。
     *
     * <p>为什么需要这个方法：追问是异步生成的，前端提交完原题答案、等到状态回到
     * QUESTIONING 之后，必须先确认"刚才这道题到底有没有追问"，才能决定是继续答追问
     * （提交时带 followUp=true）还是直接取下一题。没有这个查询，追问在界面上永远走不到。</p>
     *
     * <p>为什么返回视图对象而不是实体：实体属于 infrastructure 层，application 层
     * 的接口不该把 infrastructure 类型暴露出去；而且前端只需要 questionId / level / content
     * 三个字段，返回整行实体等于多传了 id、score、createdAt 等无用数据。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 原题 ID（追问记录就是用 session_id + question_id 关联原题的）
     * @return 追问视图；这道题没有追问时返回 null
     */
    FollowUpView find(UUID sessionId, UUID questionId);
    // 新增结束
}
```

---

#### 文件 7：`src/main/java/com/example/interview/application/FollowUpRepositoryImpl.java`（追问仓储实现，完全体）

**这个文件干什么：** 它是 `FollowUpRepository` 的实现，负责真正落到 `interview_follow_up` 表。`insertIfAbsent` 用「先查后插」实现幂等（同一道题只留一条追问），并发漏过先查时由唯一键 `uk_follow_up_session_question` 兜底；新增的 `find` 按 `session_id + question_id` 查出追问并转成 `FollowUpView` 返回给前端。必须显式 `implements FollowUpRepository`，否则方法上的 `@Override` 找不到被覆盖的目标、容器里也没有 `FollowUpRepository` 类型的 Bean，按接口注入的 `PersistFollowUp` 会启动失败。

**两个关键设计：** ① 用 `selectOne` 而不是 `selectList`——表上有唯一键 `uk_follow_up_session_question (session_id, question_id)`，同一道题的追问最多一条，语义更准，也顺手把「真出现两条」这种异常数据暴露成异常。② 查不到返回 `null` 而不是抛异常——没有追问是完全正常的业务分支（分数段决定不追问、或追问任务还在队列里没生成完），调用方要的就是「没有追问」这个信息。

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.infrastructure.mysql.FollowUpEntity;
import com.example.interview.infrastructure.mysql.FollowUpMapper;
import org.springframework.stereotype.Repository;

import java.util.UUID;

// 追问表仓储的实现类，实现 application 层定义的 FollowUpRepository 接口。
// 必须显式 implements FollowUpRepository，否则：
// 1. 方法上的 @Override 找不到被覆盖的目标，编译报错；
// 2. Spring 容器里没有 FollowUpRepository 类型的 Bean，
//    PersistFollowUp 等按接口注入的组件无法启动。
@Repository 
public class FollowUpRepositoryImpl implements FollowUpRepository {
    private final FollowUpMapper followUps;

    public FollowUpRepositoryImpl(FollowUpMapper followUps) {
        this.followUps = followUps;
    }

    @Override 
    public void insertIfAbsent(UUID sessionId, UUID questionId, String content, String level)
    {
        FollowUpEntity existing = followUps.selectOne(
                Wrappers.<FollowUpEntity>lambdaQuery()
                        .eq(FollowUpEntity::getSessionId, sessionId.toString())
                        .eq(FollowUpEntity::getQuestionId, questionId.toString())
        );
         if (existing != null) {
            return;   // 已经追问过这道题了，直接跳过，不覆盖原记录
        }
        FollowUpEntity entity = new FollowUpEntity();
        entity.setSessionId(sessionId.toString());
        entity.setQuestionId(questionId.toString());
        entity.setContent(content);
        // 只写表里真实存在的列：interview_follow_up 没有 chain 列，
        // 追问等级存在 level 列（NOT NULL，必须赋值）；
        // LiteFlow 链名只留在 FollowUpExecution 上下文里，不入库。
        entity.setLevel(level);
        // 整个方法里唯一的写库动作；即便并发插入漏过上面的先查，也会被唯一索引兜住。
        followUps.insert(entity);
    }

    // 从这里开始是新增的：查询追问，供前端在"答完原题、取下一题之前"确认有没有追问要答。
    /**
     * 按 session_id + question_id 查一条追问，转成前端视图。
     *
     * <p>为什么用 selectOne 而不是 selectList：表上有唯一键
     * uk_follow_up_session_question (session_id, question_id)，同一道题的追问最多一条，
     * 用 selectOne 语义更准，也顺手把"真出现两条"这种异常数据暴露成异常而不是悄悄取第一条。</p>
     *
     * <p>为什么查不到返回 null 而不是抛异常：没有追问是完全正常的业务分支
     * （分数段决定不追问、或追问任务还在队列里没生成完），调用方要的就是
     * "没有追问"这个信息，不该当成错误。</p>
     *
     * @param sessionId  面试会话 ID
     * @param questionId 原题 ID
     * @return 追问视图；查不到时返回 null
     */
    @Override
    public FollowUpView find(UUID sessionId, UUID questionId)
    {
        FollowUpEntity entity = followUps.selectOne(
                Wrappers.<FollowUpEntity>lambdaQuery()
                        .eq(FollowUpEntity::getSessionId, sessionId.toString())
                        .eq(FollowUpEntity::getQuestionId, questionId.toString())
        );
        if (entity == null) {
            return null;
        }
        // 只挑前端要的三个字段：题号前端已经有了，这里回传是为了让调用方
        // 能直接把视图里的 questionId 当作提交追问答案的 questionId 用。
        return new FollowUpView(
                questionId,
                entity.getLevel(),
                entity.getContent()
        );
    }
    // 新增结束
}
```

---
### 追问 3：创建 LiteFlow 规则链

**本小节目的：** 用可配置的规则链表达不同追问策略。LiteFlow 的 Chain + EL 表达式可以把"什么条件下执行什么节点"的编排逻辑从 Java 代码抽离到 XML 配置中，这样策略变化时只需要改 XML 而不用修改和重新编译业务代码。规则文件的位置由 `application.yml` 的 `liteflow.rule-source: flow/*.xml` 指定——不配这一段，LiteFlow 只会去自己的默认位置找规则，结果就是"一条链都没加载"，XML 写得再对也白搭（追问链会直接抛 chain not found）。

**本小节流程：** 编写规则 XML 文件（声明 5 个节点并定义三条"多节点串行"策略链）→ 编写 `FollowUpLiteFlowNodes`（2 个公共节点 + 3 个分数段节点）及其 AI 生成方法 → 编写 `FollowUpGenerationService` 完全体（决策 → 执行多节点链 → 校验结果 → 并发防重 → 推进状态 → 预生成）→ 补上异步队列三件套 `FollowUpTask` / `FollowUpTaskPublisher` / `FollowUpTaskConsumerLoop`（谁把任务投进来、谁消费）。整体形成"定义规则 → 实现节点 → 运行规则 → 异步触发"的完整闭环。

#### 文件 1：`src/main/resources/flow/interview.xml`

**创建这个文件干什么：** 这是 LiteFlow 的规则定义文件。它将三条不同难度的追问策略组织成"多节点串行 + 公共节点复用"的形态：每条链由「加载上下文 → 按分数段生成追问 → 持久化」三个职责单一的节点串行组成，其中 `loadQuestionContext` 和 `persistFollowUp` 是三条链共用的公共节点，只有中间那一个"按分数段生成追问"的节点因分数段不同而不同。

**改造动机（为什么不能是"一条链一个节点"）：** 改造前三条链各只有一个「生成追问」节点，`GenerateBasic/GenerateNormal/GenerateDeep` 三个节点的 `process()` 几乎逐字相同，`<chain>` 只是把一次方法调用多套了一层 XML 壳，LiteFlow 的编排价值完全没发挥出来——新增步骤要么改大量业务代码，要么复制一遍。改成多节点串行后：

- **加载与持久化被复用，新增策略零重复。** `loadQuestionContext`（加载原题与作答）、`persistFollowUp`（幂等落库）在各链之间共享。以后想加一步"追问去重/打标签"，只需加一个节点并把它插到 `<chain>` 里，三条链立即同时生效。
- **每个节点职责单一、可单独演进。** 加载上下文、生成追问正文、幂等持久化是三个演化速度完全不同的关注点：加载关注数据来源，生成关注提示词与模型调用，持久化关注幂等与去重。混在一个节点里，任何一处的改动都会牵动另外两处。
- **链的可读性即文档。** `THEN(loadQuestionContext, generateDeepFollowUp, persistFollowUp)` 一眼能读出"这题高分的候选人走什么流程"，比读三段重复的方法体直觉得多。
- **不拆的后果：** 链只剩一个节点时，LiteFlow 退化成"XML 包装函数调用"，流程里多一步少一步都必须改写节点类并重新编译；而生成节点要顺便干加载/持久化的活，重复代码还会在三条链里各写一份，改动一处漏两处。

```xml
<?xml version="1.0" encoding="UTF-8"?>
<flow>
    <!--
      追问链共 5 个节点组成三条链，每条链 3 个节点串行，
      并按"公共节点 + 分数段节点"复用：
      - loadQuestionContext / persistFollowUp 是三条链共用的公共节点；
      - generateBasicFollowUp / generateNormalFollowUp / generateDeepFollowUp
        是三条链各自唯一的「按分数段生成追问」节点。
      这种形态下，新增一种追问策略只需要加一个生成节点 + 一条 <chain>，
      「加载上下文」和「持久化」两条公共逻辑一行都不用重复。
      必须用 <nodes> 显式声明节点并在 class 上写嵌套类二进制名称
      （外层类名$嵌套类名），否则 LiteFlow 按类名反射实例化会找不到类。
    -->
    <nodes>
        <!-- 公共节点：从题目表和答案表加载原题与作答，供后续任一生成节点使用 -->
        <node id="loadQuestionContext"
              class="com.example.interview.ai.FollowUpLiteFlowNodes$LoadQuestionContext"/>
        <!-- 基础追问节点：分数 < 60 时执行，只有这一格与 normal/deep 不同 -->
        <node id="generateBasicFollowUp"
              class="com.example.interview.ai.FollowUpLiteFlowNodes$GenerateBasicFollowUp"/>
        <!-- 常规追问节点：60 <= 分数 < 80 时执行 -->
        <node id="generateNormalFollowUp"
              class="com.example.interview.ai.FollowUpLiteFlowNodes$GenerateNormalFollowUp"/>
        <!-- 深度追问节点：分数 >= 80 时执行 -->
        <node id="generateDeepFollowUp"
              class="com.example.interview.ai.FollowUpLiteFlowNodes$GenerateDeepFollowUp"/>
        <!-- 公共节点：把生成的追问幂等落入 follow_up 表，三条链共用 -->
        <node id="persistFollowUp"
              class="com.example.interview.ai.FollowUpLiteFlowNodes$PersistFollowUp"/>
    </nodes>

    <!-- 基础追问链：补齐概念定义、核心原理和最小可运行例子 -->
    <chain name="followUpBasicChain">
        THEN(loadQuestionContext, generateBasicFollowUp, persistFollowUp)
    </chain>

    <!-- 常规追问链：补充实现细节、排障过程和验证方式 -->
    <chain name="followUpNormalChain">
        THEN(loadQuestionContext, generateNormalFollowUp, persistFollowUp)
    </chain>

    <!-- 深度追问链：验证边界条件、性能取舍、故障恢复与架构决策 -->
    <chain name="followUpDeepChain">
        THEN(loadQuestionContext, generateDeepFollowUp, persistFollowUp)
    </chain>
</flow>
```

#### 文件 2：`src/main/java/com/example/interview/ai/FollowUpLiteFlowNodes.java`

**为什么五种节点都放在同一个类的嵌套静态类里：** 一个 Java 源文件里最多只能有一个 public 顶级类，若把每个节点都定义成独立 public 类，就要建五个文件。它们共享同一套"取共享上下文 → 各干各的"结构，收敛为 `FollowUpLiteFlowNodes` 的嵌套静态类，既满足"一个文件"的约束，也让「2 个公共节点 + 3 个分数段节点」在同一个文件中并列展示。节点之间并非互相复制：公共节点（加载、持久化）在三条链间复用，分数段节点各自携带不同的追问口径。

> **嵌套类必须与外部类平级（一个踩过的坑）：** 三个追问生成节点最初是写在 `LoadQuestionContext` 的花括号**里面**的，它们的二进制名因此多了一层 `$LoadQuestionContext`（`...FollowUpLiteFlowNodes$LoadQuestionContext$GenerateBasicFollowUp`），与 `interview.xml` 里写的 `...FollowUpLiteFlowNodes$GenerateBasicFollowUp` 对不上。LiteFlow 加载链时**不校验节点类是否存在**，链照样能起来，但跑到该节点时才反射实例化，直接抛 `ClassNotFoundException`。现在把三个生成节点整体左移一层、与 `LoadQuestionContext` 平级，二进制名里的 `$LoadQuestionContext` 就消失了（类名、`@Component` 名、`process()` 方法体一个字都没改）。教训：XML 里 `class` 写的是**嵌套类的二进制名**（外层类名 `$` 嵌套类名），层级差一层就对不上，而 LiteFlow 的错误只在运行到该节点时才暴露。

```java
package com.example.interview.ai;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.application.FollowUpRepository;
import com.example.interview.infrastructure.mysql.AnswerEntity;
import com.example.interview.infrastructure.mysql.AnswerEntityMapper;
import com.example.interview.infrastructure.mysql.FollowUpExecution;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.yomahub.liteflow.core.NodeComponent;
import org.springframework.stereotype.Component;

/**
 * 追问 LiteFlow 规则链的全部节点组件，按「公共节点 + 分数段节点」收敛在同一个文件中。
 *
 * <p>链表形态（多节点串行 + 公共节点复用）：
 * <ul>
 *   <li>公共节点（三条链共用）：{@link LoadQuestionContext}（加载原题与作答）、
 *       {@link PersistFollowUp}（幂等落库）。</li>
 *   <li>分数段节点（每条链各自唯一）：{@link GenerateBasicFollowUp}、
 *       {@link GenerateNormalFollowUp}、{@link GenerateDeepFollowUp}，只负责
 *       用本分数段的追问口径调用 AI 生成正文。</li>
 * </ul>
 * 拆成多个节点，是因为加载、生成、持久化三个关注点的演化速度不同：加载关注数据
 * 来源、生成关注提示词与模型、持久化关注幂等与去重。混在一个节点里任何一处的改动
 * 都会牵动另外两处；拆开并做成公共节点后，新增一个分数段只加一个生成节点，
 * 公共逻辑零重复。
 */
public final class FollowUpLiteFlowNodes {

    /**
     * 私有构造器：工具类风格，禁止外部实例化。所有节点都由 Spring
     * 通过嵌套类的构造器实例化并注入各自依赖。
     */
    private FollowUpLiteFlowNodes() {
    }

    /**
     * 公共节点：加载原题正文与候选人最近一次作答，写入共享上下文。
     *
     * <p>为什么必须单独占一个节点：追问必须围绕候选人"实际答过的原题 + 实际给出的
     * 作答"展开。只传 questionId 的话 AI 拿不到任何内容，追问很容易跑偏成一道无关的
     * 新题。三条链都要做这件事，抽出来复用，避免在三个生成节点里各写一遍数据库查询。
     */
    @Component("loadQuestionContext")
    public static final class LoadQuestionContext
            extends NodeComponent {
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
            FollowUpExecution context =
                    this.getSlot().getContextBean(
                            FollowUpExecution.class
                    );
            // 这里原本是 selectById(context.getQuestionId())：MyBatis 的 SELECT BY ID
            // 对非 String 简单类型（UUID）会把这个参数当 bean 反射去找名为 id 的 getter，
            // UUID 没有 id 属性，于是抛 “no getter ... in 'class java.util.UUID'”。
            // 改用 lambdaQuery 显式 eq 到 id 列、参数按字符串传（题目表主键 id 本身就是
            // varchar），查询语义与 selectById 完全等价，也不再触发 bean 反射。
            QuestionEntity question =
                    questions.selectOne(
                            Wrappers.<QuestionEntity>lambdaQuery()
                                    .eq(
                                            QuestionEntity::getId,
                                            context.getQuestionId().toString()
                                    )
                    );
            if (question == null) {
                throw new IllegalStateException(
                        "question not found: "
                                + context.getQuestionId()
                );
            }
            context.setQuestionTitle(question.getContent());

            // 只取该题最新一条作答作为"追问前的事实基础"：追问是在初次作答判定之后
            // 触发的，按时间（主键 id）最新的那条才反映候选人当前的真实水平。
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

    /**
     * 基础追问节点，绑定 generateBasicFollowUp。适用于评分低于 60 分的场景
     * （概念不清、回答严重偏离），追问口径是补齐概念定义、核心原理和最小可运行例子。
     *
     * <p>为什么它与 normal/deep 是不同节点而不是一个通用节点：低分候选人的追问口径
     * （先补定义、拆到最小例子、语气耐心）与高分候选人的口径（挖边界、比性能、谈架构）
     * 完全不同，两个口径塞进同一个节点只会互相干扰、难以维护。
     */
    @Component("generateBasicFollowUp")
    public static final class GenerateBasicFollowUp
            extends NodeComponent {
        private final InterviewAiFacade ai;

        public GenerateBasicFollowUp(InterviewAiFacade ai) {
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
                    "基础追问：先确认概念定义，再拆核心原理，最后要最小可运行例子；"
                            + "语气耐心、逐步解释，不预设候选人已经掌握。"
            ));
        }
    }

    /**
     * 常规追问节点，绑定 generateNormalFollowUp。适用于评分在 60~79 分之间的场景
     * （回答基本正确但不够深入），追问口径是补充实现细节、排障过程和验证方式。
     */
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

    /**
     * 深度追问节点，绑定 generateDeepFollowUp。适用于评分不低于 80 分的场景
     * （回答很好，可以挖知识深度边界），追问口径是验证边界条件、性能取舍、
     * 故障恢复与架构决策，追问要有阶梯感、从浅入深。
     */
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

    /**
     * 公共节点：把生成的追问幂等写入 follow_up 表。
     *
     * <p>为什么持久化要作为链上节点而不是链外代码：追问任务经过 async 队列 + 重试，
     * at-least-once 语义会让同一道题被消费多次。把"查重 + 插入"放进链节点，使生成与
     * 落库落在同一个 Singleflight fence 作用域内——要么整套执行、要么整套不执行，
     * 避免"生成成功却在落库前崩溃"导致这次追问白跑。
     */
    @Component("persistFollowUp")
    public static final class PersistFollowUp
            extends NodeComponent {
        private final FollowUpRepository followUps;

        public PersistFollowUp(FollowUpRepository followUps) {
            this.followUps = followUps;
        }

        @Override
        public void process() {
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
            // 只写表里真实存在的 level 列（interview_follow_up 没有 chain 列），
            // 链名只留在上下文里供日志排查，不入库。
            followUps.insertIfAbsent(
                    context.getSessionId(),
                    context.getQuestionId(),
                    context.getContent(),
                    context.getLevel()
            );
        }
    }
}
```

**补充：`InterviewAiFacade` 必须提供节点类调用的 `generateFollowUpText` 方法**。上面三个分数段生成节点都通过 `ai.generateFollowUpText(...)` 生成追问正文，该方法在前文只出现过 `generateFollowUp`（参数不带 `level`/原题/作答/口径），这里补齐 `generateFollowUpText` 的完整定义，否则节点类无法通过编译：

```java
/**
 * 按指定追问深度等级、原题、作答和追问口径生成追问正文。
 *
 * 与 generateFollowUp 的区别：多接收 level、questionTitle、answer、directive
 * 四个参数。供三个分数段生成节点（LoadQuestionContext 先填充 questionTitle/answer，
 * 再由 GenerateBasicFollowUp / GenerateNormalFollowUp / GenerateDeepFollowUp）
 * 在 process() 中按各自的链等级与口径调用。questionTitle/answer 让 AI 真正围绕
 * 候选人答过的内容发问，directive 则把三个节点各自的追问侧重说清楚，
 * 避免 AI 把概念题问成大而空的泛化问题。
 *
 * @param sessionId   当前面试会话 ID，用于 AI 追溯上下文
 * @param questionId  当前题目 ID，用于 AI 定位追问对象
 * @param score       本题评分（0-100），低分补基础、高分挖深度
 * @param level       追问深度等级：basic（基础追问）/ normal（常规追问）/ deep（深度追问）
 * @param objective   追问的具体目标说明，指引 AI 不偏离方向
 * @param questionTitle  原题正文，追问必须围绕的原题内容
 * @param answer      候选人作答，追问针对的实际答面
 * @param directive   本分数段的追问口径（节点自带），控制追问侧重与语气
 * @return 纯文本的中文追问正文，不包含 JSON 包裹或额外解释
 */
public String generateFollowUpText(
        UUID sessionId,
        UUID questionId,
        int score,
        String level,
        String objective,
        String questionTitle,
        String answer,
        String directive
) {
    return chat("""
            你是一名技术面试官。
            请根据评分、追问深度等级、追问目标、原题、作答和追问口径生成一道中文追问。
            只返回追问正文，不要返回 JSON，不要附加解释。
            sessionId：%s
            questionId：%s
            score：%s
            level：%s
            objective：%s
            原题：%s
            候选人作答：%s
            追问口径：%s
            """.formatted(
            sessionId,
            questionId,
            score,
            level,
            objective,
            questionTitle,
            answer,
            directive
    )).trim();
}
```

#### 文件 3：`src/main/java/com/example/interview/application/FollowUpGenerationService.java`（追问生成核心服务，完全体）

**本小节流程（补全追问 3 的执行环节）：** 上文完成了规则的「写」，这里完成规则的「跑」。`FollowUpGenerationService` 是追问生成的核心服务，本小节一次性给出它的**完全体**：`generate` 是唯一入口，先用 `FollowUpDecisionService.decide` 决定是否需要追问；需要追问时，在 Singleflight 和 FencingToken 的双重保护下构建共享上下文并调用 `flowExecutor.execute2Resp(chainName, requestId, context)` 执行指定链。链首的 `loadQuestionContext` 节点把原题与作答补进上下文，分数段生成节点把结果写回 `context.content`，`persistFollowUp` 节点在同一个 fence 作用域内把它幂等落库；执行完成后 `completeAndPreGenerate` 推进面试状态并预生成下一题。**后续小节对它的任何扩展都写成「在该类中追加」，不再重复整类，读者始终以这里为基准。**

> **依赖说明：** 本类引用的 `FollowUpTask`（见「十二、追问模块 - 追问 3 - 文件 4」）、`FollowUpRepository`（见「十二、追问模块 - 追问 2 - 文件 6」）、`NextQuestionPreGeneratorService`（见「九、AI 出题模块 - 出题 4」）在后续章节才定义，这里按完全体先写出引用；其余依赖（`FollowUpDecisionService`、`FollowUpExecution`、`SessionSnapshotRepository`、`InterviewSessionStateMachine`、`QuestionMapper`、`RedisSingleflight`）均已在前文定义。

```java
package com.example.interview.application;

import com.example.interview.ai.FollowUpDecisionService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.FollowUpExecution;
import com.example.interview.infrastructure.mysql.QuestionEntity;
import com.example.interview.infrastructure.mysql.QuestionMapper;
import com.example.interview.infrastructure.singleflight.RedisSingleflight;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import com.example.interview.domain.InterviewEvent;
import com.example.interview.domain.InterviewSessionSnapshot;
import com.example.interview.domain.InterviewSessionStateMachine;
import com.example.interview.infrastructure.snapshot.SessionSnapshotRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 追问生成服务。
 *
 * <p>这是异步追问架构中的核心业务类。
 * 它被 FollowUpTaskPublisher.consumeOnce 调用，
 * 负责决定是否需要追问、执行追问、持久化追问结果、
 * 推进状态机以及触发下一题预生成。
 *
 * <p>generate 的容错策略：追问是加分项，它失败绝不能把状态停死在 ASKING。
 * 因此生成逻辑整体包在 try-catch 里，无论成败最后都会推进状态并预生成下一题，
 * 失败只记日志、由异步线程自己消化，不再向上抛给 consumeOnce 的重试/死信机制。
 */
@Service
public class FollowUpGenerationService {

    // ---- 依赖注入 ---- //

    // 为什么需要这么多依赖（9 个）：
    // 这个方法涉及追问决策、AI 调用、数据库写入、状态机推进、预生成触发
    // 每个依赖对应一个独立的职责
    private final FollowUpDecisionService decisions;
    private final InterviewAiFacade ai;
    private final FollowUpRepository followUps;
    private final SessionSnapshotRepository snapshots;
    private final InterviewSessionStateMachine machine;
    private final NextQuestionPreGeneratorService preGenerator;
    private final FlowExecutor flowExecutor;
    private final RedisSingleflight singleflight;
    private final QuestionMapper questions;
    // 日志对象：追问链失败但被兜底推进时留痕——异步线程没有调用方接住异常。
    private static final Logger log =
            LoggerFactory.getLogger(FollowUpGenerationService.class);

    public FollowUpGenerationService(
            FollowUpDecisionService decisions,
            InterviewAiFacade ai,
            FollowUpRepository followUps,
            SessionSnapshotRepository snapshots,
            InterviewSessionStateMachine machine,
            NextQuestionPreGeneratorService preGenerator,
            FlowExecutor flowExecutor,
            RedisSingleflight singleflight,
            QuestionMapper questions
    ) {
        this.decisions = decisions;
        this.ai = ai;
        this.followUps = followUps;
        this.snapshots = snapshots;
        this.machine = machine;
        this.preGenerator = preGenerator;
        this.flowExecutor = flowExecutor;
        this.singleflight = singleflight;
        this.questions = questions;
    }

    /**
     * 生成追问并推进状态。
     *
     * @param task 追问任务，包含会话 ID、题目 ID、评分和重试次数
     *
     * <p>工作流程：
     * 1. 根据评分决定是否需要追问（FollowUpDecisionService.decide 输出三条链：
     *    <60 走 followUpBasicChain、60-79 走 followUpNormalChain、>=80 走 followUpDeepChain；
     *    若未来要支持"高分免追问"，可让 decide 返回 NONE，这里已预留该分支）
     * 2. 如果需要，执行多节点追问链（含 Singleflight 防重保护），
     *    persistFollowUp 节点负责把追问幂等写入 follow_up 表
     * 3. 推进 ASK_COMPLETED 事件
     * 4. 触发下一题预生成
     */
    public void generate(FollowUpTask task) {
        // 整段生成逻辑包进 try-catch：追问是加分项，它失败绝不能把状态停死在
        // ASKING 让用户无限转圈——所以无论成败，最后都会把状态推进到
        // QUESTIONING（completeAndPreGenerate 会预生成下一题）。
        try {
            // 第一步：根据评分决定追问策略
            // FollowUpDecisionService.decide 根据分数段返回对应的追问链
            // 如：<60 返回 "followUpBasicChain"（基础追问）
            //     60-79 返回 "followUpNormalChain"（常规追问）
            //     >=80 返回 "followUpDeepChain"（深度追问）
            var plan = decisions.decide((int) task.score());

            // 第二步：如果不需要追问，直接推进状态并预生成下一题
            if ("NONE".equalsIgnoreCase(plan.chain())) {
                completeAndPreGenerate(task.sessionId());
                return;
            }

            // 第三步：需要追问，执行多节点追问链（含 Singleflight 防重）。
            // 追问的生成与幂等落库都发生在链内：loadQuestionContext 加载原题与作答，
            // 分数段生成节点产出正文，persistFollowUp 节点把结果幂等写入 follow_up 表。
            // 它们落在同一个 fence 作用域内，不会"生成成功却在落库前崩溃"，
            // 因此这里不再单独调 insertIfAbsent——落库由链上的 persistFollowUp 完成。
            executeFollowUpWithSingleflight(
                    task,
                    plan
            );
        } catch (Exception e) {
            // 追问链异常：记日志，仍继续推进状态，保证面试不被追问失败卡死。
            log.error(
                    "follow-up generation failed, continue to next question, "
                            + "sessionId={}, questionId={}",
                    task.sessionId(),
                    task.questionId(),
                    e
            );
        }
        // 修改结束

        // 第四步：无论追问是否成功，都推进状态并预生成下一题（ASKING → QUESTIONING）。
        completeAndPreGenerate(task.sessionId());
    }

    /**
     * 完成追问阶段并触发预生成。
     *
     * <p>为什么这个方法需要标记为 private：
     * 这是内部逻辑，外部不应该直接调用"完成追问"。
     *
     * <p>流程：
     * 1. 加载当前快照
     * 2. 尝试推进 ASK_COMPLETED 事件
     * 3. 如果成功（changed = true），CAS 更新快照
     * 4. 从题目表读取最大 sequence_no + 1，触发下一题预生成
     */
    private void completeAndPreGenerate(UUID sessionId) {
        // 加载当前快照
        InterviewSessionSnapshot old = snapshots.load(sessionId);

        // 尝试推进 ASK_COMPLETED 事件
        // 为什么用 UUID.randomUUID() 作为事件 ID：
        // 事件 ID 用于幂等性，随机 UUID 确保每次事件是唯一的
        var moved = machine.advance(
                old,
                InterviewEvent.ASK_COMPLETED,
                UUID.randomUUID().toString()
        );

        // 只有状态真的变了才 CAS 更新快照
        // 为什么需要检查 changed：
        // 如果当前状态不允许 ASK_COMPLETED 转换（比如已经是 ANSWERING），
        // advance 返回 changed=false，不应该更新快照
        if (moved.changed()) {
            snapshots.compareAndSet(old, moved.snapshot());
        }

        // 触发下一题预生成
        // 下一题的题号直接用当前会话里最大的 sequence_no 加 1：
        // 为什么用 max(sequence_no) + 1 而不是 count(*) + 1：
        // PRE_GENERATED 状态的题目已经占用了序号，count 只能统计行数，
        // 不能反映实际的最大序号。比如有 3 道 ACTIVE 题和 1 道 PRE_GENERATED（序号 5），
        // count=4 但 max=5，下一题应该是 6 而不是 5。
        QuestionEntity latest = questions.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<QuestionEntity>lambdaQuery()
                        .eq(
                                QuestionEntity::getSessionId,
                                sessionId.toString()
                        )
                        .orderByDesc(
                                QuestionEntity::getSequenceNo
                        )
                        .last("limit 1")
        );
        preGenerator.preGenerate(
                sessionId,
                latest == null
                        ? 1
                        : latest.getSequenceNo() + 1
        );
    }

    /**
     * 使用 Singleflight 保护执行追问链。
     *
     * <p>RedisSingleflight 确保：
     * - 同一个 key（follow-up:{sessionId}:{questionId}）同时只有一个执行者
     * - 其他并发请求等待第一个请求的结果
     * - 通过 fence 机制防止"过时结果"被写入（类似 CAS 的版本号）
     *
     * @param task 追问任务
     * @param plan 追问计划（包含链名称、层级和目标）
     * @return 生成的追问内容（已由链上 persistFollowUp 节点落库）
     */
    private String executeFollowUpWithSingleflight(
            FollowUpTask task,
            FollowUpDecisionService.FollowUpPlan plan
    ) {
        // 构建 Singleflight key
        // 为什么 key 包含 sessionId 和 questionId：
        // 确保同一个面试的同一道题只有一个追问在执行
        String key = "follow-up:"
                + task.sessionId()
                + ":"
                + task.questionId();

        // 使用 Singleflight 执行（带 fence 保护）
        // Duration.ofMinutes(2) 是超时时间
        // AI 调用通常需要 10-30 秒，2 分钟的超时足够
        var result = singleflight.executeFenced(
                key,
                java.time.Duration.ofMinutes(2),
                () -> {
                    // 这是实际执行追问链的 Lambda
                    // 为什么用 Lambda 而不是方法引用：
                    // executeFenced 需要 Supplier<T>，Lambda 可以传递上下文变量

                    // 构建共享上下文：链首节点会进一步读取原题与作答并实例化内容。
                    FollowUpExecution context =
                            new FollowUpExecution();
                    context.setSessionId(task.sessionId());
                    context.setQuestionId(task.questionId());
                    context.setScore((int) task.score());
                    context.setObjective(plan.objective());
                    // persistFollowUp 节点靠 level 写库（表里只有 level 列），
                    // 必须与入参的追问等级一致。
                    context.setLevel(plan.level());

                    // 执行多节点 LiteFlow 追问链：loadQuestionContext → 生成 → persistFollowUp
                    // 整条链（加载原题/作答、生成正文、幂等落库）都发生在这个 fence 作用域内，
                    // 因此不会出现"生成成功却在落库前崩溃"导致这次追问白跑。
                    LiteflowResponse response =
                            flowExecutor.execute2Resp(
                                    plan.chain(),     // 链名称，如 "followUpDeepChain"
                                    task.sessionId().toString(),  // 请求 ID
                                    context          // 上下文数据
                            );

                    // 检查执行结果
                    if (!response.isSuccess()
                            || context.getContent() == null
                            || context.getContent().isBlank()) {
                        // LiteFlow 执行失败或内容为空，抛出异常触发重试
                        throw new IllegalStateException(
                                "follow-up LiteFlow result is empty"
                        );
                    }
                    return context.getContent();
                }
        );

        // 检查 fence 是否通过
        // 如果 !acceptFence，说明这个结果已经"过期"了
        // （比如另一个线程已经成功写入了更新的结果）
        if (!singleflight.acceptFence(
                key,
                result.token()
        )) {
            throw new IllegalStateException(
                    "stale follow-up result rejected"
            );
        }

        return result.value();
    }
}
```

> **为什么把完全体放在本小节：** 规则链只有"能跑起来"才算真正的实现，前面的 XML 和节点类只是"躺着的规则"。`FollowUpGenerationService` 把它们串起来——决策 → 构建上下文 → 执行多节点串行链 → 校验结果 → 并发防重 → 推进状态 → 预生成下一题，并让"加载 / 生成 / 幂等落库"作为一个整体落在同一个 Singleflight fence 作用域内。因此本小节直接给出类的完全体，第十二章自洽完整；后续章节对它只有增量，不再重复整类。

#### 文件 4：`src/main/java/com/example/interview/application/FollowUpTask.java`

**创建这个文件干什么：** 追问任务在 Redis 队列里的载荷对象——"投递进队列、被消费者取出"的最小上下文。它只描述"队列里传什么"，不包含 AI 返回的结果。

```java
package com.example.interview.application;

import java.util.UUID;

/**
 * 追问任务的不可变数据模型，作为 Redis 队列中传递的载荷。
 *
 * @param sessionId  面试会话 ID，用于关联会话数据
 * @param questionId 题目 ID，用于关联当前题目
 * @param score      当前题的评分（0-100），用于决定追问深度
 * @param retryCount 当前重试次数，从 0 开始，超过上限进入死信队列
 */
public record FollowUpTask(
        UUID sessionId,
        UUID questionId,
        double score,
        int retryCount
) {
}
```

#### 文件 5：`src/main/java/com/example/interview/application/FollowUpTaskPublisher.java`

**创建这个文件干什么：** 追问任务的"发布 + 消费 + 重试/死信"三合一服务。发布侧由 `InterviewWorkflowService.judge` 在评分落库、状态推进之后调用；消费侧由 `FollowUpTaskConsumerLoop` 定时触发。三 key 结构（QUEUE / PROCESSING / DEAD）换来的是"消费者崩溃不丢任务"。**本代码块与项目文件逐字一致。**

```java
// 从这里开始是新增的：以下整个文件都是新增的（项目里缺失这个文件）
package com.example.interview.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
// 从这里开始是修改的：move 需要 RedisListCommands.Direction 枚举，补上该 import。
import org.springframework.data.redis.connection.RedisListCommands.Direction;
// 修改结束
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 追问任务发布/消费服务。
 *
 * 为什么用 Redis List 而不是 RabbitMQ/Kafka：项目已经引入了 Redis，追问任务的并发量也不高，
 * 不值得为一个队列再引入一套中间件；三 key 的结构换来的是"消费者崩溃不丢任务"。
 *
 * 为什么发布和消费放在同一个类里：两者操作同一个队列的三个 key，
 * 分开反而要多维护一份 key 常量的拷贝；将来真要分离，把消费逻辑抽出去即可。
 */
@Service
public class FollowUpTaskPublisher {

    // ---- Redis Key 定义 ---- //

    /** 主队列：待处理的追问任务，使用 Redis List 结构。 */
    private static final String QUEUE =
            "interview:task:follow-up";

    /** 处理中队列：已取出但尚未完成的追问任务。
     *  为什么必须有它：消息一旦 pop 出来就离开主队列，消费者这时崩溃任务就凭空消失；
     *  压在 processing 里，至少还能被人看到、被补偿任务捞回来。 */
    private static final String PROCESSING =
            "interview:task:follow-up:processing";

    /** 死信队列：超过最大重试次数的失败任务。
     *  死信不会被自动消费，留原文是为了人工/定时任务排查，而不是让失败静默消失。 */
    private static final String DEAD =
            "interview:task:follow-up:dead";

    /** 最大重试次数。到顶就进死信，避免一条永远失败的任务无限重投。 */
    private static final int MAX_RETRY = 3;

    // ---- 依赖注入 ---- //

    // 用 StringRedisTemplate 而不是 RedisTemplate<String, FollowUpTask>：
    // 我们用 Jackson 手工控制序列化格式，队列里存的永远是"人也能看懂"的 JSON 字符串，
    // 出问题时直接去 Redis 里看一眼原文就能定位，不依赖框架的序列化黑盒。
    private final StringRedisTemplate redis;
    // Jackson 序列化器：入队前把 FollowUpTask 转成 JSON，出队后再转回对象。
    private final ObjectMapper mapper;

    // 追问生成的核心业务逻辑，消费端只负责"取任务、调它、按结果收尾"。
    private final FollowUpGenerationService generation;

    // 构造器注入三个协作者：队列客户端、JSON 序列化器、追问生成服务。
    // 字段全是 final，消费在独立线程池里执行，依赖不会被中途替换。
    public FollowUpTaskPublisher(
            StringRedisTemplate redis,
            ObjectMapper mapper,
            FollowUpGenerationService generation
    ) {
        this.redis = redis;
        this.mapper = mapper;
        this.generation = generation;
    }

    /**
     * 发布追问任务到 Redis 队列。
     *
     * 由 InterviewWorkflowService.judge 在评分落库、状态推进之后调用。
     * 为什么这个方法不是 @Async：rightPush 是毫秒级操作，异步化没有收益；
     * 更要紧的是发布失败必须让调用方当场感知（抛异常），异步化会把异常吞进另一个线程。
     *
     * @param sessionId  面试会话 ID
     * @param questionId 当前题目 ID
     * @param score      当前题的评分，决定追问深度
     */
    public void publish(
            UUID sessionId,
            UUID questionId,
            double score
    ) {
        try {
            // 序列化成 JSON 推入主队列右侧。为什么 rightPush 配消费端的 leftPop：
            // 右进左出才是 FIFO，先提交的答案先被追问；两边用错就变成后进先出，题序会乱。
            redis.opsForList().rightPush(
                    QUEUE,
                    mapper.writeValueAsString(
                            new FollowUpTask(
                                    sessionId,
                                    questionId,
                                    score,
                                    0  // 初始重试次数为 0，每次重投才 +1
                            )
                    )
            );
        } catch (JsonProcessingException e) {
            // 序列化失败通常意味着 FollowUpTask 的字段类型不可序列化，是代码问题而非偶发故障，
            // 重试多少次都一样，所以直接抛不可恢复异常，让它暴露在日志里。
            throw new IllegalStateException(
                    "cannot serialize follow-up task",
                    e
            );
        }
    }

    /**
     * 尝试消费一个追问任务。
     *
     * 核心是 leftPopAndLeftPush 这条原子命令：从主队列头部取出，同时压进 processing 队列，
     * 两步要么都成功要么都失败——换成"先 pop 再 push"一旦中间挂掉，任务就没了。
     * 超时 200 毫秒：主队列为空时最多等 200ms 就返回 null，避免在这里无限阻塞、占着线程。
     *
     * 调用频率由外部定时任务/循环控制，@Async 只表示这条消费跑在自己的线程池里。
     */
    @Async("followUpTaskExecutor")
    public void consumeOnce() {
        // 一条原子命令搞定"出队 + 移位"：从主队列头部取出，同时压进 processing 队列。
        // 中途消费者崩溃时任务仍留在 processing 里，不会凭空消失。
        String raw = redis.opsForList()
                // 从这里开始是修改的：原代码调用的 leftPopAndLeftPush(QUEUE, PROCESSING, 200, TimeUnit.MILLISECONDS)
                // 在当前 Spring Data Redis 的 ListOperations 上并不存在（编译不过），
                // 改为与 JudgeTaskPublisher 一致的 Direction 版 move：从 QUEUE 左侧弹出并原子地压进
                // PROCESSING 左侧。仍然是"右进左出"的 FIFO 语义（publish 用的是 rightPush），
                // 且保持"出队+移位"的原子性，消费者崩溃时任务仍留在 processing 里不会丢。
                // 区别只是去掉了 200ms 阻塞等待——消费由外层定时循环反复触发，非阻塞即可。
                .move(
                        QUEUE,
                        Direction.LEFT,
                        PROCESSING,
                        Direction.LEFT
                );
        // 修改结束
        if (raw == null) {
            // 队列为空，本次没有可消费的任务，直接收工。
            return;
        }

        // 先声明成 null：反序列化可能失败，catch 里要靠它区分"任务坏掉了"和"处理任务时失败"。
        FollowUpTask task = null;
        try {
            // 反序列化 JSON 为 FollowUpTask 对象
            task = mapper.readValue(raw, FollowUpTask.class);

            // 调用核心业务逻辑生成追问：决策 → Singleflight 跑 LiteFlow 链 → 落库 → 推进状态。
            generation.generate(task);

            // 生成成功，把这条消息从 processing 里摘掉。
            // 为什么用 remove 而不是 leftPop：processing 里可能压着多条任务，
            // leftPop 会摘错别人的消息，remove 按"值 + 数量 1"精确删掉当前这条。
            redis.opsForList().remove(
                    PROCESSING,
                    1,     // 只删 1 个匹配元素
                    raw    // 匹配的内容就是这条任务的原始 JSON
            );
        } catch (Exception e) {
            // 消费失败（AI 超时、数据库异常等）：交给 retryOrDeadLetter 决定重投还是进死信。
            // 这里故意不把异常抛出去——抛出会让任务永远留在 processing 里没人管。
            retryOrDeadLetter(raw, task);
        }
    }

    /**
     * 处理消费失败的任务：重投主队列或进死信队列。
     *
     * @param raw  原始 JSON 字符串（重投/进死信都以它为准，保证内容不变形）
     * @param task 解析后的任务对象；反序列化失败时为 null
     */
    private void retryOrDeadLetter(
            String raw,
            FollowUpTask task
    ) {
        // 反序列化失败（task 为 null）或重试次数已达上限 → 直接进死信，不再重试。
        // 为什么不重试反序列化失败的任务：JSON 本身已经坏了，重投多少次都还是坏的那一条。
        if (task == null || task.retryCount() >= MAX_RETRY) {
            // 原文原样丢进死信队列，方便人工照原样排查。
            redis.opsForList().rightPush(DEAD, raw);
            // 同时从 processing 摘掉：不摘的话补偿任务会一直以为它还"卡在处理中"。
            redis.opsForList().remove(
                    PROCESSING,
                    1,
                    raw
            );
            return;
        }

        try {
            // 重投：只把 retryCount 加一，其余字段原样带回，重新放回主队列尾部排队。
            FollowUpTask retry = new FollowUpTask(
                    task.sessionId(),
                    task.questionId(),
                    task.score(),
                    task.retryCount() + 1  // 重试次数 +1，到 MAX_RETRY 就改走死信
            );
            redis.opsForList().rightPush(
                    QUEUE,
                    mapper.writeValueAsString(retry)
            );
            // 新消息已经进主队列，这条旧的要从 processing 摘掉，否则会留下永不消失的幽灵消息。
            redis.opsForList().remove(
                    PROCESSING,
                    1,
                    raw
            );
        } catch (JsonProcessingException e) {
            // 重投时序列化失败：异常只能抛出去，此时任务仍留在 processing 队列中，
            // 需要补偿机制来清理（与评分队列的补偿任务同一套思路）。
            throw new IllegalStateException(
                    "cannot serialize follow-up retry",
                    e
            );
        }
    }
}
// 新增结束
```

**三个必须记住的点：** ① 出队用的是 `redis.opsForList().move(QUEUE, Direction.LEFT, PROCESSING, Direction.LEFT)`——一条原子命令把任务从主队列左侧弹出、同时压进 processing 左侧，**不是** `leftPopAndLeftPush`（该重载在当前 Spring Data Redis 的 `ListOperations` 上并不存在，编译不过）；`publish` 用 `rightPush`，右进左出即 FIFO。② 消费失败时把任务重投主队列、`retryCount + 1`，到 `MAX_RETRY = 3` 就进死信；反序列化失败（`task == null`）也直接进死信。③ 重投/进死信后都要 `remove(PROCESSING, 1, raw)` 把旧消息从 processing 摘掉，否则会留下"永远卡在处理中"的幽灵消息。

#### 文件 6：`src/main/java/com/example/interview/application/FollowUpTaskConsumerLoop.java`

**创建这个文件干什么：** "谁来消费"的答案。`consumeOnce` 带 `@Async("followUpTaskExecutor")`，语义是"被调用一次、在独立线程池里消费一条"，不会自己重复执行；没有外部反复调用，主队列里的追问任务会一直躺着。本类用 `@Scheduled(fixedDelay = 200)` 定时触发它。

```java
// 从这里开始是新增的：以下整个文件都是新增的（补全亮点.md 里只写了
// FollowUpTaskPublisher 的消费方法，并注明"调用频率由外部定时任务/循环控制"，
// 但项目里没有任何一处调用它。没有这个循环类，追问队列就进得去出不来，
// 追问永远生成不了，属于"能编译但跑不通"的缺口，因此补上本类。）
package com.example.interview.application;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// ============================================================
// 追问任务消费者循环
// ============================================================
// 职责：定时触发 FollowUpTaskPublisher.consumeOnce()，让"消费一条追问任务"
// 变成持续运行的动作。
//
// 为什么需要一个循环：consumeOnce 上带 @Async("followUpTaskExecutor")，
// 它的语义是"被调用一次、在独立线程池里消费一条"，调用方拿到的是立即返回的
// Future 占位，不会自己重复执行；没有外部反复调用，Redis 主队列里的追问任务
// 会一直躺着，评分返回的 chain 字段也就永远等不到追问落库。
//
// 为什么用 @Scheduled 而不是在方法里写 while(true)：
//   常驻循环一旦抛异常就永久退出；@Scheduled(fixedDelay) 由 Spring 调度，
//   单次异常只跳过这一轮，下一轮照常触发，健壮性更好。
//   间隔取 200ms：队列为空时最多 5 次/秒的空查询，成本可忽略。
// ============================================================
@Component
public class FollowUpTaskConsumerLoop {

    // 只依赖发布/消费服务：出队、反序列化、调生成服务、失败重投或进死信
    // 都在 FollowUpTaskPublisher 里完成，本类只负责"按节奏触发"，职责单一。
    private final FollowUpTaskPublisher publisher;

    // 构造器注入，方便测试时替换成 mock。
    public FollowUpTaskConsumerLoop(FollowUpTaskPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * 定时触发一次追问任务消费。
     *
     * <p>为什么调 consumeOnce 而不是自己写拉取逻辑：consumeOnce 内部用的是
     * move 这条原子命令（主队列 → processing），队列为空时立刻返回 null，
     * 正好适合被定时器反复调用；在这里重写一遍出队逻辑只会多出一份容易走样的拷贝。</p>
     *
     * <p>为什么用 fixedDelay 而不是 fixedRate：必须等上一轮触发完再开始计时，
     * 队列积压时不会不断叠加新的触发轮次。</p>
     */
    @Scheduled(fixedDelay = 200)
    public void poll() {
        // 一次只触发一条：单轮不长时间占用调度线程；
        // 队列为空时 consumeOnce 会立刻返回，等下一次调度即可。
        publisher.consumeOnce();
    }
}
// 新增结束
```

**为什么用 `@Scheduled` 而不是 `while(true)`：** 常驻循环一旦抛异常就永久退出；`@Scheduled(fixedDelay)` 由 Spring 调度，单次异常只跳过这一轮，下一轮照常触发，健壮性更好。间隔取 200ms（队列为空时最多 5 次/秒的空查询，成本可忽略）；用 `fixedDelay` 而不是 `fixedRate`，是必须等上一轮触发完再开始计时，队列积压时不会叠加新的触发轮次。

---

### 追问 4：追问作答的 is_follow_up 设计（本章重点）

**本小节目的：** 讲清楚"追问生成出来之后，候选人怎么回答它"。追问内容存在 `interview_follow_up` 表，**不作为新题插入 `interview_question`**——追问不是一道独立的题，它挂在原题上。前端提交追问答案时**复用原题的 `questionId`**，后端用 `interview_answer.is_follow_up` 区分"初次作答 / 追问作答"，让同一道题最多占两行答案。这样追问既不会打乱主线的题序与进度，也不会覆盖候选人的首次得分。

**本小节流程：** 表结构加 `is_follow_up` 列并调整唯一键 → 前端在提交体里带 `followUp=true` → `AnswerCommand` / `AnswerCommandService` 走追问早返回分支（只落库 + 投评分任务）→ `JudgeTask` / `JudgeTaskPublisher` 携带 `followUp` → `InterviewAiFacade.judge` 按 `followUp` 区分提示词与 singleflight key → `InterviewWorkflowService.judge` 追问早返回（不推进状态机）→ `GET /{sessionId}/follow-up` 把追问内容取回前端。

#### 文件 1：`interview_answer` 表新增 `is_follow_up` 列（SQL）

**为什么改这里：** 追问复用原题 `questionId`，如果没有区分列，追问答案会和初次答案撞上原来的唯一键 `uk_answer_question(question_id)`，被当成重复提交静默丢弃。加一列 `is_follow_up`，并把唯一键改成 `(question_id, is_follow_up)`，同一道题最多两行：一行初次作答、一行追问作答。

```sql
-- interview_answer 新增列
is_follow_up TINYINT(1) NOT NULL DEFAULT 0,   -- 0=初次作答，1=追问作答
retry_count  INT NOT NULL DEFAULT 0,          -- 异步补偿重投评分任务的次数

-- 唯一键由 (question_id) 改成 (question_id, is_follow_up)：
-- 同一道题最多两行，一行初次作答、一行追问作答。
UNIQUE KEY uk_answer_question_round (question_id, is_follow_up),
```

对应实体字段（`AnswerEntity.isFollowUp`）用包装类型 `Integer` 而不是 `boolean`：MyBatis-Plus 把基本类型 `boolean` 映射到 `TINYINT` 时存在类型处理器兼容问题（`false` 有时被写成 `null` 或读取报错），用 `Integer` 直接承载 0/1 最稳妥。

```java
// 语义：0=初次作答，1=追问作答。追问不作为新题插入 interview_question，
// 前端提交的 questionId 仍是原题 ID，靠这个标记把"初次评分"和"追问评分"分成两行。
private Integer isFollowUp;
```

#### 文件 2：`SubmitAnswerRequest` + `AnswerController`（HTTP 入口）

**为什么请求体用包装类型 `Boolean`：** 追问标记由前端提交。用包装类型 `Boolean` 而不是基本类型 `boolean`，是为了允许前端老版本**不传**该字段——缺省时 Jackson 给 `null`，控制器里用 `Boolean.TRUE.equals(...)` 兜底成 `false`（按初次作答处理），新老前端都能正常提交，不会因为缺字段直接 400。

```java
// SubmitAnswerRequest：末位新增（用包装类型 Boolean，缺省为 null）
Boolean followUp
```

```java
// AnswerController.submit：末位传入追问标记
answers.submit(new AnswerCommand(
        sessionId,
        request.questionId(),
        request.requestId(),
        request.answer(),
        // 前端可能不传 followUp（老版本），此时 request.followUp() 为 null，
        // Boolean.TRUE.equals(...) 会把 null 安全地兜底成 false（按初次作答处理），
        // 既不会抛空指针，也不会把未传字段误判成追问。
        Boolean.TRUE.equals(request.followUp())
));
```

#### 文件 3：`AnswerCommand` + `AnswerCommandService.submit`（追问早返回分支）

**`AnswerCommand` 末位多一个 `boolean followUp`：** 追问不作为新题插入 `interview_question`，前端提交的 `questionId` 仍是原题 ID，只能靠这个标记在落库、评分、状态机各环节区分两种作答。

```java
// false=初次作答，true=追问作答（候选人回答的是原题派生出的追问）。
boolean followUp
```

**`AnswerCommandService.submit` 的追问早返回分支必须放在 `machine.advance` 之前：** 若追问作答也走 `ANSWER_SUBMITTED` 推进状态机，同一道题会被推进两次、被算成两道题，题序与进度整体错乱。所以追问作答在这里就返回，只做"答案落库 + 投递追问评分任务"，既不推进主线状态机，也不触发下一题预生成。锁仍然保留，`insertIfAbsent` 靠唯一键 `(question_id, is_follow_up)` 兜底幂等。

```java
// 追问作答早返回分支（放在 machine.advance 之前）
if (command.followUp())
{
    answers.insertIfAbsent(command);
    // 末位传 true：评分队列据此把分数写回 is_follow_up=1 那一行，且不推进主线。
    publisher.publish(
            command.sessionId(),
            command.questionId(),
            command.answer(),
            true
    );
    return;
}
// 初次作答：推进状态机 → 落库 → CAS 快照 → 投评分任务(false) → 预生成下一题
var result = machine.advance(oldSnapshot, InterviewEvent.ANSWER_SUBMITTED, command.requestId());
...
publisher.publish(command.sessionId(), command.questionId(), command.answer(), false);
```

`insertIfAbsent` 里把标记落到 `is_follow_up` 列：

```java
// 唯一键已改为 (question_id, is_follow_up)，同一道题的初次作答与追问作答会各占一行；
// 不写这个标记的话，追问答案会和初次答案撞唯一键被幂等丢弃。
entity.setIsFollowUp(command.followUp() ? 1 : 0);
```

#### 文件 4：`AnswerRepository.findJudge` / `recordJudge` 加 `is_follow_up` 条件

**为什么两个方法都要加这个条件：** 同一道题现在会有两行（`is_follow_up=0` 和 `=1`）。回放评分（`findJudge`）和写回评分（`recordJudge`）如果不带 `is_follow_up`，就会读到/写到错误的那一行——追问评分覆盖掉初次评分，或初次评分回放到追问结果上。

```java
// findJudge / recordJudge 都新增 followUp 参数，并按 is_follow_up 过滤
.eq("session_id", sessionId.toString())
.eq("question_id", questionId.toString())
.eq("is_follow_up", followUp ? 1 : 0)   // 新增：只读 / 只写对应类型的作答行
```

#### 文件 5：`JudgeTask` + `JudgeTaskPublisher`（评分队列携带 followUp）

**`JudgeTask` 多一个 `boolean followUp`，插在 `retryCount` 之前：** `retryCount` 仍保持末位，"重试次数永远是最后一个分量"的约定不变。追问作答与初次作答共用同一条评分队列，队列载荷必须带上这个标记，否则消费者无法区分"该写哪一行、该不该推进状态机"。**重试 / 补偿重投时必须原样保留 `followUp`**，否则追问任务会被当成初次作答错误处理。

```java
public record JudgeTask(
        UUID sessionId,
        UUID questionId,
        String answer,
        // 新增：追问标记插在 retryCount 之前，retryCount 仍保持末位。
        boolean followUp,
        int retryCount
) {
}
```

```java
// JudgeTaskPublisher.publish 末位多一个 followUp 参数
public void publish(UUID sessionId, UUID questionId, String answer, boolean followUp) {
    redis.opsForList().rightPush(QUEUE, mapper.writeValueAsString(
            new JudgeTask(sessionId, questionId, answer, followUp, 0)));
}

// 重投时原样保留 followUp，只递增 retryCount
JudgeTask retry = new JudgeTask(
        task.sessionId(), task.questionId(), task.answer(),
        task.followUp(), task.retryCount() + 1);
```

#### 文件 6：`InterviewAiFacade.judge` / `chatJudge`（提示词与 singleflight key）

**两处都要带 `followUp`：** ① 提示词里标明"这是对同一道题的追问回答"，让 AI 结合原题评分与追问目标综合评估；初次作答保持原提示词不变，避免影响既有评分口径。② singleflight key 也要带上 `followUp`——否则"初次评分"和"追问评分"会因为 `sessionId + questionId + answer` 相同而被合并成同一个结果。

```java
// chatJudge：按 followUp 追加一句区分说明
String prompt = skillPrompt("interview-scoring")
        + "你是严格的技术面试官。"
        + "请用 JSON 返回 score(0-100) 和 feedback。"
        + "按正确性、完整性、深度、表达综合评分。"
        + (followUp
                ? "这是对同一道题的追问回答，请结合原题评分与追问目标综合评估。"
                : "")
        + ...;

// judge：key 里带上 followUp，避免两类评分被 Singleflight 合并
var key = "judge:" + sessionId + ":" + questionId
        + ":" + answer.hashCode()
        + ":" + followUp;
```

#### 文件 7：`InterviewWorkflowService.judge` 的追问早返回分支

**为什么必须在最前面判掉：** 一旦往下走到 `machine.advance(JUDGE_DONE)`，同一道题就会被推进两次，题序错乱且会被计入两道题。所以 `followUp=true` 时只做"AI 评分 → 落库 → 返回结果"，**完全不碰主线状态机、快照 CAS 和追问任务发布**（追问的追问不再生成）。初次作答则正常推进 `JUDGE_DONE`，并按分数调 `followUpPublisher.publish(...)` 把追问任务丢进队列。

```java
public WorkflowResult judge(UUID sessionId, UUID questionId, String answer, boolean followUp) {
    // 追问作答早返回分支
    if (followUp) {
        // 追问评分复用同一条 judge 链路，只是提示词里标明"这是追问回答"。
        var score = ai.judge(sessionId, questionId, answer, true);
        // 幂等回放：is_follow_up=1 那一行已有分数就直接回放，避免重复调 AI、重复写库。
        var persisted = answers.findJudge(sessionId, questionId, true);
        if (persisted != null) { /* 只读快照状态用于返回，不做 CAS 写回 */ }
        // 追问评分写回 is_follow_up=1 那一行，绝不覆盖初次评分。
        answers.recordJudge(sessionId, questionId, score, true);
        // 到此结束：不推进状态机、不写快照、不发布追问任务。
        return new WorkflowResult(..., false);
    }
    // 初次作答：ai.judge(..., false) → recordJudge(..., false) → 推进 JUDGE_DONE
    // → followUpPublisher.publish(sessionId, questionId, score) 把追问任务丢进队列
    ...
}
```

#### 文件 8：`InterviewController` 的追问查询接口

```java
@GetMapping("/{sessionId}/follow-up")
public FollowUpView followUp(@PathVariable UUID sessionId,
                             @RequestParam UUID questionId)
{
    return followUps.find(sessionId, questionId);
}
```

**为什么返回 `null` 而不是 404：** 没有追问是正常业务分支（分数段决定不追问、或追问任务还在队列里没生成完），返回空响应体（HTTP 200 + 空 body）让前端用一次判空处理两种情况，不需要把"正常"写成"异常"。调用时机是前端提交完原题答案、轮询到状态回到 `QUESTIONING` 之后。

---

### 追问 5：结束面试会话（触发报告生成的前提）

**本小目的：** 在追问完成后或面试终止时，将面试会话状态推进到 `ENDED`。只有处于 `ENDED` 状态的会话，报告模块才会为其生成综合报告。这个步骤是追问模块和报告模块之间的桥梁。

**本小节流程：** 接收 sessionId → 加载当前快照 → 状态机推动到 ENDED → CAS 写入快照 → 同步更新数据库 → 从答案表兜底汇总整场转写文本交给表现分析 → 返回会话视图。

#### 文件：`src/main/java/com/example/interview/application/InterviewController.java`（结束接口）

**为什么需要这个接口：** 面试不能无限进行下去，必须有一个明确的"结束"动作。接口路径是 `POST /api/interviews/{sessionId}/end`（**不是** `/finish`），它做了三件事：① 通过状态机将内存中的会话快照推进到 `ENDED` 状态；② 将 `ENDED` 状态同步到数据库的 `interview_session` 表；③ 从答案表兜底取整场转写文本，交给表现分析。只有完成前两步，报告模块才能正确识别该会话已终结并生成报告。

```java
@PostMapping("/{sessionId}/end")
public SessionView end(
        @PathVariable UUID sessionId
)
{
    // 1. 加载当前快照：从内存缓存中读取会话的当前状态和版本号。
    InterviewSessionSnapshot oldValue = snapshots.load(sessionId);

    // 2. 状态机推进：根据当前状态和 END 事件，计算下一个状态。
    //    InterviewEvent.END 是状态机中定义的事件枚举，第三个参数是 requestId，用于幂等判断。
    InterviewSessionStateMachine.TransitionResult result =
            machine.advance(oldValue, InterviewEvent.END, UUID.randomUUID().toString());

    // 3. CAS 写入快照：只有"读取时的版本"仍是最新版本时才更新，冲突则抛异常。
    save(oldValue, result.snapshot());

    // 4. 同步数据库状态：将 ENDED 状态写入 interview_session，服务重启也不丢终结状态。
    InterviewSessionEntity entity = sessionMapper.selectById(sessionId.toString());
    if (entity != null) {
        entity.setState(result.snapshot().state().name());
        sessionMapper.updateById(entity);
    }

    // 5. 把整场说话内容交给表现分析。
    //    面试结束时 WebSocket 早已关闭，实时转写上下文已随连接销毁，所以走答案表兜底——
    //    interview_answer.answer 里存的就是每次提交的答案文本（语音答案提交时已转写为文本），
    //    按主键升序拼起来就是整场内容。
    String transcript = answers.loadSessionTranscript(sessionId);
    // analyze 传空串会抛 IllegalArgumentException（它把空文本当成上游没取到内容），
    // 所以这里先判空：整场一句都没答时跳过分析，而不是让接口 500。
    if (transcript != null && !transcript.isBlank()) {
        demeanor.analyze(sessionId, transcript);
    }

    // 6. 返回更新后的会话视图，前端据此触发报告生成。
    return view(sessionId, result.snapshot());
}
```

---

## 十三、报告模块

### 模块主要目的

汇总整场面试的全部数据（岗位信息、每道题目的问答、评分、追问、表现分析），调用 AI 生成一份结构化、可复盘的面试综合报告，并持久化保存供前端展示。这是整个面试流程的"终点"，面试者和面试官都通过这份报告来了解面试结果。

### 模块整体流程

报告生成是一次 10~30 秒的 AI 调用，如果放在 HTTP 线程里同步等待，接口会长时间挂着、还容易被网关超时切断。所以整条链路被拆成"立即受理 + 前端轮询"：`POST` 只把记录置成 `GENERATING` 就返回，真正的组装和 AI 调用丢到独立线程池里跑，前端轮询状态接口直到出现终态。

```text
  面试会话进入 ENDED 状态
     │
     ▼
  前端 POST /api/reports/{sessionId} 触发生成
     │
     ▼
  校验会话归属（只能操作自己的会话）
     │
     ▼
  报告记录状态置为 GENERATING（不存在则先插入一条）
     │
     ▼
  接口立即返回（此时 markdown 必为 null），AI 任务交给异步线程池
     │
     ▼
  ── 以下在异步线程 reportGenerationTaskExecutor 中执行 ──
     │
     ▼
  组装报告上下文字符串（查询题目、答案、追问）
     │
     ▼
  调用 AI（InterviewAiFacade.generateReport）
     │
     ▼
  写回 Markdown 并置 COMPLETED（异常则置 FAILED）
     │
     ▼
  前端轮询 GET /api/reports/{sessionId}/status 直到终态
     │
     ▼
  状态为 COMPLETED 后用 GET /api/reports/{sessionId} 取正文，渲染 Markdown
```

---

### 报告 1：创建报告表、实体和 Mapper

**本小节目的：** 建立报告持久化结构。报告是面试的最终产出物，必须可靠存储。`session_id` 的唯一约束保证一次面试只对应一份报告，后生成的报告会覆盖之前的版本。

**本小节流程：** 设计报告表结构 → 创建 MyBatis-Plus 实体 → 创建 Mapper 接口 → 验证查询和保存逻辑。

#### 文件 1：`interview_report` 表（SQL）

**创建这个表干什么：** 存储 AI 生成的面试综合报告。content 字段使用 longtext 类型以容纳较长的 Markdown 文档。`session_id` 的唯一约束确保每场面试只有一份报告，如果同一场面试多次生成报告，后生成的会覆盖之前的。此外还多了 `status` 和 `updated_at` 两列——因为报告改成异步生成后，必须有个地方存 `GENERATING / COMPLETED / FAILED` 这个中间态，前端才能靠轮询状态判断"还要不要等"。

```sql
-- interview_report：每场面试最终生成一份综合报告。
-- session_id 唯一约束确保一次面试只对应一份报告。
create table if not exists interview_report (
    id          bigint          primary key auto_increment,  -- 自增主键，无业务含义
    session_id  varchar(36)     not null unique,             -- 面试会话 UUID，唯一（一对一的强制约束）
    content     longtext,                                    -- AI 生成的完整报告（Markdown 格式），可以为空（生成中/失败时）
    -- 报告生成状态：GENERATING（生成中）/ COMPLETED（已完成）/ FAILED（生成失败）
    status      varchar(32)     not null default 'GENERATING',
    -- 状态或内容的最后更新时间，随每次 update 自动刷新
    updated_at  timestamp       not null default current_timestamp
                                on update current_timestamp,
    created_at  timestamp       not null default current_timestamp,  -- 首次生成时间
    constraint fk_report_session
        foreign key (session_id) references interview_session (id)
);
```

> **旧库迁移：** 如果库里已经有这张表，需要执行一次 `ALTER TABLE interview_report ADD COLUMN status ... , ADD COLUMN updated_at ...`，否则实体里的 `status`、`updatedAt` 读回来永远是 null。

#### 文件 2：`src/main/java/com/example/interview/infrastructure/mysql/ReportEntity.java`

**创建这个文件干什么：** `interview_report` 表对应的 MyBatis-Plus 实体类。这里在原来 `id / sessionId / content / createdAt` 的基础上新增了 `status` 字段——异步生成后前端不再等一个长连接，只能靠轮询状态判断"还要不要等"，状态不映射到实体，查询回来的永远是 null。`updatedAt` 用于记录状态或内容的最后更新时间。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * interview_report 表的 MyBatis-Plus 实体。
 *
 * <p>一场面试最终只生成一份报告。如果多次调用生成接口，
 * content 会被覆盖更新，但 session_id 保持不变。</p>
 */
@TableName("interview_report")
public class ReportEntity {

    /** 自增主键，无业务含义。 */
    private Long id;

    /** 面试会话 UUID。唯一约束保证一次面试只对应一份报告。 */
    private String sessionId;

    /** AI 生成的完整 Markdown 报告内容。可以为 null（生成中或失败时的中间状态）。 */
    private String content;

    /**
     * 报告生成状态：GENERATING（生成中）/ COMPLETED（已完成）/ FAILED（生成失败）。
     *
     * <p>为什么必须落到实体上：异步生成后前端只能靠轮询状态判断"还要不要等"，
     * 状态不映射到实体，查询回来的就永远是 null。</p>
     */
    private String status;

    /**
     * 状态或内容的最后更新时间，由数据库在每次 update 时自动刷新。
     *
     * <p>该列已在 docker/mysql/01-schema.sql 的建表语句中补上，
     * 旧库需要执行一次 ALTER TABLE 迁移，否则这个字段读回来永远是 null。</p>
     */
    private LocalDateTime updatedAt;

    /** 报告的首次创建时间，由数据库自动写入。 */
    private LocalDateTime createdAt;

    // ---- getters / setters ----

    public Long getId() { return id; }

    public void setId(Long id) { this.id = id; }

    public String getSessionId() { return sessionId; }

    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getContent() { return content; }

    public void setContent(String content) { this.content = content; }

    // status 的读写方法。MyBatis-Plus 默认按方法名反解列名（getStatus → status），
    // 少了这两个方法，状态列查出来也不会被赋进对象。
    public String getStatus() { return status; }

    public void setStatus(String status) { this.status = status; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public LocalDateTime getCreatedAt() { return createdAt; }

    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
```

#### 文件 3：`src/main/java/com/example/interview/infrastructure/mysql/ReportMapper.java`

**创建这个文件干什么：** MyBatis-Plus 的 Mapper 接口。继承 `BaseMapper<ReportEntity>` 后自动获得 CRUD 能力。报告模块的 `ReportController` 通过它查询和保存报告。

```java
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * interview_report 表的 MyBatis-Plus Mapper。
 *
 * <p>继承 BaseMapper 后自动获得以下方法（无需手动编写 SQL）：
 * <ul>
 *   <li>insert(ReportEntity) —— 插入新报告</li>
 *   <li>selectById(Serializable) —— 按主键查询</li>
 *   <li>updateById(ReportEntity) —— 按主键更新</li>
 *   <li>selectOne(QueryWrapper) —— 按条件查询单条记录（配合 eq("session_id", id)）</li>
 * </ul>
 * </p>
 */
@Mapper
public interface ReportMapper extends BaseMapper<ReportEntity> {
}
```

---

### 报告 2：准备报告上下文

**本小节目的：** 将分散在多个表和状态机快照中的面试数据，整理为 AI 可以理解的、结构化的报告输入字符串。这是调用 AI 前最关键的一步——上下文的质量直接决定报告的质量。

**本小节流程：** 查询所有题目（按序号排序）→ 查询所有答案（含评分和反馈）→ 查询所有追问记录 → 按题目 ID 把答案和追问挂到对应题目下 → 组装为一段完整的上下文文本 → 处理可能的数据缺失情况。

报告生成前，需要读取以下数据：

```text
1. 题目列表及每题内容（interview_question）：面试中问了哪些问题，按 sequence_no 升序
2. 用户的每题答案（interview_answer）：候选人是如何回答的
3. 每道题的评分和 AI 反馈（interview_answer）：评分模块的输出
4. 追问记录（interview_follow_up）：追问模块的输出，展示 AI 针对薄弱点进行了哪些深入考察
```

注意：当前实现只查这三张表（题目、答案、追问），并没有单独读取岗位信息和语音表现分析——上下文以"逐题的题目→回答→追问"为主干，答案行里已经带了 `score` 和 `feedback`。如果想补岗位/表现维度，需要在这份实现里自行扩展查询。

将这些内容拼成一个上下文字符串传给 AI 的 `generateReport` 方法。重点：**不要只传最后一道题**，报告需要基于整场面试的全量数据。

**为什么单独抽一个 `InterviewContextAssembler` 服务来组装上下文（而不是在 Controller 里拼接）：**
- **职责分离**：上下文组装是独立的业务逻辑，不该和 HTTP 接口耦合
- **可测试性**：可以单独给组装器写单元测试，不依赖 Controller
- **可复用**：后续如果要"预览上下文"或"调试报告输入"，可以单独调用这一个方法

#### 文件 1：`src/main/java/com/example/interview/application/InterviewContextAssembler.java`

**创建这个文件干什么：** 上下文组装器的"门面"。它只负责对外暴露一个 `assemble(UUID sessionId)` 方法，真正的数据查询委托给 `InterviewReportDataRepository`。这样设计的好处是：组装器不关心数据从哪里来（MySQL、Mongo 还是将来迁移的 ES），数据读取逻辑被接口隔离，变化时不影响组装器。

```java
package com.example.interview.application;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 面试报告上下文组装器。
 *
 * 职责：将面试过程中的所有数据（题目、答案、评分、追问）组装成
 * AI 可以理解的上下文文本，用于报告生成。
 *
 * 为什么设计为委派模式（delegate to InterviewReportDataRepository）：
 * 数据查询逻辑可能会变（比如从 MySQL 迁移到 ES），
 * 通过接口隔离变化，组装器不需要知道数据来源。
 */
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
```

#### 文件 2：`src/main/java/com/example/interview/application/InterviewReportDataRepository.java`

**创建这个文件干什么：** 报告数据查询的抽象契约。业务层依赖这个接口而不是具体的 Mapper，遵循依赖倒置原则。后续如果要从 MySQL 迁移到其他存储，只需要新增一个实现类，业务调用方完全不用改。

```java
package com.example.interview.application;

import java.util.UUID;

/**
 * 面试报告数据查询接口。
 *
 * 为什么是接口而不是具体类：
 * 遵循依赖倒置原则，业务层依赖抽象。
 * 具体实现可以是 MyBatis-Plus Mapper、JPA Repository 或 JDBC Template。
 */
public interface InterviewReportDataRepository {
    /**
     * 加载面试上下文数据。
     *
     * @param sessionId 面试会话 ID
     * @return 格式化的上下文字符串
     */
    String loadContext(UUID sessionId);
}
```

#### 文件 3：`src/main/java/com/example/interview/application/InterviewReportDataRepositoryImpl.java`

**创建这个文件干什么：** 报告数据查询的实现。它把"题目、答案、追问"三张表的数据查询出来、按序号/时间排序，再拼接成一段结构化的上下文字符串。这一段字符串就是喂给 AI 的最终报告输入。

```java
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

/**
 * InterviewReportDataRepository 的 MyBatis-Plus 实现。
 *
 * 查询逻辑：
 * 1. 查询会话下所有题目（按序号排序）
 * 2. 查询会话下所有答案（按时间排序）
 * 3. 查询会话下所有追问（按时间排序）
 * 4. 拼接为统一的上下文文本
 */
@Repository
public class InterviewReportDataRepositoryImpl
        implements InterviewReportDataRepository {

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

        // 把三份数据拼接成 AI 可读的 Markdown 上下文字符串。
        // 每个小题都用二级标题（##）分隔，AI 能清楚区分"题目→回答→追问"
        // 之间的从属关系，生成的报告结构才会清晰。
        StringBuilder sb = new StringBuilder("以下是本场面试的完整数据，请据此生成报告：\n");

        for (int i = 0; i < questionList.size(); i++) {
            QuestionEntity question = questionList.get(i);
            sb.append("## 第 ").append(i + 1).append(" 题\n")
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
```

> **关于 `sessionId` 的 String 拼接建议：** 上面实现里题目/答案/追问都约定用 `sessionId` 的字符串形式做关联查询。如果这三张表中的会话字段存的是 `varchar(36)`（受 UUID 的 `toString()` 影响），务必统一用 `sessionId.toString()`，不要一会儿传 `UUID` 一会儿传 `String`，否则会查不到数据。真正的"查询接口、字段名以项目实体为准"。

> **关于追问内容怎么拼：** 实现里只取 `f.getContent()` 拼成一行"追问：…"。**不要读 `f.getChain()`**——`interview_follow_up` 表里根本没有 `chain` 列（真实列只有 `id / session_id / question_id / score / level / content / created_at`），`FollowUpEntity` 里的 `chain` 字段是标了 `@TableField(exist = false)` 的纯内存字段。一旦在查询条件里用到它，MyBatis-Plus 生成的 SQL 会带上 `chain` 列，直接报 `Unknown column 'chain'`。

---

### 报告 3：编写报告 AI 方法

**本小节目的：** 在 `InterviewAiFacade` 中增加 `generateReport` 方法，调用 AI 生成结构化、可读、可复盘的面试总结报告。固定输出格式（Markdown 的固定章节标题）便于前端渲染和将来转 PDF。

**本小节流程：** 先拼上报告 Skill（`skillPrompt("report-review")`）→ 组装 sessionId + context → 用 `excerpt` 截断超长 context → 调用 AI → 返回 Markdown 字符串。

**开始本节前先确认：** `src/main/java/com/example/interview/ai/InterviewAiFacade.java` 已经创建并持有一个 `ChatClient` 实例（该 Facade 的创建过程在"十四、表现分析模块"之前已完成）。本节只是向同一个 Facade 增加 `generateReport` 方法，**不是新建一个独立的 AI 客户端**。

**为什么是追加到 InterviewAiFacade 而不是新建一个类：** `InterviewAiFacade` 是整个面试系统与 AI 对话的唯一入口（门面模式）。所有 AI 调用——包括出题、评分、追问、报告生成、表现分析——都通过这个 Facade 转发到 `ChatClient`。这样做的优点是：① AI 客户端配置（模型、温度、超时）集中管理；② 切换 AI 供应商时只需修改这一个类。

**为什么提示词最前面要拼 `skillPrompt("report-review")`：** 出题 / 评分 / 报告 / 追问四条 AI 调用各有自己的业务约束，这些约束以 Skill 的形式放在 `resources/skills` 下，由 `SkillRegistry` 启动时统一加载。报告这一条对应 `report-review`，调用前先把 Skill 正文用「【业务 Skill：名称】…【业务 Skill 结束】」包起来拼在提示词最前面，AI 才会优先遵守这些规则。

**为什么 context 要用 `excerpt(context, 12000)` 截断：** 整场面试的题目、回答、追问拼起来可能非常长，直接塞进提示词会超出模型输入长度限制，所以在拼接前先截断到 12000 个字符。

#### 文件（追加到 `InterviewAiFacade.java`）：

```java
/**
 * 生成面试综合报告。
 *
 * <p>该方法先拼上报告 Skill（report-review），再把面试上下文嵌入固定报告结构提示词中，
 * 要求 AI 返回标准 Markdown。报告包含以下四个固定章节：
 * <ol>
 *   <li>总体评价 —— 概括性总结</li>
 *   <li>逐题点评 —— 针对每道题的问答与评分展开点评</li>
 *   <li>能力维度分析 —— 按能力维度归纳候选人表现</li>
 *   <li>改进建议 —— 具体可执行的提升建议</li>
 * </ol>
 * 固定标题的好处：前端可以直接将 Markdown 转 HTML 渲染，也便于将来转换成 PDF。
 * </p>
 *
 * @param sessionId 面试会话 UUID，嵌入提示词中供 AI 引用
 * @param context   从报告 2 步骤中组装的完整面试数据上下文（超长时用 excerpt 截断）
 * @return 纯 Markdown 格式的面试报告（不包含 JSON 包装、代码围栏或额外解释）
 */
public String generateReport(UUID sessionId, String context) {
    // 先把报告 Skill（report-review）拼在文本块前面，再交给 chat。
    return chat(skillPrompt("report-review") + "\n" + """
            你是一名资深技术面试官，需要为一场已经结束的面试撰写总结报告。
            请只依据给出的面试数据作出判断，不要编造数据中不存在的经历或结论。
            必须使用 Markdown 格式输出，并严格包含以下四个章节：
            ## 一、总体评价
            ## 二、逐题点评
            ## 三、能力维度分析
            ## 四、改进建议
            只返回 Markdown 正文，不要返回 JSON，不要附加额外解释。
            sessionId：%s
            面试数据：
            %s
            """.formatted(sessionId, excerpt(context, 12000)));
}
```

---

### 报告 4：编写报告接口

**本小节目的：** 向前端提供**三个** REST 接口——触发生成、轮询状态、读取正文，并确保每次面试只产生一份报告（存在则重置重新生成、不存在则先插入），同时保证"只能看自己的报告"。

**本小节流程：** 接收 sessionId → 校验会话归属（`assertOwner`）→ 查询或创建报告记录并置为 `GENERATING` → 立即返回 → 异步线程组装上下文、调用 `generateReport`、写回正文并置终态 → 前端轮询 `status` → `COMPLETED` 后用 `GET` 取正文。

**为什么拆成三个接口：** AI 生成一次报告要 10~30 秒，`POST` 若在 HTTP 线程里同步等 AI，接口会长时间挂着、容易被网关超时切断。所以 `POST` 只做"校验 + 置状态 + 丢任务"就立即返回；前端拿到 `GENERATING` 后轮询 `GET /status`，直到 `COMPLETED`（拿正文渲染）或 `FAILED`（提示重试）；`GET /{sessionId}` 则是状态完成后专门取正文的接口。

#### 文件 1：`src/main/java/com/example/interview/application/ReportController.java`

**创建这个文件干什么：** 这是报告模块的 REST 控制器，对外暴露三个接口：
- `POST /api/reports/{sessionId}` —— 触发生成（幂等：已存在就把状态重置回 `GENERATING` 并重新生成，不存在则先插一条）
- `GET /api/reports/{sessionId}/status` —— 轮询生成状态
- `GET /api/reports/{sessionId}` —— 状态完成后取正文

控制器依赖 `ReportMapper`（读写报告记录）、`ReportGenerationAsyncService`（异步生成）、`InterviewSessionMapper`（查会话归属）和 `CurrentUserIdProvider`（从 JWT 解析当前用户）。三个接口进门第一句都是 `assertOwner(sessionId)`：先按 sessionId 查会话，再把会话的 `userId` 与当前登录用户比对，不相等直接抛 `AccessDeniedException`——报告里有面试问答原文，不校验就能靠换 sessionId 读到别人的报告。注意 `ReportView` 是 record，**只有三个字段**：`sessionId / status / markdown`（`markdown` 只在 `COMPLETED` 时才有值）。

```java
package com.example.interview.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * 报告 Controller。
 *
 * 提供报告生成、状态查询和内容获取接口。
 * 生成操作是异步的，POST 接口立即返回，结果通过状态接口轮询获取。
 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    // 报告视图：把"状态 + 内容"打包一起返回前端。
    // markdown 只在 status=COMPLETED 时才有值，其它状态下是 null，
    // 前端据此决定是"直接渲染报告"还是"继续轮询 /status"。
    public record ReportView(
            UUID sessionId,
            String status,
            String markdown
    ) {
    }

    // 报告表 Mapper：查询 / 插入 / 更新 report 记录。
    private final ReportMapper reports;
    // 报告生成异步服务：generate 上带 @Async，调用即返回，不占用 HTTP 线程。
    private final ReportGenerationAsyncService generation;
    // 会话表 Mapper：只用来查会话归属，做"这份报告是不是你的"权限校验。
    private final InterviewSessionMapper sessions;
    // 当前登录用户提供者：从 JWT 解析出 userId，供所有者校验使用。
    private final CurrentUserIdProvider currentUser;

    // 构造器注入四个协作者，字段全是 final，Controller 保持无状态、线程安全。
    public ReportController(
            ReportMapper reports,
            ReportGenerationAsyncService generation,
            InterviewSessionMapper sessions,
            CurrentUserIdProvider currentUser
    ) {
        this.reports = reports;
        this.generation = generation;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    /**
     * 触发报告生成（异步）。
     *
     * <p>这里只做三件事：校验归属、把记录状态置成 GENERATING、把任务丢给
     * ReportGenerationAsyncService。真正的上下文组装和 AI 调用在独立线程池里跑，
     * 接口不会被几十秒的模型耗时拖住，所以是"立即受理 + 前端轮询进度"的模型。
     *
     * <p>为什么要"先查再插"而不是无脑 insert：用户在生成过程中重复点"生成报告"
     * 时，POST 应当幂等——报告记录按 session_id 唯一，已存在就把状态和内容重置
     * 后重新生成，而不是插出第二条重复记录。
     *
     * @param sessionId 面试会话 ID
     * @return 刚创建 / 重置后的报告视图，此时 markdown 一定为 null
     */
    @PostMapping("/{sessionId}")
    public ReportView generate(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 权限校验放在 Controller 层：异步线程里没有请求上下文，
        // 取不到当前用户，所以必须在这里先把"是不是本人会话"挡掉。
        assertOwner(sessionId);
        // 按 session_id 查报告记录：报告与会话一对一，查出来就是"当前这份报告"。
        ReportEntity entity = reports.selectOne(
                new QueryWrapper<ReportEntity>()
                        // 用字符串比较：库里 session_id 存的是 varchar，不是二进制 UUID。
                        .eq("session_id", sessionId.toString())
        );
        if (entity == null) {
            // 从这里开始是新增的：并发安全地创建报告记录。
            // 为什么会并发：报告页一进入会同时触发两次 loadReport（useEffect 一次、
            // finishInterview() 里再 await 一次），两处都会 POST /reports；后端又是
            // "先查再插"，两个线程可能都先查到"无记录"，随后都想 insert 一条。
            // unique(session_id) 只允许一条成功，另一条会抛 DuplicateKeyException，
            // 让接口直接 500 —— 前端把这次 500 静默吞掉后继续轮询，界面就一直停在
            // "正在生成"出不来。所以这里把创建包起来：插入冲突就回查出已存在的记录，
            // 统一走下面的"重置状态 + 重新生成"分支，保证并发触发幂等、绝不 500。
            entity = new ReportEntity();
            entity.setSessionId(sessionId.toString());
            entity.setStatus("GENERATING");
            try {
                reports.insert(entity);
            } catch (DuplicateKeyException duplicate) {
                // 另一并发请求已抢先插入：把这条记录重新查出来继续走生成；
                // 若查不到（行被并发删除的极端情况），恢复抛出，避免拿着 null 往下走到 NPE。
                entity = reports.selectOne(
                        new QueryWrapper<ReportEntity>()
                                .eq("session_id", sessionId.toString())
                );
                if (entity == null) {
                    throw duplicate;
                }
                entity.setStatus("GENERATING");
                entity.setContent(null);
                reports.updateById(entity);
            }
            // 新增结束
        } else {
            // 重新生成：状态回到 GENERATING，同时清空旧内容，
            // 避免前端在生成过程中还翻到上一版报告。
            entity.setStatus("GENERATING");
            entity.setContent(null);
            reports.updateById(entity);
        }
        // 触发异步生成：@Async 让这次调用立刻返回，HTTP 线程不等待 AI。
        generation.generate(sessionId, entity.getId());
        // 同步返回"已受理"的状态，此刻 content 必然是 null。
        return new ReportView(
                sessionId,
                entity.getStatus(),
                entity.getContent()
        );
    }

    /**
     * 查询报告生成状态。
     *
     * <p>前端在发起生成后轮询这个接口，直到 status 变成 COMPLETED 或 FAILED。
     * 它只读记录、不触发任何计算，所以很轻，可以承受高频轮询。
     *
     * @param sessionId 面试会话 ID
     * @return 当前状态（GENERATING / COMPLETED / FAILED）与已完成的内容
     */
    @GetMapping("/{sessionId}/status")
    public ReportView status(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 同样先校验归属：状态接口也不能让用户查到别人的报告。
        assertOwner(sessionId);
        ReportEntity entity = find(sessionId);
        // 状态和内容一起返回：COMPLETED 时前端能一把拿到正文，省掉再调一次 get。
        return new ReportView(
                sessionId,
                entity.getStatus(),
                entity.getContent()
        );
    }

    /**
     * 查询报告内容。
     *
     * <p>为什么内容未完成时要返回 null、而不是把 GENERATING 的半成品吐出去：
     * content 只有异步任务写完全才会落库，没完成就返回空，
     * 前端看到 null 就知道该去轮询 /status，而不是当成"报告就是空的"。
     *
     * @param sessionId 面试会话 ID
     * @return COMPLETED 时返回正文，否则 markdown 为 null
     */
    @GetMapping("/{sessionId}")
    public ReportView get(
            @PathVariable("sessionId") UUID sessionId
    ) {
        // 权限校验：报告属于会话，会话属于用户，不能跨用户读取。
        assertOwner(sessionId);
        ReportEntity entity = find(sessionId);
        if (!"COMPLETED".equals(entity.getStatus())) {
            // 还没生成完：只回状态、不回内容，避免前端拿到半截数据。
            return new ReportView(
                    sessionId,
                    entity.getStatus(),
                    null
            );
        }
        // 已完成：把 Markdown 正文一起返回。
        return new ReportView(
                sessionId,
                entity.getStatus(),
                entity.getContent()
        );
    }

    // ---- 私有方法 ---- //

    /**
     * 按会话 ID 查出报告记录，查不到直接抛异常。
     *
     * <p>把"查不到"统一收敛在这里，status / get 两个接口就不用各自写一遍判空；
     * 抛 IllegalArgumentException 是为了交给全局异常处理器映射成 404。
     *
     * @param sessionId 面试会话 ID
     * @return 该会话对应的报告记录
     */
    private ReportEntity find(UUID sessionId) {
        ReportEntity entity = reports.selectOne(
                new QueryWrapper<ReportEntity>()
                        .eq("session_id", sessionId.toString())
        );
        if (entity == null) {
            // 记录不存在：可能是 sessionId 编造的，按"资源不存在"处理。
            throw new IllegalArgumentException(
                    "report not found"
            );
        }
        return entity;
    }

    /**
     * 校验当前用户是否为会话的所有者。
     *
     * <p>为什么必须有这一步：报告里有面试问答原文，属于敏感数据，
     * 只要把 sessionId 换成别人的就能读到别人的报告。
     * 所以三个接口进门第一句都是 assertOwner。
     *
     * <p>为什么从 CurrentUserIdProvider 取用户而不是从请求参数取：
     * 请求参数里的 userId 可以伪造，只有 JWT 过滤器验签后写入的才是可信的。
     *
     * @param sessionId 要操作的面试会话 ID
     */
    private void assertOwner(UUID sessionId) {
        InterviewSessionEntity session =
                sessions.selectById(sessionId.toString());
        if (session == null) {
            // 会话都不存在，说明 sessionId 非法，按"资源不存在"处理。
            throw new IllegalArgumentException(
                    "interview session not found"
            );
        }
        // session.getUserId() 是 Long，与 currentUserId() 的 long 比较会自动拆箱做数值比较
        // （不是对象引用比较），所以这里不存在"超出 Long 缓存范围就判错"的问题。
        if (session.getUserId() != currentUser.currentUserId()) {
            // 会话属于别人：拒绝访问，由 Spring Security 统一映射成 403。
            throw new AccessDeniedException(
                    "session does not belong to current user"
            );
        }
    }
}
```

#### 文件 2：`src/main/java/com/example/interview/application/ReportGenerationAsyncService.java`

**创建这个文件干什么：** 真正干活的异步服务。`POST` 接口把任务丢进来后，它在独立线程池 `reportGenerationTaskExecutor`（核心 2 / 最大 4 / 队列 30）里组装上下文、调 AI、写回正文并置 `COMPLETED`；任何异常都置 `FAILED` 并记日志。之所以把 `@Async` 放在服务方法上而不是 Controller 方法上：Controller 方法身上挂着请求上下文（SecurityContext、请求属性），切线程后会丢，这里只接收 `sessionId` 和 `reportId` 两个纯净参数，异步线程不需要任何请求上下文。显式写线程池名是为了不让 Spring 退回默认的 `SimpleAsyncTaskExecutor`（每次新建线程、不复用，长任务跑几次就把机器拖垮）。

```java
package com.example.interview.application;

import com.example.interview.ai.DemeanorAnalysisService;
import com.example.interview.ai.InterviewAiFacade;
import com.example.interview.infrastructure.mysql.ReportEntity;
import com.example.interview.infrastructure.mysql.ReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.UUID;

// ============================================================
// 报告生成异步服务
// ============================================================
// 为什么把 @Async 放在服务方法上，而不是直接放在 Controller 方法上：
//   1. Controller 方法身上挂着请求上下文（SecurityContext、请求属性），切线程后会丢；
//      这里只接收 sessionId 和 reportId 两个纯净参数，异步线程不需要任何请求上下文；
//   2. 异步逻辑可以单独测：直接 new 出来调 generate，不必经过 HTTP。
// 为什么用独立线程池 reportGenerationTaskExecutor（AsyncConfig 里已配好）：
//   报告生成 = "拼一段长文本 + 一次 10~30 秒的 AI 调用"，和评分、语音转写混用同一个池
//   会互相排队；单独一个池（核心 2 / 最大 4 / 队列 30）能把这种长尾调用隔离开。
// 状态约定：成功写 COMPLETED，失败写 FAILED——前端只认这两个终态。
// ============================================================
@Service
public class ReportGenerationAsyncService {

    // 日志对象：异步线程里抛出的异常不会回到请求线程，失败只能靠日志留痕。
    private static final Logger log =
            LoggerFactory.getLogger(
                    ReportGenerationAsyncService.class
            );

    // 报告表 Mapper：读记录、写回正文与状态。
    private final ReportMapper reports;
    // 上下文组装器：把整场面试整理成一段 AI 可读的文本。
    private final InterviewContextAssembler contexts;
    // AI 门面：generateReport 内部会截断超长上下文，并复用统一的 chat 调用。
    private final InterviewAiFacade ai;
    // 表现分析：报告生成时对整场作答文本算一次"语言表达分析"，并作为固定章节拼进报告。
    // 为什么放在报告链路而不是放在"结束面试"接口：
    //   1) 结束接口是同步返回的，而分析是一次短则几秒、长可数十秒的 AI 调用，放进异步报告里不阻塞用户；
    //   2) 分析结果天然属于"复盘材料"，和报告同生共死——报告重新生成时分析随之刷新，永远保持一致。
    private final DemeanorAnalysisService demeanor;
    // 答案仓储：取整场作答文本（interview_answer.answer，语音答案提交时已转写成文本）。
    private final AnswerRepository answers;

    // 五个协作者全部构造器注入，字段 final：异步线程读到的依赖在装配期就固定了，不会中途被换掉。
    public ReportGenerationAsyncService(
            ReportMapper reports,
            InterviewContextAssembler contexts,
            InterviewAiFacade ai,
            DemeanorAnalysisService demeanor,
            AnswerRepository answers
    ) {
        this.reports = reports;
        this.contexts = contexts;
        this.ai = ai;
        this.demeanor = demeanor;
        this.answers = answers;
    }

    /**
     * 异步生成报告：组装上下文 → 调 AI → 写回正文并置 COMPLETED；任何异常置 FAILED。
     *
     * <p>为什么入参是 sessionId + reportId 两个：sessionId 用来查数据，reportId 用来定位
     * 要更新的那一行。记录是 Controller 那边建好的，这里只负责"填内容 + 改状态"。</p>
     *
     * @param sessionId 面试会话 ID
     * @param reportId  报告记录的主键 ID
     */
    // 显式指定线程池名。不指定的话 Spring 会退回默认的 SimpleAsyncTaskExecutor——
    // 它每次调用新建一个线程且不复用，报告生成这种长任务跑几次就能把机器拖垮。
    @Async("reportGenerationTaskExecutor")
    public void generate(UUID sessionId, Long reportId) {
        // 第一步：按主键取报告记录。取不到就直接返回——正常不会发生（Controller 刚插过），
        // 但记录被并发删除时，若继续往下走就会在 entity.setStatus 处抛 NPE，
        // 而 NPE 发生在 try 之外，日志里什么都留不下。所以先挡一道。
        ReportEntity entity = reports.selectById(reportId);
        if (entity == null) {
            return;
        }

        try {
            // 第二步：组装上下文。查题目、答案、追问三张表并拼成一段文本，
            // 具体怎么查都在实现类里，这里只拿结果字符串。
            String context = contexts.assemble(sessionId);

            // 第三步：调 AI 生成 Markdown 报告。
            // 为什么把 context 传进去，而不是让 AI 自己去查库：AI 没有、也不该有数据库访问权限，
            // 它只能看到我们喂给它的那段文本；输入完全可控，报告出错时也容易复现。
            String markdown = ai.generateReport(
                    sessionId,
                    context
            );

            // 第三步半：把表现分析作为固定章节追加到 AI 报告正文末尾。
            // 为什么用"追加"而不是交给 AI 生成：分析结果已在 DemeanorAnalysisService 里算好、
            // 结构固定且要保证每一次都出现，不依赖 AI 是否记得写这一节。
            // 单独 try-catch：分析失败只丢这一段，绝不能把整份报告拖成 FAILED。
            markdown = appendDemeanor(markdown, sessionId);

            // 第四步：写回正文并按"完成"落状态。
            // 正文和状态必须在同一次 updateById 里写下去：只写正文不改状态，
            // 前端会一直以为还在生成中，轮询永远不结束。
            entity.setContent(markdown);
            entity.setStatus("COMPLETED");
            reports.updateById(entity);

        } catch (Exception e) {
            // 失败也必须落状态：前端靠 FAILED 显示"生成失败，请重试"。
            // 不写的话记录会永远停在 GENERATING，用户看到的是一直转圈。
            entity.setStatus("FAILED");
            reports.updateById(entity);
            // 异常本身一定要记日志。异步线程的堆栈不会冒泡到任何请求里，
            // 不记日志就无法区分到底是 AI 超时、还是上下文组装时查库失败。
            log.error(
                    "report generation failed, sessionId={}",
                    sessionId,
                    e
            );
        }
    }

    /**
     * 把表现分析章节拼到报告正文末尾；分析失败时静默跳过并返回原正文。
     *
     * <p>为什么单独 try-catch：analyze 是又一次 AI 调用，可能因为整场没作答（transcript 为空，
     * 会被判为"没内容"）而跳过，也可能因上游瞬时失败而抛异常。表现分析只是报告的加分项，
     * 它失败不该让主流程辛苦生成好的整份报告一起变 FAILED，所以这里兜住并只记一条 warn。
     *
     * @param markdown  AI 生成的报告正文
     * @param sessionId 面试会话 ID，用于取整场作答文本
     * @return 追加了表现分析章节的正文；分析不可用时原样返回
     */
    private String appendDemeanor(
            String markdown,
            UUID sessionId
    ) {
        try {
            String transcript = answers.loadSessionTranscript(sessionId);
            if (transcript == null || transcript.isBlank()) {
                return markdown;
            }
            var analysis = demeanor.analyze(sessionId, transcript);
            return markdown + "\n\n" + demeanor.toMarkdown(analysis);
        } catch (Exception e) {
            log.warn(
                    "demeanor analysis skipped, sessionId={}",
                    sessionId,
                    e
            );
            return markdown;
        }
    }
}
```

接口调用流程全览：

```text
  前端 POST /api/reports/{sessionId}（无请求体）
     │
     ▼
  ReportController.generate()
     │
     ├─ 1. assertOwner(sessionId)
     │       └─ 查会话归属，非本人会话 → AccessDeniedException(403)
     │
     ├─ 2. reports.selectOne(session_id = sessionId)
     │       ├─ 不存在：insert 一条 status=GENERATING
     │       └─ 已存在：status=GENERATING、content=null，updateById
     │
     ├─ 3. generation.generate(sessionId, entity.id)  ← @Async，立即返回
     │
     └─ 4. 返回 ReportView(sessionId, "GENERATING", null)

  ── 异步线程 reportGenerationTaskExecutor ──
  ReportGenerationAsyncService.generate()
     │
     ├─ 1. reports.selectById(reportId)（取不到直接返回）
     │
     ├─ 2. contexts.assemble(sessionId) → 上下文字符串
     │
     ├─ 3. ai.generateReport(sessionId, context) → Markdown
     │
     └─ 4. setContent(markdown) + setStatus("COMPLETED") + updateById
             └─ 异常：setStatus("FAILED") + updateById + log.error

  前端轮询 GET /api/reports/{sessionId}/status
     │
     ├─ status=GENERATING → 继续轮询
     ├─ status=COMPLETED  → 用 GET /api/reports/{sessionId} 取正文渲染
     └─ status=FAILED     → 提示"生成失败，请重试"
```

## 十四、表现分析模块

### 模块主要目的

分析面试者回答时的语言表达表现（而非技术答案正确性），为面试报告补充以下维度的量化评估：
- **语速（pace）**：回答是过快、过慢还是适中
- **停顿与口头禅（fillerCount）**：出现了多少次"嗯"、"啊"、"那个"等填充词
- **表达清晰度（clarityScore）**：语言组织是否条理分明
- **自信度（confidence）**：从语气和措辞判断的自信程度
- **整体反馈（feedback）**：AI 给出的文本改进建议

这个模块**不评价答案的技术正确性**——技术评分由专门的评分模块负责。表现分析只关注"怎么说"，不关注"说什么"。

### 模块整体流程

```text
前端语音识别（阿里云 NLS 实时转写）产生分片文本
    -> WebSocket 分片上报（/ws/interviews/{sessionId}）
    -> AsyncTranscriptionBuffer 按会话隔离缓冲 + 异步消费
    -> TranscriptionSessionContext 按 seg_id 去重排序，合并为完整 transcript
    -> 面试结束：InterviewController.end() 调用 AnswerRepository.loadSessionTranscript(sessionId)
       从答案表按主键升序汇总整场答案文本，得到 transcript
    -> 检查文本非空（整场一句都没答时跳过分析，不浪费 AI 调用）
    -> DemeanorAnalysisService.analyze() 调用 AI 分析表达表现
       （InterviewAiFacade.analyzeDemeanor 组装提示词 -> 发送给大模型）
    -> InterviewAiFacade.parseDemeanor 把 AI 返回的 JSON 解析为 DemeanorAnalysis 结构化对象
    -> 分析结果当前只执行、不落库也不返回（详见"表现 2"）
```

---

### 表现 0：语音识别获取转写文本

**本小节目的：** 把面试者的语音回答实时转成可分析的文本（transcript）。表现分析分析的是"怎么说话"，前提是先把"说了什么"变成文字。本小节实现从麦克风音频到最终转写文本的完整链路：前端采集音频 → 阿里云 NLS 实时语音识别 → 分片通过 WebSocket 上报 → 后端去重合并为最终 transcript。

> **编号说明：** 编号 0 表示它是整条链路的"输入前置步骤"——先有转写文本，才有后续的表现 1（AI 分析）和表现 2（调用方与结果去向）。

**本小节流程：**

```text
前端点击"语音输入"按钮，getUserMedia 采集麦克风音频
    -> 前端打开业务 WebSocket（/ws/interviews/{sessionId}），发 {type:'audio_start'} 通知后端建立 NLS 上行
    -> 前端把降采样后的裸 PCM 以二进制帧持续推给后端（前端不再直连 NLS）
    -> InterviewWebSocketHandler.handleBinaryMessage 收到裸 PCM，交给 AliyunNlsRealtimeRelay.pushAudio() 补 NLS 帧头推流识别
    -> NLS 识别的分片由 AliyunNlsRealtimeRelay 落回 AsyncTranscriptionBuffer.receive() 入队
       （按 sessionId 隔离的 ArrayBlockingQueue，容量 256，满时阻塞 200ms 形成背压）
    -> AsyncTranscriptionBuffer.consume() 在 transcriptionTaskExecutor 线程池异步消费
       -> TranscriptionSessionContext.mergeSegment() 按 seg_id 去重、排序、拼接
       -> 只把 ed=true 的已确认分片写入 transcript，临时结果不污染最终文本
    -> 面试结束时 ReportGenerationAsyncService.generate() 调用 AnswerRepository.loadSessionTranscript(sessionId)
       从答案表汇总整场答案文本，交给 DemeanorAnalysisService.analyze()（见"表现 1"）
```

**为什么选阿里云 NLS（智能语音交互）：**
- 官方支持实时流式识别中文普通话，返回带序号的增量结果——`seg_id/pgs/rg/bg/ed` 这组字段就是 NLS 结果的标准格式
- 识别连接需要短期 Token（有效期 24 小时），由后端统一签发，AccessKey 不出服务器，安全性可控
- 浏览器自带 Web Speech API 各厂商支持不一致、断句不稳定，不能作为生产方案，因此统一改用阿里云 NLS

**文件列表：**

```text
src/main/java/com/example/interview/websocket/AliyunNlsTokenService.java      — 阿里云 NLS Token 签发与缓存（供后端中继使用）
src/main/java/com/example/interview/websocket/AliyunNlsRealtimeRelay.java     — 阿里云 NLS 后端中继：补帧头推流 + 识别分片落回 buffer
src/main/java/com/example/interview/websocket/NioAudioRingBuffer.java         — 音频环形缓冲，用于 NLS 推流读取
src/main/java/com/example/interview/websocket/WebSocketConfig.java            — 注册业务 WebSocket 路由
src/main/java/com/example/interview/websocket/AsyncTranscriptionBuffer.java   — 按会话隔离的异步音频缓冲
src/main/java/com/example/interview/websocket/TranscriptionSessionContext.java— 分片去重合并上下文
src/main/java/com/example/interview/websocket/InterviewWebSocketHandler.java  — WebSocket 消息处理器（文本控制 + 二进制音频转发）
frontend/src/nlsTranscriptionClient.js                                        — 前端纯采集端：降采样成裸 PCM 经业务 WS 推给后端中继
```

---

#### 文件 1：`AliyunNlsTokenService.java`

**文件位置：** `src/main/java/com/example/interview/websocket/AliyunNlsTokenService.java`

**文件作用：** 负责向阿里云 NLS 换取临时 Token 并缓存。Token 只在后端内部使用——`AliyunNlsRealtimeRelay` 建立上行连接时用它鉴权（后端用 `app_key + AccessKeyId + AccessKeySecret` 换取短期 Token）。**当前前端不直连 NLS、也不取 Token**，所以这个类不再承担"把 Token 给前端"的职责，前端拿不到也无需持有任何凭据。`SpeechTokenController` 仍保留 `/api/speech/token` 接口，但只是兜底/调试用，NlsTranscriptionClient 不再调用它。

**依赖关系：**
- 依赖 Spring `RestClient`（Spring Boot 3.3 自动配置 `RestClient.Builder` Bean）
- 配置项在 `application.yml`：`aliyun.nls.app-key` / `aliyun.nls.access-key-id` / `aliyun.nls.access-key-secret`
- 被谁调用：`SpeechTokenController`（暴露给前端）

```java
package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

// ============================================================
// 阿里云 NLS Token 签发服务
// ============================================================
// 职责：向阿里云智能语音交互（NLS）换取实时语音识别用的临时 Token。
//
// 为什么必须走后端换 Token：
//   前端直连阿里云识别需要 AccessKeyId/AccessKeySecret，直接暴露在前端
//   意味着任何拿到页面源码的人都能盗用云账号。正确做法是前端调用本服务
//   获取短期 Token（有效期 24 小时），再用 Token 连接 NLS。
//
// 为什么缓存 Token：
//   每次换 Token 都是一次网络请求，而 Token 有效期长达 24 小时。
//   同一场面试的多道题复用同一个 Token 即可，
//   用 volatile + double-check 保证并发安全的同时避免频繁加锁。
// ============================================================
@Service
public class AliyunNlsTokenService {

    // 阿里云 NLS Token 接口地址（上海地域，全球默认接入点）
    private static final String TOKEN_URL =
            "https://nls-meta.cn-shanghai.aliyuncs.com/oauth/token";

    private final RestClient restClient;
    private final ObjectMapper mapper;
    private final String appKey;
    private final String accessKeyId;
    private final String accessKeySecret;

    // ---- Token 缓存 ----
    // volatile：保证多线程下读到的缓存值是内存中的最新值
    private volatile String cachedToken;
    // 缓存 Token 的过期时间戳（epoch 秒），到达后必须重新换取
    private volatile long expireAt;

    /**
     * 构造器注入所有依赖。
     *
     * @param builder         Spring Boot 自动配置的 RestClient 构造器
     * @param mapper          Jackson JSON 处理器（Spring Boot 自动配置）
     * @param appKey          阿里云 NLS 应用标识（application.yml 配置）
     * @param accessKeyId     阿里云 AccessKey ID（配置注入，严禁硬编码）
     * @param accessKeySecret 阿里云 AccessKey Secret（配置注入，严禁硬编码）
     */
    public AliyunNlsTokenService(
            RestClient.Builder builder,
            ObjectMapper mapper,
            @Value("${aliyun.nls.app-key}") String appKey,
            @Value("${aliyun.nls.access-key-id}") String accessKeyId,
            @Value("${aliyun.nls.access-key-secret}") String accessKeySecret
    ) {
        this.restClient = builder.build();
        this.mapper = mapper;
        this.appKey = appKey;
        this.accessKeyId = accessKeyId;
        this.accessKeySecret = accessKeySecret;
    }

    /**
     * 获取可用的 NLS 临时 Token。
     *
     * <p>先读缓存：缓存未过期直接返回，避免每次识别都发起一次网络请求；
     * 缓存过期或不存在时，加锁后二次检查再换取（double-check），
     * 防止高并发下多个线程同时向阿里云重复换 Token。
     *
     * @return 阿里云 NLS 临时 Token 字符串
     */
    public String getToken() {
        // 快速路径：缓存有效直接返回，不加锁
        if (cachedToken != null
                && expireAt > System.currentTimeMillis() / 1000) {
            return cachedToken;
        }
        // 慢速路径：加锁后再次检查，避免重复换取
        synchronized (this) {
            if (cachedToken != null
                    && expireAt > System.currentTimeMillis() / 1000) {
                return cachedToken;
            }
            refreshToken();
            return cachedToken;
        }
    }

    /**
     * 获取 NLS 应用的 appKey。
     * 前端连接阿里云 NLS WebSocket 时，除了 Token 还需要携带 appKey。
     *
     * @return 配置中的 appKey
     */
    public String getAppKey() {
        return appKey;
    }

    // ============================================================
    // 私有方法：调用阿里云接口换取 Token
    // ============================================================
    // 阿里云返回示例：
    //   {"app_key":"...","token":"...","expire_time":1690000000}
    // 直接把 token 和过期时间写入缓存字段，后续 getToken 直接复用。
    // ============================================================
    private void refreshToken() {
        String body = restClient.get()
                .uri(
                        TOKEN_URL
                                + "?app_key={appKey}"
                                + "&access_key_id={accessKeyId}"
                                + "&access_key_secret={accessKeySecret}",
                        appKey,
                        accessKeyId,
                        accessKeySecret
                )
                .retrieve()
                .body(String.class);

        try {
            JsonNode node = mapper.readTree(body);
            cachedToken = node.path("token").asText();
            expireAt = node.path("expire_time").asLong();
        } catch (Exception error) {
            // 换 Token 失败（网络异常、密钥错误等）不允许静默通过，
            // 直接抛出，让前端提示用户稍后重试。
            throw new IllegalStateException(
                    "cannot acquire aliyun nls token",
                    error
            );
        }
    }
}
```

---

#### 文件 2：`SpeechTokenController.java`

**文件位置：** `src/main/java/com/example/interview/websocket/SpeechTokenController.java`

**文件作用：** 提供获取 NLS 临时 Token 的 REST 接口（返回 `{ token, appKey }`）。在"后端中继"架构下前端不再直连 NLS、也不再调用本接口，Token 由后端的 `AliyunNlsRealtimeRelay` 内部使用；本接口仅是后端复用 Token 服务的调试/兜底入口。

**依赖关系：**
- 依赖 `AliyunNlsTokenService`（上述文件）
- 受全局 `JwtAuthenticationFilter` 保护，未登录请求会被拦截（与项目其他接口一致）

```java
package com.example.interview.websocket;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// ============================================================
// 语音 Token 接口 —— 签发 NLS 临时 Token
// ============================================================
// 阿里云 NLS 实时识别需要短期 Token（有效期 24 小时）。该接口把 token 与
// appKey 下发出去；当前前端走"后端中继"，由 AliyunNlsRealtimeRelay 在服务端
// 获取并使用 Token，前端不再直连 NLS。AccessKey 不出服务器，只下发短期 Token。
// ============================================================
@RestController
@RequestMapping("/api/speech")
public class SpeechTokenController {

    private final AliyunNlsTokenService tokenService;

    public SpeechTokenController(AliyunNlsTokenService tokenService) {
        this.tokenService = tokenService;
    }

    /**
     * 返回 NLS 临时 Token。
     *
     * @return {"token": "...", "appKey": "..."}，
     *         后端 AliyunNlsRealtimeRelay 建立上行连接时用这两个值向阿里云 NLS 鉴权
     */
    @GetMapping("/token")
    public Map<String, String> token() {
        return Map.of(
                "token", tokenService.getToken(),
                "appKey", tokenService.getAppKey()
        );
    }
}
```

---

#### 文件 3：`WebSocketConfig.java`

**文件位置：** `src/main/java/com/example/interview/websocket/WebSocketConfig.java`

**文件作用：** 注册业务 WebSocket 路由 `/ws/interviews/{sessionId}`，并通过握手拦截器把 URL 中的 sessionId 解析进 attributes，供处理器后续使用。

**为什么路径里带 sessionId：** 后端需要用它作为 `AsyncTranscriptionBuffer` 队列的 key 和转写上下文的 key，保证不同面试的音频分片互不混淆。

**依赖关系：**
- 依赖 `InterviewWebSocketHandler`（下述文件）
- 依赖 `spring-boot-starter-websocket`（已在 pom.xml 中引入）

```java
package com.example.interview.websocket;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

// ============================================================
// WebSocket 配置 —— 注册语音转写路由
// ============================================================
// 前端连接地址：ws://localhost:18080/ws/interviews/{sessionId}
//
// 握手拦截器的作用：
//   把 URL 中的 sessionId 解析出来放入 attributes，
//   后续 handleTextMessage 直接从 session 的 attributes 读取，
//   这样处理器不需要自己解析路径，职责更单一。
//
// 注意：JWT 鉴权也可以放在握手拦截器中（在 beforeHandshake 里校验
// Authorization 头），教学版从简，生产环境建议补上。
// ============================================================
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final InterviewWebSocketHandler handler;

    public WebSocketConfig(InterviewWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(
            WebSocketHandlerRegistry registry
    ) {
        registry.addHandler(
                        handler,
                        "/ws/interviews/{sessionId}"
                )
                .setAllowedOrigins("*")  // 教学环境放开跨域；生产应按前端域名收敛
                .addInterceptors(
                        new HandshakeInterceptor() {
                            @Override
                            public boolean beforeHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler wsHandler,
                                    Map<String, Object> attributes
                            ) {
                                // 从路径 /ws/interviews/{sessionId} 中截取 sessionId
                                String path = request.getURI().getPath();
                                String sessionId = path.substring(
                                        path.lastIndexOf('/') + 1
                                );
                                attributes.put("sessionId", sessionId);
                                return true;
                            }

                            @Override
                            public void afterHandshake(
                                    ServerHttpRequest request,
                                    ServerHttpResponse response,
                                    WebSocketHandler wsHandler,
                                    Exception exception
                            ) {
                                // 握手后无需处理
                            }
                        }
                );
    }
}
```

---

#### 文件 4：`AsyncTranscriptionBuffer.java`

**文件位置：** `src/main/java/com/example/interview/websocket/AsyncTranscriptionBuffer.java`

**文件作用：** WebSocket 接收音频分片的速度可能快于合并处理的消费速度。用 `ArrayBlockingQueue` 把"接收"和"消费"解耦：接收线程只负责入队（最多阻塞 200ms），消费在独立线程池 `transcriptionTaskExecutor` 中异步完成。队列按 `sessionId` 隔离，不同面试的音频绝不混用。

**依赖关系：**
- 依赖 `TranscriptionSessionContext`（下述文件）——消费时调用其 `mergeSegment()` 合并分片
- 依赖 `AsyncConfig` 中注册的 `transcriptionTaskExecutor` 线程池
- 被谁调用：`InterviewWebSocketHandler`（receive / close）

```java
package com.example.interview.websocket;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class AsyncTranscriptionBuffer {

    // ============================================================
    // 音频分片记录 —— receive 的入队单元
    // ============================================================
    // 字段与前端上报的 JSON 一一对应：
    //   segId —— 分片序号（seg_id），用于去重和排序
    //   text  —— 分片文本内容
    //   pgs   —— 分片状态，如 "speaking" / "silence"
    //   range —— 音频范围起始位置（rg 数组的第一个元素）
    //   begin —— 分片开始时间位置（bg 数组的第一个元素）
    //   end   —— 是否最终确认结果（ed）
    // ============================================================
    public record AudioSegment(
            int segId,
            String text,
            String pgs,
            int range,
            int begin,
            boolean end
    ) {
    }

    /**
     * 按 sessionId 隔离的音频分片队列。
     *
     * <p>为什么 key 用 sessionId：一场面试对应一个转写上下文，不同面试的
     * 音频分片绝不能混入同一个队列，否则 A 用户的声音片段会被并进 B 用户的
     * 转写文本。Map 的 key 是 sessionId，value 是容量 256 的有界队列，
     * 队列满时 receive 会阻塞等待最多 200ms，起到天然的背压作用。
     */
    private final Map<String, ArrayBlockingQueue<AudioSegment>>
            queues = new ConcurrentHashMap<>();

    /**
     * 已关闭会话的标记集合，用于终止该会话的消费循环。
     *
     * <p>为什么需要它：消费线程阻塞在队列上等待分片，仅把队列从 Map 中摘除
     * 并不能让它退出。如果不加标记，每个结束的面试都会永久占用一个转写线程，
     * 连接数一多就把 transcriptionTaskExecutor 占满，后续会话的转写再也拿不到
     * 线程。close 写入标记、消费循环每轮检查标记，两者配合完成线程回收。
     */
    private final Set<String> closedSessions =
            ConcurrentHashMap.newKeySet();

    /**
     * 接收一个音频分片并放入该会话对应的队列。
     *
     * <p>为什么用带超时的 offer 而不是 put：WebSocket 接收线程不能无限阻塞，
     * 否则该连接的接收链路会被拖死；offer 等待最多 200ms，队列仍满就返回
     * false，由调用方决定丢弃或断开连接，保证接收线程始终能继续处理新分片。
     *
     * @param sessionId 当前 WebSocket 连接对应的面试会话 ID
     * @param segment   一个音频转写分片（含片段序号和文本内容）
     * @return 入队成功返回 true；队列已满且超时仍无空位返回 false
     */
    public boolean receive(
            String sessionId,
            AudioSegment segment
    ) {
        ArrayBlockingQueue<AudioSegment> queue =
                queues.computeIfAbsent(
                        sessionId,
                        ignored -> new ArrayBlockingQueue<>(256)
                );
        try {
            return queue.offer(
                    segment,
                    200,
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException error) {
            // 接收线程被中断（如服务关闭）时恢复中断标记并视为入队失败，
            // 调用方（WebSocket 处理器）据此丢弃当前分片，不阻塞接收链路。
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 异步消费指定会话的音频分片并合并进转写上下文。
     *
     * <p>方法由 @Async("transcriptionTaskExecutor") 注解在独立的转写线程池
     * 中执行，与接收线程解耦：接收只管入队，本方法在后台等待新分片。
     * 为什么用"带超时的 poll + 无限循环"而不是 take：take 在没有分片时会
     * 一直挂起，线程无法感知会话已经结束；poll 每轮最多等 200ms 就回头检查
     * 一次关闭标记，会话结束后消费线程能在一个超时周期内退出并把线程还给
     * 线程池。线程被中断（如面试结束清理连接）时恢复中断标记并退出循环。
     *
     * @param sessionId 要消费的面试会话 ID，也是队列的 key
     * @param context   该会话的转写上下文，合并后的文本写入其中
     */
    @Async("transcriptionTaskExecutor")
    public void consume(
            String sessionId,
            TranscriptionSessionContext context
    ) {
        // 连接（重新）建立时把会话标记回"活跃"，
        // 避免同一 sessionId 重连后仍被上一轮的关闭标记立刻终止。
        closedSessions.remove(sessionId);
        ArrayBlockingQueue<AudioSegment> queue =
                queues.computeIfAbsent(
                        sessionId,
                        ignored -> new ArrayBlockingQueue<>(256)
                );
        for (;;) {
            // 会话已关闭则退出循环，把线程还给线程池。
            if (closedSessions.contains(sessionId)) {
                return;
            }
            try {
                // 用带超时的 poll 而不是无限阻塞的 take：
                // 每轮最多等 200ms 就回头检查一次关闭标记，
                // 保证会话结束后消费线程能在一个超时周期内退出。
                AudioSegment segment = queue.poll(
                        200,
                        TimeUnit.MILLISECONDS
                );
                if (segment == null) {
                    continue;
                }
                context.mergeSegment(
                        segment.segId(),
                        segment.text(),
                        segment.pgs(),
                        segment.range(),
                        segment.begin(),
                        segment.end()
                );
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 面试结束时清理该会话的队列，释放内存。
     *
     * <p>为什么必须清理：如果不 remove，每个结束的面试都会在 Map 中遗留一个
     * 队列对象，长期运行下来形成内存泄漏。只摘除队列还不够——消费线程手里
     * 持有的是队列的局部引用，摘除 Map 不会让它停下来，所以这里先写入关闭
     * 标记再摘除队列：消费循环每轮 poll 超时后会检查该标记并自行 return，
     * 队列与线程因此都能被回收，无需调用方再额外中断线程。
     *
     * @param sessionId 要清理的面试会话 ID
     */
    public void close(String sessionId) {
        closedSessions.add(sessionId);
        queues.remove(sessionId);
    }
}
```

---

#### 文件 5：`TranscriptionSessionContext.java`

**文件位置：** `src/main/java/com/example/interview/websocket/TranscriptionSessionContext.java`

**文件作用：** 维护一个面试会话内的语音转写状态。负责分片的覆盖更新、乱序排序和"临时结果 / 最终结果"区分，输出合并后的完整转写文本。

**为什么需要这个类？** 同一段语音可能产生多个分片，分片可能乱序到达、重复到达。如果不做状态管理，最终得到的转写文本可能是乱序的、重复的或残缺的。

**依赖关系：**
- 无外部框架依赖（纯 Java 标准库）
- 被谁调用：`AsyncTranscriptionBuffer.consume()`（每个分片消费时调用 `mergeSegment()`）和业务层（`getTranscript()`）

```java
package com.example.interview.websocket;

import java.util.Map;
import java.util.TreeMap;

// ============================================================
// 语音转写会话上下文 —— 维护单个面试会话内的转写状态
// ============================================================
// 职责：
//   1. 按 seg_id 保存所有收到的转写分片（覆盖更新，不会产生重复）
//   2. 区分"临时结果"（ed=false）和"最终结果"（ed=true）
//   3. 按 seg_id 顺序拼接连续已确认分片，生成最终的完整转写文本
//   4. 提供线程安全的方法（synchronized），支持并发访问
//
// 关键设计决策：
//   - 使用 TreeMap 而非 HashMap：需要按 seg_id 顺序遍历
//   - put 是覆盖语义：同一 seg_id 后到的结果覆盖旧值，不会追加
//   - synchronized 方法：一个会话可能被多个线程同时操作
//     （WebSocket 接收线程、异步消费线程和业务处理线程）
// ============================================================
public class TranscriptionSessionContext {

    // ============================================================
    // 合并结果记录 —— mergeSegment 的返回值封装
    // ============================================================
    // 字段说明：
    //   preview     —— 临时预览文本（前端实时展示用，可能包含未确认的临时内容）
    //   transcript  —— 最终文本（只包含已确认的连续分片，适合落库）
    //   finalResult —— true 表示这是最终确认的结果，false 表示只是临时预览
    //   segId       —— 当前处理的分片 ID（原样透传，前端用于跟踪）
    //   pgs         —— 分片状态（原样透传，如 "speaking" / "silence"）
    //   rg          —— 音频范围起始位置（原样透传）
    //   bg          —— 分片开始时间位置（原样透传）
    // ============================================================
    public record MergeResult(
            String preview,      // 合并后的预览文本
            String transcript,   // 合并后的最终文本
            boolean finalResult, // 是否最终确认结果
            int segId,           // 当前分片 ID
            String pgs,          // 分片状态（原样透传）
            int rg,              // 音频范围起始（原样透传）
            int bg               // 分片开始时间（原样透传）
    ) {
    }

    // ---- 分片存储 ----
    // 按 seg_id 排序保存所有分片的最新文本。
    // key：seg_id（语音分片序号，从 0 开始递增）
    // value：分片文本（已 trim 处理）
    // 同一个 seg_id 后到的结果会覆盖旧值——这是 TreeMap.put 的自然行为。
    private final TreeMap<Integer, String> segmentMap = new TreeMap<>();

    // ---- 已确认分片 ----
    // 只保存 ed=true（最终结果）的分片。
    // 用于生成连续的最终句子，不会被临时结果污染。
    private final TreeMap<Integer, String> confirmedSegments = new TreeMap<>();

    // ---- 临时预览文本 ----
    // 保存当前看到的临时预览内容。
    // 由已确认句子 + 最新临时文本拼接而成。
    // 不直接作为最终答案落库。
    private String preview = "";

    // ---- 最终转写文本 ----
    // 只保存 ed=true 的最终转写结果。
    // 由连续已确认分片按 seg_id 顺序拼接而成。
    // 最终会存入答案表，作为面试答案的来源之一。
    private String transcript = "";

    // ============================================================
    // 核心方法：合并一个转写分片
    // ============================================================
    // 每次异步消费线程取出一个分片时调用此方法。
    //
    // 关键逻辑：
    //   1. 所有分片都存入 segmentMap（覆盖旧值）
    //   2. 如果 ed=true，同时存入 confirmedSegments，
    //      并重新拼接所有连续已确认分片，更新 transcript 和 preview
    //   3. 如果 ed=false，只拼接预览文本（confirmedSentence + 当前临时文本），
    //      不修改 transcript
    // ============================================================
    public synchronized MergeResult mergeSegment(
            int segId,
            String text,
            String pgs,
            int rg,
            int bg,
            boolean ed
    ) {
        // ---- 文本预处理 ----
        // 如果 text 为 null，转为空字符串并 trim（去除首尾空白）。
        // trim 的目的是去除 ASR 可能引入的前后空格。
        String normalized = text == null ? "" : text.trim();

        // ---- 覆盖更新分片 ----
        // TreeMap.put 是覆盖语义（replace），不是追加语义（append）。
        // 同一 seg_id 的多次到达只会保留最后一条文本，
        // 不会产生"我今今天去去公司"这样的重复错误。
        segmentMap.put(segId, normalized);

        // ---- 分支1：最终结果（ed=true） ----
        if (ed) {
            // 将当前分片加入"已确认"集合
            confirmedSegments.put(segId, normalized);

            // 从最小 seg_id 开始，拼接所有连续的已确认分片
            String sentence = rebuildConfirmedSentence();

            // 同时更新 transcript 和 preview（最终结果到达后两者完全同步）
            transcript = sentence;
            preview = sentence;

            return new MergeResult(
                    sentence,    // preview
                    sentence,    // transcript
                    true,        // finalResult（这是最终结果）
                    segId, pgs, rg, bg
            );
        }

        // ---- 分支2：临时结果（ed=false） ----
        // 临时结果只用于刷新预览，不能写入 transcript。
        // 这样可以防止"嗯...啊..."等临时修正痕迹出现在最终答案中。
        String temporaryPreview = rebuildPreview(segId, normalized);
        preview = temporaryPreview;

        return new MergeResult(
                temporaryPreview,  // preview（临时预览）
                transcript,        // transcript（保持之前的值不变）
                false,             // finalResult（这不是最终结果）
                segId, pgs, rg, bg
        );
    }

    // ============================================================
    // 私有方法：拼接连续已确认分片
    // ============================================================
    // 从最小 seg_id（即第一个分片）开始遍历 confirmedSegments，
    // 只拼接 seg_id 连续的分片。遇到不连续的分片就停止。
    //
    // 为什么只拼接连续分片？
    //   假设 confirmedSegments 中有 {0:"今天", 1:"天气", 3:"不错"}，
    //   seg_id=2 缺失（可能尚未到达或仍在处理中），
    //   此时只拼接 0 和 1（"今天天气"），不拼接 3，
    //   因为跳过 seg_id=2 直接拼接 3 会产生语义断裂。
    //   等 seg_id=2 到达并确认后，下一次调用会重新拼接所有连续分片。
    // ============================================================
    private String rebuildConfirmedSentence() {
        StringBuilder result = new StringBuilder();
        Integer expected = null;  // 预期的下一个 seg_id

        // TreeMap 的 entrySet 按 key 升序遍历
        for (Map.Entry<Integer, String> entry : confirmedSegments.entrySet()) {
            if (expected == null) {
                // 第一次迭代：以第一个分片的 seg_id 为起始
                expected = entry.getKey();
            }
            // 检查 seg_id 是否连续
            if (!entry.getKey().equals(expected)) {
                // 出现不连续（跳号），停止拼接
                // 例如 expected=2 但当前 entry.key=3，说明 seg_id=2 缺失
                break;
            }
            // 将当前分片文本附加到结果中
            appendText(result, entry.getValue());
            expected++;  // 期望下一个 seg_id
        }

        return result.toString();
    }

    // ============================================================
    // 私有方法：拼接临时预览文本
    // ============================================================
    // 临时预览 = 已确认句子 + 当前临时分片文本
    // 例如：
    //   已确认句子 = "今天天气"
    //   当前临时文本 = "真不错"
    //   预览结果 = "今天天气真不错"
    // ============================================================
    private String rebuildPreview(int segId, String temporaryText) {
        // 先获取当前已确认的连续句子
        String confirmed = rebuildConfirmedSentence();

        // 如果临时文本为空，预览仅包含已确认内容
        if (temporaryText.isBlank()) {
            return confirmed;
        }
        // 如果已确认句子为空，预览仅包含当前临时文本
        if (confirmed.isBlank()) {
            return temporaryText;
        }
        // 两者都不为空：已确认句子 + 临时文本
        // 用 confirmed + temporaryText 而不是 confirmed + " " + temporaryText，
        // 因为 appendText 已在已确认文本的末尾加了空格
        return confirmed + temporaryText;
    }

    // ============================================================
    // 私有方法：向 StringBuilder 中追加文本（自动加空格分隔）
    // ============================================================
    // 作用是在文本片段之间添加适当的分隔符：
    //   1. 如果 builder 为空，直接追加
    //   2. 如果 builder 末尾已经是空白字符，直接追加（不需要再加空格）
    //   3. 如果 builder 末尾不是空白字符，先加一个空格再追加
    // ============================================================
    private void appendText(StringBuilder builder, String value) {
        // 跳过 null 或空文本
        if (value == null || value.isBlank()) {
            return;
        }
        // 检查 builder 末尾是否是空白字符
        // 如果不是空白，先加一个空格再追加文本，避免词语粘连
        if (builder.length() > 0
                && !Character.isWhitespace(
                        builder.charAt(builder.length() - 1)
                )) {
            builder.append(' ');
        }
        // 追加文本内容
        builder.append(value);
    }

    // ============================================================
    // 公共方法：获取当前预览文本
    // ============================================================
    // 为什么是 synchronized？
    //   防止在 mergeSegment 正在进行时读取到不一致的中间状态。
    // ============================================================
    public synchronized String getPreview() {
        return preview;
    }

    // ============================================================
    // 公共方法：获取当前最终转写文本
    // ============================================================
    // 此方法被业务层调用，用于获取完整的面试答案文本。
    // transcript 只会在 ed=true 的分片到达时更新。
    // ============================================================
    public synchronized String getTranscript() {
        return transcript;
    }
}
```

---

#### 文件 6：`InterviewWebSocketHandler.java`

**文件位置：** `src/main/java/com/example/interview/websocket/InterviewWebSocketHandler.java`

**文件作用：** WebSocket 通信处理器。连接建立时创建转写上下文并启动异步消费；收到消息时解析 JSON 字段、构造 `AudioSegment` 入队（不阻塞接收线程）；响应心跳；连接关闭时清理队列和上下文。

**这个文件在整个语音转写流程中的角色：**
- **最底层**：ASR（自动语音识别）引擎产生分片文本
- **通信层**：InterviewWebSocketHandler（本文件）负责 WebSocket 消息的解析和入队
- **缓冲层**：AsyncTranscriptionBuffer 负责按会话隔离的接收/消费解耦
- **状态管理层**：TranscriptionSessionContext 负责分片的去重、排序和合并
- **业务层**：面试流程引擎在需要时从 context 获取完整的 transcript

```java
package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================
// WebSocket 处理器 —— 语音转写分片的接收与转发
// ============================================================
// 职责：
//   1. 连接建立：读取 sessionId，创建 TranscriptionSessionContext，
//      启动 AsyncTranscriptionBuffer 的异步消费线程
//   2. 消息接收：解析 JSON 字段，构造 AudioSegment 入队
//      （入队用带超时的 offer，队列满时丢弃分片而不是阻塞接收线程）
//   3. 心跳：响应前端 ping，返回 pong，保持长连接不被网络设备断开
//   4. 连接关闭：清理队列和上下文，避免内存泄漏
//
// 与"十六点一"同步版的区别：
//   本版本是最终实现——接收与消费解耦，接收线程只负责入队，
//   合并去重由 AsyncTranscriptionBuffer.consume 在独立线程池完成。
//   好处是 ASR 分片突增时不会拖垮 WebSocket 接收线程。
// ============================================================
@Component  // 声明为 Spring 组件，由 WebSocketConfig 注册到处理链
public class InterviewWebSocketHandler
        extends TextWebSocketHandler {

    // Jackson JSON 处理器：用于解析收到的消息和序列化推送结果
    private final ObjectMapper mapper;
    // 异步音频缓冲：接收与消费解耦的核心
    private final AsyncTranscriptionBuffer buffer;

    // 面试会话 ID -> 转写上下文的映射
    // key：面试会话 ID（握手拦截器从 URL 解析后放入 attributes）
    private final Map<String, TranscriptionSessionContext> contexts =
            new ConcurrentHashMap<>();

    /**
     * 构造器注入依赖。
     *
     * @param mapper Jackson JSON 处理器（Spring Boot 自动配置）
     * @param buffer 异步音频缓冲，负责分片入队与异步消费
     */
    public InterviewWebSocketHandler(
            ObjectMapper mapper,
            AsyncTranscriptionBuffer buffer
    ) {
        this.mapper = mapper;
        this.buffer = buffer;
    }

    // ============================================================
    // 连接建立回调
    // ============================================================
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = sessionId(session);
        TranscriptionSessionContext context =
                new TranscriptionSessionContext();
        contexts.put(sessionId, context);
        // 启动该会话的异步消费：
        // 分片从队列取出后由 mergeSegment 去重合并成最终 transcript
        buffer.consume(sessionId, context);
    }

    // ============================================================
    // 消息接收回调
    // ============================================================
    // 前端消息格式（JSON）：
    //   {
    //     "type": "transcription",       // 消息类型，非 transcription 类型直接忽略
    //     "seg_id": 0,                   // 分片序号（必填，整数）
    //     "text": "今天天气真不错",       // 分片文本（可选，默认空字符串）
    //     "pgs": "speaking",             // 分片状态（可选，如 "speaking"/"silence"）
    //     "rg": [0, 100],                // 音频范围数组（可选，取第一个元素）
    //     "bg": [0, 50],                 // 分片开始时间数组（可选，取第一个元素）
    //     "ed": true                     // 是否最终结果（可选，默认 false）
    //   }
    //   心跳消息：{"type": "ping"}
    // ============================================================
    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws IOException {
        // ---- 步骤1：解析收到的 JSON 消息 ----
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText();

        // ---- 步骤2：心跳处理 ----
        // 前端每 10 秒发一次 ping，这里回 pong 保持连接活跃
        if ("ping".equals(type)) {
            session.sendMessage(new TextMessage(
                    mapper.writeValueAsString(
                            Map.of("type", "pong")
                    )
            ));
            return;
        }

        // ---- 步骤3：消息类型过滤 ----
        // 只处理 type="transcription" 的消息，其他类型静默忽略
        if (!"transcription".equals(type)) {
            return;
        }

        // ---- 步骤4：提取各字段 ----
        // seg_id：分片序号，必须存在且为整数，否则抛异常
        int segId = requiredInt(root, "seg_id");
        // text：分片文本内容，可选字段，缺失时默认为空字符串
        String text = root.path("text").asText("");
        // pgs：分片状态，如 "speaking" 表示正在说话
        String pgs = root.path("pgs").asText("");
        // rg：音频范围位置，可能是数组 [start, end]，取第一个值
        int rg = readRangeValue(root, "rg");
        // bg：分片开始时间，可能是数组 [start]，取第一个值
        int bg = readRangeValue(root, "bg");
        // ed：是否最终结果，支持布尔值和字符串 "true"/"false"
        boolean ed = readBoolean(root, "ed");

        // ---- 步骤5：构造分片并入队 ----
        // receive 内部用带超时的 offer（最多等 200ms），队列满返回 false。
        // 此时直接丢弃该分片——ASR 后续还会推送更准确的修正结果，
        // 丢一两个中间分片不会影响最终转写，绝不能阻塞接收线程。
        boolean accepted = buffer.receive(
                sessionId(session),
                new AsyncTranscriptionBuffer.AudioSegment(
                        segId, text, pgs, rg, bg, ed
                )
        );
        if (!accepted) {
            // 队列已满：丢弃当前分片，等待后续分片自然补齐
            return;
        }
    }

    // ============================================================
    // 连接关闭回调
    // ============================================================
    // 清理队列和上下文，释放内存。否则已关闭的连接会一直驻留在内存中，
    // 长期运行形成内存泄漏。
    // ============================================================
    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            org.springframework.web.socket.CloseStatus status
    ) {
        String sessionId = sessionId(session);
        // 清理该会话的缓冲队列：close 会写入关闭标记，
        // 该会话的消费循环随即自行退出并把线程归还线程池
        buffer.close(sessionId);
        // 清理转写上下文
        contexts.remove(sessionId);
    }

    // ============================================================
    // 私有方法：从握手 attributes 读取面试会话 ID
    // ============================================================
    // sessionId 由 WebSocketConfig 的握手拦截器在握手阶段解析并放入
    // attributes，这里直接读取即可，无需再解析路径。
    // ============================================================
    private String sessionId(WebSocketSession session) {
        return (String) session.getAttributes().get("sessionId");
    }

    // ============================================================
    // 私有方法：必填整数字段提取
    // ============================================================
    // 如果字段不存在或不能转换为整数，抛出 IllegalArgumentException。
    // 这种"严格校验"的设计确保我们不会把无效数据传给 buffer。
    // ============================================================
    private int requiredInt(JsonNode root, String field) {
        if (!root.has(field) || !root.get(field).canConvertToInt()) {
            throw new IllegalArgumentException(
                    "missing or invalid " + field
            );
        }
        return root.get(field).asInt();
    }

    // ============================================================
    // 私有方法：数组类型字段提取（取第一个元素）
    // ============================================================
    // ASR 引擎返回的 rg（音频范围）和 bg（开始时间）可能是数组格式，
    // 如 [0, 100]。我们只取数组的第一个元素作为位置的起始值。
    // ============================================================
    private int readRangeValue(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            return 0;
        }
        return value.get(0).asInt(0);
    }

    // ============================================================
    // 私有方法：布尔值提取（支持多种格式）
    // ============================================================
    // ASR 引擎可能以不同格式返回布尔值：
    //   - 标准布尔：true / false
    //   - 字符串："true" / "false" / "1" / "0"
    // 此方法兼容上述所有格式。
    // ============================================================
    private boolean readBoolean(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            return false;  // 字段不存在，默认 false
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return "true".equalsIgnoreCase(value.asText());
    }
}
```

---

#### 前端语音采集与分片发送

**文件位置：** `frontend/src/nlsTranscriptionClient.js`（语音客户端）+ `frontend/src/main.jsx`（页面接线）

**本部分作用：** 前端只做"采集 + 推流"：采集麦克风音频，用 `AudioContext` 降采样成 NLS 要求的 16k 单声道裸 PCM，直接以二进制帧推给后端业务 WebSocket；后端 `AliyunNlsRealtimeRelay` 补齐 NLS 的 4 字节帧头并转发给阿里云 NLS，识别分片再由它落回后端 `AsyncTranscriptionBuffer` 去重合并，临时结果以 `{type:'asr_preview'}` 推回前端做实时预览。前端**不再直连阿里云 NLS，也不再取 Token**——接入点、鉴权与识别都收敛到后端，链路上根本不存在把 AccessKey 下发到浏览器的风险。

**一次 `start()` 干四件事：**
1. 打开业务 WebSocket（`/ws/interviews/{sessionId}`），发 `{type:'audio_start'}` 通知后端建立阿里云 NLS 上行
2. `getUserMedia` 拿麦克风，`AudioContext` 把音频降采样成 16k 单声道 PCM
3. 采集的裸 PCM 以二进制帧持续推给后端（NLS 的 4 字节帧头由后端 `AliyunNlsRealtimeRelay` 补齐）
4. 后端把 NLS 识别的临时文本以 `{type:'asr_preview'}` 推回，这里转给界面实时预览

**整体链路：**

```javascript
// 文件：frontend/src/nlsTranscriptionClient.js
// NLS 语音转写客户端 —— 前端这一头（纯采集端，音频走后端中继）。
// 一次 start() 干四件事：拿麦克风降采样 → 建业务 WS 发 audio_start → 裸 PCM 二进制帧推给后端
// → 后端用 asr_preview 把 NLS 临时识别文本推回来预览。前端不再直连 NLS、不再取 Token。
// 为什么改成"后端中继"而不是前端直连 NLS：项目亮点是"基于 WebSocket + 阿里云 NLS 的实时
// ASR 链路"，要求后端存在"音频 → NLS"的转发。接入点、鉴权与识别结果都收敛到后端，
// 链路上不把 AccessKey 下发到浏览器。

// 采样率只支持 16k / 8k，项目统一用 16k
const SAMPLE_RATE = 16000;

export class NlsTranscriptionClient {

  constructor(options) {
    this.sessionId = options.sessionId;
    this.onPreview = options.onPreview || (() => {});   // 后端推回的预览文本
    this.onError = options.onError || (() => {});
    this.business = null;      // 业务 WebSocket：上行音频 + 下行预览
    this.audioContext = null;
    this.processor = null;
    this.micStream = null;
    this.recording = false;
  }

  async start() {
    // 先建业务 WS，再拿麦克风，最后发 audio_start —— 顺序保证后端上行就绪后才会有音频。
    this.openBusinessSocket();
    // 只要音频；拿不到就直接报错，让用户改用文本输入
    this.micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // 等 WS 真正 OPEN 再发音频，否则 start 后的第一批帧会被后端当成"无上行"丢帧。
    await new Promise(resolve => {
      if (this.business?.readyState === WebSocket.OPEN) {
        resolve();
        return;
      }
      this.business.addEventListener('open', () => resolve(), { once: true });
    });
    // 通知后端：为本会话建立上行 NLS 连接。
    this.business.send(JSON.stringify({ type: 'audio_start' }));
    this.recording = true;
    this.startCapture();   // 采集 + 降采样 + 分包推送
  }

  // 业务 WS：地址与 WebSocketConfig 注册的路由一致；
  // 同一连接既上行音频帧，又下行 NLS 预览文本。
  openBusinessSocket() {
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws';
    this.business = new WebSocket(
        `${scheme}://${location.host}/ws/interviews/${this.sessionId}`
    );
    // 下行：后端把 NLS 的临时识别结果以 asr_preview 推回，刷新界面预览。
    this.business.onmessage = event => {
      if (typeof event.data !== 'string') return;
      const message = JSON.parse(event.data);
      if (message.type === 'asr_preview' && message.text) {
        this.onPreview(message.text);
      }
    };
    this.business.onerror = () => this.onError('语音连接异常');
  }

  // 采集：AudioContext 默认采样率通常是 48k，这里线性降采样到 16k 再发
  startCapture() {
    this.audioContext = new AudioContext();
    const source = this.audioContext.createMediaStreamSource(this.micStream);
    // 用 ScriptProcessor 逐块拿 PCM：已废弃但零依赖、够教学用；
    // 生产可换 AudioWorklet（多一个 worklet 文件，逻辑完全相同）。
    this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);
    const ratio = this.audioContext.sampleRate / SAMPLE_RATE;
    this.processor.onaudioprocess = event => {
      if (this.business?.readyState !== WebSocket.OPEN) return;   // WS 没连上就丢帧，不缓存
      const input = event.inputBuffer.getChannelData(0);
      // 降采样 + float32 → int16（NLS 要的是 16 位小端 PCM）
      const pcm = new Int16Array(Math.floor(input.length / ratio));
      for (let i = 0; i < pcm.length; i += 1) {
        const sample = input[Math.floor(i * ratio)];
        pcm[i] = Math.max(-1, Math.min(1, sample)) * 0x7fff | 0;
      }
      // 直接发裸 PCM 二进制帧；NLS 的 4 字节帧头由后端 AliyunNlsRealtimeRelay 补。
      this.business.send(pcm.buffer);
    };
    source.connect(this.processor);
    this.processor.connect(this.audioContext.destination);
  }

  stop() {
    this.recording = false;
    // 停麦前先告诉后端"音频发完了"，后端据此发 0x04 结束帧并关闭 NLS 上行。
    if (this.business?.readyState === WebSocket.OPEN) {
      this.business.send(JSON.stringify({ type: 'audio_end' }));
    }
    // 关麦克风：浏览器才会撤掉录音指示条
    this.micStream?.getTracks().forEach(track => track.stop());
    this.processor?.disconnect();
    this.audioContext?.close();
    // 关闭业务 WS：后端 afterConnectionClosed 会清理队列、转写上下文与 NLS 上行
    this.business?.close();
  }
}
```

**答题页面中的接线（`frontend/src/main.jsx`）：** 录音逻辑已换成 `NlsTranscriptionClient`；`onPreview` 把 NLS 的临时结果写进 `transcript` 做界面预览，最终文本由后端去重合并后落进 `interview_answer.answer`。

```javascript
// 文件：frontend/src/main.jsx（节选：开始/停止录音）
  const toggleRecording = async () => {
    if (recording) {
      // 停止：关麦克风、关两条 WebSocket、清 ping 定时器都在 stop() 里做。
      nlsClient.current?.stop();
      nlsClient.current = null;
      setRecording(false);
      setNotice('语音输入已停止，转写文本已随答案上报。');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setError('当前浏览器不支持录音，请使用文本输入。');
      return;
    }
    if (!session) {
      setError('请先开始面试，再使用语音输入。');
      return;
    }
    try {
      const client = new NlsTranscriptionClient({
        sessionId: session.sessionId,
        // 取 /api/speech/token 时要带上登录态；JwtAuthenticationFilter 认的是 Authorization 头。
        jwt: localStorage.getItem('interview_token') || '',
        // 临时结果只做界面预览，最终文本由后端去重合并后统一取。
        onPreview: text => setTranscript(text),
        onError: message => setError(message)
      });
      nlsClient.current = client;
      await client.start();
      setRecording(true);
    } catch (caught) {
      nlsClient.current = null;
      setRecording(false);
      setError(caught.message || '语音输入启动失败，请改用文本输入。');
    }
  };
```

**与"前端 4：编写答题页面"的关系：** 提交逻辑不变——`currentAnswer` 优先取文本框 `answer`，为空时取语音转写 `transcript`（即 NLS 临时结果回填的预览文本），两者都为空时提示输入。

---

#### 从回答表读取转写文本（兜底）

**文件位置：** `src/main/java/com/example/interview/application/AnswerRepository.java`（新增一个方法）

**本部分作用：** 面试结束后 WebSocket 连接已经关闭，无法再从 `TranscriptionSessionContext` 取文本。此时从答案表兜底读取：语音答案在提交时已经把转写文本写入了 `interview_answer.answer` 字段，按 sessionId 汇总所有非空答案即可得到整场面试的说话内容。

**为什么需要这个方法：** 表现分析需要整场面试的"说话内容"作为输入。兜底方案不依赖 WebSocket 连接仍然存活，任何时候都能取到数据。

```java
/**
 * 加载某场面试的全部答案文本，拼接成一段完整转写文本。
 *
 * <p>为什么需要这个方法：表现分析需要整场面试的"说话内容"作为输入。
 * 语音答案在提交时已经以转写文本的形式存入 interview_answer.answer，
 * 因此直接按 sessionId 汇总即可，不依赖 WebSocket 连接仍然存活
 * （面试结束后连接早已关闭）。
 *
 * <p>实现要点：按答案记录自增主键升序取出该会话的全部答案，
 * 过滤掉 null 或空白内容后，用换行符拼接成一段完整文本。
 * 该文本随后交给表现分析（DemeanorAnalysisService）作为输入。
 *
 * @param sessionId 面试会话 ID
 * @return 所有非空答案用换行拼接的文本；一场面试可能为空字符串
 */
public String loadSessionTranscript(UUID sessionId) {
    return mapper.selectList(
                    Wrappers.<AnswerEntity>lambdaQuery()
                            .eq(AnswerEntity::getSessionId,
                                    sessionId.toString())
                            .orderByAsc(AnswerEntity::getId)
            )
            .stream()
            .map(AnswerEntity::getAnswer)
            .filter(answer -> answer != null && !answer.isBlank())
            .collect(Collectors.joining("\n"));
}
```

> **需要补充的两个 import：** `com.baomidou.mybatisplus.core.toolkit.Wrappers` 和 `java.util.stream.Collectors`。

**在整条链路中的使用位置：** 面试结束时由 `InterviewController.end()` 调用（见"表现 2"），从答案表汇总整场文本后直接交给表现分析：

```java
// 文件：src/main/java/com/example/interview/application/InterviewController.java（节选：end）
// 面试结束时 WebSocket 早已关闭，实时转写上下文已经随连接销毁，
// 所以走答案表兜底——interview_answer.answer 里存的就是每次提交的答案文本
// （语音答案在提交时已经转写成了文本），按主键升序拼起来就是整场内容。
String transcript = answers.loadSessionTranscript(sessionId);
// analyze 传空串会抛 IllegalArgumentException（它把空文本当成上游没取到内容），
// 所以这里先判空：整场一句都没答时跳过分析，而不是让接口 500。
if (transcript != null && !transcript.isBlank()) {
    demeanor.analyze(sessionId, transcript);
}
```

---

> **与"十六点一、语音转写分段去重整改"的覆盖关系：** 十六点一给出的是同步版（Handler 收到分片后同步调用 `mergeSegment()` 并把合并结果推回前端）。本小节是最终版本：接收与消费解耦（`AsyncTranscriptionBuffer`），合并结果不再逐条推回前端（前端实时预览直接来自本地 ASR 客户端），最终转写文本由业务层通过 `getTranscript()` 或答案表获取。复制代码时以本小节的六个文件为准。

### 表现 1：调用 AI 分析转写文本

**本小节目的：** 将语音转写或文本回答交给 AI，提取表达层面的可改进信息。

**本小节流程：**
1. `DemeanorAnalysisService` 接收 sessionId 和 transcript（回答文本）
2. 校验 transcript 非空——空文本没有任何分析意义
3. 调用 `InterviewAiFacade.analyzeDemeanor(...)` 组装提示词并请求 AI
4. AI 返回原始 JSON 字符串（包含 confidence、fillerCount 等字段）
5. 调用 `InterviewAiFacade.parseDemeanor(...)` 将原始 JSON 解析为结构化 `DemeanorAnalysis` 对象
6. 返回该结构化对象（当前调用方 `InterviewController.end()` 只执行分析、不落库也不返回结果）

---

#### 文件：`DemeanorAnalysisService.java`

**文件位置：** `src/main/java/com/example/interview/ai/DemeanorAnalysisService.java`

**文件作用：** 表现分析的服务入口。它不直接调用大模型 API，而是通过 `InterviewAiFacade`（AI 门面，在前面的 AI 模块中定义）完成 AI 请求和响应解析。这样做的好处是：如果未来更换 AI 供应商或者修改提示词格式，只需要改 Facade，表现分析服务本身不受影响。

**依赖关系：**
- 依赖 `InterviewAiFacade`（在 AI 门面模块中定义）——提供 `analyzeDemeanor()` 和 `parseDemeanor()` 两个方法
- 被谁调用：`InterviewController.end()`（面试结束时，见"表现 2"）
- 输出：`DemeanorAnalysis` record（包含语速、口头禅、清晰度等字段）

```java
package com.example.interview.ai;

import org.springframework.stereotype.Service;

import java.util.UUID;

// ============================================================
// 表现分析服务
// ============================================================
// 职责：只分析语言表达表现，不判断技术答案是否正确。
//       核心逻辑是"组装输入 -> 调 AI -> 解析输出"三步，
//       具体 AI 通信细节委托给 InterviewAiFacade。
// ============================================================
@Service  // 声明为 Spring Bean，自动被依赖注入扫描注册
public class DemeanorAnalysisService {

    // ============================================================
    // 表现分析结果的数据结构（Java 14+ record，不可变对象）
    // ============================================================
    // 说明：这是一个内部 record，定义在 DemeanorAnalysisService 内部，
    //       因为它只在此服务的上下文中使用，不暴露给外部模块。
    //       外部模块通过 getter 方法读取各字段，不能修改。
    //
    // 各字段详细说明：
    //   confidence      —— 自信度评分，0.0~1.0，越高越自信
    //   fillerCount     —— 口头禅/填充词出现次数（如"嗯""啊""那个"）
    //   transcriptLength—— 转写文本的实际字符长度（由服务端传入，确保准确）
    //   clarityScore    —— 表达清晰度评分，0.0~1.0，越高越清晰
    //   pace            —— 语速评价，枚举值：slow / normal / fast
    //   feedback        —— AI 给出的文本改进建议（自然语言描述）
    // ============================================================
    public record DemeanorAnalysis(
            double confidence,      // 自信度（0.0 ~ 1.0）
            int fillerCount,        // 填充词/口头禅出现次数
            int transcriptLength,   // 转写文本长度（服务端统计，保证准确）
            double clarityScore,    // 表达清晰度（0.0 ~ 1.0）
            String pace,            // 语速分类："slow" / "normal" / "fast"
            String feedback         // 改进建议（自然语言文本）
    ) {
    }

    // AI 门面对象：负责拼提示词、调 AI、解析响应
    // 通过构造器注入（Spring 自动注入，无需 @Autowired）
    private final InterviewAiFacade ai;

    /**
     * 构造器注入 InterviewAiFacade。
     * Spring 会自动从容器中查找 InterviewAiFacade 实例并传入。
     *
     * @param ai AI 门面对象，负责所有与大模型交互的细节
     */
    public DemeanorAnalysisService(InterviewAiFacade ai) {
        this.ai = ai;
    }

    /**
     * 分析指定会话中某段回答的语言表达表现。
     *
     * @param sessionId  面试会话 ID（用于日志追踪和缓存键生成）
     * @param transcript 语音转写或文本回答内容（不能为空或空白）
     * @return DemeanorAnalysis 结构化对象，包含语速、口头禅、清晰度等维度的分析结果
     * @throws IllegalArgumentException 如果 transcript 为空或全空白字符
     */
    public DemeanorAnalysis analyze(UUID sessionId, String transcript) {
        // ---- 步骤1：校验输入 ----
        // 如果 transcript 为 null 或全部是空白字符（空格、换行、Tab），
        // 直接抛异常终止，避免浪费一次 AI 调用。
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalArgumentException("transcript is empty");
        }

        // ---- 步骤2：调用 AI 获取原始 JSON ----
        // 先通过 Facade 组装提示词并发起 AI 请求，
        // 拿到 AI 返回的原始 JSON 字符串。
        // 为什么不在这里直接解析？因为原始 JSON 可能还需要在其他地方复用
        // （例如缓存原始 JSON 而非结构化对象），所以把"请求"和"解析"拆成两步。
        String rawJson = ai.analyzeDemeanor(sessionId, transcript);

        // ---- 步骤3：解析原始 JSON 为结构化对象 ----
        // 把 AI 返回的 JSON 字符串解析成强类型的 DemeanorAnalysis record。
        // transcript.length() 作为实际转写长度传入，因为 AI 返回的 JSON 中
        // 可能不包含这个字段，或者计算方式不一致，我们以服务端为准。
        return ai.parseDemeanor(rawJson, transcript.length());
    }
}
```

---

#### 在 `InterviewAiFacade.java` 中增加的两个方法

**文件位置：** `src/main/java/com/example/interview/ai/InterviewAiFacade.java`（AI 门面类，在前面的「AI 模块」中已定义初始结构）

**方法作用说明：**
- `analyzeDemeanor(...)`：组装表现分析专用的提示词，调用通用 AI 通信方法 `chat(...)`，返回原始 JSON。这个方法负责"和 AI 对话"，不管 JSON 结构。
- `parseDemeanor(...)`：将 AI 返回的原始 JSON 字符串解析成 `DemeanorAnalysis` 结构化对象。这个方法负责"解析"，不管 AI 通信细节。

**被谁调用：** 上述 `DemeanorAnalysisService.analyze()` 方法。

```java
// ============================================================
// 方法1：analyzeDemeanor —— 组装表现分析提示词并调用 AI
// ============================================================
// 目的：向大模型发送一段面试回答文本，请求分析其中的语言表达表现。
//      返回的是原始 JSON 字符串，而不是结构化对象——这样调用方可以
//      选择缓存原始 JSON 还是缓存结构化对象。
//
// 参数：
//   sessionId  —— 面试会话 ID，拼入提示词便于 AI 理解上下文
//   transcript —— 语音转写或文本回答内容，是分析的主体
//
// 返回值：AI 返回的原始 JSON 字符串（格式见下方提示词中的 JSON 结构）
//
// 注意：提示词中明确要求 AI "只分析语言表达表现，不评价技术正确性"，
//       并禁止编造语音、视频、表情或肢体动作信息——因为 AI 有时会
//       幻觉式地添加"候选人的表情很自信"等无法从纯文本推断的信息。
// ============================================================
public String analyzeDemeanor(UUID sessionId, String transcript) {
    // 调用本类的 chat 方法（通用 AI 通信方法），
    // 传入专门为表现分析设计的提示词。
    // 提示词使用 Java 15 的 text block（"""..."""）编写，
    // 避免字符串拼接产生的转义地狱。
    return chat("""
            你是一名面试表达分析助手。
            请只分析候选人的语言表达表现，不评价技术正确性。
            不要编造语音、视频、表情或肢体动作信息。
            请返回严格的 JSON 对象，不要返回 Markdown 代码围栏或额外解释。
            JSON 必须包含以下字段：
            {
              "confidence": 0,
              "fillerCount": 0,
              "transcriptLength": 0,
              "clarityScore": 0,
              "pace": "normal",
              "feedback": ""
            }
            sessionId：%s
            回答文本：
            %s
            """.formatted(sessionId, transcript));
}

// ============================================================
// 方法2：parseDemeanor —— 解析 AI 返回的表现分析 JSON
// ============================================================
// 目的：将 AI 返回的原始 JSON 字符串解析成强类型的 DemeanorAnalysis 对象。
//      使用 parseJson（Facade 中定义的通用 JSON 解析方法）先转为 JsonNode，
//      然后逐字段提取值。每个字段都提供默认值，防止 AI 返回的 JSON 缺少字段
//      导致整个流程崩溃。
//
// 参数：
//   rawJson          —— AI 返回的原始 JSON 字符串
//   transcriptLength —— 转写文本的实际长度（由调用方传入，比 AI 计算的更准确）
//
// 返回值：DemeanorAnalysis 结构化对象
//
// 容错设计：
//   - pace 字段缺失时默认 "normal"（最安全的语速分类）
//   - feedback 字段缺失时默认空字符串
//   - 数值字段使用 Jackson 的 path().asXxx() 方法，即使路径不存在也返回 0
// ============================================================
public DemeanorAnalysisService.DemeanorAnalysis parseDemeanor(
        String rawJson,
        int transcriptLength
) {
    // 使用 Jackson 的 ObjectMapper（在 Facade 中已配置）将 JSON 解析为树模型
    var node = parseJson(rawJson);

    // 逐字段提取：path("字段名") 即使路径不存在也不会抛异常，
    // 后续的 asDouble() / asInt() / asText() 返回默认值。
    return new DemeanorAnalysisService.DemeanorAnalysis(
            node.path("confidence").asDouble(),    // 自信度，默认 0.0
            node.path("fillerCount").asInt(),       // 口头禅次数，默认 0
            transcriptLength,                       // 转写长度（由服务端传入，保证准确）
            node.path("clarityScore").asDouble(),   // 清晰度，默认 0.0
            node.path("pace").asText("normal"),     // 语速，默认 "normal"
            node.path("feedback").asText("")        // 反馈文本，默认 ""
    );
}
```

---

### 表现 2：表现分析的调用方与结果去向（无 Singleflight 缓存）

**本小节目的：** 说清楚 `DemeanorAnalysisService.analyze()` 在真实代码里由谁调用、结果怎么处理。**这里没有 Singleflight 合并、也没有缓存**——请以代码为准，不要照搬出题 / 评分模块的 Singleflight 写法。

> **与出题、评分模块的区别：** 出题（`generateQuestion`）和评分（`judge`）都走 `RedisSingleflight.executeFenced(...)` 做请求合并与防过期；但 `DemeanorAnalysisService` 全类没有引用 `RedisSingleflight`，`InterviewAiFacade.analyzeDemeanor()` 也只是直接 `chat(...)`，**既没有 singleflight、也没有缓存、更没有 5 分钟过期**。之所以不需要：整场面试的文本只在结束时分析一次，不存在并发重复请求的场景。

**真实调用链：**

1. 面试结束：前端 `POST /api/interviews/{sessionId}/end` 命中 `InterviewController.end()`
2. 取文本：`answers.loadSessionTranscript(sessionId)` 从 `interview_answer` 表按主键升序汇总整场答案文本
3. 判空：`transcript` 为 null 或空白时直接跳过，避免空文本白调一次 AI
4. 分析：`demeanor.analyze(sessionId, transcript)` → `InterviewAiFacade.analyzeDemeanor()` 组装提示词调 AI → `parseDemeanor()` 解析为 `DemeanorAnalysis`
5. 结果去向：`end()` **没有接收返回值，也没有落库、没有暴露接口**——`DemeanorAnalysis` 目前没有对应的表、也没有 REST 接口对外返回，本次分析只被"执行"。

**代码位置：** `InterviewController.end()`（完整片段见"从回答表读取转写文本（兜底）"小节）：

```java
// 文件：src/main/java/com/example/interview/application/InterviewController.java（节选：end）
String transcript = answers.loadSessionTranscript(sessionId);
if (transcript != null && !transcript.isBlank()) {
    demeanor.analyze(sessionId, transcript);   // 返回值未被使用
}
```

> **关于线程池：** `AsyncConfig` 里确实定义了 `demeanorAnalysisTaskExecutor` 线程池，但 `DemeanorAnalysisService.analyze()` 没有标注 `@Async`，因此该 Bean 当前没有被本链路使用；`analyze()` 实际是在 `end()` 的请求线程里同步执行的。

**关键设计要点：**
- 不合并、不缓存：整场文本只在面试结束时分析一次，没有并发重复请求，无需 Singleflight
- 不落库、不返回：`DemeanorAnalysis` 没有对应表与接口，返回值在 `end()` 中被丢弃
- 若将来要接入缓存或异步执行，应以出题 / 评分模块的 `RedisSingleflight` 与 `@Async` 为参照，并新增相应的表与接口

---

## 十五、Skill 模块

### 模块主要目的

把出题、评分、追问、报告和简历匹配所需的 AI 规则独立成 Markdown 文件，通过统一的加载和注册机制供业务使用。

**为什么需要 Skill 模块？** 直接将 AI 规则写在 Java 字符串中有以下问题：
1. **难以维护**：业务人员无法直接修改 Java 代码中的规则文本
2. **版本管理困难**：无法追踪规则文件的变更历史
3. **复用性差**：同样的规则分散在多个 Java 类中
4. **测试困难**：规则和代码耦合，难以单独测试规则效果

通过将规则抽取为 `src/main/resources/skills/` 下的独立 Markdown 文件，业务人员可以直接编辑规则文件，调整 AI 的角色设定、输入输出格式和约束条件，无需修改 Java 代码。

### 模块整体流程

```text
业务人员编写规则 Markdown 文件（在 resources/skills/ 目录下）
    -> 应用启动时 SkillLoader 读取 classpath 中的 Markdown 文件
    -> 解析为 SkillDefinition 对象（包含名称、版本、正文）
    -> 注册到 SkillRegistry（启动时自动注册所有默认 Skill）
    -> InterviewAiFacade 通过 skillPrompt(name) 从注册表取出正文，
       用「【业务 Skill：名称】…【业务 Skill 结束】」包起来
    -> 拼在提示词最前面，分别驱动出题 / 评分 / 追问 / 报告四条 AI 链路
```

**Skill 已经真正接入四条 AI 链路（不是待接入的占位）：**

`InterviewAiFacade` 构造时注入 `SkillRegistry`，在四个方法的提示词最前面各拼一次对应 Skill：

| AI 链路 | 方法 | 注入的 Skill |
| --- | --- | --- |
| 出题 | `generateQuestion(...)` | `skillPrompt("question-generation")` |
| 评分 | `chatJudge(...)`（由 `judge(...)` 触发） | `skillPrompt("interview-scoring")` |
| 追问 | `generateFollowUpText(...)` | `skillPrompt("follow-up-decision")` |
| 报告 | `generateReport(...)` | `skillPrompt("report-review")` |

`skillPrompt(name)` 内部用文本块把 Skill 正文包成 `【业务 Skill：名称】…【业务 Skill 结束】`，
再拼在原有提示词之前；如果名称写错，`skills.get(name)` 会抛
`IllegalStateException("skill not found: " + name)`，第一次调用就暴露，
不会静默退化成"没有规则的提示词"。

### Skill 1：创建规则文件

**本小节目的：** 用 Markdown 编写可读、可维护的 AI 角色、输入、输出和约束规则。

**本小节流程：**
1. 分析面试流程中的 AI 调用场景，划分不同 Skill 类型
2. 为每个 Skill 编写独立的 Markdown 文件
3. 约定文件命名规则（`{skill-name}.md`）
4. 确认文件可被 classpath 加载

**文件列表（位于 `src/main/resources/skills/` 目录下）：**

```text
resume-matching.md      —— 简历匹配规则：大模型抽取简历技能 + 代码做集合比对算匹配度
report-review.md        —— 报告生成规则：生成固定四章节的 Markdown 面试总结报告
question-generation.md  —— 出题规则：根据岗位和简历生成中文技术面试题
interview-scoring.md    —— 评分规则：对面试答案做多维度评分并输出 JSON
follow-up-decision.md   —— 追问决策规则：按分数段选口径，大模型只产出追问正文
```

**规则文件的内容结构（以 `question-generation.md` 为例）：**

一级标题固定为 `# Skill：<name>（<中文名>）`，正文按场景分节。以出题规则为例，共五节：

- **一、这个 Skill 是做什么的**：说明用途，并点名调用入口与调用时机
  （`question-generation.md` 写明由 `InterviewAiFacade.generateQuestion(...)`
  和预生成服务 `NextQuestionPreGeneratorService` 调用）。
- **二、输入**：用表格列出模型能拿到的参数（如 `positionName`、`resumeContent`、`sequenceNo`）。
- **三、输出契约（必须严格遵守）**：规定返回结构。出题要求**只返回 JSON**
  `{"content":"题目正文","category":"考察分类"}`，不要 Markdown 围栏和多余文字。
- **四、出题规则**：具体业务约束（如"以简历为事实来源""禁止编造""难度递进""一题一问"）。
- **五、禁止事项**：明确不许做的事（如"不要输出 JSON 之外的任何字符""不要泄露答案"）。

其余文件沿用同一骨架，按自身场景调整章节：`follow-up-decision.md` 把第二节换成
"分数 → 策略映射"表，`resume-matching.md` 拆成"大模型抽技能"和"代码算匹配"两段。

规则文件把 AI 应该遵守的业务标准单独保存，后续调整规则不需要改 Java 字符串。

---

### Skill 2：创建规则对象和加载器

**本小节目的：** 将 Markdown 文件转换成程序可读取的规则对象，并统一处理文件读取异常。

**本小节流程：**
1. 定义 `SkillDefinition` record —— 规则文件被加载到内存后的不可变表示
2. 编写 `SkillLoader` 组件 —— 从 classpath 读取 Markdown 文件并解析为 `SkillDefinition`
3. 统一处理文件不存在、IO 异常等错误场景

**文件列表：**

```text
src/main/java/com/example/interview/skill/SkillDefinition.java
src/main/java/com/example/interview/skill/SkillLoader.java
```

**加载流程：**

```text
classpath 下的 Markdown 文件（如 /skills/question-generation.md）
    -> Spring ResourceLoader 读取文件内容（UTF-8 编码）
    -> SkillLoader 将文件内容包装为 SkillDefinition 对象
    -> SkillDefinition 保存结构化字段：名称、版本、用途(purpose)、入口(entryPoints)、输入(inputs)、输出契约(outputContract)、正文(content)
    -> 如果文件不存在或读取失败，抛出 IllegalStateException（启动即失败，避免运行时才发现）
```

---

#### 文件：`SkillDefinition.java`

**文件位置：** `src/main/java/com/example/interview/skill/SkillDefinition.java`

**文件作用：** 定义 Skill 的不可变数据结构。一份 Skill Markdown 文件被加载到内存后，会用这个 record 对象来表示。record 的不可变性确保 Skill 内容在程序运行期间不会被意外修改。

**依赖关系：**
- 无外部依赖（纯数据类）
- 被 `SkillLoader`（加载时创建）和 `SkillRegistry`（存储和查询时使用）引用
- 被 `SkillController`（通过 REST API 对外暴露）序列化为 JSON 返回给前端或调试工具

```java
package com.example.interview.skill;

import java.util.List;

// ============================================================
// Skill 定义 —— 结构化的业务技能，可被 Code Agent 直接消费
// ============================================================
// 原本只是 (name, version, content) 三字段的裸 Markdown 壳，
// 升级后把"技能元数据 + 规则正文"拆成可编程访问的字段：
//   name/version     —— 标识与版本（Code Agent 用来寻址）
//   purpose          —— 一句话用途（概述）
//   entryPoints      —— 调用入口（自动消费点，标识"哪些链路会用到它"）
//   inputs           —— 输入契约（参数名清单）
//   outputContract   —— 输出契约（返回结构与格式约束）
//   content          —— 规则主体（Markdown，注入提示词的正文）
//
// 为什么这样分层："可被 Code Agent 直接消费"意味着 Skill 要能被程序读懂、
// 而不只是塞给大模型的一段文本。元数据字段让代码能按用途/入口/契约检索和调度，
// content 则是最底层的"规则正文"，由统一注入点（InterviewAiFacade.skillPrompt）
// 自动拼进提示词。整条定义经 SkillController 以 JSON 暴露给外部消费方。
// ============================================================
public record SkillDefinition (
    String name,
    String version,
    String purpose,
    List<String> entryPoints,
    List<String> inputs,
    String outputContract,
    String content
) {}
```

---

#### 文件：`SkillLoader.java`

**文件位置：** `src/main/java/com/example/interview/skill/SkillLoader.java`

**文件作用：** 从 classpath 读取 Markdown 规则文件，并将其包装为 `SkillDefinition` 对象。使用 Spring 的 `ResourceLoader` 抽象，不直接依赖文件系统路径，这样在单元测试中可以轻松地 mock 文件读取逻辑。

**依赖关系：**
- 依赖 Spring Framework 的 `ResourceLoader` 和 `Resource` 接口（Spring 核心模块，无需额外引入）
- 输出依赖：创建 `SkillDefinition` 对象
- 被谁调用：`SkillRegistry`（在注册 Skill 时调用 `loader.load(...)`）
- 异常处理：如果文件不存在或 IO 异常，抛出 `IllegalStateException`（让应用启动失败，暴露问题）

```java
package com.example.interview.skill;

import org.springframework.core.io.Resource;      // Spring 资源抽象，可表示 classpath、文件系统、URL 等来源
import org.springframework.core.io.ResourceLoader; // Spring 资源加载器，支持 "classpath:" 等前缀
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

// ============================================================
// Skill 加载器 —— 读取 markdown 并解析出结构化字段
// ============================================================
// 每个 skill 文件头部有一段 front matter（用 --- 包裹的 key: value 行）：
//   ---
//   purpose: 一句话用途
//   entry-points: A, B
//   inputs: p1, p2
//   output: 输出契约
//   ---
//   正文...
// load() 把元数据剥离出来，剩余正文作为 SkillDefinition.content。
// 没有 front matter 的旧文件也能加载（各元数据字段退化为空），保证向后兼容。
// ============================================================
@Component
public class SkillLoader {
    private final ResourceLoader resources;

    public SkillLoader(ResourceLoader resources) {
        this.resources = resources;
    }

    public SkillDefinition load(String name, String version) {
        Resource resource = resources.getResource("classpath:skills/" + name + ".md");
        try {
            String raw = resource.getContentAsString(StandardCharsets.UTF_8);
            Meta meta = parse(raw);
            return new SkillDefinition(
                    name,
                    version,
                    meta.purpose(),
                    meta.entryPoints(),
                    meta.inputs(),
                    meta.output(),
                    // 正文去掉首尾空白，避免注入提示词时带上多余换行。
                    meta.body().trim()
            );
        } catch (IOException e) {
            throw new IllegalStateException(
                    "cannot load skill:" + name,
                    e
            );
        }
    }

    // ============================================================
    // 私有：解析 front matter，返回元数据 + 正文
    // ============================================================
    private Meta parse(String raw) {
        List<String> lines = List.of(raw.split("\r?\n"));
        // 第一行不是 "---"：没有 front matter，整篇都当正文。
        if (lines.isEmpty() || !lines.get(0).trim().equals("---")) {
            return new Meta("", List.of(), List.of(), "", raw);
        }
        String purpose = "";
        String output = "";
        List<String> entryPoints = new ArrayList<>();
        List<String> inputs = new ArrayList<>();
        int index = 1;
        // 逐行读到第二个 "---"，其间的行按 key: value 切分。
        for (; index < lines.size(); index += 1) {
            String line = lines.get(index);
            if (line.trim().equals("---")) {
                index += 1;   // 再跳一行，index 指向正文起点
                break;
            }
            int colon = line.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "purpose" -> purpose = value;
                case "output" -> output = value;
                case "entry-points" -> entryPoints = splitComma(value);
                case "inputs" -> inputs = splitComma(value);
                default -> { /* 未知 key 忽略，不阻塞加载 */ }
            }
        }
        // 正文 = front matter 之后的所有行。
        String body = String.join("\n", lines.subList(index, lines.size()));
        return new Meta(purpose, entryPoints, inputs, output, body);
    }

    // 把 "a, b, c" 拆成去空白、滤空串的字符串列表（元数据里 entry-points/inputs 都是逗号分隔）。
    private List<String> splitComma(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    // ============================================================
    // 内部记录：front matter 解析结果
    // ============================================================
    private record Meta(
            String purpose,
            List<String> entryPoints,
            List<String> inputs,
            String output,
            String body
    ) {
    }
}
```

---

### Skill 3：创建注册表和接口

**本小节目的：** 提供按名称查找 Skill 的统一入口，让业务代码不需要直接依赖文件路径。

**本小节流程：**
1. 创建 `SkillRegistry` 组件：启动时自动加载所有默认 Skill
2. 按名称注册到 `ConcurrentHashMap`：保证线程安全地存取
3. 提供 `get(name)` 查询接口：未找到时抛异常
4. 创建 `SkillController` REST 控制器：通过 HTTP 对外暴露 Skill 查询能力
5. `InterviewAiFacade` 按名称从注册表获取 Skill 内容，分别拼入出题 / 评分 / 追问 / 报告四条提示词

**开始本节前先确认：**
- `SkillDefinition.java` 和 `SkillLoader.java` 已经编写完成
- `src/main/resources/skills/` 目录下的 Markdown 文件已经创建
- `SkillController` 只能调用注册表查询 Skill，不能直接读取磁盘文件（遵循依赖倒置原则）

**文件列表：**

```text
src/main/java/com/example/interview/skill/SkillRegistry.java
src/main/java/com/example/interview/skill/SkillController.java
```

---

#### 文件：`SkillRegistry.java`

**文件位置：** `src/main/java/com/example/interview/skill/SkillRegistry.java`

**文件作用：** Skill 注册表，在应用启动时自动加载所有规则文件并集中管理。业务代码通过 `registry.get("question-generation")` 获取规则内容，不需要知道文件是存储在 classpath、数据库还是远程服务器。

**依赖关系：**
- 依赖 `SkillLoader`（上述文件）：真正执行文件读取的组件
- 被谁调用：
  - `InterviewAiFacade`（在前面的 AI 模块中定义）：组装提示词时获取 Skill 内容
  - `SkillController`：通过 REST API 对外暴露查询接口
- 内部数据结构：`ConcurrentHashMap`（线程安全的哈希表，支持高并发读取）

```java
package com.example.interview.skill;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================
// Skill 注册表 —— 集中管理所有已加载的 Skill 定义
// ============================================================
// 职责：
//   1. 应用启动时通过构造器自动加载默认的 Skill 列表
//   2. 提供按名称查询 Skill 的线程安全方法
//   3. 支持运行时动态注册新的 Skill（register 方法）
//
// 为什么用 ConcurrentHashMap 而不是 HashMap？
//   - 面试系统可能有多个线程同时调用 get() 方法
//     （如多个面试会话并行进行），ConcurrentHashMap 保证
//     读操作不会阻塞，且不会产生 ConcurrentModificationException。
//   - 虽然现阶段 registerDefaults() 只在启动时调用一次，
//     但 register() 方法是 public 的，未来可能支持热加载。
// ============================================================
@Component  // 声明为 Spring 组件，Spring 容器启动时会自动调用构造器完成初始化
public class SkillRegistry {

    // Skill 加载器：负责从文件系统读取规则内容
    private final SkillLoader loader;

    // Skill 定义缓存：key 是 Skill 名称（如 "question-generation"），
    // value 是 SkillDefinition 对象。
    // ConcurrentHashMap 保证线程安全的同时不牺牲读性能。
    private final Map<String, SkillDefinition> definitions =
            new ConcurrentHashMap<>();

    /**
     * 构造器注入 SkillLoader，并自动注册默认的 Skill 列表。
     * 这是 Spring 容器启动时的初始化流程：
     *   1. Spring 创建 SkillLoader 实例
     *   2. Spring 创建 SkillRegistry 实例（通过此构造器）
     *   3. 构造器调用 registerDefaults() 加载所有 Markdown 文件
     *   4. 如果任何一个文件不存在，构造器抛出异常，应用启动失败
     *
     * @param loader Skill 文件加载器
     */
    public SkillRegistry(SkillLoader loader) {
        this.loader = loader;
        registerDefaults();  // 启动时自动注册所有默认 Skill
    }

    /**
     * 注册所有默认的 Skill。
     * 每个方法调用对应一个 Skill 文件，如果某个文件不存在，
     * loader.load() 会抛出 IllegalStateException 并终止启动过程。
     * 这种"快速失败"（fail-fast）设计能在开发阶段尽早发现问题。
     */
    private void registerDefaults() {
        register("question-generation");  // 出题规则
        register("interview-scoring");    // 评分规则
        register("follow-up-decision");   // 追问决策规则
        register("report-review");        // 报告生成规则
        register("resume-matching");      // 简历匹配规则
    }

    /**
     * 注册指定名称的 Skill。
     * 调用 loader.load() 读取文件并创建 SkillDefinition，
     * 然后存入 definitions 缓存。
     *
     * @param name Skill 名称，对应 "classpath:skills/{name}.md"
     * @throws IllegalStateException 如果文件不存在或 IO 异常（由 loader.load() 抛出）
     */
    public void register(String name) {
        definitions.put(
                name,                           // key：Skill 名称
                loader.load(name, "v1")          // value：从文件加载的 Skill 定义（版本 v1）
        );
    }

    /**
     * 按名称查询 Skill 定义。
     *
     * @param name Skill 名称，如 "question-generation"
     * @return SkillDefinition 对象（包含名称、版本、正文）
     * @throws IllegalStateException 如果指定名称的 Skill 未注册
     */
    public SkillDefinition get(String name) {
        // ConcurrentHashMap.get() 是线程安全的，
        // 多个线程同时调用 get() 不会相互干扰。
        SkillDefinition definition = definitions.get(name);

        // 如果未找到，抛出明确的异常信息，
        // 包含出错的 Skill 名称，方便排查配置错误。
        if (definition == null) {
            throw new IllegalStateException("skill not found: " + name);
        }
        return definition;
    }
}
```

---

#### 文件：`SkillController.java`

**文件位置：** `src/main/java/com/example/interview/skill/SkillController.java`

**文件作用：** 对外提供 RESTful API，允许前端或调试工具通过 HTTP 查询 Skill 定义。这个接口只负责按名称查询 Skill，不能直接访问磁盘（通过 `SkillRegistry` 间接获取）。

**依赖关系：**
- 依赖 `SkillRegistry`（上述文件）：通过注册表获取 Skill 定义
- 被谁调用：前端页面（用于调试和展示）、监控工具、运维脚本
- 输出：HTTP GET `/api/skills/{name}` 返回 Skill 定义的 JSON 表示

```java
package com.example.interview.skill;

import org.springframework.web.bind.annotation.*;

// ============================================================
// Skill 查询接口 —— 通过 REST API 暴露 Skill 定义查询能力
// ============================================================
// 设计原则：
//   - 只读接口：不提供 POST/PUT/DELETE 方法，避免运行时修改规则
//   - 单一职责：只负责查询，不负责加载和注册
//   - 只通过 registry 访问数据，不直接操作文件或缓存
//
// 为什么不直接在 SkillRegistry 上加 @RestController？
//   - 职责分离：SkillRegistry 是业务组件，SkillController 是 Web 层
//   - 便于测试：业务组件不需要处理 HTTP 相关的测试逻辑
//   - 灵活性：未来可以增加认证、限流、缓存等 Web 层功能而不影响业务代码
// ============================================================
@RestController                         // 声明为 RESTful 控制器
@RequestMapping("/api/skills")          // 所有接口以 /api/skills 为前缀
public class SkillController {

    // Skill 注册表：提供线程安全的查询能力
    private final SkillRegistry registry;

    /**
     * 构造器注入 SkillRegistry。
     *
     * @param registry Skill 注册表实例
     */
    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }

    /**
     * 按名称查询 Skill 定义。
     *
     * HTTP 请求示例：
     *   GET /api/skills/question-generation
     *
     * 返回 JSON 示例：
     *   {
     *     "name": "question-generation",
     *     "version": "v1",
     *     "content": "# 出题规则\n\n你是一名资深的技术面试官..."
     *   }
     *
     * @param name Skill 名称（URL 路径变量）
     * @return SkillDefinition 对象（Spring Boot 自动序列化为 JSON）
     * @throws IllegalArgumentException 如果 Skill 未注册（返回 HTTP 400）
     */
    @GetMapping("/{name}")
    public SkillDefinition get(@PathVariable String name) {
        // 委托给注册表查询，如果未找到会抛出 IllegalArgumentException，
        // Spring Boot 默认会将其转换为 HTTP 400 Bad Request 响应。
        return registry.get(name);
    }
}
```

**Skill 与状态机的关系说明：**
- **Skill** 是 AI 的业务知识（如"如何出题""如何评分"），定义了 AI 的行为规则
- **状态机**（在前面的「状态机模块」中定义）是面试流程规则（如"什么状态可以提交答案""什么状态可以结束面试"）
- 两者职责不同：Skill 管"怎么做"，状态机管"能不能做"

---

## 十六、前端完整页面模块

### 模块主要目的

把后端接口和实时状态组织成完整的用户界面，让用户可以从登录开始，完成简历准备、面试答题和报告查看的完整流程。

**本模块涉及的前端技术栈：**
- React 18 + Vite 5（使用 Hooks：useState、useEffect、useMemo、useRef）
- 原生 fetch API（没有引入 axios 等第三方 HTTP 库）
- 原生 WebSocket（面试会话保活 + 阿里云 NLS 实时转写）
- localStorage 持久化登录状态（`interview_token` / `interview_user`）
- 样式内联在 `main.jsx` 末尾的模板字符串里（没有独立 CSS 文件，也没有 CSS 模块或 UI 框架）

### 模块整体流程

```text
用户打开页面
    -> 从 localStorage 恢复登录状态
       -> 未登录：显示登录/注册页面
       -> 已登录：进入准备页面
    -> 准备页面：选择岗位、上传简历、等待解析
    -> 创建面试会话，进入答题页面
    -> 答题页面：加载题目、提交文本/语音答案、等待评分
    -> 重复答题直到面试结束
    -> 报告页面：展示综合评分和逐题反馈
```

### 前端 1：创建 React 页面状态

**本小节目的：** 建立用户、简历、岗位、会话、题目和报告等页面状态的统一管理方式。

**本小节流程：**
1. 定义从 localStorage 恢复用户登录状态的工具函数
2. 定义后端返回数据的字段规范化函数
3. 封装统一 HTTP 请求函数（自动携带 JWT Token、区分 JSON 与文件上传、401 自动登出）
4. 定义幂等请求 ID 生成函数 `idForRequest`
5. 使用 `useState` 声明所有页面级状态变量
6. 每个状态变量对应一个业务对象或页面阶段

**文件位置：** `frontend/src/main.jsx`

**核心状态说明：**

所有页面状态集中在 `main.jsx` 中集中声明，原因如下：
- 这个应用是单页的（所有页面切换通过 `screen` 状态控制），不需要 React Router
- 状态集中管理便于理解完整的页面数据流
- 每个 `useState` 都对应一个明确的业务对象或页面阶段

**为什么请求都写成相对路径：** `main.jsx` 与 `nlsTranscriptionClient.js` 里的请求都是 `/api/...` 与 `/ws/...` 这样的同源相对路径。开发时前端跑在 5173、后端跑在 18080，靠 `vite.config.js` 里的代理把 `/api`（HTTP）和 `/ws`（WebSocket）两类前缀转发到 `localhost:18080`；生产环境由 `VITE_API_ROOT` 注入真实后端地址。这属于前端脚手架的一部分，代码见本章末尾的「前端脚手架」小节。

下面这段是 `main.jsx` 的模块头部与状态声明部分（节选，`escapeHtml` / `markdownToHtml` 两个 Markdown 工具函数放在「前端 5：编写报告页面」里讲）。所有代码与项目真实代码逐字一致。

```javascript
// 文件：frontend/src/main.jsx（节选：模块头部 + 状态声明）
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
// 从这里开始是新增的：阿里云 NLS 实时转写客户端（麦克风采集 → 16k PCM → NLS → 分片上报后端）。
// 原来这段录音逻辑是"MediaRecorder 录完就丢"的占位实现，换成真实链路。
import { NlsTranscriptionClient } from './nlsTranscriptionClient';
// 新增结束

const API_ROOT = import.meta.env.VITE_API_ROOT || '';

const restoreStoredUser = () => {
  try {
    const raw = localStorage.getItem('interview_user');
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
};

const normalizeResume = value => value
  ? { ...value, id: String(value.id), userId: String(value.userId) }
  : null;

const request = async (url, options = {}) => {
  const token = localStorage.getItem('interview_token');
  const isForm = options.body instanceof FormData;
  const response = await fetch(`${API_ROOT}${url}`, {
    ...options,
    headers: {
      ...(isForm ? {} : { 'Content-Type': 'application/json' }),
      ...(options.headers || {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    }
  });
  const body = await response.text();
  if (!response.ok) {
    if (response.status === 401) {
      localStorage.removeItem('interview_token');
      localStorage.removeItem('interview_user');
      window.location.reload();
    }
    let message = body;
    try {
      message = JSON.parse(body).message || body;
    } catch {
      // Keep the raw response when the server did not return JSON.
    }
    throw new Error(message || `HTTP ${response.status}`);
  }
  return body ? JSON.parse(body) : null;
};

const idForRequest = () => (
  globalThis.crypto?.randomUUID?.() ||
  `${Date.now()}-${Math.random().toString(16).slice(2)}`
);

const defaultPositions = [
  { id: 'backend-java', name: 'Java 后端开发工程师', description: 'Spring Boot、数据库与分布式系统' },
  { id: 'frontend-react', name: '前端开发工程师', description: 'React、工程化与交互体验' },
  { id: 'fullstack', name: '全栈开发工程师', description: '前后端协作与产品落地' }
];

function App() {
  const [user, setUser] = useState(restoreStoredUser);
  const [form, setForm] = useState({ username: '', password: '', displayName: '' });
  const [register, setRegister] = useState(false);
  const [positions, setPositions] = useState([]);
  const [position, setPosition] = useState(null);
  const [resume, setResume] = useState(null);
  const [session, setSession] = useState(null);
  const [question, setQuestion] = useState(null);
  // 从这里开始是新增的：当前这道原题派生出的追问。
  // 追问存在 interview_follow_up 表、不作为新题插入 interview_question，
  // 所以它不会出现在 question 里，必须单独查出来放这里。
  // 非空表示"现在要答的是追问"，提交答案时会带上 followUp=true。
  const [followUp, setFollowUp] = useState(null);
  // 新增结束
  const [answer, setAnswer] = useState('');
  const [transcript, setTranscript] = useState('');
  const [report, setReport] = useState(null);
  const [screen, setScreen] = useState('SETUP');
  const [busy, setBusy] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [recording, setRecording] = useState(false);
  // 从这里开始是修改的：原来这里是 MediaRecorder 的 ref，现在换成 NLS 转写客户端
  // （它内部自己持有两条 WebSocket 与 AudioContext，stop() 时统一释放）。
  const nlsClient = useRef(null);
  // 面试 WebSocket 的 ref：会话开始时建立，页面卸载或会话切换时关闭。
  const socketRef = useRef(null);
  // 修改结束
  const requestIdRef = useRef(null);

  const userId = user?.userId;
  const currentAnswer = useMemo(() => (answer || transcript).trim(), [answer, transcript]);
```

**这段代码里几个和旧版本不一样的地方（教学重点）：**

1. **请求函数叫 `request`，不叫 `api`，并且前缀是 `API_ROOT`**：`const API_ROOT = import.meta.env.VITE_API_ROOT || '';`。开发环境该值为空字符串，所有请求都写成同源相对路径（`/api/...`、`/ws/...`），由 `vite.config.js` 的代理转发到后端；生产环境通过 `VITE_API_ROOT` 注入真实域名。
2. **上传简历走 `FormData`**：`const isForm = options.body instanceof FormData;` 用来判断是不是文件上传。是表单时**不能**设置 `Content-Type`（否则浏览器不会自动补上 `multipart/form-data; boundary=...`），代码里用 `...(isForm ? {} : { 'Content-Type': 'application/json' })` 处理。
3. **401 自动登出**：拿到 401 时清掉 localStorage 里的 token 和 user 并 `window.location.reload()`，用户会被弹回登录页。
4. **错误消息优先取后端的 `message` 字段**：`JSON.parse(body).message || body`，解析失败才用原始响应体。
5. **`requestId` 用 `idForRequest()` 生成**：优先用 `crypto.randomUUID()`，浏览器不支持时退化为「时间戳 + 随机数」。
6. **状态变多了**：除了 `user/positions/position/resume/session/question/answer/transcript/screen`，还有新增的 `followUp`（当前追问）、`busy`（全局请求中）、`submitting`（提交答案中）、`error`/`notice`（错误与提示）、`recording`（是否正在录音），以及两个 ref：`nlsClient`（阿里云 NLS 转写客户端）和 `socketRef`（面试 WebSocket）。
7. **`currentAnswer` 用 `useMemo` 派生**：`(answer || transcript).trim()`，文本优先、语音转写兜底，两者都空时提交会报错。

**这些状态与后端接口的对应关系：**

| 前端状态      | 后端接口/数据               | 说明                           |
|---------------|----------------------------|--------------------------------|
| user          | /api/auth/register, /login | 用户认证                        |
| positions     | /api/positions             | 岗位列表                        |
| position      | 用户选择                    | 选中的岗位 ID                   |
| resume        | /api/resumes（GET/POST）    | 上传简历（POST）与查询已解析简历（GET） |
| session       | /api/interviews（POST，查询参数）| 创建面试会话；/start、/state 推进与查询 |
| question      | /api/interviews/{id}/questions | 取下一题（POST，预生成题优先）    |
| followUp      | /api/interviews/{id}/follow-up?questionId= | 查原题的追问    |
| answer        | 用户输入                    | 文本答案                        |
| transcript    | 阿里云 NLS 转写分片          | 语音转写文本（临时预览）         |
| report        | /api/reports/{id}（POST + /status + GET）| 报告触发生成、轮询状态与读取正文 |
| socketRef     | /ws/interviews/{id}        | 面试 WebSocket 连接（ping/pong 保活） |
| screen        | 前端内部状态                | 控制当前显示哪个页面             |

---

### 前端 2：编写登录注册页面

**本小节目的：** 完成用户注册、登录和登录状态恢复，作为所有业务页面的入口。

**本小节流程：**
1. 用户输入用户名和密码（注册时还需输入显示名称）
2. 点击"登录"或"注册并登录"按钮
3. `auth()` 按 `register` 状态选择 `/api/auth/login` 或 `/api/auth/register`，POST 一个 JSON
4. 成功后把后端返回的 `token` 与整个 user 对象写进 localStorage
5. 更新 `user` 状态，页面自动切换到准备页面

**条件渲染逻辑：** 当 `user` 为空时，渲染认证页面；当 `user` 存在时，渲染业务页面。

**为什么所有异步操作都包一层 `run()`：** 每个会发起请求的动作（登录、上传、开始面试……）都写成 `run(async () => { ... })`。`run` 负责统一置位 `busy`、清空 `error`/`notice`、捕获异常并写入 `error`、最后在 `finally` 里恢复 `busy`。这样每个业务函数只管写正常逻辑，不用各自重复 try/catch 和按钮 loading 状态。

```javascript
// 文件：frontend/src/main.jsx（节选：统一动作包装 + 认证 + 登出）
  const run = async action => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await action();
    } catch (caught) {
      setError(caught.message || '请求失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  };

  const auth = () => run(async () => {
    const endpoint = register ? '/api/auth/register' : '/api/auth/login';
    const payload = register
      ? form
      : { username: form.username, password: form.password };
    const value = await request(endpoint, {
      method: 'POST',
      body: JSON.stringify(payload)
    });
    localStorage.setItem('interview_token', value.token);
    localStorage.setItem('interview_user', JSON.stringify(value));
    setUser(value);
  });

  const logout = () => {
    localStorage.removeItem('interview_token');
    localStorage.removeItem('interview_user');
    setUser(null);
    setSession(null);
    setScreen('SETUP');
  };
```

登录/注册页面本身（`user` 为空时的返回值）：

```javascript
// 文件：frontend/src/main.jsx（节选：登录/注册页面）
  if (!user) {
    return (
      <>
        <style>{styles}</style>
        <main className="auth-shell">
          <section className="auth-panel">
            <div className="brand-mark">AI</div>
            <p className="eyebrow">INTERVIEW WORKSPACE</p>
            <h1>码上面试</h1>
            <p className="muted">从简历解析到面试复盘，集中完成准备与练习。</p>
            <div className="form-stack">
              <label>用户名<input value={form.username} onChange={event => setForm({ ...form, username: event.target.value })} placeholder="输入用户名" /></label>
              {register && <label>显示名称<input value={form.displayName} onChange={event => setForm({ ...form, displayName: event.target.value })} placeholder="面试页面显示名称" /></label>}
              <label>密码<input type="password" value={form.password} onChange={event => setForm({ ...form, password: event.target.value })} placeholder="输入密码" onKeyDown={event => event.key === 'Enter' && auth()} /></label>
              {error && <p className="error">{error}</p>}
              <button className="primary-button" disabled={busy} onClick={auth}>{busy ? '处理中...' : register ? '注册并登录' : '登录'}</button>
              <button className="link-button" onClick={() => { setRegister(!register); setError(''); }}>{register ? '已有账号，返回登录' : '创建新账号'}</button>
            </div>
          </section>
        </main>
      </>
    );
  }
```

**几个要点：**
- 注册时的 payload 直接是整个 `form`（含 `displayName`）；登录时只取 `username` 与 `password` 两个字段。这与后端 `RegisterRequest` / `LoginRequest` 的字段一一对应。
- 密码框绑了 `onKeyDown={event => event.key === 'Enter' && auth()}`，在输入框里回车即提交。
- "创建新账号 / 已有账号，返回登录"这个按钮切换 `register`，并顺手清掉上一次的错误提示。
- 页面整体用 `auth-shell` / `auth-panel` / `form-stack` 等类名布局，这些样式定义在文件底部的 `styles` 常量里（见「前端 6：编写样式」）。

---

### 前端 3：编写准备面试页面

**本小节目的：** 让用户上传简历、查看解析结果、选择岗位并创建面试。

**本小节流程：**
1. 页面加载时（`useEffect`）调用 `/api/positions` 获取岗位列表、调用 `/api/resumes?userId=` 查询已解析简历
2. 用户在岗位卡片里点选目标岗位（不是下拉框，是一排可点击的 `position-option`）
3. 用户选择简历文件并上传，`POST /api/resumes`（`multipart/form-data`）
4. 后端**同步**解析简历，接口返回时状态已经是 `PARSED`（不需要前端轮询）
5. 简历状态为 `PARSED` 后，"开始面试"按钮才可点击
6. 点击"开始面试"：创建会话 → 调用 `/start` → 取第一题，切换到答题页面

**准备页面包含的 UI 元素：**

```text
岗位卡片列表（position-list）—— 展示 positions 列表，点选后 setPosition
简历文件选择器（upload-box）  —— type="file" 的 input，accept=".pdf,.doc,.docx"
上传状态提示（resume-status） —— 展示文件名与"解析完成，可以开始面试"
开始面试按钮（primary-button wide）—— resume.status === 'PARSED' 时才可点击
```

**开始按钮的启用条件：** `disabled={busy || !resume || resume.status !== 'PARSED'}`。简历必须解析完成，否则无法开始面试——后端 `InterviewController.create` 也会校验 `resume.getStatus()` 必须是 `PARSED`。

**页面加载时的数据拉取（`useEffect`）：**

```javascript
// 文件：frontend/src/main.jsx（节选：准备页面加载数据）
  useEffect(() => {
    if (!user) return;
    request('/api/positions')
      .then(items => {
        const values = Array.isArray(items) ? items : [];
        setPositions(values.length ? values : defaultPositions);
        setPosition(values[0] || defaultPositions[0]);
      })
      .catch(() => {
        setPositions(defaultPositions);
        setPosition(defaultPositions[0]);
      });

    request(`/api/resumes?userId=${encodeURIComponent(userId)}`)
      .then(value => setResume(normalizeResume(value)))
      .catch(() => setResume(null));
  }, [user, userId]);
```

**上传简历与开始面试：**

```javascript
// 文件：frontend/src/main.jsx（节选：上传简历 + 开始面试）
  const uploadResume = file => run(async () => {
    if (!file) return;
    const data = new FormData();
    data.append('userId', userId);
    data.append('file', file);
    const value = await request('/api/resumes', { method: 'POST', body: data });
    setResume(normalizeResume(value));
    setNotice('简历解析完成，可以开始面试。');
  });

  const startInterview = () => run(async () => {
    if (!resume || resume.status !== 'PARSED' || !position) {
      throw new Error('请先上传并解析简历，再选择岗位');
    }
    // 从这里开始是修改的：创建面试。后端 InterviewController.create 用的是
    // @RequestParam（查询参数），不是 JSON 请求体，原来发 JSON 会 400；
    // 另外创建完必须再调一次 /start，否则后端 preparationTasks.prepare(sessionId)
    // 不会跑，后面取题拿不到东西。
    const query = new URLSearchParams({
      userId: String(userId),
      resumeId: String(resume.id),
      positionId: String(position.id)
    });
    const value = await request(`/api/interviews?${query}`, {
      method: 'POST'
    });
    setSession(value);
    setScreen('ANSWERING');
    // 启动面试：状态机 INIT → ...，同时异步准备第一题。
    await request(`/api/interviews/${value.sessionId}/start`, {
      method: 'POST'
    });
    // 取第一题：后端只有 POST .../questions 这个入口（没有 GET .../questions/current）。
    // 从这里开始是修改的：改用统一的 takeQuestion——后端出首题可能碰 singleflight
    // 可重试超时(500)或短暂非 ACTIVE，takeQuestion 会 10 次重试直到命中就绪，替代一次性 request。
    const first = await takeQuestion(value.sessionId);
    setQuestion(first);
    // 新增：开始一场新面试时清掉上一场残留的追问，否则第一题会被上一场的追问盖住。
    setFollowUp(null);
    // 修改结束
  });
```

**准备页面的 JSX：**

```javascript
// 文件：frontend/src/main.jsx（节选：准备页面 JSX）
            {screen === 'SETUP' && (
              <div className="setup-grid">
                <section className="section-block">
                  <div className="section-title"><span className="number">01</span><div><h2>选择目标岗位</h2><p>确定本次练习的重点方向</p></div></div>
                  <div className="position-list">
                    {positions.map(item => <button key={item.id} className={`position-option ${position?.id === item.id ? 'selected' : ''}`} onClick={() => setPosition(item)}><span><strong>{item.name}</strong><small>{item.description || '为你生成匹配的面试题'}</small></span><span className="radio" /></button>)}
                  </div>
                </section>
                <section className="section-block">
                  <div className="section-title"><span className="number">02</span><div><h2>上传你的简历</h2><p>支持 PDF、DOC、DOCX，最大 10MB</p></div></div>
                  <label className="upload-box"><input type="file" accept=".pdf,.doc,.docx" onChange={event => uploadResume(event.target.files?.[0])} /><span className="upload-icon">↑</span><strong>{busy ? '正在解析...' : '点击选择简历文件'}</strong><small>{resume?.fileName || '上传后将自动提取简历内容'}</small></label>
                  {resume && <div className="resume-status"><span className="check">✓</span><div><strong>{resume.fileName}</strong><small>{resume.status === 'PARSED' ? '解析完成，可以开始面试' : '正在解析简历'}</small></div></div>}
                  <button className="primary-button wide" disabled={busy || !resume || resume.status !== 'PARSED'} onClick={startInterview}>开始面试 <span>→</span></button>
                </section>
              </div>
            )}
```

**几个要点：**
- **创建会话是查询参数，不是 JSON body**：`POST /api/interviews?userId=&positionId=&resumeId=`，三个参数都是 `@RequestParam`。旧写法发 JSON 会直接 400。
- **创建完必须再调 `/start`**：`POST /api/interviews/{sessionId}/start` 才会触发 `preparationTasks.prepare(sessionId)` 去异步准备第一题。
- **取题只有 POST 一个入口**：`POST /api/interviews/{sessionId}/questions`，没有 `GET .../questions/current`。
- 岗位列表接口 `/api/positions` 失败时回退到内置的 `defaultPositions`，保证页面不会白屏。

---

### 前端 4：编写答题页面

**本小节目的：** 展示当前题目，支持文本或语音答题，处理「原题 → 追问 → 下一题」的完整链路，并反馈提交、评分和下一题状态。

**本小节流程：**
1. 会话建立后连上面试 WebSocket（`/ws/interviews/{sessionId}`）并每 10 秒发 `ping` 保活
2. 用户在文本框中输入答案，或点"开始录音"走阿里云 NLS 实时转写
3. 点击"提交答案"：生成 `requestId`，`POST /api/answers/{sessionId}/answers`（带 `followUp` 标记）
4. 轮询 `GET /api/interviews/{sessionId}/state`，等状态从评分中回到 `QUESTIONING`（或 `ENDED`）
5. 状态为 `QUESTIONING` 且这趟是原题作答：先查 `GET /api/interviews/{sessionId}/follow-up?questionId=`，有追问就把追问当题目展示；没有才 `POST .../questions` 取下一题
6. 状态为 `ENDED`：跳报告页

**答题页面包含的 UI 元素：**

```text
题目元信息（question-meta）—— 原题显示 QUESTION n，追问显示 FOLLOW-UP
题目正文   —— 追问优先展示 followUp.content，否则展示 question.content
文本输入框 —— 用户输入文字答案
语音转写预览（transcript）—— 阿里云 NLS 回来的临时结果
开始/停止录音按钮 —— 控制 NlsTranscriptionClient
提交答案按钮 —— 提交期间 disabled，显示"提交中..."
结束面试按钮（finish-button）—— 手动结束面试，跳转到报告页面
```

### 面试 WebSocket 保活

会话一旦建立，前端就连上 `/ws/interviews/{sessionId}`。这条连接同时承担两件事：**接收服务端状态推送**和**语音转写分片上报**（分片上报由 NLS 客户端复用同一个地址）。

```javascript
// 文件：frontend/src/main.jsx（节选：面试 WebSocket + ping/pong 保活）
  /**
   * 连接面试 WebSocket，并每 10 秒发一次 ping 保持连接活跃。
   *
   * 为什么需要 ping/pong 保活：某些网络设备（负载均衡器、防火墙）会关闭
   * 长时间空闲的 WebSocket 连接；定期发送心跳包让连接始终有数据往来。
   *
   * 为什么用 setInterval 而不是 setTimeout：ping 是周期性动作，
   * setInterval 不用自己重排下一次；代价是必须在 onclose 里清除定时器，
   * 否则连接断开后定时器还在往一个已关闭的 socket 上发消息。
   *
   * @param sessionId 面试会话 ID，出现在 URL 路径上，服务端靠握手拦截器解析它
   * @returns 已建立（或正在建立）的 WebSocket 实例，调用方可自行持有以便关闭
   */
  const connectInterviewSocket = sessionId => {
    // 同源推导协议与地址：https 页面必须用 wss，否则浏览器会拦掉混合内容。
    const socket = new WebSocket(
      `${location.protocol === 'https:' ? 'wss' : 'ws'}`
        + `://${location.host}/ws/interviews/${sessionId}`
    );

    // 每 10 秒发一次 ping。先判断 readyState：连接还在握手时 send 会抛异常。
    const pingTimer = window.setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'ping' }));
      }
    }, 10000);

    // 处理接收到的消息。
    socket.onmessage = event => {
      const message = JSON.parse(event.data);
      if (message.type === 'pong') {
        // 服务端对 ping 的响应，只证明连接还活着，不需要改任何界面状态。
        return;
      }
      if (message.type === 'state') {
        // 服务端推送状态变更：只覆盖 state 与 version 两个字段，
        // 用函数式更新合并，避免把 previous 直接替换掉导致 sessionId 等字段丢失。
        setSession(previous => ({
          ...(previous || {}),
          state: message.state,
          version: message.version
        }));
      }
    };

    // 连接关闭时清除 ping 定时器：不清的话定时器会一直空转，
    // 直到页面卸载，属于典型的内存与定时器泄漏。
    socket.onclose = () => {
      window.clearInterval(pingTimer);
    };

    return socket;
  };
```

```javascript
// 文件：frontend/src/main.jsx（节选：会话切换时建立/销毁连接）
  useEffect(() => {
    if (!session?.sessionId) {
      return undefined;
    }
    socketRef.current = connectInterviewSocket(session.sessionId);
    return () => {
      socketRef.current?.close();
      socketRef.current = null;
      // 离开答题页时把还在跑的语音输入也停掉，否则麦克风会一直占着。
      nlsClient.current?.stop();
      nlsClient.current = null;
    };
  }, [session?.sessionId]);
```

**要点：** 依赖数组写成 `[session?.sessionId]`，表示这个 effect 只关心"连的是哪个会话"；状态推进（`state` / `version` 变化）时**不会**重连。清理函数里同时关掉 WebSocket 和 NLS 录音，避免麦克风一直占着。

### 等待状态推进（轮询）

提交答案后不能立刻取下一题——后端还在异步评分、裁决追问、预生成下一题。前端用一个轻量轮询等状态就绪。

```javascript
// 文件：frontend/src/main.jsx（节选：等待状态推进）
  const waitForNextQuestionState = async sessionId => {
    // 上限 150 秒：后端评分/追问/出题是多次 AI 调用，正常几十秒内会推进到下一题。
    // 万一某环节异常导致状态停在中间态，也不能让用户无限转圈，超时就明确报错。
    for (let attempt = 0; attempt < 150; attempt += 1) {
      const current = await request(
        `/api/interviews/${sessionId}/state`
      );
      // 每次拿到的最新状态都写回 session：状态一起更新，界面不会停在旧值。
      setSession(previous => ({
        ...(previous || {}),
        ...current
      }));
      // 已经可以答下一题：直接返回，交给调用方去取题目。
      if (
        current.state === 'QUESTIONING'
        || current.state === 'ANSWERING'
      ) {
        return current;
      }
      // 面试结束：同样返回，由调用方跳报告页，不要再等下去。
      if (current.state === 'ENDED') {
        return current;
      }
      // 还在评分 / 推进中：等 1 秒再查，避免打满后端。
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    throw new Error('等待下一题超时，请刷新页面重试。');
  };
```

### 取题并等待就绪（takeQuestion）

后端出题接口 POST /api/interviews/{sessionId}/questions 在异步出题尚未落库时会返回 HTTP 500（singleflight 的可重试超时信号），或返回 status 非 ACTIVE 的中间态题。一次性的 request 撞上就报错，所以统一用"有限次重取 + 校验 ACTIVE"的辅助函数，供首次取题与后续取题复用。

```javascript
// 文件：frontend/src/main.jsx（节选：统一取题）
  const takeQuestion = async sessionId => {
    const maxAttempts = 10; // 单题最多等约 10*1.5=15 秒：首题生成通常几十秒内完成。
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      let next;
      try {
        next = await request(`/api/interviews/${sessionId}/questions`, {
          method: 'POST'
        });
      } catch (error) {
        // 后端 singleflight 超时会以 500 形式抛出"可重试"信号，这里吞掉后重试，
        // 不把可恢复的生成中态当成失败直接展示给用户。
      }
      // 只有 ACTIVE 的题才可作答：预生成题和中间态都不能展示。
      if (next && next.id && next.status === 'ACTIVE') {
        return next;
      }
      await new Promise(resolve => setTimeout(resolve, 1500));
    }
    throw new Error('题目生成超时，请稍后刷新重试。');
  };
```

### 提交逻辑（submit 函数）

**这段是本次改动最大的地方**，包含三处关键变化：接口路径从 `/api/interviews/{sessionId}/answers` 改成 `/api/answers/{sessionId}/answers`；提交前先判断这一趟是原题作答还是追问作答（`followUp` 标记）；提交后先轮询状态、再查追问、最后才取下一题。

```javascript
// 文件：frontend/src/main.jsx（节选：提交答案）
  const submit = () => {
    if (submitting) return;
    setSubmitting(true);

    return run(async () => {
    if (!currentAnswer || !question || !session) {
      throw new Error('请输入答案后再提交');
    }
    const requestId = requestIdRef.current || idForRequest();
    requestIdRef.current = requestId;

    // 从这里开始是新增的：这一趟提交的是"原题作答"还是"追问作答"。
    // 追问复用原题的 questionId，只能靠这个布尔值区分；后端据此把答案写进
    // interview_answer 的 is_follow_up=1 那一行，并且不推进主线状态机
    // （否则同一道题会被算成两道题，题序错乱）。
    const isFollowUp = Boolean(followUp);
    // 新增结束

    // 从这里开始是修改的：提交答案的路径改成后端真实存在的
    // POST /api/answers/{sessionId}/answers（AnswerController 类级是 /api/answers）。
    await request(`/api/answers/${session.sessionId}/answers`, {
      method: 'POST',
      body: JSON.stringify({
        questionId: question.id,
        requestId,
        answer: currentAnswer,
        // 新增：追问作答标记，由上面的 isFollowUp 决定。
        followUp: isFollowUp
      })
    });
    requestIdRef.current = null;
    setAnswer('');
    setTranscript('');

    // 先等后端把状态推进到位（评分 → 追问裁决 → 预生成下一题），再去取下一题。
    // 旧写法提交返回就立刻要下一题，此时后端还在评分，拿到的还是旧题。
    const state = await waitForNextQuestionState(session.sessionId);
    // 状态已经 ENDED：这场面试到此为止，直接进报告页。
    if (state.state === 'ENDED') {
      setFollowUp(null);
      setScreen('REPORT');
      await loadReport();
      return;
    }

    // 从这里开始是新增的：取下一题之前，先看这道题有没有派生追问。
    // 为什么顺序不能反：追问不推进主线状态机，如果先取下一题，界面上就会直接
    // 跳到新题，候选人再也没机会答追问；而且追问答案要挂在刚答完的原题上，
    // 原题一旦被下一题顶掉就找不回来了。
    // 为什么只在"原题作答"这一趟查：追问的追问不生成，答追问时再查一次纯属多余。
    if (!isFollowUp) {
      const pending = await request(
        `/api/interviews/${session.sessionId}/follow-up?questionId=${question.id}`
      );
      // 查到了追问：留在当前页，把追问当作新的一道"题"展示。
      // question 保持不变（追问的 questionId 就是原题的 questionId）。
      if (pending && pending.content) {
        setFollowUp(pending);
        setNotice('这道题还有一次追问，请继续作答。');
        return;
      }
    }
    setFollowUp(null);
    // 新增结束

    // 状态已推进：这时候才去取下一题（后端只有 POST .../questions）。
    // 从这里开始是修改的：改用统一的 takeQuestion——后端出下一题可能碰 singleflight
    // 可重试超时(500)或短暂未 ACTIVE，takeQuestion 会重取到就绪，替代原来的一次性 request。
    const next = await takeQuestion(session.sessionId);
    setQuestion(next);
    setNotice('答案已提交，已进入下一题。');
    // 修改结束
    }).finally(() => {
      setSubmitting(false);
    });
  };
```

**提交按钮绑定：** `<button className="primary-button" disabled={submitting} onClick={submit}>{submitting ? '提交中...' : '提交答案'} <span>→</span></button>`

**网络失败处理：** `requestIdRef.current` 在提交成功后立即清空；只有 submit 函数本身在网络层失败（`request()` 抛出异常）时它才会保留，重试时复用同一个 requestId，后端据此做幂等去重。

### 语音录音链路（阿里云 NLS）

录音已经**不是** MediaRecorder 占位实现，也**不是**讯飞 / 星辰方案，而是 `NlsTranscriptionClient`：麦克风采集 → AudioContext 降采样到 16k 单声道 PCM → 前端**不再直连阿里云 NLS**，而是把裸 PCM 经业务 WebSocket（`/ws/interviews/{sessionId}`）推给后端，由后端 `AliyunNlsRealtimeRelay` 补齐 NLS 帧头并推流识别；识别结果由后端去重合并，临时文本经 `asr_preview` 推回做实时预览。

```javascript
// 文件：frontend/src/main.jsx（节选：开始/停止录音）
  const toggleRecording = async () => {
    if (recording) {
      // 停止：关麦克风、关两条 WebSocket、清 ping 定时器都在 stop() 里做。
      nlsClient.current?.stop();
      nlsClient.current = null;
      setRecording(false);
      setNotice('语音输入已停止，转写文本已随答案上报。');
      return;
    }
    if (!navigator.mediaDevices?.getUserMedia) {
      setError('当前浏览器不支持录音，请使用文本输入。');
      return;
    }
    if (!session) {
      setError('请先开始面试，再使用语音输入。');
      return;
    }
    try {
      const client = new NlsTranscriptionClient({
        sessionId: session.sessionId,
        // 取 /api/speech/token 时要带上登录态；JwtAuthenticationFilter 认的是 Authorization 头。
        jwt: localStorage.getItem('interview_token') || '',
        // 临时结果只做界面预览，最终文本由后端去重合并后统一取。
        onPreview: text => setTranscript(text),
        onError: message => setError(message)
      });
      nlsClient.current = client;
      await client.start();
      setRecording(true);
    } catch (caught) {
      nlsClient.current = null;
      setRecording(false);
      setError(caught.message || '语音输入启动失败，请改用文本输入。');
    }
  };
```

`NlsTranscriptionClient` 的完整实现在 `frontend/src/nlsTranscriptionClient.js`，一次 `start()` 干四件事：

1. `getUserMedia` 拿麦克风，`AudioContext` 把音频降采样成 16k 单声道 PCM
2. 打开业务 WebSocket，发 `{type:'audio_start'}` 通知后端建立阿里云 NLS 上行
3. 采集的裸 PCM 以二进制帧持续推给后端（NLS 的帧头由后端 `AliyunNlsRealtimeRelay` 补齐，前端**不再直连 NLS**）
4. 后端把 NLS 识别的临时文本以 `{type:'asr_preview'}` 推回，这里转给界面实时预览

```javascript
// 文件：frontend/src/nlsTranscriptionClient.js（节选：后端中继，纯采集端）
// 一次 start() 干四件事：
//   1. getUserMedia 拿麦克风，AudioContext 把音频降采样成 16k 单声道 PCM
//   2. 打开业务 WebSocket，发 {type:'audio_start'} 通知后端建立阿里云 NLS 上行
//   3. 采集的裸 PCM 以二进制帧持续推给后端（NLS 帧头由后端 AliyunNlsRealtimeRelay 补齐）
//   4. 后端把 NLS 识别的临时文本以 {type:'asr_preview'} 推回，这里转给界面实时预览
const SAMPLE_RATE = 16000;

export class NlsTranscriptionClient {
  constructor(options) {
    this.sessionId = options.sessionId;
    this.onPreview = options.onPreview || (() => {});
    this.onError = options.onError || (() => {});
    this.business = null;      // 业务 WebSocket：上行音频 + 下行预览
  }

  async start() {
    // 先建业务 WS，再拿麦克风，最后发 audio_start —— 顺序保证后端上行就绪后才会有音频。
    this.openBusinessSocket();
    this.micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
    // 等 WS 真正 OPEN 再发音频，否则 start 后的第一批帧会被后端当成"无上行"丢帧。
    await new Promise(resolve => {
      if (this.business?.readyState === WebSocket.OPEN) {
        resolve();
        return;
      }
      this.business.addEventListener('open', () => resolve(), { once: true });
    });
    // 通知后端：为本会话建立上行 NLS 连接。
    this.business.send(JSON.stringify({ type: 'audio_start' }));
    this.recording = true;
    this.startCapture();   // 采集 + 降采样 + 分包推送
  }

  // 采集：AudioContext 默认采样率通常是 48k，这里线性降采样到 16k 再发
  startCapture() {
    this.audioContext = new AudioContext();
    const source = this.audioContext.createMediaStreamSource(this.micStream);
    // 用 ScriptProcessor 逐块拿 PCM：已废弃但零依赖、够教学用；生产可换 AudioWorklet。
    this.processor = this.audioContext.createScriptProcessor(4096, 1, 1);
    const ratio = this.audioContext.sampleRate / SAMPLE_RATE;
    this.processor.onaudioprocess = event => {
      if (this.business?.readyState !== WebSocket.OPEN) return;
      const input = event.inputBuffer.getChannelData(0);
      // 降采样 + float32 → int16（NLS 要的是 16 位小端 PCM）
      const pcm = new Int16Array(Math.floor(input.length / ratio));
      for (let i = 0; i < pcm.length; i += 1) {
        const sample = input[Math.floor(i * ratio)];
        pcm[i] = Math.max(-1, Math.min(1, sample)) * 0x7fff | 0;
      }
      // 直接发裸 PCM 二进制帧；NLS 的 4 字节帧头由后端 AliyunNlsRealtimeRelay 补。
      this.business.send(pcm.buffer);
    };
    source.connect(this.processor);
    this.processor.connect(this.audioContext.destination);
  }
  // ...（省略 openBusinessSocket / stop / 下行 asr_preview）...
}
```

**答题页面的 JSX（追问优先展示）：**

```javascript
// 文件：frontend/src/main.jsx（节选：答题页面 JSX）
            {screen === 'ANSWERING' && (
              <section className="interview-panel">
                {/* 从这里开始是修改的：追问优先展示。
                    追问是原题的补充考察，不作为新题插入 interview_question，
                    所以它不在 question 里，而是单独查出来放在 followUp 里。
                    followUp 非空时整块替换成追问内容，提交时带上 followUp=true。 */}
                <div className="question-meta"><span>{followUp ? 'FOLLOW-UP' : `QUESTION ${question?.index || 1}`}</span><span>{followUp ? (followUp.level || '追问') : (question?.category || position?.name || '综合面试')}</span></div>
                <h2>{followUp ? followUp.content : (question?.content || question?.question || '正在加载题目...')}</h2>
                {/* 修改结束 */}
                <textarea value={answer} onChange={event => setAnswer(event.target.value)} placeholder="输入你的回答，尽量结合具体项目和结果..." />
                {transcript && <div className="transcript"><small>语音转写</small><p>{transcript}</p></div>}
                <div className="answer-actions"><button className={`secondary-button ${recording ? 'recording' : ''}`} onClick={toggleRecording}>{recording ? '停止录音' : '开始录音'}</button><button className="primary-button" disabled={submitting} onClick={submit}>{submitting ? '提交中...' : '提交答案'} <span>→</span></button></div>
                <button className="finish-button" onClick={finishInterview}>结束面试并查看报告</button>
              </section>
            )}
```

**手动结束面试：** `finishInterview` 调用 `POST /api/interviews/{sessionId}/end`（注意是 `/end` 不是 `/finish`），清掉未作答的追问，再进报告页。

```javascript
// 文件：frontend/src/main.jsx（节选：结束面试）
  const finishInterview = () => run(async () => {
    if (!session) return;
    // 从这里开始是修改的：后端结束面试的映射是 /end，不是 /finish。
    await request(`/api/interviews/${session.sessionId}/end`, {
      method: 'POST',
      body: JSON.stringify({})
    });
    // 修改结束
    // 新增：结束面试时清掉未作答的追问，避免下次进答题页还挂着上一场的追问。
    setFollowUp(null);
    setScreen('REPORT');
    await loadReport();
  });
```

---

### 前端 5：编写报告页面

**本小节目的：** 展示整场面试的综合评分、逐题反馈、表现分析和改进建议。

**本小节流程：**
1. 进入报告页面时调用 `loadReport()`
2. `loadReport` 是**三步接口**：`POST /api/reports/{sessionId}` 触发生成（只把记录置成 `GENERATING` 就返回）→ 轮询 `GET /api/reports/{sessionId}/status` → `GET /api/reports/{sessionId}` 取正文
3. 报告以 Markdown 格式返回（由后端 `ReportGenerationAsyncService` 异步生成）
4. 前端用 `markdownToHtml` 把 Markdown 转成 HTML 渲染

**后端返回的字段（`ReportController.ReportView`）：** 只有 `sessionId` / `status` / `markdown` 三个字段，**没有** `summary`、`content`。`status` 不是 `COMPLETED` 时 `markdown` 为 `null`。

**报告生成的三步（`loadReport`）：**

```javascript
// 文件：frontend/src/main.jsx（节选：报告生成 loadReport + pollReportUntilSettled）
  // 单轮轮询：每 1 秒查一次 "/api/reports/{sessionId}/status"，默认最多等 90 秒。
  // 一旦拿到非 GENERATING（COMPLETED / FAILED）就立刻带着该状态返回；等满一整轮
  // 仍是 GENERATING 时，也会把后端最新状态取回来交还给调用方（loadReport），
  // 由调用方决定是否"兜底重触发"——这里只负责"等价满一轮后带最新状态返回"，
  // 不再自己无限等下去——无限等只会让界面永远停在"生成中"。
  const pollReportUntilSettled = async (maxAttempts = 90) => {
    for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
      const s = await request(`/api/reports/${session.sessionId}/status`);
      if (s.status !== 'GENERATING') {
        return s;
      }
      await new Promise(resolve => setTimeout(resolve, 1000));
    }
    return request(`/api/reports/${session.sessionId}/status`);
  };

  const loadReport = async () => {
    if (!session) return;
    // 立即给出"生成中"的真实状态，而不是停在空白转圈，让用户明确知道在生成哪一步。
    setReport({ status: 'GENERATING' });
    // 先读一次状态。首次进报告页时库里还没有记录，status 接口返回 404，
    // 用 try/catch 接住并当作"没有报告"，自然会落到下面的"触发生成"分支。
    let existing = null;
    try {
      existing = await request(`/api/reports/${session.sessionId}/status`);
    } catch {
      existing = null;
    }
    // 已完成：直接展示，不重新生成。
    if (existing && existing.markdown) {
      setReport(existing);
      return;
    }
    // 没有记录，或上一次生成失败：POST 触发生成（后端幂等，已在生成中的不会重复起）。
    if (!existing || existing.status === 'FAILED') {
      try {
        await request(`/api/reports/${session.sessionId}`, { method: 'POST' });
      } catch {
        // 幂等触发失败（例如已在生成中）不致命，下面继续轮询即可。
      }
    }
    // 已在生成中或刚触发：持续轮询直到 COMPLETED/FAILED 再展示，绝不再固定停在 GENERATING。
    // 兜底重试：单轮 pollReportUntilSettled 默认只等 90 秒。如果那个异步生成任务因 AI 上游
    // 超时/异常而没真正起效，记录就会一直停在 GENERATING。于是每轮等满仍看到 GENERATING 时，
    // 显式再 POST 一次触发生成——后端对已存在的 GENERATING 记录会"重置状态 + 重新触发一个
    // 新的异步生成任务"，把卡死的记录"救活"。最多兜底 2 次，总计最长约 270 秒；重试完仍是
    // GENERATING，就把后端真实状态展示出来让用户点「刷新报告」手动再试。
    let settled = await pollReportUntilSettled();
    let retries = 0;
    while (settled.status === 'GENERATING' && retries < 2) {
      try {
        await request(`/api/reports/${session.sessionId}`, { method: 'POST' });
      } catch {
        // 重置触发即使失败也不致命，下一轮轮询仍会尽力读到终态。
      }
      retries += 1;
      settled = await pollReportUntilSettled();
    }
    setReport(settled);
  };
```

**为什么不能只 GET 一次：** 后端 `POST` 只把记录置成 `GENERATING` 就立刻返回，正文由后台线程（`@Async("reportGenerationTaskExecutor")`）写入。旧写法只 GET 一次，库里还没报告行时后端直接抛 `report not found`，页面会永远停在"报告正在生成，请稍后刷新"。

**Markdown 转 HTML（`markdownToHtml` + `escapeHtml`）：**

```javascript
// 文件：frontend/src/main.jsx（节选：Markdown 渲染工具）
const escapeHtml = value => String(value)
  .replaceAll('&', '&amp;')
  .replaceAll('<', '&lt;')
  .replaceAll('>', '&gt;')
  .replaceAll('"', '&quot;')
  .replaceAll("'", '&#039;');

const markdownToHtml = markdown => {
  const safe = escapeHtml(markdown || '');
  return safe
    .replace(/^### (.*)$/gm, '<h3>$1</h3>')
    .replace(/^## (.*)$/gm, '<h2>$1</h2>')
    .replace(/^# (.*)$/gm, '<h1>$1</h1>')
    .replace(/^\s*[-*] (.*)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>\n?)+/g, '<ul>$&</ul>')
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/\n/g, '<br />')
    .replace(/^(.+)$/s, '<p>$1</p>');
};
```

**安全要点：** 这里没有引入第三方 Markdown 库，而是自己写了一个极简实现——**关键是先 `escapeHtml` 把 `<`、`>`、`&`、引号全部转义，再做 Markdown 替换**，所以 AI 报告里即使混进 `<script>` 也会被转义成文本，不会被执行。渲染时用 `dangerouslySetInnerHTML` 把转换后的 HTML 插入页面（React 提供的"逃逸口"）。生产环境若要支持表格等复杂语法，建议换用 `marked` + `DOMPurify.sanitize()` 这类成熟方案。

**报告页面的 JSX：**

```javascript
// 文件：frontend/src/main.jsx（节选：报告页面 JSX）
            {screen === 'REPORT' && (
              <section className="report-panel">
                <div className="report-header"><div><p className="eyebrow">INTERVIEW REPORT</p><h2>本次面试报告</h2></div><button className="secondary-button" onClick={() => run(loadReport)}>刷新报告</button></div>
                {/* 从这里开始是修改的：后端 ReportView 只有 sessionId / status / markdown
                    三个字段，原来的 summary、content 在后端根本不存在。
                    status 不是 COMPLETED 时 markdown 为 null，此时显示后端给的状态，
                    而不是笼统一句"请稍后刷新"。 */}
                {report ? (
                  report.markdown
                    ? <article className="report-document" dangerouslySetInnerHTML={{ __html: markdownToHtml(report.markdown) }} />
                    : <div className="empty-state"><span className="loader" />{`报告状态：${report.status || 'GENERATING'}，正在生成。若较长时间无进展，请点「刷新报告」重试。`}</div>
                ) : <div className="empty-state"><span className="loader" />正在加载面试报告...</div>}
                {/* 修改结束 */}
              </section>
            )}
```

---

## 十六点一、语音转写分段去重整改

### 模块主要目的

解决语音转写过程中的三个核心问题：
1. **分片覆盖更新**：同一个分片 ID（seg_id）可能多次到达，后面到达的结果更准确，需要覆盖旧值而非追加
2. **乱序到达**：网络延迟可能导致后发送的分片先到达，需要按 seg_id 排序后合并
3. **临时结果抖动**：AI 语音识别在确认前会不断修正临时结果，需要区分"临时预览"和"最终结果"

### 模块整体流程

**注意：真实实现是"接收与消费解耦"的异步版本**——接收线程只负责把分片入队就立刻返回，真正的去重合并由独立线程池里的消费线程完成。这样 ASR 分片突增时不会拖垮 WebSocket 接收线程。

```text
前端 NLS 客户端把识别分片经 ws://.../ws/interviews/{sessionId} 上报
    （type=transcription，含 seg_id / text / pgs / rg / bg / ed）
    -> InterviewWebSocketHandler.handleTextMessage 解析字段、校验类型
       -> AsyncTranscriptionBuffer.receive(sessionId, AudioSegment) 入队
          （带超时的 offer，队列满则丢弃该分片，绝不阻塞接收线程）
    -> 该会话的异步消费线程（@Async("transcriptionTaskExecutor")）在队列上 poll
       -> 取出分片，调用 TranscriptionSessionContext.mergeSegment() 去重排序
          -> TreeMap.put(segId, text) 覆盖同一分片旧值
          -> 如果 ed=true（最终结果）：
               -> 将分片标记为"已确认"
               -> 按照 seg_id 顺序拼接连续已确认分片
               -> 更新 transcript（最终答案）与 preview（预览同步）
          -> 如果 ed=false（临时结果）：
               -> 只更新 preview（预览文本），不修改 transcript
       -> 合并结果写进 context，业务层按需读取 getTranscript() / getPreview()
    -> 另有 ping/pong 心跳：前端每 10 秒发 {"type":"ping"}，处理器回 {"type":"pong"}
```

**核心数据结构：`TreeMap<Integer, String>`**

选择 `TreeMap` 而非 `HashMap` 的原因：
- `TreeMap` 按键的自然顺序排序（Integer 的自然顺序即数值升序）
- 天然支持按 `seg_id` 顺序遍历，不需要额外排序操作
- `put` 操作是覆盖语义，同一 `seg_id` 重复到达时自动替换旧值
- 时间复杂度 O(log n)，对于一场面试中的分片数量（通常几百个）完全足够

### 核心文件

```text
src/main/java/com/example/interview/websocket/InterviewWebSocketHandler.java
src/main/java/com/example/interview/websocket/AsyncTranscriptionBuffer.java
src/main/java/com/example/interview/websocket/TranscriptionSessionContext.java
src/main/java/com/example/interview/websocket/WebSocketConfig.java
```

---

#### 文件：`TranscriptionSessionContext.java`

**文件位置：** `src/main/java/com/example/interview/websocket/TranscriptionSessionContext.java`

**文件作用：** 维护一个 WebSocket 会话内的语音转写状态。每个 WebSocket 连接对应一个独立的 `TranscriptionSessionContext` 实例，所有分片状态只在该会话内共享，互不干扰。

**为什么需要这个类？** 语音转写不同于普通消息收发——同一段语音可能产生多个分片，分片可能乱序到达、重复到达，必须有一个专门的状态管理器来维护分片的完整性和顺序性。如果不这样做，最终得到的转写文本可能是乱序的、重复的或残缺的。

**依赖关系：**
- 无外部框架依赖（纯 Java 标准库）
- 数据结构：使用 `java.util.TreeMap` 保存分片，保证 seg_id 自动排序
- 被谁调用：`AsyncTranscriptionBuffer.consume`（异步消费线程每取出一个分片就调用其 `mergeSegment()` 方法）
- 输出：`MergeResult` record（包含合并后的预览文本和最终文本）

```java
package com.example.interview.websocket;

import java.util.Map;
import java.util.TreeMap;

// ============================================================
// 语音转写会话上下文 —— 维护单个面试会话内的转写状态
// ============================================================
// 职责：
//   1. 按 seg_id 保存所有收到的转写分片（覆盖更新，不会产生重复）
//   2. 区分"临时结果"（ed=false）和"最终结果"（ed=true）
//   3. 按 seg_id 顺序拼接连续已确认分片，生成最终的完整转写文本
//   4. 提供线程安全的方法（synchronized），支持并发访问
//
// 关键设计决策：
//   - 使用 TreeMap 而非 HashMap：需要按 seg_id 顺序遍历
//   - put 是覆盖语义：同一 seg_id 后到的结果覆盖旧值，不会追加
//   - synchronized 方法：一个会话可能被多个线程同时操作
//     （WebSocket 接收线程、异步消费线程和业务处理线程）
// ============================================================
public class TranscriptionSessionContext {

    // ============================================================
    // 合并结果记录 —— mergeSegment 的返回值封装
    // ============================================================
    // 字段说明：
    //   preview     —— 临时预览文本（前端实时展示用，可能包含未确认的临时内容）
    //   transcript  —— 最终文本（只包含已确认的连续分片，适合落库）
    //   finalResult —— true 表示这是最终确认的结果，false 表示只是临时预览
    //   segId       —— 当前处理的分片 ID（原样透传，前端用于跟踪）
    //   pgs         —— 分片状态（原样透传，如 "speaking" / "silence"）
    //   rg          —— 音频范围起始位置（原样透传）
    //   bg          —— 分片开始时间位置（原样透传）
    // ============================================================
    public record MergeResult(
            String preview,      // 合并后的预览文本
            String transcript,   // 合并后的最终文本
            boolean finalResult, // 是否最终确认结果
            int segId,           // 当前分片 ID
            String pgs,          // 分片状态（原样透传）
            int rg,              // 音频范围起始（原样透传）
            int bg               // 分片开始时间（原样透传）
    ) {
    }

    // ---- 分片存储 ----
    // 按 seg_id 排序保存所有分片的最新文本。
    // key：seg_id（语音分片序号，从 0 开始递增）
    // value：分片文本（已 trim 处理）
    // 同一个 seg_id 后到的结果会覆盖旧值——这是 TreeMap.put 的自然行为。
    private final TreeMap<Integer, String> segmentMap = new TreeMap<>();

    // ---- 已确认分片 ----
    // 只保存 ed=true（最终结果）的分片。
    // 用于生成连续的最终句子，不会被临时结果污染。
    private final TreeMap<Integer, String> confirmedSegments = new TreeMap<>();

    // ---- 临时预览文本 ----
    // 保存当前看到的临时预览内容。
    // 由已确认句子 + 最新临时文本拼接而成。
    // 不直接作为最终答案落库。
    private String preview = "";

    // ---- 最终转写文本 ----
    // 只保存 ed=true 的最终转写结果。
    // 由连续已确认分片按 seg_id 顺序拼接而成。
    // 最终会存入答案表，作为面试答案的来源之一。
    private String transcript = "";

    // ============================================================
    // 核心方法：合并一个转写分片
    // ============================================================
    // 每次异步消费线程取出一个分片时调用此方法。
    //
    // 关键逻辑：
    //   1. 所有分片都存入 segmentMap（覆盖旧值）
    //   2. 如果 ed=true，同时存入 confirmedSegments，
    //      并重新拼接所有连续已确认分片，更新 transcript 和 preview
    //   3. 如果 ed=false，只拼接预览文本（confirmedSentence + 当前临时文本），
    //      不修改 transcript
    // ============================================================
    public synchronized MergeResult mergeSegment(
            int segId,
            String text,
            String pgs,
            int rg,
            int bg,
            boolean ed
    ) {
        // ---- 文本预处理 ----
        // 如果 text 为 null，转为空字符串并 trim（去除首尾空白）。
        // trim 的目的是去除 ASR 可能引入的前后空格。
        String normalized = text == null ? "" : text.trim();

        // ---- 覆盖更新分片 ----
        // TreeMap.put 是覆盖语义（replace），不是追加语义（append）。
        // 同一 seg_id 的多次到达只会保留最后一条文本，
        // 不会产生"我今今天去去公司"这样的重复错误。
        segmentMap.put(segId, normalized);

        // ---- 分支1：最终结果（ed=true） ----
        if (ed) {
            // 将当前分片加入"已确认"集合
            confirmedSegments.put(segId, normalized);

            // 从最小 seg_id 开始，拼接所有连续的已确认分片
            String sentence = rebuildConfirmedSentence();

            // 同时更新 transcript 和 preview（最终结果到达后两者完全同步）
            transcript = sentence;
            preview = sentence;

            return new MergeResult(
                    sentence,    // preview
                    sentence,    // transcript
                    true,        // finalResult（这是最终结果）
                    segId, pgs, rg, bg
            );
        }

        // ---- 分支2：临时结果（ed=false） ----
        // 临时结果只用于刷新预览，不能写入 transcript。
        // 这样可以防止"嗯...啊..."等临时修正痕迹出现在最终答案中。
        String temporaryPreview = rebuildPreview(segId, normalized);
        preview = temporaryPreview;

        return new MergeResult(
                temporaryPreview,  // preview（临时预览）
                transcript,        // transcript（保持之前的值不变）
                false,             // finalResult（这不是最终结果）
                segId, pgs, rg, bg
        );
    }

    // ============================================================
    // 私有方法：拼接连续已确认分片
    // ============================================================
    // 从最小 seg_id（即第一个分片）开始遍历 confirmedSegments，
    // 只拼接 seg_id 连续的分片。遇到不连续的分片就停止。
    //
    // 为什么只拼接连续分片？
    //   假设 confirmedSegments 中有 {0:"今天", 1:"天气", 3:"不错"}，
    //   seg_id=2 缺失（可能尚未到达或仍在处理中），
    //   此时只拼接 0 和 1（"今天天气"），不拼接 3，
    //   因为跳过 seg_id=2 直接拼接 3 会产生语义断裂。
    //   等 seg_id=2 到达并确认后，下一次调用会重新拼接所有连续分片。
    // ============================================================
    private String rebuildConfirmedSentence() {
        StringBuilder result = new StringBuilder();
        Integer expected = null;  // 预期的下一个 seg_id

        // TreeMap 的 entrySet 按 key 升序遍历
        for (Map.Entry<Integer, String> entry : confirmedSegments.entrySet()) {
            if (expected == null) {
                // 第一次迭代：以第一个分片的 seg_id 为起始
                expected = entry.getKey();
            }
            // 检查 seg_id 是否连续
            if (!entry.getKey().equals(expected)) {
                // 出现不连续（跳号），停止拼接
                // 例如 expected=2 但当前 entry.key=3，说明 seg_id=2 缺失
                break;
            }
            // 将当前分片文本附加到结果中
            appendText(result, entry.getValue());
            expected++;  // 期望下一个 seg_id
        }

        return result.toString();
    }

    // ============================================================
    // 私有方法：拼接临时预览文本
    // ============================================================
    // 临时预览 = 已确认句子 + 当前临时分片文本
    // 例如：
    //   已确认句子 = "今天天气"
    //   当前临时文本 = "真不错"
    //   预览结果 = "今天天气真不错"
    // ============================================================
    private String rebuildPreview(int segId, String temporaryText) {
        // 先获取当前已确认的连续句子
        String confirmed = rebuildConfirmedSentence();

        // 如果临时文本为空，预览仅包含已确认内容
        if (temporaryText.isBlank()) {
            return confirmed;
        }
        // 如果已确认句子为空，预览仅包含当前临时文本
        if (confirmed.isBlank()) {
            return temporaryText;
        }
        // 两者都不为空：已确认句子 + 临时文本
        // 用 confirmed + temporaryText 而不是 confirmed + " " + temporaryText，
        // 因为 appendText 已在已确认文本的末尾加了空格
        return confirmed + temporaryText;
    }

    // ============================================================
    // 私有方法：向 StringBuilder 中追加文本（自动加空格分隔）
    // ============================================================
    // 作用是在文本片段之间添加适当的分隔符：
    //   1. 如果 builder 为空，直接追加
    //   2. 如果 builder 末尾已经是空白字符，直接追加（不需要再加空格）
    //   3. 如果 builder 末尾不是空白字符，先加一个空格再追加
    // ============================================================
    private void appendText(StringBuilder builder, String value) {
        // 跳过 null 或空文本
        if (value == null || value.isBlank()) {
            return;
        }
        // 检查 builder 末尾是否是空白字符
        // 如果不是空白，先加一个空格再追加文本，避免词语粘连
        if (builder.length() > 0
                && !Character.isWhitespace(
                        builder.charAt(builder.length() - 1)
                )) {
            builder.append(' ');
        }
        // 追加文本内容
        builder.append(value);
    }

    // ============================================================
    // 公共方法：获取当前预览文本
    // ============================================================
    // 为什么是 synchronized？
    //   防止在 mergeSegment 正在进行时读取到不一致的中间状态。
    // ============================================================
    public synchronized String getPreview() {
        return preview;
    }

    // ============================================================
    // 公共方法：获取当前最终转写文本
    // ============================================================
    // 此方法被业务层调用，用于获取完整的面试答案文本。
    // transcript 只会在 ed=true 的分片到达时更新。
    // ============================================================
    public synchronized String getTranscript() {
        return transcript;
    }
}
```

---

#### 文件：InterviewWebSocketHandler.java

**文件位置：** `src/main/java/com/example/interview/websocket/InterviewWebSocketHandler.java`

**文件作用：** WebSocket 通信处理器，负责接收前端上报的语音转写分片，把分片投进 `AsyncTranscriptionBuffer` 的队列，由异步消费线程调用 `TranscriptionSessionContext` 做去重排序；同时响应前端的 ping 心跳。

**这个文件在整个语音转写流程中的角色：**
- **最底层**：前端 NLS 客户端产生识别分片文本
- **通信层**：InterviewWebSocketHandler（本文件）解析消息、入队、回 pong
- **缓冲层**：AsyncTranscriptionBuffer 按会话隔离队列，接收与消费解耦
- **状态管理层**：TranscriptionSessionContext 负责分片的去重、排序和合并

**依赖关系：**
- 依赖 com.fasterxml.jackson.databind.JsonNode 和 ObjectMapper（Jackson 库，Spring Boot 默认包含）：用于解析消息和序列化 pong
- 依赖 AsyncTranscriptionBuffer：分片入队与异步消费
- 依赖 TranscriptionSessionContext：分片状态管理
- 依赖 Spring WebSocket 模块（org.springframework.web.socket）
- 被谁调用：Spring WebSocket 框架（当收到 WebSocket 消息时自动调用 handleTextMessage()）
- 输出：只向前端回 `pong`；转写结果不再由这里推送，而是写进 context 供业务层读取

```java
package com.example.interview.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

// ============================================================
// WebSocket 处理器 —— 语音转写分片的接收与转发
// ============================================================
// 职责：
//   1. 连接建立：读取 sessionId，创建 TranscriptionSessionContext，
//      启动 AsyncTranscriptionBuffer 的异步消费线程
//   2. 消息接收：解析 JSON 字段，构造 AudioSegment 入队
//      （入队用带超时的 offer，队列满时丢弃分片而不是阻塞接收线程）
//   3. 心跳：响应前端 ping，返回 pong，保持长连接不被网络设备断开
//   4. 连接关闭：清理队列和上下文，避免内存泄漏
//
// 与"十六点一"同步版的区别：
//   本版本是最终实现——接收与消费解耦，接收线程只负责入队，
//   合并去重由 AsyncTranscriptionBuffer.consume 在独立线程池完成。
//   好处是 ASR 分片突增时不会拖垮 WebSocket 接收线程。
// ============================================================
@Component  // 声明为 Spring 组件，由 WebSocketConfig 注册到处理链
public class InterviewWebSocketHandler
        extends TextWebSocketHandler {

    // Jackson JSON 处理器：用于解析收到的消息和序列化推送结果
    private final ObjectMapper mapper;
    // 异步音频缓冲：接收与消费解耦的核心
    private final AsyncTranscriptionBuffer buffer;

    // 面试会话 ID -> 转写上下文的映射
    // key：面试会话 ID（握手拦截器从 URL 解析后放入 attributes）
    private final Map<String, TranscriptionSessionContext> contexts =
            new ConcurrentHashMap<>();

    /**
     * 构造器注入依赖。
     *
     * @param mapper Jackson JSON 处理器（Spring Boot 自动配置）
     * @param buffer 异步音频缓冲，负责分片入队与异步消费
     */
    public InterviewWebSocketHandler(
            ObjectMapper mapper,
            AsyncTranscriptionBuffer buffer
    ) {
        this.mapper = mapper;
        this.buffer = buffer;
    }

    // ============================================================
    // 连接建立回调
    // ============================================================
    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String sessionId = sessionId(session);
        TranscriptionSessionContext context =
                new TranscriptionSessionContext();
        contexts.put(sessionId, context);
        // 启动该会话的异步消费：
        // 分片从队列取出后由 mergeSegment 去重合并成最终 transcript
        buffer.consume(sessionId, context);
    }

    // ============================================================
    // 消息接收回调
    // ============================================================
    // 前端消息格式（JSON）：
    //   {
    //     "type": "transcription",       // 消息类型，非 transcription 类型直接忽略
    //     "seg_id": 0,                   // 分片序号（必填，整数）
    //     "text": "今天天气真不错",       // 分片文本（可选，默认空字符串）
    //     "pgs": "speaking",             // 分片状态（可选，如 "speaking"/"silence"）
    //     "rg": [0, 100],                // 音频范围数组（可选，取第一个元素）
    //     "bg": [0, 50],                 // 分片开始时间数组（可选，取第一个元素）
    //     "ed": true                     // 是否最终结果（可选，默认 false）
    //   }
    //   心跳消息：{"type": "ping"}
    // ============================================================
    @Override
    protected void handleTextMessage(
            WebSocketSession session,
            TextMessage message
    ) throws IOException {
        // ---- 步骤1：解析收到的 JSON 消息 ----
        JsonNode root = mapper.readTree(message.getPayload());
        String type = root.path("type").asText();

        // ---- 步骤2：心跳处理 ----
        // 前端每 10 秒发一次 ping，这里回 pong 保持连接活跃
        if ("ping".equals(type)) {
            session.sendMessage(new TextMessage(
                    mapper.writeValueAsString(
                            Map.of("type", "pong")
                    )
            ));
            return;
        }

        // ---- 步骤3：消息类型过滤 ----
        // 只处理 type="transcription" 的消息，其他类型静默忽略
        if (!"transcription".equals(type)) {
            return;
        }

        // ---- 步骤4：提取各字段 ----
        // seg_id：分片序号，必须存在且为整数，否则抛异常
        int segId = requiredInt(root, "seg_id");
        // text：分片文本内容，可选字段，缺失时默认为空字符串
        String text = root.path("text").asText("");
        // pgs：分片状态，如 "speaking" 表示正在说话
        String pgs = root.path("pgs").asText("");
        // rg：音频范围位置，可能是数组 [start, end]，取第一个值
        int rg = readRangeValue(root, "rg");
        // bg：分片开始时间，可能是数组 [start]，取第一个值
        int bg = readRangeValue(root, "bg");
        // ed：是否最终结果，支持布尔值和字符串 "true"/"false"
        boolean ed = readBoolean(root, "ed");

        // ---- 步骤5：构造分片并入队 ----
        // receive 内部用带超时的 offer（最多等 200ms），队列满返回 false。
        // 此时直接丢弃该分片——ASR 后续还会推送更准确的修正结果，
        // 丢一两个中间分片不会影响最终转写，绝不能阻塞接收线程。
        boolean accepted = buffer.receive(
                sessionId(session),
                new AsyncTranscriptionBuffer.AudioSegment(
                        segId, text, pgs, rg, bg, ed
                )
        );
        if (!accepted) {
            // 队列已满：丢弃当前分片，等待后续分片自然补齐
            return;
        }
    }

    // ============================================================
    // 连接关闭回调
    // ============================================================
    // 清理队列和上下文，释放内存。否则已关闭的连接会一直驻留在内存中，
    // 长期运行形成内存泄漏。
    // ============================================================
    @Override
    public void afterConnectionClosed(
            WebSocketSession session,
            org.springframework.web.socket.CloseStatus status
    ) {
        String sessionId = sessionId(session);
        // 清理该会话的缓冲队列：close 会写入关闭标记，
        // 该会话的消费循环随即自行退出并把线程归还线程池
        buffer.close(sessionId);
        // 清理转写上下文
        contexts.remove(sessionId);
    }

    // ============================================================
    // 私有方法：从握手 attributes 读取面试会话 ID
    // ============================================================
    // sessionId 由 WebSocketConfig 的握手拦截器在握手阶段解析并放入
    // attributes，这里直接读取即可，无需再解析路径。
    // ============================================================
    private String sessionId(WebSocketSession session) {
        return (String) session.getAttributes().get("sessionId");
    }

    // ============================================================
    // 私有方法：必填整数字段提取
    // ============================================================
    // 如果字段不存在或不能转换为整数，抛出 IllegalArgumentException。
    // 这种"严格校验"的设计确保我们不会把无效数据传给 buffer。
    // ============================================================
    private int requiredInt(JsonNode root, String field) {
        if (!root.has(field) || !root.get(field).canConvertToInt()) {
            throw new IllegalArgumentException(
                    "missing or invalid " + field
            );
        }
        return root.get(field).asInt();
    }

    // ============================================================
    // 私有方法：数组类型字段提取（取第一个元素）
    // ============================================================
    // ASR 引擎返回的 rg（音频范围）和 bg（开始时间）可能是数组格式，
    // 如 [0, 100]。我们只取数组的第一个元素作为位置的起始值。
    // ============================================================
    private int readRangeValue(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isArray() || value.isEmpty()) {
            return 0;
        }
        return value.get(0).asInt(0);
    }

    // ============================================================
    // 私有方法：布尔值提取（支持多种格式）
    // ============================================================
    // ASR 引擎可能以不同格式返回布尔值：
    //   - 标准布尔：true / false
    //   - 字符串："true" / "false" / "1" / "0"
    // 此方法兼容上述所有格式。
    // ============================================================
    private boolean readBoolean(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null) {
            return false;  // 字段不存在，默认 false
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return "true".equalsIgnoreCase(value.asText());
    }
}
```

**再补充说明 `AsyncTranscriptionBuffer.java`（新增文件）：** 它是"接收与消费解耦"的关键。`receive()` 用带 200ms 超时的 `offer` 入队（队列容量 256），满了就丢弃分片而不阻塞接收线程；`consume()` 标了 `@Async("transcriptionTaskExecutor")`，在独立线程池里用带超时的 `poll` 循环取分片，交给 `mergeSegment` 合并；`close()` 先写入"会话已关闭"标记再摘除队列，让消费循环自行退出、回收线程。这三处设计共同保证了转写线程不会随会话泄漏。

---

### 关于 transcript 更新机制的补充说明

**关键设计原则：transcript 只在 ed=true 时才会更新。**

这意味着：
1. **临时结果不会污染答案表**：ASR 在识别过程中会不断修正临时结果（如同声传译里常见的"我今...我今天去...我今天去公司"），如果把这些临时结果都写入 transcript，最终得到的文本会充斥着冗馀和错误。
2. **同一 seg_id 的后续更新只替换 TreeMap 节点**：不会追加文本，不会产生"我今天我今天去"这样的重复。
3. **前端实时展示 preview**：用户看到的是临时预览（preview），体验流畅；但最终落入答案表的是 transcript（只包含已确认的分片）。

**业务层如何获取 transcript？**
`TranscriptionSessionContext` 对外只暴露两个只读方法：`getTranscript()` 返回"只含已确认分片"的完整文本（适合落库），`getPreview()` 返回"含临时结果"的实时预览（适合前端刷新展示）。

需要说明的是，当前真实代码里"面试结束拿整场说话内容"走的**不是** `getTranscript()`，而是答案表兜底：面试结束时 WebSocket 早已关闭、`TranscriptionSessionContext` 随连接销毁，`InterviewController.end()` 调用的是 `answers.loadSessionTranscript(sessionId)`——把 `interview_answer.answer` 里每次提交的答案文本按主键升序拼起来。原因见"表现分析"小节：语音答案在提交时已经转写成了文本，答案表本身就是整场内容的可靠落点。

---

### 前端 6：编写样式

**本小节目的：** 统一页面布局、状态反馈和响应式表现，保证主要流程在桌面和移动端都可用。

**本小节流程：**
1. 设计页面整体结构（登录页、侧栏 + 主内容区的工作区、报告页）
2. 编写公共样式（颜色、字体、间距、按钮基本样式）
3. 完成各页面布局（登录表单居中、准备页两栏、答题区自适应、报告区可滚动）
4. 适配小屏幕（媒体查询，按钮和输入框在小屏上全宽）
5. 检查内容不重叠（使用 grid / flex 布局，避免固定高度）

**样式写在哪里：** 本项目**没有独立的 CSS 文件**（不存在 `frontend/src/styles.css`），也没有引入任何 UI 组件库。所有样式以模板字符串的形式内联在 `frontend/src/main.jsx` 文件末尾的 `const styles` 常量里，再通过组件 JSX 顶部的 `<style>{styles}</style>` 注入页面。

这样做的原因：项目体量小，样式和结构放在同一个文件里，改页面时不用来回跳文件；同时省掉了 CSS 预处理器和额外的构建配置，对初学者最直观。

**注入方式（main.jsx 的返回值开头，登录页和主页面各有一处）：**

```jsx
  if (!user) {
    return (
      <>
        <style>{styles}</style>
        <main className="auth-shell">
```

**样式负责的页面区域：**

```text
登录页（auth-shell / auth-panel）   —— 居中显示，表单纵向排列，按钮突出
整体框架（app-shell / topbar）      —— 顶栏 + 下方工作区
工作区（workspace）                 —— 240px 侧栏 + 主内容区两栏 grid
流程侧栏（sidebar / step）          —— 显示当前面试阶段，active 态高亮
准备页（setup-grid / upload-box）   —— 岗位卡片与简历上传区
答题页（interview-panel / question-meta / transcript / answer-actions）
                                    —— 题目、分类标签、语音转写、录音与提交按钮
报告页（report-panel / report-document / empty-state / loader）
                                    —— Markdown 富文本展示与加载中状态
移动端布局（@media）                —— 800px 以下侧栏变横向、内容区全宽；480px 以下按钮全宽
```

**关键样式片段（节选，完整内容见 `main.jsx` 末尾 `const styles`）：**

```css
.auth-shell { min-height: 100vh; display: grid; place-items: center; padding: 24px; background: #eaf0ef; }
.app-shell { min-height: 100vh; } .topbar { height: 68px; display: flex; align-items: center; justify-content: space-between; padding: 0 5vw; background: #fff; border-bottom: 1px solid #dae4e1; }
.workspace { display: grid; grid-template-columns: 240px minmax(0, 1fr); min-height: calc(100vh - 68px); max-width: 1440px; margin: auto; }
.step.active { border-left-color: #1c6b5a; background: #fff; color: #1c6b5a; }
.setup-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 24px; }
.position-option.selected { border-color: #1c6b5a; background: #f1f8f5; }
.transcript { margin-top: 12px; padding: 12px 15px; background: #f4f7f7; color: #526166; }
.report-document { line-height: 1.8; color: #35454a; }
.empty-state { display: grid; place-items: center; gap: 15px; min-height: 250px; color: #718087; }
.loader { width: 22px; height: 22px; border: 3px solid #d5e1de; border-top-color: #1c6b5a; border-radius: 50%; animation: spin .8s linear infinite; } @keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 800px) { .workspace { display: block; } .sidebar { display: flex; gap: 8px; padding: 12px 18px; overflow-x: auto; border-right: 0; border-bottom: 1px solid #dae4e1; } .setup-grid { grid-template-columns: 1fr; } }
@media (max-width: 480px) { .answer-actions { flex-direction: column-reverse; } .answer-actions button { width: 100%; } }
```

**样式编写原则：**
- 请求、状态和业务流程放在 main.jsx 的组件逻辑里，不要把 JavaScript 逻辑塞进样式字符串
- 样式全部写在 `const styles` 模板字符串中，不创建额外的 CSS 文件
- 使用类名选择器而非标签选择器，避免污染全局样式
- 用 grid / flex 做布局，避免写死高度导致内容重叠

---

### 前端脚手架：package.json / vite.config.js / index.html

**本小节目的：** 把 `frontend/` 目录下支撑 React + Vite 跑起来的最小工程文件补齐，说明每个文件解决什么问题。前面几节讲的都是 `src/` 里的页面逻辑，这一节补上"页面之外、但少了就跑不起来"的三个文件。

**本小节流程：**
1. `package.json`：声明依赖（React 18、Vite 5）和启动脚本
2. `vite.config.js`：配置开发服务器端口与 `/api`、`/ws` 两个代理
3. `index.html`：提供 React 的挂载点 `<div id="root">`

**文件位置：** `frontend/package.json`、`frontend/vite.config.js`、`frontend/index.html`

**为什么必须有这三个文件：**

| 文件 | 作用 | 少了会怎样 |
| --- | --- | --- |
| `package.json` | 声明依赖和 `npm run dev` 脚本 | 装不了依赖、起不了开发服务器 |
| `vite.config.js` | 把 `/api`、`/ws` 代理到后端 18080 | 前端请求全部 404，WebSocket 握手被 5173 截胡 |
| `index.html` | React 的挂载点 | `createRoot(document.getElementById('root'))` 拿到 null，页面白屏 |

**`frontend/package.json`：**

```json
{
  "name": "interview-frontend",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1"
  },
  "devDependencies": {
    "@vitejs/plugin-react": "^4.3.4",
    "vite": "^5.4.11"
  }
}
```

**`frontend/vite.config.js`：**

```javascript
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 从这里开始是新增的：前端开发服务器配置。
// 为什么需要它：main.jsx 与 nlsTranscriptionClient.js 里的请求都写成同源相对路径
// （/api/... 与 /ws/...），开发时前端跑在 5173、后端跑在 18080，必须靠代理把这两类
// 前缀转发到后端；ws: true 是给 WebSocket 用的，不写这一行 /ws/interviews/... 的
// 升级握手会被前端开发服务器自己截胡，连接直接失败。
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // REST 接口统一走 /api 前缀（与后端各 Controller 的 @RequestMapping 一致）。
      '/api': {
        target: 'http://localhost:18080',
        changeOrigin: true
      },
      // WebSocket：面试会话的实时通道（转写分片上报 + ping/pong 保活）。
      '/ws': {
        target: 'ws://localhost:18080',
        ws: true
      }
    }
  }
});
// 新增结束
```

**`frontend/index.html`：**

```html
<!doctype html>
<!-- 从这里开始是新增的：前端入口页。项目里 frontend/ 下原本只有 src/main.jsx，
     没有这个挂载点，浏览器打开后 createRoot(document.getElementById('root')) 会拿到 null。 -->
<html lang="zh-CN">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>码上面试</title>
  </head>
  <body>
    <!-- React 的挂载点，id 必须与 src/main.jsx 末尾的 getElementById('root') 一致。 -->
    <div id="root"></div>
    <!-- type="module" 是 Vite 的要求：main.jsx 里用的是 import 语法。 -->
    <script type="module" src="/src/main.jsx"></script>
  </body>
</html>
<!-- 新增结束 -->
```

**启动方式：** 在 `frontend/` 目录执行 `npm install` 安装依赖，再执行 `npm run dev`，开发服务器监听 5173；后端 Spring Boot 监听 18080。访问 `http://localhost:5173` 即可，所有 `/api`、`/ws` 请求由 Vite 代理转发到后端，浏览器视角下是"同源"，不存在跨域问题。

**与生产环境的关系：** `npm run build` 产出的静态文件由后端或 Nginx 托管，此时没有 Vite 代理，靠 `VITE_API_ROOT` 环境变量把 `API_ROOT` 指向真实后端地址（见「前端 1」里的 `const API_ROOT = import.meta.env.VITE_API_ROOT || ''`）。

---


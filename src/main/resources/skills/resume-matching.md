---
purpose: 抽取简历真实技术技能并计算与目标岗位的匹配度
entry-points: ResumeAiExtractionService.extractSkills, PositionController.match
inputs: parsedResumeText, positionSkills
output: 技能 JSON 字符串数组 + 匹配度打分
---
# Skill：resume-matching（简历匹配规则）

## 一、这个 Skill 是做什么的

分两段：先用大模型把简历正文里**真正的技术技能**抽成结构化数组，
再用纯代码把这份技能数组和岗位要求技能做**集合比对**，算出匹配度。

调用入口：
- 技能抽取：`ResumeAiExtractionService.extractSkills(...)`
- 匹配打分：`PositionController.match(...)`（`POST /api/positions/{id}/match?resumeId=...`）

## 二、技能抽取（大模型负责）

### 输入
简历解析后的正文文本（`parsedResumeText`）。

### 输出契约（必须严格遵守）

**只返回 JSON 字符串数组，不要返回解释、不要返回 Markdown 代码围栏。**

```json
["Java","Spring Boot","MySQL","Redis","Docker"]
```

- 数组元素必须是**非空字符串**，元素类型只能是字符串（不能是对象或嵌套数组）。
- 数组里只放技能名，不要放"熟悉""精通""3 年经验"这类修饰词。
- 同一技能只出现一次（后端也会去重）。

> 后端校验：必须是 JSON 数组；元素必须是非空字符串；
> 任一不满足即抛 `AI resume skills must be a valid JSON array`，本次抽取作废。

### 抽取范围（只抽这五类）

| 类别 | 示例 |
| --- | --- |
| 编程语言 | Java、Python、Go、TypeScript、SQL |
| 后端框架 | Spring Boot、Spring Cloud、MyBatis、Netty |
| 数据库 | MySQL、PostgreSQL、MongoDB、ClickHouse |
| 中间件 | Redis、Kafka、RabbitMQ、Elasticsearch |
| 云原生工具 | Docker、Kubernetes、Jenkins、Prometheus |

### 不抽取的内容

- 办公软件（Word、Excel、PPT）等非技术技能；
- 软技能（沟通能力、团队协作、抗压能力）；
- 学历、学校、公司名、职位名、证书名称；
- 简历里没有出现过的技术（**严禁推断和补全**）。

## 三、匹配打分（代码负责，模型不参与）

1. 岗位技能与候选人技能都按 JSON 数组解析（`parseSkills`），过滤空白并去重。
2. 比对前做归一化：`trim().toLowerCase()`，即**忽略大小写和首尾空格**
   （`Java` 与 `java`、`Spring Boot` 与 ` spring boot ` 视为同一技能）。
3. 命中技能 = 岗位要求技能中，能在候选人技能集合里找到的那些。
4. 匹配分计算公式：

```
matchScore = round(命中技能数 * 10000 / 岗位要求技能数) / 100.0
```

即**命中率百分比，保留两位小数**（如要求 3 项命中 1 项 → `33.33`）。
岗位技能要求为空时，`matchScore` 直接为 `0`。

5. 返回给前端的 `reason` 字段格式固定为：`匹配技能：<技能1>、<技能2>`
   （用中文顿号连接，无命中时该字段为空串）。

## 四、禁止事项

- 不要把整段简历正文当作技能写进数组。
- 不要把"Java"和"Java 开发"当成两个技能重复输出。
- 不要在抽取结果里做匹配度判断或给出建议——匹配打分由代码统一计算，模型只负责抽取。
- 不要输出 JSON 数组之外的任何字符。

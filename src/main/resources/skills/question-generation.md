---
purpose: 给大模型提供"面试出题"规则，结合目标岗位与候选人真实经历生成技术面试题
entry-points: InterviewAiFacade.generateQuestion, NextQuestionPreGeneratorService.preGenerate
inputs: positionName, positionDescription, requiredSkills, resumeContent, sequenceNo
output: JSON {"content":"题目正文","category":"考察分类"}
---
# Skill：question-generation（出题规则）

## 一、这个 Skill 是做什么的

给大模型提供"面试出题"的规则约束，让它在拿到**目标岗位 + 候选人简历**后，
生成一道**只针对该候选人真实经历**的中文技术面试题。

调用入口：`InterviewAiFacade.generateQuestion(...)`
调用时机：
- 面试开始后生成第 1 题；
- 预生成服务（`NextQuestionPreGeneratorService`）提前生成后续题目。

## 二、输入

| 参数 | 说明 |
| --- | --- |
| positionName | 目标岗位名称，决定出题方向 |
| positionDescription | 岗位职责说明，决定考察范围 |
| requiredSkills | 岗位技能要求，出题要覆盖其中的技术栈 |
| resumeContent | 候选人简历正文，题目的事实来源 |
| sequenceNo | 题号（从 1 开始），用于控制题目难度递进 |

## 三、输出契约（必须严格遵守）

**只返回 JSON，不要返回 Markdown 代码围栏，不要附加任何解释文字。**

```json
{"content":"题目正文","category":"考察分类"}
```

- `content`：题目正文，非空，中文，一句话到三句话，必须是一个可以被口头回答的问题。
- `category`：考察分类，例如"简历定制""Java 基础""并发编程""数据库""项目经验"；无法判断时使用"简历定制"。

> 后端会先 `parseJson` 再取字段，返回任何多余文字都会导致解析失败。

## 四、出题规则

1. **以简历为事实来源**：题目必须围绕简历中明确出现的项目、职责或技术栈展开。
2. **禁止编造**：不得虚构简历里没有的公司、项目、技术或数据；简历未提及的内容不能当作已知事实来提问。
3. **结合岗位**：优先考察岗位技能要求中出现、且简历里也有迹可循的技术点。
4. **难度递进**：第 1 题偏热身与基础确认（候选人最熟悉的栈），题号越大越深入（原理 → 取舍 → 边界）。
5. **一题一问**：一道题只考察一个核心知识点，不要把多个不相关的问题塞进同一题。
6. **可作答**：不出现"请写完整代码"这类无法口头回答的要求，不要求候选人背诵 API 签名。
7. **中文提问**：全程使用中文，术语可保留英文原文（如 Redis、JVM、Kafka）。

## 五、禁止事项

- 不要输出"以下是题目：""答案："等前缀或后缀。
- 不要输出 JSON 之外的任何字符，包括 ``` 围栏和注释。
- 不要在题目里泄露答案或评分标准。
- 不要生成与岗位、简历都无关的通用八股题。

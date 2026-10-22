package com.example.interview.skill;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component 
public class SkillRegistry {
    private final SkillLoader loader;
    private final Map<String,SkillDefinition> definitions = new ConcurrentHashMap<>();

    public SkillRegistry(SkillLoader loader) {
        this.loader = loader;
        registerDefaults();  // 启动时自动注册所有默认 Skill
    }
    public void register(String name) {
        definitions.put(
                name,                           // key：Skill 名称
                loader.load(name, "v1")          // value：从文件加载的 Skill 定义（版本 v1）
        );
    }
    private void registerDefaults() {
        register("question-generation");  // 出题规则
        register("interview-scoring");    // 评分规则
        register("follow-up-decision");   // 追问决策规则
        register("report-review");        // 报告生成规则
        register("resume-matching");      // 简历匹配规则
    }

    public SkillDefinition get(String name)
    {
        SkillDefinition definition = definitions.get(name);

        if(definition == null)
        {
            throw new IllegalStateException("skill not found: " + name);
        }
        return definition;
    }
}

package com.example.interview.skill;

import org.springframework.web.bind.annotation.*;

@RestController 
@RequestMapping("/api/skills")
public class SkillController {
    private final SkillRegistry registry;
    public SkillController(SkillRegistry registry) {
        this.registry = registry;
    }
    @GetMapping("/{name}")
    public SkillDefinition get(@PathVariable String name) {
        // 委托给注册表查询，如果未找到会抛出 IllegalArgumentException，
        // Spring Boot 默认会将其转换为 HTTP 400 Bad Request 响应。
        return registry.get(name);
    }
}

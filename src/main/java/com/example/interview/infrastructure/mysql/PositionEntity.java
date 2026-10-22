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
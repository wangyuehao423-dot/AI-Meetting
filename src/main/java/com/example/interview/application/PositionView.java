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
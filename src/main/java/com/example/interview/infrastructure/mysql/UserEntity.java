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
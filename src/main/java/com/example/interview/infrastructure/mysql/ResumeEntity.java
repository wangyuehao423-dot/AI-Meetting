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

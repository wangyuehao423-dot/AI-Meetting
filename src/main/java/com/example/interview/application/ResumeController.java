package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.infrastructure.mysql.ResumeEntity;
import com.example.interview.infrastructure.mysql.ResumeMapper;
import com.example.interview.infrastructure.mysql.UserEntity;
import com.example.interview.infrastructure.mysql.UserMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/api/resumes")
public class ResumeController
{
    private final ResumeMapper resumes;
    // 从这里开始是新增的：用户表 Mapper，用于上传前校验 userId 是否真实存在。
    // 为什么必须校验：interview_resume.user_id 有外键指向 app_user.id，若前端
    // 携带的 userId 在库里不存在（账号被删、数据库重建导致旧登录态失效等），
    // insert 会抛 DataIntegrityViolationException，接口直接裸 500。上传前先查
    // 一次用户表，不存在时返回明确的 400 "user not found"，提示重新登录/注册，
    // 而不是把外键异常原样抛给前端。
    private final UserMapper users;
    // 新增结束
    // 先用 parser 提取简历原文，再把原文交给 AI 提取技术技能。
    private final ResumeParserService parser;
    private final ResumeAiExtractionService aiExtraction;
    private final Path root = Path.of("data/resumes");

    public ResumeController(ResumeMapper resumes,
                            UserMapper users,
                            ResumeParserService parser,
                            ResumeAiExtractionService aiExtraction)
    {
        this.resumes = resumes;
        this.users = users;
        this.parser = parser;
        this.aiExtraction = aiExtraction;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumeView upload(@RequestParam("userId") long userId,
                             @RequestPart("file")MultipartFile file) throws IOException {
        // 从这里开始是新增的：上传前校验用户存在，避免外键异常裸 500。
        if (users.selectById(userId) == null) {
            throw new IllegalArgumentException(
                    "user not found, please re-login or register"
            );
        }
        // 新增结束
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
        String original = file.getOriginalFilename() == null ? "resume" : Path.of(file.getOriginalFilename())
                .getFileName()
                .toString();
        Path path = root.resolve(System.currentTimeMillis() + "-" + original);
        try {
            file.transferTo(path);

            ResumeEntity entity = new ResumeEntity();
            entity.setUserId(userId);
            entity.setFileName(original);
            entity.setStoragePath(path.toString());
            entity.setStatus("PARSING");

            // parsedContent 保存文件解析出的完整原文，便于后续出题、报告等功能使用。
            String parsedResumeText = parser.parse(path);
            entity.setParsedContent(parsedResumeText);
            // AI 只从原文中提取技术技能，并将 JSON 数组保存到独立字段。
            entity.setExtractedSkills(aiExtraction.extractSkills(parsedResumeText));
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

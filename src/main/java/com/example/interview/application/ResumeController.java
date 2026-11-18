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
    // 从这里开始是新增的：当前登录用户提供者，用来校验请求里的 userId 确实是本人。
    private final CurrentUserIdProvider currentUser;
    // 新增结束
    private final Path root = Path.of("data/resumes");

    public ResumeController(ResumeMapper resumes,
                            UserMapper users,
                            ResumeParserService parser,
                            ResumeAiExtractionService aiExtraction,
                            CurrentUserIdProvider currentUser)
    {
        this.resumes = resumes;
        this.users = users;
        this.parser = parser;
        this.aiExtraction = aiExtraction;
        this.currentUser = currentUser;
    }

    /**
     * 把请求参数里的 userId 与 JWT 里的真实身份对齐。
     *
     * <p>为什么不能直接信 {@code @RequestParam("userId")}：那个值是前端随便填的，
     * 任何人只要知道别人的 userId 就能拉走别人的简历全文——简历里是手机号、邮箱、
     * 完整工作经历。所以这里强制以 token 为准：不带 token 直接 401（currentUserId
     * 会抛 AuthException），带了 token 但参数写的是别人则 403。</p>
     *
     * <p>保留这个参数是为了不破坏既有前端调用（它传的本来就是自己的 id），
     * 但它的作用从「身份来源」降级成了「一致性校验」。</p>
     */
    private long resolveUserId(long requested) {
        long actual = currentUser.currentUserId();
        if (requested != actual) {
            throw new org.springframework.security.access.AccessDeniedException(
                    "不能查看或上传他人的简历");
        }
        return actual;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumeView upload(@RequestParam("userId") long userId,
                             @RequestPart("file")MultipartFile file) throws IOException {
        // 先把 userId 钉死成 token 里的本人：伪造别人的 id 会 403，
        // 不带 token 会 401，不会走到下面任何一步。
        userId = resolveUserId(userId);
        // 从这里开始是新增的：上传前校验用户存在，避免外键异常裸 500。
        if (users.selectById(userId) == null) {
            // 能通过 resolveUserId 说明 token 有效，但库里已经没有这个人了
            // （账号被删 / 库重建导致旧登录态失效）——属于身份失效，回 401 让前端重新登录。
            throw new AuthException(
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
        // 同上：只能查自己的简历。这是原来最大的一个口子——
        // GET /api/resumes?userId=别人 曾能直接拿到别人的简历全文。
        userId = resolveUserId(userId);
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

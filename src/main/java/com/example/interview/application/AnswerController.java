package com.example.interview.application;

import com.example.interview.infrastructure.mysql.InterviewSessionEntity;
import com.example.interview.infrastructure.mysql.InterviewSessionMapper;
import jakarta.validation.Valid;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/answers")
public class AnswerController {
    private final AnswerCommandService answers;
    // 从这里开始是新增的：会话 Mapper + 当前用户提供者。
    // 为什么答题接口必须校验归属：这个接口会改会话状态、写答案、触发评分与追问。
    // 原来它完全开放——不带 token、只知道 sessionId，就能往别人的面试里塞答案、
    // 把状态机推乱。读接口漏了是信息泄露，写接口漏了是数据污染。
    private final InterviewSessionMapper sessions;
    private final CurrentUserIdProvider currentUser;
    // 新增结束

    public AnswerController(AnswerCommandService answers,
                            InterviewSessionMapper sessions,
                            CurrentUserIdProvider currentUser)
    {
        this.answers = answers;
        this.sessions = sessions;
        this.currentUser = currentUser;
    }

    @PostMapping ("/{sessionId}/answers")
    public void submit(@PathVariable("sessionId") UUID sessionId,
                        @Valid @RequestBody SubmitAnswerRequest request
    )
    {
        // 不带 token → currentUserId() 抛 AuthException → 401；
        // 会话不存在 → 404；会话属于别人 → 403。
        long actual = currentUser.currentUserId();
        InterviewSessionEntity session = sessions.selectById(sessionId.toString());
        if (session == null) {
            throw new NotFoundException("interview session not found");
        }
        if (session.getUserId() == null || session.getUserId() != actual) {
            throw new AccessDeniedException("不能向他人的面试提交答案");
        }

        answers.submit(new AnswerCommand(
            sessionId,
            request.questionId(),
            request.requestId(),
            request.answer(),
            // 从这里开始是新增的：末位传入追问标记。
            // 前端可能不传 followUp（老版本），此时 request.followUp() 为 null，
            // Boolean.TRUE.equals(...) 会把 null 安全地兜底成 false（按初次作答处理），
            // 既不会抛空指针，也不会把未传字段误判成追问。
            Boolean.TRUE.equals(request.followUp())
            // 新增结束
        ));
    }
}
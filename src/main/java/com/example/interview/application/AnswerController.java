package com.example.interview.application;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/answers")
public class AnswerController {
    private final AnswerCommandService answers;
    public AnswerController(AnswerCommandService answers)
    {
        this.answers = answers;
    }

    @PostMapping ("/{sessionId}/answers")
    public void submit(@PathVariable("sessionId") UUID sessionId,
                        @Valid @RequestBody SubmitAnswerRequest request
    )
    {
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
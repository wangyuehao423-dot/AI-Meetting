package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("interview_question")
public class QuestionEntity {
    private String id;
    private String sessionId;
    private Long positionId;
    private String content;
    private String category;
    private Integer sequenceNo;
    private String status;
}
package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface InterviewSessionMapper
        extends BaseMapper<InterviewSessionEntity> {
}
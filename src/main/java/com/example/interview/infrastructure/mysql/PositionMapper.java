package com.example.interview.infrastructure.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

// BaseMapper 提供岗位的基础查询和保存能力。
@Mapper
public interface PositionMapper
        extends BaseMapper<PositionEntity> {
}
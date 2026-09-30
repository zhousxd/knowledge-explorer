package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.AuditLogEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLogEntity> {}

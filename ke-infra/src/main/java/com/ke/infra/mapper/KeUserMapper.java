package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.KeUserEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface KeUserMapper extends BaseMapper<KeUserEntity> {}

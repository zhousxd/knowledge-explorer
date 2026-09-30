package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.EntryEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EntryMapper extends BaseMapper<EntryEntity> {}

package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.AnalyticsEventEntity;
import org.apache.ibatis.annotations.Mapper;

/** analytics_event 埋点写入（V6__analytics.sql）；6 指标聚合 SQL 在 {@link MetricsMapper} */
@Mapper
public interface AnalyticsMapper extends BaseMapper<AnalyticsEventEntity> {}

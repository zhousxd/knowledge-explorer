package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.AgentRunEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentRunMapper extends BaseMapper<AgentRunEntity> {

    /** 心跳回收兜底：RUNNING 且心跳超时的运行判 TIMEOUT，防卡死（02 §5.1） */
    @Update("update agent_run set status='TIMEOUT', error='heartbeat expired', updated_at=now() " +
            "where status='RUNNING' and heartbeat_at < now() - make_interval(secs => #{staleSeconds})")
    int recycleStale(@Param("staleSeconds") int staleSeconds);

    @Update("update agent_run set heartbeat_at=now() where id=#{id}")
    int touch(@Param("id") Long id);
}

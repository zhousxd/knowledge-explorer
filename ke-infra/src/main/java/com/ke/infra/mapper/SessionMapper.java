package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.SessionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SessionMapper extends BaseMapper<SessionEntity> {

    /**
     * 每会话节点数与分支数（按用户一次聚合，列表/latest 共用，防 N+1）。
     * 分支语义（01）：分支=回到历史节点产生的新子树，即「拥有 ≥2 个子节点的节点数」（真实分叉点数）。
     * 实现：先按 (session_id, parent_node_id) 分组数出每父的孩子数 cnt，
     * 再对组聚合——SUM(cnt)=总节点数；parent 非空且 cnt≥2 的组数=分叉点数。
     * 注：简报给的简化式 COUNT(*)-COUNT(DISTINCT COALESCE(parent_node_id,id)) 实为「叶子数」——
     * 根（parent 为空回退 id）会与其首子的 parent 值合并计数，纯链（0 个分叉点）会误报 1，故不采用。
     * 无节点的会话不出现在结果里，调用方按 0/0 兜底。
     */
    @Select("""
            SELECT grp.session_id AS sessionId,
                   SUM(grp.cnt) AS nodeCount,
                   COUNT(*) FILTER (WHERE grp.parent_node_id IS NOT NULL AND grp.cnt >= 2) AS branchCount
            FROM (
                SELECT session_id, parent_node_id, COUNT(*) AS cnt
                FROM path_node
                WHERE session_id IN (SELECT id FROM exploration_session WHERE user_id = #{userId})
                GROUP BY session_id, parent_node_id
            ) grp
            GROUP BY grp.session_id
            """)
    List<SessionStatsRow> selectStatsByUser(@Param("userId") long userId);

    /** 上面聚合查询的行形状（MyBatis setter 绑定，mapUnderscoreToCamelCase） */
    class SessionStatsRow {
        private Long sessionId;
        private Long nodeCount;
        private Long branchCount;

        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
        public Long getNodeCount() { return nodeCount; }
        public void setNodeCount(Long nodeCount) { this.nodeCount = nodeCount; }
        public Long getBranchCount() { return branchCount; }
        public void setBranchCount(Long branchCount) { this.branchCount = branchCount; }
    }
}

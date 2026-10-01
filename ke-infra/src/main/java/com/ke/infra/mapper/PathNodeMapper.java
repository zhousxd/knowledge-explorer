package com.ke.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ke.infra.entity.PathNodeEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.OffsetDateTime;
import java.util.List;

@Mapper
public interface PathNodeMapper extends BaseMapper<PathNodeEntity> {

    /**
     * 递归子树（Phase 4 钉死契约，成果整理/分享共用）：从任意节点向下收集整棵子树。
     * parent_node_id 只增不改（无 UPDATE 路径），结构必为森林无环，UNION ALL 安全；
     * 递归臂额外约束 n.session_id = #{sessionId} 双保险（子节点跨会话也不串树）；
     * ORDER BY visited_at, id 给出稳定的「访问顺序」视图。
     */
    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT * FROM path_node WHERE session_id = #{sessionId} AND id = #{nodeId}
                UNION ALL
                SELECT n.* FROM path_node n JOIN subtree s ON n.parent_node_id = s.id AND n.session_id = #{sessionId}
            ) SELECT * FROM subtree ORDER BY visited_at, id
            """)
    List<PathNodeEntity> selectSubtree(@Param("sessionId") long sessionId, @Param("nodeId") long nodeId);

    /** 整棵会话树：同一递归从根（parent_node_id IS NULL）出发（断点续探 GET /api/sessions/{id} 用） */
    @Select("""
            WITH RECURSIVE subtree AS (
                SELECT * FROM path_node WHERE session_id = #{sessionId} AND parent_node_id IS NULL
                UNION ALL
                SELECT n.* FROM path_node n JOIN subtree s ON n.parent_node_id = s.id AND n.session_id = #{sessionId}
            ) SELECT * FROM subtree ORDER BY visited_at, id
            """)
    List<PathNodeEntity> selectTree(@Param("sessionId") long sessionId);

    /**
     * 列表页「最近节点」批量取：每会话按 visited_at DESC, id DESC 取第一行
     * （DISTINCT ON 为 PG 方言，库版本钉死 16）。用于 title 取最新节点所访卡题。
     */
    @Select("""
            SELECT DISTINCT ON (n.session_id)
                   n.session_id AS sessionId,
                   n.card_version_id AS cardVersionId,
                   n.visited_at AS visitedAt
            FROM path_node n
            WHERE n.session_id IN (SELECT id FROM exploration_session WHERE user_id = #{userId})
            ORDER BY n.session_id, n.visited_at DESC, n.id DESC
            """)
    List<LatestNodeRow> selectLatestNodeByUser(@Param("userId") long userId);

    /** 上面批量查询的行形状 */
    class LatestNodeRow {
        private Long sessionId;
        private Long cardVersionId;
        private OffsetDateTime visitedAt;

        public Long getSessionId() { return sessionId; }
        public void setSessionId(Long sessionId) { this.sessionId = sessionId; }
        public Long getCardVersionId() { return cardVersionId; }
        public void setCardVersionId(Long cardVersionId) { this.cardVersionId = cardVersionId; }
        public OffsetDateTime getVisitedAt() { return visitedAt; }
        public void setVisitedAt(OffsetDateTime visitedAt) { this.visitedAt = visitedAt; }
    }
}

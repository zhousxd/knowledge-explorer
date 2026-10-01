package com.ke.service.analytics;

/**
 * 埋点事件类型常量（FR-O05 / 01 §5，Task 33）。event_type 列 VARCHAR(30)，取值域以此类为权威：
 * <ul>
 *   <li>session_start：创建探索会话（SessionService.create）；</li>
 *   <li>node_visit：追加路径节点，payload.isNewKnowledge 标记是否新知（SessionService.addNode）；</li>
 *   <li>service_run：智能服务终态，payload.{serviceType,status,latencyMs,sessionId}
 *       （ExplainService / SummaryService 终态回写处）；</li>
 *   <li>artifact_save：整理成果保存（SummaryService DONE 落 REPORT artifact）；</li>
 *   <li>share_create / share_view / share_continue：分享创建 / 免登录浏览 / 接续副本（ShareService）；</li>
 *   <li>entry_create：入口保存，payload.{scope,testTotal}（EntryMutationService.create）；</li>
 *   <li>favorite：收藏卡片（FavoriteService.favorite）。</li>
 * </ul>
 */
public final class AnalyticsEvents {

    public static final String SESSION_START = "session_start";
    public static final String NODE_VISIT = "node_visit";
    public static final String SERVICE_RUN = "service_run";
    public static final String ARTIFACT_SAVE = "artifact_save";
    public static final String SHARE_CREATE = "share_create";
    public static final String SHARE_VIEW = "share_view";
    public static final String SHARE_CONTINUE = "share_continue";
    public static final String ENTRY_CREATE = "entry_create";
    public static final String FAVORITE = "favorite";

    private AnalyticsEvents() {
    }
}

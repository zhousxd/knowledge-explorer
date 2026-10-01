package com.ke.service.explore;

import com.ke.service.common.ApiResponse;
import com.ke.service.common.NotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 探索会话端点族（/api/sessions，02 §7）。恒需认证（匿名 401 由 SecurityConfig 兜底，
 * 勿加 permitAll）：POST 创建 201、GET 列表、GET latest（404=无会话，P3-13 冻结契约）、
 * GET {id}（会话+树，401/403/404 三分）、POST {id}/nodes、PUT {id}/explain-level。
 */
@RestController
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) { this.sessions = sessions; }

    public record CreateSessionRequest(String theme, String goal) {
    }

    public record AddNodeRequest(Long cardVersionId, Long entryId, Long parentNodeId, String questionText) {
    }

    public record ExplainLevelRequest(String level) {
    }

    /** 创建会话 {theme,goal} → 201 {sessionId}（theme 收 key：academy/cuisine/sound，后端不硬校验） */
    @PostMapping("/api/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> create(@RequestBody CreateSessionRequest req) {
        long sessionId = sessions.create(currentUserId(), req.theme(), req.goal());
        return ApiResponse.ok(Map.of("sessionId", sessionId));
    }

    /** 我的路径列表（updated_at DESC；page 从 1 起、size ≤ 50 默认 20） */
    @GetMapping("/api/sessions")
    public ApiResponse<SessionService.SessionPage> list(@RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(sessions.listMine(currentUserId(), page, size));
    }

    /** 断点续探摘要：无会话 → 404 envelope（保持 P3-13 冻结契约，前端归一为 null 隐藏续探卡） */
    @GetMapping("/api/sessions/latest")
    public ApiResponse<SessionService.ResumeSession> latest() {
        SessionService.ResumeSession resume = sessions.latest(currentUserId());
        if (resume == null) {
            throw new NotFoundException("暂无可续探的会话");
        }
        return ApiResponse.ok(resume);
    }

    /** 会话 + 完整树（匿名 401 / 非属主 403 / 不存在 404 三分） */
    @GetMapping("/api/sessions/{id}")
    public ApiResponse<SessionService.SessionDetail> detail(@PathVariable long id) {
        return ApiResponse.ok(sessions.detail(currentUserId(), id));
    }

    /** 追加节点（分支=指定历史 parentNodeId 即成） */
    @PostMapping("/api/sessions/{id}/nodes")
    public ApiResponse<SessionService.NodeView> addNode(@PathVariable long id,
                                                        @RequestBody AddNodeRequest req) {
        SessionService.AddNodeCommand cmd =
                new SessionService.AddNodeCommand(req.cardVersionId(), req.entryId(), req.parentNodeId(),
                        req.questionText());
        return ApiResponse.ok(sessions.addNode(currentUserId(), id, cmd));
    }

    /** 讲解度（FR-E10 会话内记忆）：SIMPLE/DEEP/CHILD 白名单，非法 → 400 */
    @PutMapping("/api/sessions/{id}/explain-level")
    public ApiResponse<Map<String, Object>> explainLevel(@PathVariable long id,
                                                         @RequestBody ExplainLevelRequest req) {
        String level = sessions.updateExplainLevel(currentUserId(), id, req.level());
        return ApiResponse.ok(Map.of("explainLevel", level));
    }

    private static long currentUserId() {
        return Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
    }
}

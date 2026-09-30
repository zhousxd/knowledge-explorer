package com.ke.service.auth;

import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.service.common.ApiResponse;
import com.ke.service.common.UnauthorizedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class MeController {
    private final KeUserMapper users;
    public MeController(KeUserMapper users) { this.users = users; }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me() {
        long uid = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        KeUserEntity u = users.selectById(uid);
        if (u == null) throw new UnauthorizedException("用户不存在");
        return ApiResponse.ok(Map.of("id", u.getId(), "nickname", u.getNickname(), "role", u.getRole()));
    }
}

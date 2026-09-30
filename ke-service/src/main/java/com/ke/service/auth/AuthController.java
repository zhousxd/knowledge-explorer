package com.ke.service.auth;

import com.ke.infra.entity.KeUserEntity;
import com.ke.service.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }

    public record RegisterReq(@NotBlank String phone, @NotBlank String password, @NotBlank String nickname) {}

    public record LoginReq(@NotBlank String phone, @NotBlank String password) {}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> register(@Valid @RequestBody RegisterReq req) {
        KeUserEntity u = auth.register(req.phone(), req.password(), req.nickname());
        return ApiResponse.ok(Map.of("id", u.getId(), "nickname", u.getNickname(), "role", u.getRole()));
    }

    @PostMapping("/login")
    public ApiResponse<Map<String, String>> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(auth.login(req.phone(), req.password()));
    }

    public record RefreshReq(@NotBlank String refreshToken) {}

    @PostMapping("/refresh")
    public ApiResponse<Map<String, String>> refresh(@Valid @RequestBody RefreshReq req) {
        return ApiResponse.ok(auth.refresh(req.refreshToken()));
    }
}

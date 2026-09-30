package com.ke.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.UnauthorizedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class AuthService {
    private final KeUserMapper users;
    private final JwtService jwt;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(KeUserMapper users, JwtService jwt) { this.users = users; this.jwt = jwt; }

    public KeUserEntity register(String phone, String password, String nickname) {
        if (users.selectOne(new LambdaQueryWrapper<KeUserEntity>()
                .eq(KeUserEntity::getPhone, phone)) != null) {
            throw new BadRequestException("该手机号已注册");
        }
        KeUserEntity u = new KeUserEntity();
        u.setPhone(phone);
        u.setPasswordHash(encoder.encode(password));
        u.setNickname(nickname);
        u.setRole("EXPLORER");
        u.setStatus("ACTIVE");
        users.insert(u);
        return u;
    }

    public Map<String, String> login(String phone, String password) {
        KeUserEntity u = users.selectOne(new LambdaQueryWrapper<KeUserEntity>()
            .eq(KeUserEntity::getPhone, phone));
        if (u == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new UnauthorizedException("手机号或密码错误");
        }
        return Map.of(
            "accessToken", jwt.issueAccess(u.getId(), u.getRole()),
            "refreshToken", jwt.issueRefresh(u.getId()));
    }

    public Map<String, String> refresh(String refreshToken) {
        var claims = jwt.parse(refreshToken);
        if (!"refresh".equals(claims.get("typ"))) throw new UnauthorizedException("无效的刷新令牌");
        Long userId = Long.valueOf(claims.getSubject());
        KeUserEntity u = users.selectById(userId);
        return Map.of("accessToken", jwt.issueAccess(u.getId(), u.getRole()),
                      "refreshToken", jwt.issueRefresh(u.getId()));
    }
}

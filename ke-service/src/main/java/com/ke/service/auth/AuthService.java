package com.ke.service.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ke.infra.entity.KeUserEntity;
import com.ke.infra.mapper.KeUserMapper;
import com.ke.service.common.BadRequestException;
import com.ke.service.common.UnauthorizedException;
import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            users.insert(u);
        } catch (DataIntegrityViolationException e) {
            // check-then-insert 之间的并发竞争：uk_phone 唯一约束兜底
            throw new BadRequestException("该手机号已注册");
        }
        return u;
    }

    public Map<String, String> login(String phone, String password) {
        KeUserEntity u = users.selectOne(new LambdaQueryWrapper<KeUserEntity>()
            .eq(KeUserEntity::getPhone, phone));
        if (u == null || !encoder.matches(password, u.getPasswordHash())) {
            throw new UnauthorizedException("手机号或密码错误");
        }
        return issueTokens(u);
    }

    /** 短信验证码登录（FR-U01）：用户不存在则自动注册 EXPLORER，昵称=探索者+手机尾号 4 位 */
    public Map<String, String> smsLogin(String phone) {
        KeUserEntity u = users.selectOne(new LambdaQueryWrapper<KeUserEntity>()
            .eq(KeUserEntity::getPhone, phone));
        if (u == null) u = autoRegister(phone);
        return issueTokens(u);
    }

    private KeUserEntity autoRegister(String phone) {
        KeUserEntity u = new KeUserEntity();
        u.setPhone(phone);
        // 短信注册用户无密码：随机串占位 hash，使密码登录自然失败
        u.setPasswordHash(encoder.encode(java.util.UUID.randomUUID().toString()));
        u.setNickname("探索者" + phone.substring(phone.length() - 4));
        u.setRole("EXPLORER");
        u.setStatus("ACTIVE");
        try {
            users.insert(u);
        } catch (DataIntegrityViolationException e) {
            // check-then-insert 之间的并发竞争：uk_phone 唯一约束兜底，回落为读取既有用户
            u = users.selectOne(new LambdaQueryWrapper<KeUserEntity>().eq(KeUserEntity::getPhone, phone));
        }
        return u;
    }

    private Map<String, String> issueTokens(KeUserEntity u) {
        return Map.of("accessToken", jwt.issueAccess(u.getId(), u.getRole()),
                      "refreshToken", jwt.issueRefresh(u.getId()));
    }

    public Map<String, String> refresh(String refreshToken) {
        var claims = jwt.parse(refreshToken);
        if (!"refresh".equals(claims.get("typ"))) throw new UnauthorizedException("无效的刷新令牌");
        Long userId = Long.valueOf(claims.getSubject());
        KeUserEntity u = users.selectById(userId);
        if (u == null) throw new UnauthorizedException("用户不存在");
        return Map.of("accessToken", jwt.issueAccess(u.getId(), u.getRole()),
                      "refreshToken", jwt.issueRefresh(u.getId()));
    }
}

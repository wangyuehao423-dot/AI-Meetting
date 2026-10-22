package com.example.interview.application;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.example.interview.config.JwtTokenService;
import com.example.interview.infrastructure.mysql.UserEntity;
import com.example.interview.infrastructure.mysql.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class UserController
{
    private final UserMapper users;

    private final PasswordEncoder encoder;

    private final JwtTokenService tokens;

    public UserController(UserMapper users, PasswordEncoder encoder, JwtTokenService tokens)
    {
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
    }
    @PostMapping("/register")
// 处理注册请求：校验用户名、加密密码、保存用户并返回登录 Token。
    public AuthResponse register(
            // @Valid 触发参数校验；@RequestBody 把 JSON 转成 RegisterRequest。
            @Valid @RequestBody RegisterRequest request
    ) {
        var count = users.selectCount(
                // lambdaQuery 用方法引用绑定实体字段，避免手写字段名。
                Wrappers.<UserEntity>lambdaQuery()
                        .eq(
                                UserEntity::getUsername,
                                request.username()
                        )
        );

        if (count > 0) {
            throw new IllegalStateException(
                    "username already exists"
            );
        }

        var user = new UserEntity();
        user.setUsername(request.username());
        user.setDisplayName(request.displayName());
        user.setPasswordHash(
                encoder.encode(request.password())
        );
        users.insert(user);

        return new AuthResponse(
                tokens.issue(user.getId(), user.getUsername()),
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getDisplayName()
        );
    }
    @PostMapping("/login")
// 处理登录请求：查询用户、比较密码哈希，成功后返回新的 JWT。
    public AuthResponse login(
            // Spring 会先把 JSON 绑定到 LoginRequest，再执行 @NotBlank 校验。
            @Valid @RequestBody LoginRequest request
    ) {
        var user = users.selectOne(
                Wrappers.<UserEntity>lambdaQuery()
                        .eq(
                                UserEntity::getUsername,
                                request.username()
                        )
        );

        if (user == null
                || !encoder.matches(
                request.password(),
                user.getPasswordHash()
        )) {
            throw new IllegalArgumentException(
                    "invalid credentials"
            );
        }

        return new AuthResponse(
                tokens.issue(user.getId(), user.getUsername()),
                String.valueOf(user.getId()),
                user.getUsername(),
                user.getDisplayName()
        );
    }
}

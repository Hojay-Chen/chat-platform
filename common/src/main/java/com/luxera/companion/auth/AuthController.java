package com.luxera.companion.auth;

import com.luxera.companion.config.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUser currentUser;

    public AuthController(AuthService authService, CurrentUser currentUser) {
        this.authService = authService;
        this.currentUser = currentUser;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthService.AuthResponse register(@Valid @RequestBody AuthService.RegisterRequest req) {
        throw new com.luxera.companion.common.BusinessException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "注册功能已关闭", "账号由管理员创建, 如需开通请联系管理员");
    }

    @PostMapping("/login")
    public AuthService.AuthResponse login(@Valid @RequestBody AuthService.LoginRequest req) {
        return authService.login(req);
    }

    @GetMapping("/me")
    public AuthService.UserDto me() {
        return authService.me(currentUser.requireUserId());
    }
}

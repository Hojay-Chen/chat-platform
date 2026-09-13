package com.luxera.companion.auth;

import com.luxera.companion.common.BusinessException;
import com.luxera.companion.config.JwtUtil;
import lombok.Data;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String username = req.getUsername().trim().toLowerCase();
        if (!StringUtils.hasText(username) || username.length() < 3) {
            throw BusinessException.badRequest("用户名至少 3 个字符");
        }
        if (req.getPassword() == null || req.getPassword().length() < 6) {
            throw BusinessException.badRequest("密码至少 6 位");
        }
        if (userRepository.existsByUsername(username)) {
            throw BusinessException.badRequest("用户名已被注册");
        }
        if (StringUtils.hasText(req.getEmail()) && userRepository.existsByEmail(req.getEmail().trim().toLowerCase())) {
            throw BusinessException.badRequest("邮箱已被注册");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(req.getPassword()));
        user.setEmail(StringUtils.hasText(req.getEmail()) ? req.getEmail().trim().toLowerCase() : null);
        user.setNickname(StringUtils.hasText(req.getNickname()) ? req.getNickname() : username);
        user.setBirthDate(req.getBirthDate());
        user.setGender(req.getGender());
        userRepository.save(user);

        return new AuthResponse(jwtUtil.generateToken(user.getId(), user.getUsername()), toDto(user));
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        String username = req.getUsername().trim().toLowerCase();
        User user = userRepository.findByUsername(username)
                .or(() -> userRepository.findByEmail(username))
                .orElseThrow(() -> new org.springframework.security.authentication.BadCredentialsException("用户名或密码错误"));
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new org.springframework.security.authentication.BadCredentialsException("用户名或密码错误");
        }
        return new AuthResponse(jwtUtil.generateToken(user.getId(), user.getUsername()), toDto(user));
    }

    @Transactional(readOnly = true)
    public UserDto me(String userId) {
        return toDto(userRepository.findById(userId)
                .orElseThrow(() -> new javax.persistence.EntityNotFoundException("用户不存在")));
    }

    public static UserDto toDto(User u) {
        UserDto dto = new UserDto();
        dto.setId(u.getId());
        dto.setUsername(u.getUsername());
        dto.setEmail(u.getEmail());
        dto.setNickname(u.getNickname());
        dto.setTimezone(u.getTimezone());
        dto.setBirthDate(u.getBirthDate());
        dto.setGender(u.getGender());
        dto.setCreatedAt(u.getCreatedAt());
        return dto;
    }

    @Data
    public static class RegisterRequest {
        private String username;
        private String password;
        private String email;
        private String nickname;
        private LocalDate birthDate;
        private String gender;
    }

    @Data
    public static class LoginRequest {
        private String username;
        private String password;
    }

    @Data
    public static class AuthResponse {
        private final String token;
        private final UserDto user;
    }

    @Data
    public static class UserDto {
        private String id;
        private String username;
        private String email;
        private String nickname;
        private String timezone;
        private LocalDate birthDate;
        private String gender;
        private LocalDateTime createdAt;
    }
}

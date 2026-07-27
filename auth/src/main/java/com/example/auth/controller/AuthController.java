package com.example.auth.controller;

import com.example.auth.JwtService;
import com.example.auth.model.User;
import com.example.auth.repository.UserRepository;
import com.example.auth.dto.LoginDto;
import com.example.auth.dto.RegisterDto;
import com.example.auth.dto.TokenResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository repo;
    private final PasswordEncoder encoder;
    private final JwtService jwtService;

    public AuthController(UserRepository repo, PasswordEncoder encoder, JwtService jwtService) {
        this.repo = repo;
        this.encoder = encoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@Valid @RequestBody RegisterDto dto) {
        if (repo.findByLogin(dto.login()).isPresent()) {
            return ResponseEntity.badRequest().body("User exists");
        }

        User u = new User(dto.login(), encoder.encode(dto.password()));
        repo.save(u);

        String token = jwtService.generateToken(u.getLogin(), u.getId());

        return ResponseEntity.ok(new TokenResponse(token, "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDto dto) {
        User u = repo.findByLogin(dto.login()).orElse(null);

        if (u == null || !encoder.matches(dto.password(), u.getPassword())) {
            return ResponseEntity.status(401).body("Invalid credentials");
        }

        String token = jwtService.generateToken(u.getLogin(), u.getId());

        return ResponseEntity.ok(new TokenResponse(token, "Logged in successfully"));
    }
}




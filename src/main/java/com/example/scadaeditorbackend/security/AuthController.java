package com.example.scadaeditorbackend.security;

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
    public ResponseEntity<?> register(@RequestBody RegisterDto dto) {
        if (repo.findByLogin(dto.login()).isPresent()) {
            return ResponseEntity.badRequest().body("User exists");
        }

        User u = new User(dto.login(), encoder.encode(dto.password()));
        repo.save(u);

        String token = jwtService.generateToken(u.getLogin(), u.getId());

        return ResponseEntity.ok(new RegisterResponse(token, "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDto dto) {
        User u = repo.findByLogin(dto.login())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!encoder.matches(dto.password(), u.getPassword())) {
            return ResponseEntity.status(401).body("Incorrect password");
        }

        String token = jwtService.generateToken(u.getLogin(), u.getId());

        return ResponseEntity.ok(new TokenResponse(token));
    }
}

record RegisterDto(String login, String password) {}
record LoginDto(String login, String password) {}
record RegisterResponse(String token, String message) {}
record TokenResponse(String token) {}



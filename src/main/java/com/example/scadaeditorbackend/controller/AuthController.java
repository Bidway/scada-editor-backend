package com.example.scadaeditorbackend.controller;

import com.example.scadaeditorbackend.dto.authDto.LoginDto;
import com.example.scadaeditorbackend.dto.authDto.RegisterDto;
import com.example.scadaeditorbackend.dto.authDto.TokenResponse;
import com.example.scadaeditorbackend.model.User;
import com.example.scadaeditorbackend.repository.UserRepository;
import com.example.scadaeditorbackend.security.JwtService;
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

        return ResponseEntity.ok(new TokenResponse(token, "Registered successfully"));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginDto dto) {
        User u = repo.findByLogin(dto.login())
                .orElseThrow(() -> new RuntimeException("User not found"));

        if (!encoder.matches(dto.password(), u.getPassword())) {
            return ResponseEntity.status(401).body("Incorrect password");
        }

        String token = jwtService.generateToken(u.getLogin(), u.getId());

        return ResponseEntity.ok(new TokenResponse(token,"Logged in successfully"));
    }
}




package com.loopai.backend.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/user")
    public ResponseEntity<Map<String, Object>> getUser(@AuthenticationPrincipal OAuth2User principal) {
        if (principal == null) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        Map<String, Object> userInfo = new HashMap<>();
        userInfo.put("name", principal.getAttribute("name"));
        userInfo.put("email", principal.getAttribute("email"));
        userInfo.put("picture", principal.getAttribute("picture"));
        userInfo.put("given_name", principal.getAttribute("given_name"));
        userInfo.put("family_name", principal.getAttribute("family_name"));

        return ResponseEntity.ok(userInfo);
    }

    @GetMapping("/check")
    public ResponseEntity<Map<String, Object>> checkAuth(@AuthenticationPrincipal OAuth2User principal) {
        Map<String, Object> response = new HashMap<>();
        response.put("authenticated", principal != null);

        if (principal != null) {
            response.put("name", principal.getAttribute("name"));
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/login-url")
    public ResponseEntity<Map<String, String>> getLoginUrl() {
        Map<String, String> response = new HashMap<>();
        response.put("url", "/oauth2/authorization/google");
        return ResponseEntity.ok(response);
    }
}

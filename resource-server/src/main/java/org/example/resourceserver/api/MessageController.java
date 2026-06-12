package org.example.resourceserver.api;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/messages")
public class MessageController {

    @GetMapping
    public Map<String, Object> getMessage(Authentication authentication) {
        return Map.of(
                "message", "JWT accepted. Protected resource returned successfully.",
                "subject", authentication.getName(),
                "authorities", authentication.getAuthorities(),
                "issuedAt", Instant.now().toString());
    }
}


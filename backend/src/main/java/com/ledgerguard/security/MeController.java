package com.ledgerguard.security;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {

    public record Me(String username, List<String> roles) {}

    @GetMapping("/api/v1/me")
    public Me me(Authentication authentication) {
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .map(a -> a.replaceFirst("^ROLE_", ""))
                .sorted()
                .toList();
        return new Me(authentication.getName(), roles);
    }
}

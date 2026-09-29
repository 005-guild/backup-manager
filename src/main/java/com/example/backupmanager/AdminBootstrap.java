package com.example.backupmanager;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class AdminBootstrap {
    @Bean
    ApplicationRunner createFirstAdmin(UserMapper users, PasswordEncoder encoder,
            @Value("${app.admin-user:}") String username,
            @Value("${app.admin-password:}") String password) {
        return args -> {
            if (users.countAll() > 0) return;
            if (username.isBlank() || password.length() < 12 || password.startsWith("replace-")) {
                throw new IllegalStateException("Set BOOTSTRAP_ADMIN_USER and a private BOOTSTRAP_ADMIN_PASSWORD of at least 12 characters");
            }
            try {
                users.insert(username, encoder.encode(password), "ADMIN");
            } catch (DuplicateKeyException ignored) {
                // Another instance created the first administrator concurrently.
            }
        };
    }
}

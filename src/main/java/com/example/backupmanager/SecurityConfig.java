package com.example.backupmanager;

import java.io.IOException;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class SecurityConfig {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    UserDetailsService userDetailsService(UserMapper users) {
        return username -> {
            UserAccount account = users.findAccount(username);
            if (account == null) throw new UsernameNotFoundException(username);
            return User.withUsername(account.username())
                .password(account.passwordHash())
                .roles(account.role())
                .disabled(!account.enabled())
                .build();
        };
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, ObjectMapper mapper) throws Exception {
        http.authorizeHttpRequests(auth -> auth
            .antMatchers("/actuator/health", "/api/auth/csrf", "/api/auth/login").permitAll()
            .antMatchers("/api/admin/**").hasRole("ADMIN")
            .antMatchers("/api/**").authenticated()
            .anyRequest().denyAll());
        http.csrf(csrf -> csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse()));
        http.formLogin(form -> form.loginProcessingUrl("/api/auth/login")
            .successHandler((request, response, authentication) -> writeJson(response, mapper, 200, Compat.mapOf("username", authentication.getName(), "admin", authentication.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")))))
            .failureHandler((request, response, error) -> writeJson(response, mapper, 401, Compat.mapOf("error", "用户名或密码错误"))));
        http.logout(logout -> logout.logoutUrl("/api/auth/logout")
            .logoutSuccessHandler((request, response, authentication) -> writeJson(response, mapper, 200, Compat.mapOf("ok", true))));
        http.exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, exception) -> writeJson(response, mapper, 401, Compat.mapOf("error", "请先登录")))
            .accessDeniedHandler((request, response, exception) -> writeJson(response, mapper, 403, Compat.mapOf("error", "没有权限"))));
        return http.build();
    }

    private static void writeJson(javax.servlet.http.HttpServletResponse response, ObjectMapper mapper, int status, Object value) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        mapper.writeValue(response.getWriter(), value);
    }
}

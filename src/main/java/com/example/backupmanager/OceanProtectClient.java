package com.example.backupmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class OceanProtectClient {
    @FunctionalInterface
    interface CopyConsumer {
        void accept(JsonNode copy) throws Exception;
    }

    record FetchSummary(int fetched, int slaCount, int pages) {}
    private record PageSummary(int items, int pages) {}
    private static final class TokenSession {
        private String token;
        private TokenSession(String token) { this.token = token; }
    }

    private final ObjectMapper mapper;
    private final HttpClient http;

    @Value("${app.oceanprotect-base-url:}") private String baseUrl;
    @Value("${app.oceanprotect-username:}") private String username;
    @Value("${app.oceanprotect-password:}") private String password;
    @Value("${app.oceanprotect-auth-type:STORAGE_SYSTEM}") private String authType;
    @Value("${app.oceanprotect-user-type:common}") private String userType;
    @Value("${app.oceanprotect-language:1}") private int language;
    @Value("${app.oceanprotect-page-size:100}") private int pageSize;
    @Value("${app.oceanprotect-sla-page-size:100}") private int slaPageSize;
    @Value("${app.oceanprotect-max-pages:1000}") private int maxPages;

    @Autowired
    OceanProtectClient(ObjectMapper mapper) {
        this(mapper, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    OceanProtectClient(ObjectMapper mapper, HttpClient http) {
        this.mapper = mapper;
        this.http = http;
    }

    boolean configured() {
        return !baseUrl.isBlank() && !username.isBlank() && !password.isBlank();
    }

    FetchSummary fetchCopies(CopyConsumer consumer) throws Exception {
        validateConfiguration();
        TokenSession session = new TokenSession(authenticate());
        Set<String> slaNames = new LinkedHashSet<>();

        PageSummary slaSummary = readPages("/v1/slas", slaPageSize, session, item -> {
            if (OceanProtectParser.kindsFromSla(item, mapper).isEmpty()) return;
            String name = item.path("name").asText("").trim();
            if (!name.isEmpty()) slaNames.add(name);
        });

        int fetched = 0;
        int completedPages = slaSummary.pages();
        for (String slaName : slaNames) {
            String path = "/v1/copies?orders=" + encode("display_timestamp")
                + "&conditions=" + encode("%sla_name%:" + slaName);
            PageSummary copies = readPages(path, pageSize, session, consumer);
            fetched += copies.items();
            completedPages += copies.pages();
        }
        return new FetchSummary(fetched, slaNames.size(), completedPages);
    }

    private PageSummary readPages(String path, int size, TokenSession session,
                                  CopyConsumer consumer) throws Exception {
        int itemCount = 0;
        int completedPages = 0;
        Set<String> signatures = new HashSet<>();
        String separator = path.contains("?") ? "&" : "?";
        for (int page = 0; page < maxPages; page++) {
            URI uri = endpoint(path + separator + "page_no=" + page + "&page_size=" + size);
            HttpResponse<String> response = sendGet(uri, session.token);
            if (response.statusCode() == 401 || response.statusCode() == 403) {
                session.token = authenticate();
                response = sendGet(uri, session.token);
            }
            requireSuccess(response, "查询");
            JsonNode body = mapper.readTree(response.body());
            JsonNode items = body.path("items");
            if (!items.isArray()) throw new IllegalStateException("OceanProtect 分页响应缺少 items 数组");
            if (body.has("page_no") && body.path("page_no").asInt() != page) {
                throw new IllegalStateException("OceanProtect 返回的 page_no 与请求不一致");
            }
            completedPages++;
            if (items.isEmpty()) return new PageSummary(itemCount, completedPages);

            String signature = items.size() + ":" + items.get(0) + ":" + items.get(items.size() - 1);
            if (!signatures.add(signature)) throw new IllegalStateException("OceanProtect 分页返回重复数据，请检查 page_no");
            for (JsonNode item : items) consumer.accept(item);
            itemCount += items.size();

            int pages = body.path("pages").asInt(-1);
            if ((pages >= 0 && page + 1 >= pages) || (pages < 0 && items.size() < size)) {
                return new PageSummary(itemCount, completedPages);
            }
        }
        throw new IllegalStateException("OceanProtect 达到最大分页数，已停止同步");
    }

    private String authenticate() throws Exception {
        String body = mapper.writeValueAsString(Map.of("authRequest", Map.of(
            "userName", username,
            "password", password,
            "authType", authType,
            "userType", userType,
            "language", language
        )));
        HttpRequest request = HttpRequest.newBuilder(endpoint("/v1/auth/token"))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        requireSuccess(response, "认证");
        String token = mapper.readTree(response.body()).path("token").asText("").trim();
        if (token.isEmpty()) throw new IllegalStateException("OceanProtect 认证响应缺少 token");
        return token;
    }

    private HttpResponse<String> sendGet(URI uri, String token) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri)
            .header("X-Auth-Token", token)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(90))
            .GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void validateConfiguration() {
        if (!configured()) throw new IllegalStateException("OceanProtect 地址、用户名或密码尚未配置");
        URI base;
        try { base = URI.create(trimmedBaseUrl()); }
        catch (IllegalArgumentException error) { throw new IllegalStateException("OceanProtect 地址无效", error); }
        if (!"https".equalsIgnoreCase(base.getScheme()) && !"http".equalsIgnoreCase(base.getScheme())) {
            throw new IllegalStateException("OceanProtect 地址必须使用 HTTP 或 HTTPS");
        }
        if (base.getHost() == null || base.getQuery() != null || base.getFragment() != null) {
            throw new IllegalStateException("OceanProtect 地址必须是有效的服务根地址");
        }
        if (authType.isBlank() || userType.isBlank() || language < 1 || language > 2) {
            throw new IllegalStateException("OceanProtect 认证配置无效");
        }
        if (pageSize < 1 || pageSize >= 200 || slaPageSize < 1 || slaPageSize > 1000
                || maxPages < 1 || maxPages > 10_000) {
            throw new IllegalStateException("OceanProtect 分页配置无效");
        }
    }

    private URI endpoint(String path) {
        return URI.create(trimmedBaseUrl() + path);
    }

    private String trimmedBaseUrl() {
        String value = baseUrl.trim();
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static void requireSuccess(HttpResponse<?> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OceanProtect " + operation + "失败，HTTP " + response.statusCode());
        }
    }
}

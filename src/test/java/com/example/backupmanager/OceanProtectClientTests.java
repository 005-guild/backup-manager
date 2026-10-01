package com.example.backupmanager;

import static com.example.backupmanager.Compat.listOf;
import static com.example.backupmanager.Compat.mapOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OceanProtectClientTests {
    private static final String MONTHLY_SLA = "月备 主策略/华东?&+";
    private static final String YEARLY_SLA = "Yearly:Core % 2026";

    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger authCount = new AtomicInteger();
    private final List<AuthCall> authCalls = new CopyOnWriteArrayList<>();
    private final List<HttpCall> slaCalls = new CopyOnWriteArrayList<>();
    private final List<HttpCall> copyCalls = new CopyOnWriteArrayList<>();
    private final List<Throwable> handlerFailures = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String baseUrl;
    private boolean rejectEveryCopy;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void usesDocumentedAuthenticationAndPaginatesOnlyMonthlyAndYearlySlas() throws Exception {
        OceanProtectClient client = client();
        List<JsonNode> deliveredCopies = new ArrayList<>();

        OceanProtectClient.FetchSummary summary = client.fetchCopies(deliveredCopies::add);

        assertEquals(2, summary.fetched());
        assertEquals(2, summary.slaCount());
        assertEquals(5, summary.pages());
        assertEquals(listOf("monthly-copy-0", "monthly-copy-1"),
            deliveredCopies.stream().map(item -> item.path("uuid").asText()).collect(Collectors.toList()));

        assertEquals(2, authCalls.size());
        for (AuthCall call : authCalls) {
            assertEquals("POST", call.method());
            assertEquals("/v1/auth/token", call.path());
            assertEquals("application/json", call.contentType());
            JsonNode root = mapper.readTree(call.body());
            assertEquals(1, root.size());
            JsonNode request = root.path("authRequest");
            assertEquals(5, request.size());
            assertEquals("test-user", request.path("userName").asText());
            assertEquals("test-password", request.path("password").asText());
            assertEquals("STORAGE_SYSTEM", request.path("authType").asText());
            assertEquals("common", request.path("userType").asText());
            assertEquals(1, request.path("language").asInt());
        }

        assertEquals(listOf("0", "1"), values(slaCalls, "page_no"));
        assertEquals(listOf("2", "2"), values(slaCalls, "page_size"));
        assertTrue(slaCalls.stream().allMatch(call -> call.token().equals("token-1")));

        assertEquals(4, copyCalls.size());
        assertEquals(listOf("0", "0", "1", "0"), values(copyCalls, "page_no"));
        assertTrue(copyCalls.stream().allMatch(call -> Integer.parseInt(call.query().get("page_size")) < 200));
        assertTrue(copyCalls.stream().allMatch(call -> call.query().get("page_size").equals("199")));
        assertTrue(copyCalls.stream().allMatch(call -> call.query().get("orders").equals("display_timestamp")));
        assertEquals(listOf("token-1", "token-2", "token-2", "token-2"),
            copyCalls.stream().map(HttpCall::token).collect(Collectors.toList()));

        List<String> conditions = copyCalls.stream()
            .map(call -> call.query().get("conditions"))
            .distinct()
            .collect(Collectors.toList());
        assertEquals(listOf("%sla_name%:" + MONTHLY_SLA, "%sla_name%:" + YEARLY_SLA), conditions);
        assertFalse(conditions.stream().anyMatch(condition -> condition.contains("daily-only")));
        assertTrue(copyCalls.get(0).rawQuery().contains("%25sla_name%25%3A"));
        assertFalse(copyCalls.get(0).rawQuery().contains(MONTHLY_SLA));
        assertTrue(handlerFailures.isEmpty(), () -> "HTTP handler failures: " + handlerFailures);
    }

    @Test
    void monthlyFetchIncludesOtherCurrentSlaKindsToFindHistoricalMonthlyCopies() throws Exception {
        OceanProtectClient client = client();
        List<JsonNode> deliveredCopies = new ArrayList<>();

        OceanProtectClient.FetchSummary summary = client.fetchCopies("monthly", deliveredCopies::add);

        assertEquals(2, summary.fetched());
        assertEquals(2, summary.slaCount());
        assertEquals(listOf("monthly-copy-0", "monthly-copy-1"),
            deliveredCopies.stream().map(item -> item.path("uuid").asText()).collect(Collectors.toList()));
        assertTrue(copyCalls.stream().anyMatch(call ->
            ("%sla_name%:" + MONTHLY_SLA).equals(call.query().get("conditions"))));
        assertTrue(copyCalls.stream().anyMatch(call ->
            ("%sla_name%:" + YEARLY_SLA).equals(call.query().get("conditions"))));
        assertFalse(copyCalls.isEmpty());
        assertTrue(handlerFailures.isEmpty(), () -> "HTTP handler failures: " + handlerFailures);
    }

    @Test
    void retriesAnUnauthorizedCopiesRequestOnlyOnceAfterReauthentication() {
        rejectEveryCopy = true;
        OceanProtectClient client = client();

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> client.fetchCopies(copy -> {}));

        assertTrue(error.getMessage().contains("HTTP 401"));
        assertEquals(2, authCalls.size());
        assertEquals(2, copyCalls.size());
        assertEquals(listOf("token-1", "token-2"),
            copyCalls.stream().map(HttpCall::token).collect(Collectors.toList()));
        assertEquals(copyCalls.get(0).rawQuery(), copyCalls.get(1).rawQuery());
        assertEquals("0", copyCalls.get(0).query().get("page_no"));
        assertTrue(handlerFailures.isEmpty(), () -> "HTTP handler failures: " + handlerFailures);
    }

    private OceanProtectClient client() {
        OceanProtectClient client = new OceanProtectClient(mapper);
        setField(client, "baseUrl", baseUrl + "/");
        setField(client, "username", "test-user");
        setField(client, "password", "test-password");
        setField(client, "authType", "STORAGE_SYSTEM");
        setField(client, "userType", "common");
        setField(client, "language", 1);
        setField(client, "pageSize", 199);
        setField(client, "slaPageSize", 2);
        setField(client, "maxPages", 10);
        return client;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/v1/auth/token")) {
                handleAuthentication(exchange);
            } else if (path.equals("/v1/slas")) {
                handleSlas(exchange);
            } else if (path.equals("/v1/copies")) {
                handleCopies(exchange);
            } else {
                respond(exchange, 404, mapOf("error", "not found"));
            }
        } catch (Throwable error) {
            handlerFailures.add(error);
            respond(exchange, 500, mapOf("error", error.toString()));
        } finally {
            exchange.close();
        }
    }

    private void handleAuthentication(HttpExchange exchange) throws IOException {
        String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
        authCalls.add(new AuthCall(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
            exchange.getRequestHeaders().getFirst("Content-Type"), body));
        int call = authCount.incrementAndGet();
        respond(exchange, 200, mapOf("token", "token-" + call));
    }

    private void handleSlas(HttpExchange exchange) throws IOException {
        HttpCall call = record(exchange);
        slaCalls.add(call);
        int page = Integer.parseInt(call.query().get("page_no"));
        if (page == 0) {
            respond(exchange, 200, mapOf(
                "page_no", 0,
                "pages", 2,
                "items", listOf(
                    sla(MONTHLY_SLA, "month"),
                    sla("daily-only", "day")
                )
            ));
        } else if (page == 1) {
            respond(exchange, 200, mapOf(
                "page_no", 1,
                "pages", 2,
                "items", listOf(sla(YEARLY_SLA, "year"))
            ));
        } else {
            respond(exchange, 500, mapOf("error", "unexpected SLA page " + page));
        }
    }

    private void handleCopies(HttpExchange exchange) throws IOException {
        HttpCall call = record(exchange);
        copyCalls.add(call);
        if (rejectEveryCopy || copyCalls.size() == 1) {
            respond(exchange, 401, mapOf("error", "expired token"));
            return;
        }

        int page = Integer.parseInt(call.query().get("page_no"));
        String condition = call.query().get("conditions");
        if (condition.equals("%sla_name%:" + MONTHLY_SLA) && page <= 1) {
            respond(exchange, 200, mapOf(
                "page_no", page,
                "pages", 2,
                "items", listOf(mapOf("uuid", "monthly-copy-" + page))
            ));
        } else if (condition.equals("%sla_name%:" + YEARLY_SLA) && page == 0) {
            respond(exchange, 200, mapOf("page_no", 0, "pages", 1, "items", listOf()));
        } else {
            respond(exchange, 500, mapOf("error", "unexpected copies request"));
        }
    }

    private HttpCall record(HttpExchange exchange) {
        URI uri = exchange.getRequestURI();
        return new HttpCall(exchange.getRequestMethod(), uri.getPath(), uri.getRawQuery(),
            queryParameters(uri), exchange.getRequestHeaders().getFirst("X-Auth-Token"));
    }

    private Map<String, String> queryParameters(URI uri) {
        Map<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() == null || uri.getRawQuery().isEmpty()) return result;
        Arrays.stream(uri.getRawQuery().split("&"))
            .map(part -> part.split("=", 2))
            .forEach(pair -> result.put(decode(pair[0]), pair.length == 2 ? decode(pair[1]) : ""));
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException error) {
            throw new AssertionError(error);
        }
    }

    private static Map<String, Object> sla(String name, String action) {
        return mapOf(
            "name", name,
            "policy_list", listOf(mapOf("schedule", mapOf("trigger_action", action)))
        );
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    private static List<String> values(List<HttpCall> calls, String key) {
        return calls.stream().map(call -> call.query().get(key)).collect(Collectors.toList());
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError("Cannot set field " + name, error);
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static final class AuthCall {
        private final String method;
        private final String path;
        private final String contentType;
        private final String body;

        private AuthCall(String method, String path, String contentType, String body) {
            this.method = method;
            this.path = path;
            this.contentType = contentType;
            this.body = body;
        }

        private String method() { return method; }
        private String path() { return path; }
        private String contentType() { return contentType; }
        private String body() { return body; }
    }

    private static final class HttpCall {
        private final String method;
        private final String path;
        private final String rawQuery;
        private final Map<String, String> query;
        private final String token;

        private HttpCall(String method, String path, String rawQuery,
                         Map<String, String> query, String token) {
            this.method = method;
            this.path = path;
            this.rawQuery = rawQuery;
            this.query = query;
            this.token = token;
        }

        private String method() { return method; }
        private String path() { return path; }
        private String rawQuery() { return rawQuery; }
        private Map<String, String> query() { return query; }
        private String token() { return token; }
    }
}

package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DbaasAssetSyncServiceTests {
    private final ObjectMapper json = new ObjectMapper();
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final List<Throwable> handlerErrors = new CopyOnWriteArrayList<>();
    private CatalogRepository catalog;
    private DbaasAssetRepository assets;
    private DatabaseSyncLock syncLock;
    private DbaasAssetSyncService service;
    private HttpServer server;
    private Mode mode = Mode.NORMAL;

    @BeforeEach
    void setUp() throws IOException {
        catalog = mock(CatalogRepository.class);
        assets = mock(DbaasAssetRepository.class);
        syncLock = mock(DatabaseSyncLock.class);
        when(catalog.startSync("assets")).thenReturn(42L);
        when(assets.upsert(any(DbaasAsset.class), any(LocalDate.class), eq(42L))).thenReturn(true);
        when(syncLock.acquireAssetLock()).thenReturn(() -> {});
        service = new DbaasAssetSyncService(catalog, assets, json, syncLock);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/apiService/service/DBLIST", this::handle);
        server.start();
        setField("url", "http://127.0.0.1:" + server.getAddress().getPort()
            + "/api/apiService/service/DBLIST");
        setField("token", "test-dbaas-token");
        setField("pageSize", 1);
        setField("maxPages", 3);
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    @Test
    void readsZeroBasedPagesWithValidFilterAndStringTotal() throws Exception {
        Map<String, Object> result = service.syncAssets();

        assertEquals(2, result.get("total"));
        assertEquals(2, result.get("fetched"));
        assertEquals(2, result.get("saved"));
        assertEquals(2, requests.size());
        for (int page = 0; page < requests.size(); page++) {
            Request request = requests.get(page);
            assertEquals("POST", request.method);
            assertEquals("Bearer test-dbaas-token", request.authorization);
            assertTrue(request.contentType.startsWith("application/json"));
            JsonNode body = json.readTree(request.body);
            assertEquals(page, body.path("pageIndex").asInt());
            assertEquals(1, body.path("pageSize").asInt());
            assertEquals("Y", body.path("customParams").path("VALID").asText());
            assertEquals(3, body.size());
        }
        verify(assets, times(2)).upsert(any(DbaasAsset.class), any(LocalDate.class), eq(42L));
        verify(assets).reconcileSnapshot(42L);
        verify(catalog).finishSync(42L, "success", 2, 2, "");
        assertTrue(handlerErrors.isEmpty(), () -> "HTTP handler failures: " + handlerErrors);
    }

    @Test
    void duplicateAssetIdAcrossPagesMarksSyncFailed() {
        mode = Mode.DUPLICATE;

        assertThrows(IllegalStateException.class, () -> service.syncAssets());

        assertEquals(2, requests.size());
        verify(assets).upsert(any(DbaasAsset.class), any(LocalDate.class), eq(42L));
        verify(assets, times(0)).reconcileSnapshot(42L);
        verify(catalog).finishSync(eq(42L), eq("failed"), eq(1), eq(1), anyString());
        assertTrue(handlerErrors.isEmpty(), () -> "HTTP handler failures: " + handlerErrors);
    }

    @Test
    void prematureEmptyPageMarksSyncFailed() {
        mode = Mode.EMPTY;

        assertThrows(IllegalStateException.class, () -> service.syncAssets());

        assertEquals(2, requests.size());
        verify(assets).upsert(any(DbaasAsset.class), any(LocalDate.class), eq(42L));
        verify(assets, times(0)).reconcileSnapshot(42L);
        verify(catalog).finishSync(eq(42L), eq("failed"), eq(1), eq(1), anyString());
        assertTrue(handlerErrors.isEmpty(), () -> "HTTP handler failures: " + handlerErrors);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String body = new String(readAllBytes(exchange.getRequestBody()), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(),
                exchange.getRequestHeaders().getFirst("Authorization"),
                exchange.getRequestHeaders().getFirst("Content-Type"), body));
            int page = json.readTree(body).path("pageIndex").asInt(-1);
            String row = page == 0 || mode == Mode.DUPLICATE
                ? "{\"ID\":\"asset-1\",\"DBNAME\":\"first_db\",\"VALID\":\"Y\"}"
                : "{\"ID\":\"asset-2\",\"DBNAME\":\"second_db\",\"VALID\":\"Y\"}";
            String rows = page == 1 && mode == Mode.EMPTY ? "" : row;
            String response = "{\"code\":0,\"msg\":\"成功\",\"pagination\":{\"total\":\"2\","
                + "\"current\":\"" + page + "\",\"size\":\"1\"},\"data\":[" + rows + "]}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Throwable error) {
            handlerErrors.add(error);
            byte[] bytes = error.toString().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private void setField(String name, Object value) {
        try {
            Field field = DbaasAssetSyncService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (ReflectiveOperationException error) {
            throw new AssertionError(error);
        }
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private enum Mode { NORMAL, DUPLICATE, EMPTY }

    private static final class Request {
        private final String method;
        private final String authorization;
        private final String contentType;
        private final String body;

        private Request(String method, String authorization, String contentType, String body) {
            this.method = method;
            this.authorization = authorization;
            this.contentType = contentType;
            this.body = body;
        }
    }
}

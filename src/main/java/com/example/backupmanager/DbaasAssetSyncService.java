package com.example.backupmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Service
public class DbaasAssetSyncService {
    private static final Logger log = LoggerFactory.getLogger(DbaasAssetSyncService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final CatalogRepository catalog;
    private final DbaasAssetRepository assets;
    private final DatabaseSyncLock syncLock;
    private final ObjectMapper mapper;
    private final RestTemplate http = HttpSupport.createRestTemplate();

    @Value("${app.asset-url:}") private String url;
    @Value("${app.asset-token:}") private String token;
    @Value("${app.asset-page-size:500}") private int pageSize;
    @Value("${app.asset-max-pages:1000}") private int maxPages;
    @Value("${app.scheduler-enabled:false}") private boolean schedulerEnabled;

    DbaasAssetSyncService(CatalogRepository catalog, DbaasAssetRepository assets,
                          ObjectMapper mapper, DatabaseSyncLock syncLock) {
        this.catalog = catalog;
        this.assets = assets;
        this.mapper = mapper;
        this.syncLock = syncLock;
    }

    public synchronized Map<String, Object> syncAssets() {
        if (Compat.isBlank(url) || Compat.isBlank(token)) {
            throw new IllegalStateException("DBLIST 接口地址或令牌尚未配置");
        }
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw new IllegalStateException("DBLIST 接口地址无效");
        }
        if (pageSize < 1 || pageSize > 2000 || maxPages < 1 || maxPages > 10000) {
            throw new IllegalStateException("DBLIST 分页配置无效");
        }
        try (DatabaseSyncLock.Lease ignored = syncLock.acquireAssetLock()) {
            return syncAssetsLocked();
        }
    }

    private Map<String, Object> syncAssetsLocked() {
        long runId = catalog.startSync("assets");
        int fetched = 0;
        int saved = 0;
        int expectedTotal = -1;
        Set<String> seenIds = new HashSet<>();
        LocalDate monitorFrom = LocalDate.now(ZONE);
        try {
            for (int page = 0; page < maxPages; page++) {
                String body = mapper.writeValueAsString(Compat.mapOf(
                    "pageIndex", page, "pageSize", pageSize,
                    "customParams", Compat.mapOf("VALID", "Y")));
                HttpHeaders headers = new HttpHeaders();
                headers.setBearerAuth(token);
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(Compat.listOf(MediaType.APPLICATION_JSON));
                ResponseEntity<String> response = http.exchange(URI.create(url), HttpMethod.POST,
                    new HttpEntity<String>(body, headers), String.class);
                if (response.getStatusCodeValue() < 200 || response.getStatusCodeValue() >= 300) {
                    throw new IllegalStateException("DBLIST 返回 HTTP " + response.getStatusCodeValue());
                }
                JsonNode root = mapper.readTree(response.getBody());
                if (root == null || root.path("code").asInt(-1) != 0 || !root.path("data").isArray()) {
                    throw new IllegalStateException("DBLIST 响应格式或状态码无效");
                }
                int total = total(root.path("pagination").path("total"));
                if (page == 0 && total == 0 && assets.countAll() > 0) {
                    throw new IllegalStateException("DBLIST 返回空资产清单，已保留上次完整同步结果");
                }
                if (expectedTotal < 0) expectedTotal = total;
                if (total != expectedTotal || total(root.path("pagination").path("current")) != page
                    || total(root.path("pagination").path("size")) != pageSize) {
                    throw new IllegalStateException("DBLIST 分页信息与请求不一致");
                }
                JsonNode rows = root.path("data");
                if (rows.size() > pageSize || (long) fetched + rows.size() > expectedTotal) {
                    throw new IllegalStateException("DBLIST 返回的资产数量超过分页范围");
                }
                if (rows.size() != Math.min(pageSize, expectedTotal - fetched)) {
                    throw new IllegalStateException("DBLIST 返回的分页数量不完整");
                }
                if (rows.size() == 0 && fetched < expectedTotal) {
                    throw new IllegalStateException("DBLIST 在读取全部资产前返回空页");
                }
                for (JsonNode row : rows) {
                    DbaasAsset asset = DbaasAssetParser.parse(row);
                    if (!seenIds.add(asset.externalId())) {
                        throw new IllegalStateException("DBLIST 分页重复返回资产 ID " + asset.externalId());
                    }
                    if (!"Y".equalsIgnoreCase(asset.getValid())) {
                        throw new IllegalStateException("DBLIST 返回了非有效资产 ID " + asset.externalId());
                    }
                    LocalDate start = asset.metadata().createdOn();
                    if (start == null || start.isAfter(monitorFrom)) start = monitorFrom;
                    if (assets.upsert(asset, start, runId)) saved++;
                    fetched++;
                }
                if (fetched >= expectedTotal) {
                    int inactive = assets.reconcileSnapshot(runId);
                    catalog.finishSync(runId, "success", fetched, saved, "");
                    return Compat.mapOf("total", expectedTotal, "fetched", fetched,
                        "saved", saved, "inactive", inactive);
                }
            }
            throw new IllegalStateException("DBLIST 达到最大分页数，尚未读取全部资产");
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            catalog.finishSync(runId, "failed", fetched, saved,
                message.substring(0, Math.min(message.length(), 1000)));
            throw new IllegalStateException("数据库资产同步失败，请查看同步记录", error);
        }
    }

    private static int total(JsonNode value) {
        if (value.isMissingNode() || value.isNull()) throw new IllegalStateException("DBLIST 缺少 pagination.total");
        try {
            long parsed = Long.parseLong(value.asText());
            if (parsed < 0 || parsed > Integer.MAX_VALUE) throw new NumberFormatException();
            return (int) parsed;
        } catch (NumberFormatException error) {
            throw new IllegalStateException("DBLIST pagination.total 无效", error);
        }
    }

    @Scheduled(cron = "${app.asset-sync-cron:0 0 0 * * *}", zone = "Asia/Shanghai")
    void scheduledSync() {
        if (!schedulerEnabled) return;
        if (Compat.isBlank(url) || Compat.isBlank(token)) {
            log.warn("DBLIST 资产同步未配置，跳过 0 点任务");
            return;
        }
        try { syncAssets(); }
        catch (Exception error) { log.error("DBLIST 资产同步失败", error); }
    }
}

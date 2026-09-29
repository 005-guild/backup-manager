package com.example.backupmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BackupSyncService {
    private static final Logger log = LoggerFactory.getLogger(BackupSyncService.class);
    private final CatalogRepository catalog;
    private final ObjectMapper mapper;
    private final OceanProtectClient oceanProtect;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Value("${app.daily-url:}") private String dailyUrl;
    @Value("${app.daily-token:}") private String dailyToken;
    @Value("${app.daily-data-path:}") private String dataPath;
    @Value("${app.daily-page-size:20000}") private int pageSize;
    @Value("${app.daily-max-pages:100}") private int maxPages;
    @Value("${app.scheduler-enabled:false}") private boolean schedulerEnabled;

    BackupSyncService(CatalogRepository catalog, ObjectMapper mapper, OceanProtectClient oceanProtect) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.oceanProtect = oceanProtect;
    }

    public synchronized Map<String, Object> syncDaily() {
        if (dailyUrl.isBlank() || dailyToken.isBlank()) throw new IllegalStateException("日备接口地址或令牌尚未配置");
        if (!dailyUrl.startsWith("https://") && !dailyUrl.startsWith("http://")) throw new IllegalStateException("接口地址无效");
        if (pageSize < 1 || pageSize > 20000 || maxPages < 1 || maxPages > 1000) throw new IllegalStateException("分页配置无效");
        long runId = catalog.startSync("daily");
        int fetched = 0, saved = 0;
        Set<String> signatures = new HashSet<>();
        try {
            for (int page = 1; page <= maxPages; page++) {
                String body = mapper.writeValueAsString(Map.of("pageIndex", page, "pageSize", pageSize, "customParams", Map.of()));
                HttpRequest request = HttpRequest.newBuilder(URI.create(dailyUrl))
                    .header("Authorization", "Bearer " + dailyToken)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(90))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("平台返回 HTTP " + response.statusCode());
                JsonNode list = PlatformParser.rows(mapper.readTree(response.body()), dataPath);
                if (list.isEmpty()) {
                    catalog.finishSync(runId, "success", fetched, saved, "");
                    return Map.of("fetched", fetched, "saved", saved);
                }
                String signature = list.size() + ":" + list.get(0).toString() + ":" + list.get(list.size() - 1).toString();
                if (!signatures.add(signature)) throw new IllegalStateException("接口分页返回重复数据，请检查 pageIndex");
                List<PlatformParser.Normalized> normalized = new ArrayList<>();
                list.forEach(item -> normalized.add(PlatformParser.normalize(item)));
                for (PlatformParser.Normalized item : normalized) {
                    long databaseId = catalog.getOrCreateDatabase(item.databaseName(), item.backupDate());
                    boolean exists = catalog.hasBackup("daily", item.externalId());
                    catalog.upsertBackup(databaseId, "daily", item.externalId(), item.backupDate(), item.dateInferred(), item.eventTime(), item.status(), item.rawJson());
                    if (!exists) saved++;
                }
                fetched += list.size();
            }
            throw new IllegalStateException("达到最大分页数，已停止同步，请检查接口分页设置");
        } catch (Exception error) {
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            catalog.finishSync(runId, "failed", fetched, saved, message.substring(0, Math.min(message.length(), 1000)));
            throw new IllegalStateException("同步失败，请查看同步记录", error);
        }
    }

    public synchronized Map<String, Object> syncOceanProtect() {
        if (!oceanProtect.configured()) {
            throw new IllegalStateException("OceanProtect 地址、用户名或密码尚未配置");
        }
        long monthlyRunId = catalog.startSync("monthly");
        long yearlyRunId;
        try {
            yearlyRunId = catalog.startSync("yearly");
        } catch (Exception error) {
            catalog.finishSync(monthlyRunId, "failed", 0, 0, "无法创建年备同步记录");
            throw error;
        }

        int[] matched = {0, 0};
        int[] saved = {0, 0};
        int[] ignored = {0};
        int[] ambiguous = {0};
        try {
            OceanProtectClient.FetchSummary summary = oceanProtect.fetchCopies(copy -> {
                OceanProtectParser.ParseResult result = OceanProtectParser.parse(copy, mapper);
                if (result.ambiguous()) {
                    ambiguous[0]++;
                    return;
                }
                if (!result.parsed()) {
                    ignored[0]++;
                    return;
                }
                int index = result.kind().equals("monthly") ? 0 : 1;
                PlatformParser.Normalized item = result.normalized();
                matched[index]++;
                long databaseId = catalog.getOrCreateDatabase(item.databaseName(), item.backupDate());
                boolean exists = catalog.hasBackup(result.kind(), item.externalId());
                catalog.upsertBackup(databaseId, result.kind(), item.externalId(), item.backupDate(),
                    item.dateInferred(), item.eventTime(), item.status(), item.rawJson());
                if (!exists) saved[index]++;
            });
            catalog.finishSync(monthlyRunId, "success", matched[0], saved[0], syncNote(ignored[0], ambiguous[0]));
            catalog.finishSync(yearlyRunId, "success", matched[1], saved[1], syncNote(ignored[0], ambiguous[0]));
            return Map.of(
                "fetched", summary.fetched(),
                "slaCount", summary.slaCount(),
                "pages", summary.pages(),
                "monthlyFetched", matched[0],
                "monthlySaved", saved[0],
                "yearlyFetched", matched[1],
                "yearlySaved", saved[1],
                "ignored", ignored[0],
                "ambiguous", ambiguous[0]
            );
        } catch (Exception error) {
            String message = safeMessage(error);
            catalog.finishSync(monthlyRunId, "failed", matched[0], saved[0], message);
            catalog.finishSync(yearlyRunId, "failed", matched[1], saved[1], message);
            throw new IllegalStateException("月备/年备同步失败，请查看同步记录", error);
        }
    }

    boolean oceanProtectConfigured() {
        return oceanProtect.configured();
    }

    @Scheduled(cron = "${app.sync-cron:0 0 6 * * *}", zone = "Asia/Shanghai")
    public void scheduledSync() {
        if (!schedulerEnabled) return;
        if (!dailyUrl.isBlank() && !dailyToken.isBlank()) {
            try { syncDaily(); }
            catch (Exception error) { log.error("Daily backup sync failed: {}", error.getMessage()); }
        }
        if (oceanProtect.configured()) {
            try { syncOceanProtect(); }
            catch (Exception error) { log.error("OceanProtect backup sync failed: {}", error.getMessage()); }
        }
    }

    private static String syncNote(int ignored, int ambiguous) {
        if (ignored == 0 && ambiguous == 0) return "";
        return "跳过 " + ignored + " 条，策略类型不明确 " + ambiguous + " 条";
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(message.length(), 1000));
    }
}

package com.example.backupmanager;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
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
import static com.example.backupmanager.Compat.isBlank;
import static com.example.backupmanager.Compat.listOf;
import static com.example.backupmanager.Compat.mapOf;

@Service
public class BackupSyncService {
    private static final Logger log = LoggerFactory.getLogger(BackupSyncService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final CatalogRepository catalog;
    private final ObjectMapper mapper;
    private final OceanProtectClient oceanProtect;
    private final BackupRetentionService retention;
    private final ReentrantLock monthlyLock = new ReentrantLock();
    private final ReentrantLock yearlyLock = new ReentrantLock();
    private final RestTemplate http = HttpSupport.createRestTemplate();
    @Value("${app.daily-url:}") private String dailyUrl;
    @Value("${app.daily-token:}") private String dailyToken;
    @Value("${app.daily-data-path:}") private String dataPath;
    @Value("${app.daily-page-size:20000}") private int pageSize;
    @Value("${app.daily-max-pages:100}") private int maxPages;
    @Value("${app.scheduler-enabled:false}") private boolean schedulerEnabled;

    BackupSyncService(CatalogRepository catalog, ObjectMapper mapper, OceanProtectClient oceanProtect,
                      BackupRetentionService retention) {
        this.catalog = catalog;
        this.mapper = mapper;
        this.oceanProtect = oceanProtect;
        this.retention = retention;
    }

    public synchronized Map<String, Object> syncDaily() {
        if (isBlank(dailyUrl) || isBlank(dailyToken)) throw new IllegalStateException("日备接口地址或令牌尚未配置");
        if (!dailyUrl.startsWith("https://") && !dailyUrl.startsWith("http://")) throw new IllegalStateException("接口地址无效");
        if (pageSize < 1 || pageSize > 20000 || maxPages < 1 || maxPages > 1000) throw new IllegalStateException("分页配置无效");
        BackupRetentionService.RetentionPolicy retentionPolicy = retention.currentPolicy();
        LocalDate today = LocalDate.now(ZONE);
        long runId = catalog.startSync("daily");
        int fetched = 0, saved = 0;
        int expired = 0;
        Set<String> signatures = new HashSet<>();
        try {
            for (int page = 1; page <= maxPages; page++) {
                String body = mapper.writeValueAsString(mapOf("pageIndex", page, "pageSize", pageSize, "customParams", mapOf()));
                HttpHeaders headers = new HttpHeaders();
                headers.set("Authorization", "Bearer " + dailyToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setAccept(listOf(MediaType.APPLICATION_JSON));
                ResponseEntity<String> response = http.exchange(URI.create(dailyUrl), HttpMethod.POST,
                    new HttpEntity<String>(body, headers), String.class);
                int status = response.getStatusCodeValue();
                if (status < 200 || status >= 300) throw new IllegalStateException("平台返回 HTTP " + status);
                JsonNode list = PlatformParser.rows(mapper.readTree(response.getBody()), dataPath);
                if (list.isEmpty()) {
                    catalog.finishSync(runId, "success", fetched, saved, expiredNote(expired));
                    return mapOf("fetched", fetched, "saved", saved, "expired", expired);
                }
                String signature = list.size() + ":" + list.get(0).toString() + ":" + list.get(list.size() - 1).toString();
                if (!signatures.add(signature)) throw new IllegalStateException("接口分页返回重复数据，请检查 pageIndex");
                List<PlatformParser.Normalized> normalized = new ArrayList<>();
                list.forEach(item -> normalized.add(PlatformParser.normalize(item)));
                for (PlatformParser.Normalized item : normalized) {
                    if (!retentionPolicy.shouldKeep("daily", item.backupDate(), today)) {
                        expired++;
                        continue;
                    }
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

    public Map<String, Object> syncMonthly() {
        return syncOceanProtectKind("monthly", monthlyLock);
    }

    public Map<String, Object> syncYearly() {
        return syncOceanProtectKind("yearly", yearlyLock);
    }

    private Map<String, Object> syncOceanProtectKind(String kind, ReentrantLock lock) {
        if (!lock.tryLock()) throw new IllegalStateException(kind + " 备份同步正在运行");
        try { return syncOceanProtectKindLocked(kind); }
        finally { lock.unlock(); }
    }

    private Map<String, Object> syncOceanProtectKindLocked(String kind) {
        if (!oceanProtect.configured()) {
            throw new IllegalStateException("OceanProtect 地址、用户名或密码尚未配置");
        }
        BackupRetentionService.RetentionPolicy retentionPolicy = retention.currentPolicy();
        LocalDate today = LocalDate.now(ZONE);
        long runId = catalog.startSync(kind);
        int[] matched = {0};
        int[] saved = {0};
        int[] ignored = {0};
        int[] ambiguous = {0};
        int[] expired = {0};
        try {
            OceanProtectClient.FetchSummary summary = oceanProtect.fetchCopies(kind, copy -> {
                OceanProtectParser.ParseResult result = OceanProtectParser.parse(copy, mapper);
                if (result.ambiguous()) { ambiguous[0]++; return; }
                if (!result.parsed() || !kind.equals(result.kind())) { ignored[0]++; return; }
                PlatformParser.Normalized item = result.normalized();
                matched[0]++;
                if (!retentionPolicy.shouldKeep(kind, item.backupDate(), today)) {
                    expired[0]++;
                    return;
                }
                long databaseId = catalog.getOrCreateDatabase(item.databaseName(), item.backupDate());
                boolean exists = catalog.hasBackup(kind, item.externalId());
                catalog.upsertBackup(databaseId, kind, item.externalId(), item.backupDate(),
                    item.dateInferred(), item.eventTime(), item.status(), item.rawJson());
                if (!exists) saved[0]++;
            });
            catalog.finishSync(runId, "success", matched[0], saved[0],
                syncNote(ignored[0], ambiguous[0], expired[0]));
            return mapOf("fetched", summary.fetched(), "slaCount", summary.slaCount(),
                "pages", summary.pages(), "matched", matched[0], "saved", saved[0],
                "ignored", ignored[0], "ambiguous", ambiguous[0], "expired", expired[0]);
        } catch (Exception error) {
            catalog.finishSync(runId, "failed", matched[0], saved[0], safeMessage(error));
            throw new IllegalStateException(kind.equals("monthly") ? "月备同步失败，请查看同步记录" :
                "年备同步失败，请查看同步记录", error);
        }
    }

    public Map<String, Object> syncOceanProtect() {
        if (!monthlyLock.tryLock()) throw new IllegalStateException("月备同步正在运行");
        if (!yearlyLock.tryLock()) {
            monthlyLock.unlock();
            throw new IllegalStateException("年备同步正在运行");
        }
        try { return syncOceanProtectBoth(); }
        finally {
            yearlyLock.unlock();
            monthlyLock.unlock();
        }
    }

    private Map<String, Object> syncOceanProtectBoth() {
        if (!oceanProtect.configured()) {
            throw new IllegalStateException("OceanProtect 地址、用户名或密码尚未配置");
        }
        BackupRetentionService.RetentionPolicy retentionPolicy = retention.currentPolicy();
        LocalDate today = LocalDate.now(ZONE);
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
        int[] expired = {0, 0};
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
                if (!retentionPolicy.shouldKeep(result.kind(), item.backupDate(), today)) {
                    expired[index]++;
                    return;
                }
                long databaseId = catalog.getOrCreateDatabase(item.databaseName(), item.backupDate());
                boolean exists = catalog.hasBackup(result.kind(), item.externalId());
                catalog.upsertBackup(databaseId, result.kind(), item.externalId(), item.backupDate(),
                    item.dateInferred(), item.eventTime(), item.status(), item.rawJson());
                if (!exists) saved[index]++;
            });
            catalog.finishSync(monthlyRunId, "success", matched[0], saved[0],
                syncNote(ignored[0], ambiguous[0], expired[0]));
            catalog.finishSync(yearlyRunId, "success", matched[1], saved[1],
                syncNote(ignored[0], ambiguous[0], expired[1]));
            return mapOf(
                "fetched", summary.fetched(),
                "slaCount", summary.slaCount(),
                "pages", summary.pages(),
                "monthlyFetched", matched[0],
                "monthlySaved", saved[0],
                "yearlyFetched", matched[1],
                "yearlySaved", saved[1],
                "monthlyExpired", expired[0],
                "yearlyExpired", expired[1],
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

    @Scheduled(cron = "${app.daily-sync-cron:0 0 0 * * *}", zone = "Asia/Shanghai")
    public void scheduledDailySync() {
        if (!schedulerEnabled) return;
        if (!isBlank(dailyUrl) && !isBlank(dailyToken)) {
            try { syncDaily(); }
            catch (Exception error) { log.error("Daily backup sync failed: {}", error.getMessage()); }
        }
    }

    @Scheduled(cron = "${app.monthly-sync-cron:0 30 0 * * *}", zone = "Asia/Shanghai")
    public void scheduledMonthlySync() {
        if (!schedulerEnabled) return;
        if (oceanProtect.configured()) {
            try { syncMonthly(); }
            catch (Exception error) { log.error("Monthly backup sync failed: {}", error.getMessage()); }
        }
    }

    @Scheduled(cron = "${app.yearly-sync-cron:0 0 1 * * *}", zone = "Asia/Shanghai")
    public void scheduledYearlySync() {
        if (!schedulerEnabled) return;
        if (oceanProtect.configured()) {
            try { syncYearly(); }
            catch (Exception error) { log.error("Yearly backup sync failed: {}", error.getMessage()); }
        }
    }

    private static String syncNote(int ignored, int ambiguous) {
        if (ignored == 0 && ambiguous == 0) return "";
        return "跳过 " + ignored + " 条，策略类型不明确 " + ambiguous + " 条";
    }

    private static String syncNote(int ignored, int ambiguous, int expired) {
        String note = syncNote(ignored, ambiguous);
        if (expired == 0) return note;
        return note.isEmpty() ? expiredNote(expired) : note + "；" + expiredNote(expired);
    }

    private static String expiredNote(int expired) {
        return expired == 0 ? "" : "跳过超出保留期 " + expired + " 条";
    }

    private static String safeMessage(Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        return message.substring(0, Math.min(message.length(), 1000));
    }
}

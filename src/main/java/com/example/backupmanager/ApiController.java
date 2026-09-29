package com.example.backupmanager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final CatalogRepository catalog;
    private final RuleService rules;
    private final CoverageService coverage;
    private final BackupSyncService sync;
    private final PasswordEncoder encoder;
    ApiController(CatalogRepository catalog, RuleService rules, CoverageService coverage, BackupSyncService sync, PasswordEncoder encoder) {
        this.catalog = catalog; this.rules = rules; this.coverage = coverage; this.sync = sync; this.encoder = encoder;
    }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private DatabaseRow requireDatabase(long id) {
        DatabaseRow found = catalog.database(id);
        if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据库不存在");
        return found;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard() {
        LocalDate today = today();
        List<DatabaseRow> databases = catalog.databases("").stream().filter(DatabaseRow::active).toList();
        List<Map<String, Object>> rows = new ArrayList<>();
        int missing = 0, pending = 0, unknown = 0;
        for (DatabaseRow database : databases) {
            List<CheckRow> checks = rules.checks(database, today.minusDays(6), today, today);
            long dbMissing = checks.stream().filter(c -> c.state().equals("missing")).count();
            long dbPending = checks.stream().filter(c -> c.state().equals("pending")).count();
            long dbUnknown = checks.stream().filter(c -> c.state().equals("unknown")).count();
            missing += dbMissing; pending += dbPending; unknown += dbUnknown;
            List<BackupRow> latest = catalog.backups(database.id(), null, null, "", "", 1, 0);
            rows.add(Map.of("database", database, "latest", latest.isEmpty() ? Map.of() : latest.get(0),
                "missing", dbMissing, "pending", dbPending, "unknown", dbUnknown));
        }
        rows.sort((a,b) -> Long.compare((Long)b.get("missing"), (Long)a.get("missing")));
        List<SyncRow> runs = catalog.syncRuns(1);
        return Map.of("databaseCount", databases.size(), "backupCount", catalog.backupCount(),
            "missing", missing, "pending", pending, "unknown", unknown,
            "databases", rows, "lastSync", runs.isEmpty() ? Map.of() : runs.get(0));
    }

    @GetMapping("/databases") List<DatabaseRow> databases(@RequestParam(defaultValue = "") String q) { return catalog.databases(q.trim()); }
    @GetMapping("/databases/{id}") DatabaseRow database(@PathVariable long id) { return requireDatabase(id); }
    @GetMapping("/databases/{id}/coverage") List<CoverageGroup> coverage(@PathVariable long id) {
        return coverage.coverage(requireDatabase(id));
    }
    record AddDatabase(String name, LocalDate monitorFrom) {}
    @PostMapping("/admin/databases")
    @ResponseStatus(HttpStatus.CREATED)
    DatabaseRow addDatabase(@RequestBody AddDatabase input) {
        if (input.name() == null || input.name().isBlank() || input.name().length() > 255) throw new IllegalArgumentException("请输入有效的数据库名称");
        return catalog.addDatabase(input.name().trim(), input.monitorFrom() == null ? today() : input.monitorFrom());
    }
    @PutMapping("/admin/databases/{id}/metadata")
    DatabaseRow updateDatabaseMetadata(@PathVariable long id, @RequestBody DatabaseMetadata input) {
        requireDatabase(id);
        if (input == null) throw new IllegalArgumentException("数据库信息不能为空");
        String[] values = {input.dbid(), input.tag(), input.lifecycleStatus(), input.securityTier(),
            input.framework(), input.dbVersion(), input.subsystem(), input.developer(), input.dba(), input.serviceUnit()};
        int[] maxLengths = {100, 100, 40, 40, 80, 80, 100, 100, 100, 100};
        for (int index = 0; index < values.length; index++) {
            if (values[index] != null && values[index].length() > maxLengths[index])
                throw new IllegalArgumentException("数据库信息字段过长");
        }
        return catalog.updateMetadata(id, input);
    }

    @GetMapping("/backups")
    Map<String, Object> backups(@RequestParam(required = false) Long databaseId,
                                @RequestParam(required = false) LocalDate date,
                                @RequestParam(required = false) LocalDate from,
                                @RequestParam(required = false) LocalDate to,
                                @RequestParam(defaultValue = "") String kind,
                                @RequestParam(defaultValue = "") String status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 200) throw new IllegalArgumentException("分页参数无效");
        if (!kind.isEmpty() && !List.of("daily","monthly","yearly").contains(kind)) throw new IllegalArgumentException("备份类型无效");
        if (date != null) { from = date; to = date; }
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("日期范围无效");
        List<BackupRow> found = catalog.backups(databaseId, from, to, kind, status, size + 1, page * size);
        boolean more = found.size() > size;
        return Map.of("items", more ? found.subList(0, size) : found, "hasMore", more, "page", page);
    }

    @GetMapping("/calendar")
    Map<String, Object> calendar(@RequestParam int year, @RequestParam int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) throw new IllegalArgumentException("月份无效");
        YearMonth period = YearMonth.of(year, month);
        List<Map<String, Object>> days = catalog.calendar(period.atDay(1), period.atEndOfMonth()).stream()
            .map(row -> Map.<String, Object>of("day", row.get("backup_date").toString(), "count", row.get("backup_count"))).toList();
        return Map.of("year", year, "month", month, "days", days);
    }
    @GetMapping("/rules") List<RuleRow> rules() { return catalog.rules(); }
    record UpdateRule(boolean enabled, int graceDays, Integer retentionDays) {}
    @PutMapping("/admin/rules/{id}")
    RuleRow updateRule(@PathVariable long id, @RequestBody UpdateRule input) {
        RuleRow rule = catalog.rules().stream().filter(r -> r.id() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在"));
        if (input.graceDays() < 0 || input.graceDays() > 30 || (input.retentionDays() != null && (input.retentionDays() < 1 || input.retentionDays() > 36500))) throw new IllegalArgumentException("规则数值无效");
        catalog.updateRule(id, input.enabled(), input.graceDays(), input.retentionDays());
        return catalog.rules().stream().filter(r -> r.id() == id).findFirst().orElseThrow();
    }

    @GetMapping("/checks")
    List<CheckRow> checks(@RequestParam long databaseId,
                          @RequestParam LocalDate from, @RequestParam LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366) throw new IllegalArgumentException("检查范围必须在 366 天内");
        return rules.checks(requireDatabase(databaseId), from, to, today());
    }
    @GetMapping("/sync-runs") List<SyncRow> syncRuns() { return catalog.syncRuns(50); }
    @PostMapping("/admin/sync/daily") Map<String, Object> syncDaily() { return sync.syncDaily(); }
    @PostMapping("/admin/sync/oceanprotect") Map<String, Object> syncOceanProtect() { return sync.syncOceanProtect(); }
    @GetMapping("/admin/users") List<Map<String, Object>> users() { return catalog.users(); }
    record AddUser(String username, String password, String role) {}
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> addUser(@RequestBody AddUser input) {
        if (input.username() == null || !input.username().matches("[A-Za-z0-9_.-]{3,100}")) throw new IllegalArgumentException("用户名需为 3-100 位字母、数字、点、横线或下划线");
        if (input.password() == null || input.password().length() < 12) throw new IllegalArgumentException("密码至少 12 位");
        if (input.role() == null || !List.of("ADMIN", "VIEWER").contains(input.role())) throw new IllegalArgumentException("角色无效");
        catalog.addUser(input.username(), encoder.encode(input.password()), input.role());
        return Map.of("username", input.username(), "role", input.role());
    }
}

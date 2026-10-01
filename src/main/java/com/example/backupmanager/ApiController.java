package com.example.backupmanager;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
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
    private final DashboardService dashboard;
    private final BackupSyncService sync;
    private final DbaasAssetSyncService assetSync;
    private final PasswordEncoder encoder;
    ApiController(CatalogRepository catalog, RuleService rules, CoverageService coverage,
                  DashboardService dashboard, BackupSyncService sync,
                  DbaasAssetSyncService assetSync, PasswordEncoder encoder) {
        this.catalog = catalog; this.rules = rules; this.coverage = coverage; this.sync = sync;
        this.dashboard = dashboard; this.assetSync = assetSync; this.encoder = encoder;
    }
    private static LocalDate today() { return LocalDate.now(ZoneId.of("Asia/Shanghai")); }
    private DatabaseRow requireDatabase(long id) {
        DatabaseRow found = catalog.database(id);
        if (found == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据库不存在");
        return found;
    }

    @GetMapping("/dashboard")
    Map<String, Object> dashboard() {
        return dashboard.summary(today());
    }

    @GetMapping("/databases") List<DatabaseRow> databases(@RequestParam(defaultValue = "") String q) { return catalog.databases(q.trim()); }
    @GetMapping("/databases/page")
    Map<String, Object> databasePage(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "all") String backupState,
                                      @RequestParam(defaultValue = "") String monitorState,
                                      @RequestParam(defaultValue = "") String framework,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 100)
            throw new IllegalArgumentException("分页参数无效");
        if (q.length() > 255 || framework.length() > 80)
            throw new IllegalArgumentException("筛选条件过长");
        if (!Compat.listOf("all", "with", "empty").contains(backupState)
            || !Compat.listOf("", "active", "paused").contains(monitorState))
            throw new IllegalArgumentException("筛选条件无效");
        String search = q.trim();
        long all = catalog.databaseCount(search, "all", monitorState, framework);
        long with = catalog.databaseCount(search, "with", monitorState, framework);
        long total = "with".equals(backupState) ? with : "empty".equals(backupState) ? all - with : all;
        List<DatabaseRow> items = catalog.databasePage(search, backupState, monitorState, framework, page, size);
        return Compat.mapOf("items", items, "total", total, "page", page, "size", size,
            "hasMore", (long) page * size + items.size() < total,
            "counts", Compat.mapOf("all", all, "with", with, "empty", all - with));
    }
    @GetMapping("/databases/frameworks") List<String> databaseFrameworks() {
        return catalog.databaseFrameworks();
    }
    @GetMapping("/databases/{id}") DatabaseRow database(@PathVariable long id) { return requireDatabase(id); }
    @GetMapping("/databases/{id}/coverage") List<CoverageGroup> coverage(@PathVariable long id) {
        return coverage.coverage(requireDatabase(id));
    }
    public static class AddDatabase {
        private String name;
        private LocalDate monitorFrom;

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public LocalDate getMonitorFrom() { return monitorFrom; }
        public void setMonitorFrom(LocalDate monitorFrom) { this.monitorFrom = monitorFrom; }
    }
    @PostMapping("/admin/databases")
    @ResponseStatus(HttpStatus.CREATED)
    DatabaseRow addDatabase(@RequestBody AddDatabase input) {
        if (Compat.isBlank(input.getName()) || input.getName().length() > 255) throw new IllegalArgumentException("请输入有效的数据库名称");
        return catalog.addDatabase(input.getName().trim(), input.getMonitorFrom() == null ? today() : input.getMonitorFrom());
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
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                @RequestParam(defaultValue = "") String kind,
                                @RequestParam(defaultValue = "") String status,
                                @RequestParam(defaultValue = "0") int page,
                                @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || page > 100000 || size < 1 || size > 200) throw new IllegalArgumentException("分页参数无效");
        if (!kind.isEmpty() && !Compat.listOf("daily","monthly","yearly").contains(kind)) throw new IllegalArgumentException("备份类型无效");
        if (date != null) { from = date; to = date; }
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("日期范围无效");
        List<BackupRow> found = catalog.backups(databaseId, from, to, kind, status, size + 1, page * size);
        boolean more = found.size() > size;
        return Compat.mapOf("items", more ? found.subList(0, size) : found, "hasMore", more, "page", page);
    }

    @GetMapping("/calendar")
    Map<String, Object> calendar(@RequestParam int year, @RequestParam int month) {
        if (year < 2000 || year > 2100 || month < 1 || month > 12) throw new IllegalArgumentException("月份无效");
        YearMonth period = YearMonth.of(year, month);
        List<Map<String, Object>> days = catalog.calendar(period.atDay(1), period.atEndOfMonth()).stream()
            .map(row -> Compat.<String, Object>mapOf("day", row.get("backup_date").toString(), "count", row.get("backup_count")))
            .collect(Collectors.toList());
        return Compat.mapOf("year", year, "month", month, "days", days);
    }
    @GetMapping("/rules") List<RuleRow> rules() { return catalog.rules(); }
    public static class UpdateRule {
        private boolean enabled;
        private int graceDays;
        private Integer retentionDays;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getGraceDays() { return graceDays; }
        public void setGraceDays(int graceDays) { this.graceDays = graceDays; }
        public Integer getRetentionDays() { return retentionDays; }
        public void setRetentionDays(Integer retentionDays) { this.retentionDays = retentionDays; }
    }
    @PutMapping("/admin/rules/{id}")
    RuleRow updateRule(@PathVariable long id, @RequestBody UpdateRule input) {
        RuleRow rule = catalog.rules().stream().filter(r -> r.id() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在"));
        if (input.getGraceDays() < 0 || input.getGraceDays() > 30 || (input.getRetentionDays() != null && (input.getRetentionDays() < 1 || input.getRetentionDays() > 36500))) throw new IllegalArgumentException("规则数值无效");
        if (!"yearly".equals(rule.kind()) && input.getRetentionDays() == null)
            throw new IllegalArgumentException("日备和月备必须设置保留天数");
        catalog.updateRule(id, input.isEnabled(), input.getGraceDays(), input.getRetentionDays());
        return catalog.rules().stream().filter(r -> r.id() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "规则不存在"));
    }

    @GetMapping("/checks")
    List<CheckRow> checks(@RequestParam long databaseId,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) > 366) throw new IllegalArgumentException("检查范围必须在 366 天内");
        return rules.checks(requireDatabase(databaseId), from, to, today());
    }
    @GetMapping("/sync-runs") List<SyncRow> syncRuns() { return catalog.syncRuns(50); }
    @PostMapping("/admin/sync/daily") Map<String, Object> syncDaily() { return sync.syncDaily(); }
    @PostMapping("/admin/sync/monthly") Map<String, Object> syncMonthly() { return sync.syncMonthly(); }
    @PostMapping("/admin/sync/yearly") Map<String, Object> syncYearly() { return sync.syncYearly(); }
    @PostMapping("/admin/sync/oceanprotect") Map<String, Object> syncOceanProtect() { return sync.syncOceanProtect(); }
    @PostMapping("/admin/sync/assets") Map<String, Object> syncAssets() { return assetSync.syncAssets(); }
    @GetMapping("/admin/users") List<Map<String, Object>> users() { return catalog.users(); }
    public static class AddUser {
        private String username;
        private String password;
        private String role;

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getRole() { return role; }
        public void setRole(String role) { this.role = role; }
    }
    @PostMapping("/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, String> addUser(@RequestBody AddUser input) {
        if (input.getUsername() == null || !input.getUsername().matches("[A-Za-z0-9_.-]{3,100}")) throw new IllegalArgumentException("用户名需为 3-100 位字母、数字、点、横线或下划线");
        if (input.getPassword() == null || input.getPassword().length() < 12) throw new IllegalArgumentException("密码至少 12 位");
        if (input.getRole() == null || !Compat.listOf("ADMIN", "VIEWER").contains(input.getRole())) throw new IllegalArgumentException("角色无效");
        catalog.addUser(input.getUsername(), encoder.encode(input.getPassword()), input.getRole());
        return Compat.mapOf("username", input.getUsername(), "role", input.getRole());
    }
}

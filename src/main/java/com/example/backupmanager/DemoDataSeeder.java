package com.example.backupmanager;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
@ConditionalOnProperty(name = "app.demo-seed", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final CatalogRepository catalog;
    private final ObjectMapper mapper;

    DemoDataSeeder(CatalogRepository catalog, ObjectMapper mapper) {
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate firstDay = LocalDate.of(today.getYear() - 4, 1, 1);
        int records = 0;

        for (int number = 1; number <= 64; number++) {
            String name = databaseName(number);
            long databaseId = catalog.addDatabase(name, firstDay).id();
            setDemoMetadata(databaseId, name, firstDay, number);
            for (int age = 59; age >= 0; age--) {
                LocalDate date = today.minusDays(age);
                int pattern = (number * 7 + age * 11) % 23;
                if (number != 1 && pattern <= 2) continue; // No backup record.

                String status = number == 17 || pattern <= 4 ? "cancel"
                    : age <= 1 && pattern <= 6 ? "DISPATCHING" : "successed";
                if (number == 1) status = "successed";
                boolean inferred = status.equals("successed") && number != 1 && pattern == 9;
                save(databaseId, name, "daily", date, status, inferred);
                records++;
            }

            if (number <= 16) {
                for (int monthsAgo = 0; monthsAgo < 12; monthsAgo++) {
                    LocalDate date = YearMonth.from(today).minusMonths(monthsAgo).atDay(1);
                    save(databaseId, name, "monthly", date, monthsAgo % 7 == 0 ? "cancel" : "successed", false);
                    records++;
                }
                LocalDate yearEnd = LocalDate.of(today.getYear() - 1, 12, 31);
                save(databaseId, name, "yearly", yearEnd, number % 5 == 0 ? "cancel" : "successed", false);
                records++;
            }
        }

        for (int number = 1; number <= 8; number++) {
            String name = "DEMO_empty_" + String.format("%03d", number);
            long databaseId = catalog.addDatabase(name, firstDay).id();
            setDemoMetadata(databaseId, name, firstDay, 64 + number);
        }
        log.info("Demo data ready: 72 databases and {} backup records (idempotent)", records);
    }

    private String databaseName(int number) {
        String group = number <= 16 ? "pay" : number <= 32 ? "trade" : number <= 48 ? "customer" : "report";
        return "DEMO_" + group + "_" + String.format("%03d", number);
    }

    private void setDemoMetadata(long databaseId, String name, LocalDate firstDay, int number) {
        catalog.setDemoMonitoring(databaseId, name, firstDay);
        catalog.updateMetadata(databaseId, new DatabaseMetadata(
            "DBID_A_LB_" + String.format("%04d", number), number % 11 == 0 ? "防冲击" : null,
            "已上线", number % 5 == 0 ? "A" : number % 3 == 0 ? "B" : "C",
            number % 4 == 0 ? "ZA21" : "ZA24", "8.0.24", "LB" + String.format("%02d", number % 40 + 1),
            "演示开发/0" + String.format("%04d", number), "演示DBA/8" + String.format("%04d", number),
            "LB" + String.format("%02d", number % 40 + 1) + ".01@demo", firstDay.plusDays(number * 3L)));
    }

    private void save(long databaseId, String name, String kind, LocalDate date, String status, boolean inferred) throws Exception {
        LocalDateTime updated = date.atTime(2 + (int) (databaseId % 5), 10);
        String externalId = "DEMO:" + kind + ":" + name + ":" + date;
        Map<String, Object> rawFields = new LinkedHashMap<>(Compat.mapOf(
            "demo", true, "dbname", name, "status", status, "time", updated.toString()));
        if (!inferred) rawFields.put("backupDate", date.toString());
        String raw = mapper.writeValueAsString(rawFields);
        catalog.upsertBackup(databaseId, kind, externalId, date, inferred, updated.atZone(ZONE).toInstant(), status, raw);
    }
}

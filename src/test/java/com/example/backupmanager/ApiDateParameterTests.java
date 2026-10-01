package com.example.backupmanager;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:api_date_parameter_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-user=test_admin",
    "app.admin-password=test-password-123",
    "app.demo-seed=false",
    "app.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@WithMockUser(roles = "VIEWER")
class ApiDateParameterTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogRepository catalog;

    @Test
    void backupsAcceptIsoDateAndFilterToThatDay() throws Exception {
        DatabaseRow database = catalog.addDatabase("api_date_filter_test", LocalDate.of(2026, 9, 1));
        catalog.upsertBackup(database.id(), "daily", "API-DATE-2026-10-01",
            LocalDate.of(2026, 10, 1), false, Instant.parse("2026-10-01T00:00:00Z"), "successed", "{}");
        catalog.upsertBackup(database.id(), "daily", "API-DATE-2026-10-02",
            LocalDate.of(2026, 10, 2), false, Instant.parse("2026-10-02T00:00:00Z"), "successed", "{}");

        mvc.perform(get("/api/backups")
                .param("databaseId", Long.toString(database.id()))
                .param("date", "2026-10-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].externalId").value("API-DATE-2026-10-01"));

        mvc.perform(get("/api/backups")
                .param("databaseId", Long.toString(database.id()))
                .param("from", "2026-10-01")
                .param("to", "2026-10-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].externalId").value("API-DATE-2026-10-01"));
    }

    @Test
    void checksAcceptIsoDateRange() throws Exception {
        DatabaseRow database = catalog.addDatabase("api_checks_date_test", LocalDate.of(2026, 9, 1));

        mvc.perform(get("/api/checks")
                .param("databaseId", Long.toString(database.id()))
                .param("from", "2026-10-01")
                .param("to", "2026-10-01"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].due").value("2026-10-01"));
    }
}

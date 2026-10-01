package com.example.backupmanager;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:demo_visibility_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-user=test_admin",
    "app.admin-password=test-password-123",
    "app.demo-seed=false",
    "app.scheduler-enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("local")
@WithMockUser(roles = "ADMIN")
class DemoVisibilityTests {
    @Autowired MockMvc mvc;
    @Autowired CatalogRepository catalog;
    @Autowired JdbcTemplate jdbc;

    @Test
    @WithMockUser(roles = "VIEWER")
    void onlyAdministratorsCanChangeVisibility() throws Exception {
        mvc.perform(get("/api/admin/demo-data")).andExpect(status().isForbidden());
        mvc.perform(put("/api/admin/demo-data").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"visible\":false}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void switchHidesExistingDemoDataEverywhereAndCanRestoreIt() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 1);
        DatabaseRow demo = catalog.addDatabase("DEMO_switch_test", date.minusDays(1));
        DatabaseRow real = catalog.addDatabase("real_switch_test", date.minusDays(1));
        jdbc.update("update database_catalog set is_demo = true, framework = 'DEMO_ONLY' where id = ?", demo.id());
        jdbc.update("update database_catalog set framework = 'REAL_ONLY' where id = ?", real.id());
        catalog.upsertBackup(demo.id(), "daily", "DEMO:daily:switch-test", date, false,
            Instant.parse("2026-10-01T00:00:00Z"), "successed", "{}");
        catalog.upsertBackup(real.id(), "daily", "REAL:daily:switch-test", date, false,
            Instant.parse("2026-10-01T00:00:00Z"), "successed", "{}");

        mvc.perform(get("/api/admin/demo-data"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visible").value(true))
            .andExpect(jsonPath("$.databaseCount").value(1))
            .andExpect(jsonPath("$.backupCount").value(1));
        mvc.perform(get("/api/databases/page"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));

        mvc.perform(put("/api/admin/demo-data").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"visible\":false}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visible").value(false));
        mvc.perform(get("/api/databases/page"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(1))
            .andExpect(jsonPath("$.items[0].name").value("real_switch_test"));
        mvc.perform(get("/api/databases/" + demo.id())).andExpect(status().isNotFound());
        mvc.perform(get("/api/databases/frameworks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0]").value("REAL_ONLY"))
            .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/backups").param("date", date.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(1))
            .andExpect(jsonPath("$.items[0].databaseName").value("real_switch_test"));
        mvc.perform(get("/api/calendar").param("year", "2026").param("month", "10"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.days[0].day").value("2026-10-01"))
            .andExpect(jsonPath("$.days[0].count").value(1));
        mvc.perform(get("/api/dashboard"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.databaseCount").value(1))
            .andExpect(jsonPath("$.backupCount").value(1));

        mvc.perform(put("/api/admin/demo-data").with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.visible").value(true));
        mvc.perform(get("/api/databases/page"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/backups").param("date", date.toString()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items.length()").value(2));
    }
}

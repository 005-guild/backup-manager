package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:database_listing_test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "app.admin-user=test_admin",
    "app.admin-password=test-password-123",
    "app.demo-seed=false"
})
@ActiveProfiles("local")
class DatabaseListingTests {
    @Autowired CatalogRepository catalog;

    @Test
    void pagesAndFiltersAssetsAcrossDatabaseFields() {
        LocalDate start = LocalDate.of(2026, 9, 1);
        DatabaseRow alpha = catalog.addDatabase("page_asset_alpha", start);
        DatabaseRow beta = catalog.addDatabase("page_asset_beta", start);
        catalog.addDatabase("page_asset_gamma", start);
        catalog.updateMetadata(alpha.id(), new DatabaseMetadata("DBID-PAGE-ALPHA", null,
            "Y", "A", "ZA21", "4.4", "SYS-1", "developer", "asset DBA", "UNIT-1", start));
        catalog.upsertBackup(alpha.id(), "daily", "PAGE-ALPHA-BACKUP", start, false,
            Instant.parse("2026-09-01T00:00:00Z"), "successed", "{}");

        assertEquals(3, catalog.databaseCount("page_asset_", "all", "", ""));
        assertEquals(1, catalog.databaseCount("page_asset_", "with", "", ""));
        assertEquals(2, catalog.databaseCount("page_asset_", "empty", "", ""));
        List<DatabaseRow> firstPage = catalog.databasePage("page_asset_", "all", "", "", 0, 2);
        assertEquals(2, firstPage.size());
        assertEquals(alpha.id(), firstPage.get(0).id());
        assertEquals(beta.id(), firstPage.get(1).id());
        assertEquals(1, catalog.databasePage("page_asset_", "all", "", "", 1, 2).size());
        assertTrue(catalog.databasePage("page_asset_", "all", "", "", 2, 2).isEmpty());

        assertEquals(alpha.id(), catalog.databasePage("DBID-PAGE-ALPHA", "with", "active", "ZA21", 0, 20).get(0).id());
        assertEquals(alpha.id(), catalog.databasePage("asset DBA", "all", "", "", 0, 20).get(0).id());
        assertEquals(0, catalog.databaseCount("page_asset_", "all", "paused", ""));
        assertFalse(catalog.databaseFrameworks().isEmpty());
    }
}

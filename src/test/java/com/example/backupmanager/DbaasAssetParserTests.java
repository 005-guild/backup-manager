package com.example.backupmanager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DbaasAssetParserTests {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void mapsDbaasAssetFieldsAndPreservesRawResponse() throws Exception {
        DbaasAsset asset = DbaasAssetParser.parse(mapper.readTree("{"
            + "\"ID\":\"909767228xzdata02\",\"DBNAME\":\"xzdata02\","
            + "\"LOGICDB_CODE\":\"xzdata02_13578\",\"LDBID\":13578,\"VALID\":\"Y\","
            + "\"DBID\":\"DBID_A_LX05.01@xzdata02_NEO4J_PRD_BIZ\","
            + "\"LEVEL\":\"D\",\"DBVERSION\":\"4.4\","
            + "\"APP_FRAMEWORK\":\"ZA21\",\"CMBSYSCODE\":\"LX05.01/知识图谱\","
            + "\"DEVUSER\":\"季江舟/80279940\",\"DBAUSER\":\"刘博/80328525\","
            + "\"DB_SERVICE_UNIT\":\"LX05.01@xzdata02_NEO4J_PRD_BIZ\","
            + "\"DB_CREATE_DATE\":\"2026-03-03\",\"VIP\":\"11.125.204.93\"}"));

        assertEquals("909767228xzdata02", asset.externalId());
        assertEquals("xzdata02", asset.dbName());
        assertEquals("xzdata02_13578", asset.getLogicdbCode());
        assertEquals(Long.valueOf(13578), asset.getLdbid());
        assertEquals("Y", asset.getValid());
        assertTrue(asset.getRawData().contains("\"VIP\":\"11.125.204.93\""));
        DatabaseMetadata metadata = asset.metadata();
        assertEquals("DBID_A_LX05.01@xzdata02_NEO4J_PRD_BIZ", metadata.dbid());
        assertEquals("Y", metadata.lifecycleStatus());
        assertEquals("D", metadata.securityTier());
        assertEquals("ZA21", metadata.framework());
        assertEquals("4.4", metadata.dbVersion());
        assertEquals("LX05.01/知识图谱", metadata.subsystem());
        assertEquals("季江舟/80279940", metadata.developer());
        assertEquals("刘博/80328525", metadata.dba());
        assertEquals("LX05.01@xzdata02_NEO4J_PRD_BIZ", metadata.serviceUnit());
        assertEquals(LocalDate.of(2026, 3, 3), metadata.createdOn());
    }

    @Test
    void optionalNullAndMalformedValuesDoNotRejectAsset() throws Exception {
        DbaasAsset asset = DbaasAssetParser.parse(mapper.readTree("{"
            + "\"ID\":\"asset-2\",\"DBNAME\":\"example\","
            + "\"LDBID\":\"bad\",\"DBVERSION\":\"null\","
            + "\"DEVUSERS\":\"fallback developer\",\"DB_CREATE_DATE\":\"bad\"}"));

        assertNull(asset.getLdbid());
        assertNull(asset.metadata().dbVersion());
        assertEquals("fallback developer", asset.metadata().developer());
        assertNull(asset.metadata().createdOn());
        assertThrows(IllegalArgumentException.class,
            () -> DbaasAssetParser.parse(mapper.readTree("{\"ID\":\"asset-3\"}")));
    }
}

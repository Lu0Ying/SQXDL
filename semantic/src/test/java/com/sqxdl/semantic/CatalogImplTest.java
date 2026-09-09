package com.sqxdl.semantic;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CatalogImplTest {

    private CatalogImpl catalog;

    @BeforeEach
    void setUp() {
        catalog = new CatalogImpl();
    }

    @Test
    void createTable_success() {
        List<String> columns = Arrays.asList("id", "name", "age");
        catalog.createTable("student", columns);

        assertTrue(catalog.tableExists("student"));
    }

    @Test
    void createTable_duplicate_throwsException() {
        List<String> columns = Arrays.asList("id", "name");
        catalog.createTable("student", columns);

        assertThrows(IllegalArgumentException.class, () -> {
            catalog.createTable("student", columns);
        });
    }

    @Test
    void tableExists_notExists_returnsFalse() {
        assertFalse(catalog.tableExists("nonexistent"));
    }

    @Test
    void getColumns_exists_returnsCorrectColumns() {
        List<String> columns = Arrays.asList("id", "name", "age");
        catalog.createTable("student", columns);

        List<String> result = catalog.getColumns("student");
        assertEquals(Arrays.asList("id", "name", "age"), result);
    }

    @Test
    void getColumns_notExists_returnsNull() {
        assertNull(catalog.getColumns("nonexistent"));
    }

    @Test
    void getColumns_returnsDefensiveCopy() {
        List<String> columns = Arrays.asList("id", "name");
        catalog.createTable("student", columns);

        List<String> result = catalog.getColumns("student");
        result.add("extra");

        List<String> columnsAfter = catalog.getColumns("student");
        assertEquals(2, columnsAfter.size());
        assertFalse(columnsAfter.contains("extra"));
    }

    @Test
    void createTable_inputListModified_doesNotAffectCatalog() {
        List<String> columns = new java.util.ArrayList<>(Arrays.asList("id", "name"));
        catalog.createTable("student", columns);

        columns.add("extra");

        List<String> stored = catalog.getColumns("student");
        assertEquals(2, stored.size());
        assertFalse(stored.contains("extra"));
    }

    @Test
    void createTable_multipleTables() {
        catalog.createTable("student", Arrays.asList("id", "name"));
        catalog.createTable("course", Arrays.asList("cid", "cname"));

        assertTrue(catalog.tableExists("student"));
        assertTrue(catalog.tableExists("course"));
        assertEquals(2, catalog.getColumns("student").size());
        assertEquals(2, catalog.getColumns("course").size());
    }

    // ========== 带类型的建表 ==========

    @Test
    void createTableWithTypes_success() {
        List<CatalogImpl.ColumnInfo> columns = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR)
        );
        catalog.createTableWithTypes("student", columns);

        assertTrue(catalog.tableExists("student"));
        assertEquals(CatalogImpl.DataType.INT, catalog.getColumnType("student", "id"));
        assertEquals(CatalogImpl.DataType.VARCHAR, catalog.getColumnType("student", "name"));
    }

    @Test
    void createTableWithTypes_duplicate_throwsException() {
        List<CatalogImpl.ColumnInfo> columns = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT)
        );
        catalog.createTableWithTypes("student", columns);

        assertThrows(IllegalArgumentException.class, () -> {
            catalog.createTableWithTypes("student", columns);
        });
    }

    @Test
    void columnExists_exists_returnsTrue() {
        catalog.createTable("student", Arrays.asList("id", "name"));
        assertTrue(catalog.columnExists("student", "id"));
        assertTrue(catalog.columnExists("student", "name"));
    }

    @Test
    void columnExists_notExists_returnsFalse() {
        catalog.createTable("student", Arrays.asList("id", "name"));
        assertFalse(catalog.columnExists("student", "age"));
        assertFalse(catalog.columnExists("nonexistent", "id"));
    }

    @Test
    void getColumnType_tableNotExists_returnsNull() {
        assertNull(catalog.getColumnType("nonexistent", "id"));
    }

    @Test
    void getColumnType_columnNotExists_returnsNull() {
        catalog.createTable("student", Arrays.asList("id", "name"));
        assertNull(catalog.getColumnType("student", "age"));
    }

    @Test
    void createTable_defaultTypeIsVarchar() {
        catalog.createTable("student", Arrays.asList("id", "name"));
        assertEquals(CatalogImpl.DataType.VARCHAR, catalog.getColumnType("student", "id"));
        assertEquals(CatalogImpl.DataType.VARCHAR, catalog.getColumnType("student", "name"));
    }

    @Test
    void getColumnInfos_returnsCorrectInfos() {
        List<CatalogImpl.ColumnInfo> columns = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR)
        );
        catalog.createTableWithTypes("student", columns);

        List<CatalogImpl.ColumnInfo> result = catalog.getColumnInfos("student");
        assertEquals(2, result.size());
        assertEquals("id", result.get(0).getName());
        assertEquals(CatalogImpl.DataType.INT, result.get(0).getType());
        assertEquals("name", result.get(1).getName());
        assertEquals(CatalogImpl.DataType.VARCHAR, result.get(1).getType());
    }

    @Test
    void getColumnInfos_notExists_returnsNull() {
        assertNull(catalog.getColumnInfos("nonexistent"));
    }

    @Test
    void getColumnInfos_returnsDefensiveCopy() {
        List<CatalogImpl.ColumnInfo> columns = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT)
        );
        catalog.createTableWithTypes("student", columns);

        List<CatalogImpl.ColumnInfo> result = catalog.getColumnInfos("student");
        result.add(new CatalogImpl.ColumnInfo("extra", CatalogImpl.DataType.VARCHAR));

        List<CatalogImpl.ColumnInfo> after = catalog.getColumnInfos("student");
        assertEquals(1, after.size());
    }

    // ========== dropTable ==========

    @Test
    void dropTable_existing_succeeds() {
        catalog.createTable("test", Arrays.asList("a", "b"));
        assertTrue(catalog.tableExists("test"));

        catalog.dropTable("test");
        assertFalse(catalog.tableExists("test"));
    }

    @Test
    void dropTable_notExisting_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> catalog.dropTable("nonexistent"));
    }

    @Test
    void dropTable_otherTablesUnaffected() {
        catalog.createTable("a", Arrays.asList("col1"));
        catalog.createTable("b", Arrays.asList("col2"));

        catalog.dropTable("a");

        assertFalse(catalog.tableExists("a"));
        assertTrue(catalog.tableExists("b"));
    }

    // ========== 与存储引擎同步（方案 B） ==========

    /** 简易 mock：模拟存储引擎返回的表名列表与列结构 */
    private static class MockMetadataProvider implements TableMetadataProvider {
        private final List<String> tableNames;
        private final java.util.Map<String, List<CatalogImpl.ColumnInfo>> tableColumns;

        MockMetadataProvider(List<String> tableNames,
                             java.util.Map<String, List<CatalogImpl.ColumnInfo>> tableColumns) {
            this.tableNames = tableNames;
            this.tableColumns = tableColumns;
        }

        @Override
        public List<String> getTableNames() {
            return tableNames;
        }

        @Override
        public List<CatalogImpl.ColumnInfo> getTableColumns(String tableName) {
            return tableColumns.getOrDefault(tableName, List.of());
        }
    }

    @Test
    void syncFromStorage_populatesAllTables() {
        List<CatalogImpl.ColumnInfo> studentCols = Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR),
                new CatalogImpl.ColumnInfo("active", CatalogImpl.DataType.BOOLEAN)
        );
        List<CatalogImpl.ColumnInfo> courseCols = Arrays.asList(
                new CatalogImpl.ColumnInfo("cid", CatalogImpl.DataType.INT),
                new CatalogImpl.ColumnInfo("cname", CatalogImpl.DataType.VARCHAR)
        );
        java.util.Map<String, List<CatalogImpl.ColumnInfo>> tables = new java.util.HashMap<>();
        tables.put("student", studentCols);
        tables.put("course", courseCols);

        MockMetadataProvider provider = new MockMetadataProvider(
                Arrays.asList("student", "course"), tables);

        catalog.syncFromStorage(provider);

        assertTrue(catalog.tableExists("student"));
        assertTrue(catalog.tableExists("course"));
        assertEquals(3, catalog.getColumns("student").size());
        assertEquals(CatalogImpl.DataType.INT, catalog.getColumnType("student", "id"));
        assertEquals(CatalogImpl.DataType.VARCHAR, catalog.getColumnType("student", "name"));
        assertEquals(CatalogImpl.DataType.BOOLEAN, catalog.getColumnType("student", "active"));
        assertEquals(CatalogImpl.DataType.INT, catalog.getColumnType("course", "cid"));
    }

    @Test
    void syncFromStorage_clearsExistingBeforeSync() {
        // 本地预存一张表
        catalog.createTable("old_table", Arrays.asList("x"));

        MockMetadataProvider provider = new MockMetadataProvider(
                List.of("student"),
                java.util.Map.of("student", Arrays.asList(
                        new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT)
                )));

        catalog.syncFromStorage(provider);

        // 旧表应被清空
        assertFalse(catalog.tableExists("old_table"));
        // 新表已同步
        assertTrue(catalog.tableExists("student"));
    }

    @Test
    void syncFromStorage_emptyStorage_clearsAll() {
        catalog.createTable("a", Arrays.asList("col1"));
        catalog.createTable("b", Arrays.asList("col2"));

        MockMetadataProvider provider = new MockMetadataProvider(List.of(), java.util.Map.of());

        catalog.syncFromStorage(provider);

        assertTrue(catalog.getAllTableNames().isEmpty());
    }

    @Test
    void syncFromStorage_skipsTableWithEmptyColumns() {
        // 某张表 describeTable 返回空（如表结构损坏），应跳过不注册
        java.util.Map<String, List<CatalogImpl.ColumnInfo>> tables = new java.util.HashMap<>();
        tables.put("good", Arrays.asList(
                new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT)
        ));
        tables.put("broken", List.of());

        MockMetadataProvider provider = new MockMetadataProvider(
                Arrays.asList("good", "broken"), tables);

        catalog.syncFromStorage(provider);

        assertTrue(catalog.tableExists("good"));
        assertFalse(catalog.tableExists("broken"));
    }

    @Test
    void syncTableFromStorage_addsNewTable() {
        MockMetadataProvider provider = new MockMetadataProvider(
                List.of(),
                java.util.Map.of("student", Arrays.asList(
                        new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                        new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR)
                )));

        catalog.syncTableFromStorage("student", provider);

        assertTrue(catalog.tableExists("student"));
        assertEquals(CatalogImpl.DataType.INT, catalog.getColumnType("student", "id"));
    }

    @Test
    void syncTableFromStorage_overwritesExisting() {
        // 本地预存旧结构
        catalog.createTable("student", Arrays.asList("id", "name"));

        // 存储引擎返回新结构（多了一列，类型也变了）
        MockMetadataProvider provider = new MockMetadataProvider(
                List.of(),
                java.util.Map.of("student", Arrays.asList(
                        new CatalogImpl.ColumnInfo("id", CatalogImpl.DataType.INT),
                        new CatalogImpl.ColumnInfo("name", CatalogImpl.DataType.VARCHAR),
                        new CatalogImpl.ColumnInfo("age", CatalogImpl.DataType.INT)
                )));

        catalog.syncTableFromStorage("student", provider);

        assertEquals(3, catalog.getColumns("student").size());
        assertEquals(CatalogImpl.DataType.INT, catalog.getColumnType("student", "id"));
        assertTrue(catalog.columnExists("student", "age"));
    }

    @Test
    void syncTableFromStorage_emptyRemovesTable() {
        catalog.createTable("student", Arrays.asList("id"));

        MockMetadataProvider provider = new MockMetadataProvider(
                List.of(), java.util.Map.of());

        catalog.syncTableFromStorage("student", provider);

        assertFalse(catalog.tableExists("student"));
    }

    @Test
    void getAllTableNames_returnsAllRegistered() {
        catalog.createTable("student", Arrays.asList("id"));
        catalog.createTable("course", Arrays.asList("cid"));

        List<String> names = catalog.getAllTableNames();
        assertEquals(2, names.size());
        assertTrue(names.contains("student"));
        assertTrue(names.contains("course"));
    }
}

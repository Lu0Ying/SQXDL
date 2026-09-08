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
}

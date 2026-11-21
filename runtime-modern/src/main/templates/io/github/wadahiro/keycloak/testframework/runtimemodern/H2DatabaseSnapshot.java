package io.github.wadahiro.keycloak.testframework.runtimemodern;

import java.sql.*;
import java.util.*;

/** Logical data snapshot for the fixed PUBLIC schema of a disposable H2 DB. */
final class H2DatabaseSnapshot implements AutoCloseable {
    private final Connection db;
    private final List<Table> tables = new ArrayList<>();
    private final Map<String, Long> sequences;
    private final Map<String, Long> identities;
    private final List<String> schema;
    private boolean broken;
    private boolean closed;

    H2DatabaseSnapshot(Connection db) throws SQLException {
        this.db = db;
        assertIdle(db);
        schema = schema(db);
        sequences = sequences(db);
        identities = identities(db);
        try {
            for (String name : tableNames(db)) {
                Table table = new Table(name);
                tables.add(table);
                try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM " + name)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int size = md.getColumnCount();
                    StringJoiner columns = new StringJoiner(","), parameters = new StringJoiner(",");
                    for (int i = 1; i <= size; i++) { columns.add(quote(md.getColumnName(i))); parameters.add("?"); }
                    table.insert = db.prepareStatement("INSERT INTO " + name + " (" + columns + ") VALUES (" + parameters + ")");
                    while (rs.next()) {
                        Object[] row = new Object[size];
                        for (int i = 1; i <= size; i++) {
                            Object value = rs.getObject(i);
                            if (value instanceof Clob) value = rs.getString(i);
                            if (value instanceof Blob) value = rs.getBytes(i);
                            row[i - 1] = value;
                        }
                        table.rows.add(row);
                    }
                }
            }
        } catch (SQLException | RuntimeException failure) { close(); throw failure; }
    }

    void restore() throws SQLException {
        if (broken || closed) throw new IllegalStateException("Snapshot is closed or failed; start a fresh server");
        try {
            assertIdle(db);
            if (!schema.equals(schema(db))) throw new SQLException("Schema changes are unsupported by H2 data reset");
            if (!sequences.keySet().equals(sequences(db).keySet())) throw new SQLException("Sequence schema changed");
            try (Statement st = db.createStatement()) {
                st.execute("SET REFERENTIAL_INTEGRITY FALSE");
                try {
                    for (Table table : tables) st.execute("TRUNCATE TABLE " + table.name);
                    db.setAutoCommit(false);
                    for (Table table : tables) {
                        for (Object[] row : table.rows) {
                            for (int i = 0; i < row.length; i++) table.insert.setObject(i + 1, row[i]);
                            table.insert.addBatch();
                        }
                        if (!table.rows.isEmpty()) table.insert.executeBatch();
                        table.insert.clearBatch();
                    }
                    db.commit();
                    db.setAutoCommit(true);
                    for (Map.Entry<String, Long> sequence : sequences.entrySet()) {
                        st.execute("ALTER SEQUENCE " + sequence.getKey() + " RESTART WITH " + sequence.getValue());
                    }
                    for (Map.Entry<String, Long> identity : identities.entrySet()) {
                        st.execute("ALTER TABLE " + identity.getKey() + " RESTART WITH " + identity.getValue());
                    }
                } finally {
                    if (!db.getAutoCommit()) db.rollback();
                    db.setAutoCommit(true);
                    st.execute("SET REFERENTIAL_INTEGRITY TRUE");
                }
            }
        } catch (SQLException | RuntimeException failure) { broken = true; throw failure; }
    }

    static void assertIdle(Connection db) throws SQLException {
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT * FROM INFORMATION_SCHEMA.SESSIONS WHERE CONTAINS_UNCOMMITTED = TRUE")) {
            if (rs.next()) throw new SQLException("An uncommitted H2 transaction is still active");
        }
    }

    private static List<String> tableNames(Connection db) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet rs = db.getMetaData().getTables(null, null, "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (rs.next()) {
                String schema = rs.getString("TABLE_SCHEM");
                if ("INFORMATION_SCHEMA".equals(schema)) continue;
                if (!"PUBLIC".equals(schema)) throw new SQLException("Only PUBLIC tables are supported: " + schema);
                names.add(quote(schema) + "." + quote(rs.getString("TABLE_NAME")));
            }
        }
        Collections.sort(names);
        return names;
    }

    private static Map<String, Long> sequences(Connection db) throws SQLException {
        Map<String, Long> values = new TreeMap<>();
        String query = db.getMetaData().getDatabaseMajorVersion() >= 2
                ? "SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, BASE_VALUE FROM INFORMATION_SCHEMA.SEQUENCES"
                : "SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, CURRENT_VALUE + INCREMENT FROM INFORMATION_SCHEMA.SEQUENCES";
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(query)) {
            while (rs.next()) values.put(quote(rs.getString(1)) + "." + quote(rs.getString(2)), rs.getLong(3));
        }
        return values;
    }

    private static Map<String, Long> identities(Connection db) throws SQLException {
        Map<String, Long> values = new TreeMap<>();
        if (db.getMetaData().getDatabaseMajorVersion() < 2) return values;
        try (Statement st = db.createStatement(); ResultSet rs = st.executeQuery(
                "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, IDENTITY_BASE FROM INFORMATION_SCHEMA.COLUMNS WHERE IS_IDENTITY = 'YES'")) {
            while (rs.next()) values.put(quote(rs.getString(1)) + "." + quote(rs.getString(2)) + " ALTER COLUMN " + quote(rs.getString(3)), rs.getLong(4));
        }
        return values;
    }

    private static List<String> schema(Connection db) throws SQLException {
        List<String> fingerprint = new ArrayList<>(tableNames(db));
        DatabaseMetaData md = db.getMetaData();
        try (ResultSet rs = md.getColumns(null, "PUBLIC", "%", "%")) {
            while (rs.next()) fingerprint.add("C:" + rs.getString("TABLE_NAME") + ":" + rs.getString("COLUMN_NAME")
                    + ":" + rs.getInt("DATA_TYPE") + ":" + rs.getInt("COLUMN_SIZE") + ":" + rs.getInt("DECIMAL_DIGITS")
                    + ":" + rs.getInt("NULLABLE") + ":" + rs.getString("COLUMN_DEF"));
        }
        try (ResultSet names = md.getTables(null, "PUBLIC", "%", new String[]{"TABLE", "BASE TABLE"})) {
            while (names.next()) {
                String name = names.getString("TABLE_NAME");
                try (ResultSet rs = md.getIndexInfo(null, "PUBLIC", name, false, false)) {
                    while (rs.next()) fingerprint.add("I:" + name + ":" + rs.getString("INDEX_NAME") + ":"
                            + rs.getString("COLUMN_NAME") + ":" + rs.getBoolean("NON_UNIQUE") + ":" + rs.getInt("ORDINAL_POSITION"));
                }
                try (ResultSet rs = md.getImportedKeys(null, "PUBLIC", name)) {
                    while (rs.next()) fingerprint.add("F:" + name + ":" + rs.getString("FK_NAME") + ":"
                            + rs.getString("FKCOLUMN_NAME") + ":" + rs.getString("PKTABLE_NAME") + ":" + rs.getString("PKCOLUMN_NAME"));
                }
            }
        }
        Collections.sort(fingerprint);
        return fingerprint;
    }

    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }
    @Override public void close() {
        closed = true;
        for (Table table : tables) {
            try { if (table.insert != null) table.insert.close(); } catch (SQLException ignored) { }
            table.rows.clear();
        }
        tables.clear();
    }
    private static final class Table {
        final String name;
        final List<Object[]> rows = new ArrayList<>();
        PreparedStatement insert;
        Table(String name) { this.name = name; }
    }
}

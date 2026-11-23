package io.github.wadahiro.keycloak.testframework.quarkus;

import java.sql.Connection;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** In-memory row snapshot of Keycloak's H2 PUBLIC schema. */
final class H2DataSnapshot implements AutoCloseable {
    private final Connection connection;
    private final List<Table> tables = new ArrayList<>();
    private final Map<String, Long> sequences;
    private final Map<String, Long> identities;
    private final List<String> schema;
    private boolean broken;
    private boolean closed;

    H2DataSnapshot(Connection connection) throws SQLException {
        this.connection = connection;
        if (!"H2".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) {
            throw new SQLException("The Quarkus H2 snapshot requires an H2 datasource, got "
                    + connection.getMetaData().getDatabaseProductName());
        }
        assertIdle(connection);
        schema = schema(connection);
        sequences = sequences(connection);
        identities = identities(connection);
        try {
            for (String name : tableNames(connection)) {
                Table table = new Table(name);
                tables.add(table);
                try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM " + name)) {
                    ResultSetMetaData metadata = rows.getMetaData();
                    StringJoiner columns = new StringJoiner(",");
                    StringJoiner parameters = new StringJoiner(",");
                    for (int i = 1; i <= metadata.getColumnCount(); i++) {
                        columns.add(quote(metadata.getColumnName(i)));
                        parameters.add("?");
                    }
                    table.insert = connection.prepareStatement("INSERT INTO " + name + " (" + columns + ") VALUES (" + parameters + ")");
                    while (rows.next()) {
                        Object[] row = new Object[metadata.getColumnCount()];
                        for (int i = 1; i <= row.length; i++) {
                            Object value = rows.getObject(i);
                            if (value instanceof Clob) value = rows.getString(i);
                            if (value instanceof Blob) value = rows.getBytes(i);
                            row[i - 1] = value;
                        }
                        table.rows.add(row);
                    }
                }
            }
        } catch (SQLException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    void restore() throws SQLException {
        if (broken || closed) throw new IllegalStateException("Snapshot is closed or failed; start a fresh server");
        try {
            assertIdle(connection);
            if (!schema.equals(schema(connection))) throw new SQLException("Schema changes are unsupported by H2 data reset");
            if (!sequences.keySet().equals(sequences(connection).keySet())) throw new SQLException("Sequence schema changed");
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET REFERENTIAL_INTEGRITY FALSE");
                try {
                    for (Table table : tables) statement.execute("TRUNCATE TABLE " + table.name);
                    connection.setAutoCommit(false);
                    for (Table table : tables) {
                        for (Object[] row : table.rows) {
                            for (int i = 0; i < row.length; i++) table.insert.setObject(i + 1, row[i]);
                            table.insert.addBatch();
                        }
                        if (!table.rows.isEmpty()) table.insert.executeBatch();
                        table.insert.clearBatch();
                    }
                    connection.commit();
                    connection.setAutoCommit(true);
                    for (Map.Entry<String, Long> sequence : sequences.entrySet()) {
                        statement.execute("ALTER SEQUENCE " + sequence.getKey() + " RESTART WITH " + sequence.getValue());
                    }
                    for (Map.Entry<String, Long> identity : identities.entrySet()) {
                        statement.execute("ALTER TABLE " + identity.getKey() + " RESTART WITH " + identity.getValue());
                    }
                } finally {
                    if (!connection.getAutoCommit()) connection.rollback();
                    connection.setAutoCommit(true);
                    statement.execute("SET REFERENTIAL_INTEGRITY TRUE");
                }
            }
        } catch (SQLException | RuntimeException failure) {
            broken = true;
            throw failure;
        }
    }

    private static void assertIdle(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT 1 FROM INFORMATION_SCHEMA.SESSIONS WHERE CONTAINS_UNCOMMITTED = TRUE")) {
            if (result.next()) throw new SQLException("An uncommitted H2 transaction is active during snapshot operation");
        }
    }

    private static List<String> tableNames(Connection connection) throws SQLException {
        List<String> names = new ArrayList<>();
        try (ResultSet result = connection.getMetaData().getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (result.next()) names.add(quote("PUBLIC") + "." + quote(result.getString("TABLE_NAME")));
        }
        Collections.sort(names);
        return names;
    }

    private static Map<String, Long> sequences(Connection connection) throws SQLException {
        Map<String, Long> values = new LinkedHashMap<>();
        String query = connection.getMetaData().getDatabaseMajorVersion() >= 2
                ? "SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, BASE_VALUE FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = 'PUBLIC'"
                : "SELECT SEQUENCE_SCHEMA, SEQUENCE_NAME, CURRENT_VALUE + INCREMENT FROM INFORMATION_SCHEMA.SEQUENCES WHERE SEQUENCE_SCHEMA = 'PUBLIC'";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(query)) {
            while (result.next()) {
                values.put(quote(result.getString(1)) + "." + quote(result.getString(2)), result.getLong(3));
            }
        }
        return values;
    }

    private static Map<String, Long> identities(Connection connection) throws SQLException {
        Map<String, Long> values = new LinkedHashMap<>();
        if (connection.getMetaData().getDatabaseMajorVersion() < 2) return values;
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT TABLE_SCHEMA, TABLE_NAME, COLUMN_NAME, IDENTITY_BASE FROM INFORMATION_SCHEMA.COLUMNS WHERE IS_IDENTITY = 'YES' AND TABLE_SCHEMA = 'PUBLIC'")) {
            while (result.next()) {
                String table = quote(result.getString("TABLE_SCHEMA")) + "." + quote(result.getString("TABLE_NAME"));
                values.put(table + " ALTER COLUMN " + quote(result.getString("COLUMN_NAME")), result.getLong("IDENTITY_BASE"));
            }
        }
        return values;
    }

    private static int column(ResultSetMetaData metadata, String name) throws SQLException {
        for (int i = 1; i <= metadata.getColumnCount(); i++) if (name.equalsIgnoreCase(metadata.getColumnLabel(i))) return i;
        return -1;
    }

    private static List<String> schema(Connection connection) throws SQLException {
        List<String> fingerprint = new ArrayList<>(tableNames(connection));
        var metadata = connection.getMetaData();
        try (ResultSet columns = metadata.getColumns(null, "PUBLIC", "%", "%")) {
            while (columns.next()) fingerprint.add("C:" + columns.getString("TABLE_NAME") + ":" + columns.getString("COLUMN_NAME")
                    + ":" + columns.getInt("DATA_TYPE") + ":" + columns.getInt("COLUMN_SIZE") + ":" + columns.getInt("DECIMAL_DIGITS")
                    + ":" + columns.getInt("NULLABLE") + ":" + columns.getString("COLUMN_DEF"));
        }
        try (ResultSet tables = metadata.getTables(null, "PUBLIC", "%", new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                try (ResultSet indexes = metadata.getIndexInfo(null, "PUBLIC", name, false, false)) {
                    while (indexes.next()) fingerprint.add("I:" + name + ":" + indexes.getString("INDEX_NAME") + ":"
                            + indexes.getString("COLUMN_NAME") + ":" + indexes.getBoolean("NON_UNIQUE") + ":" + indexes.getInt("ORDINAL_POSITION"));
                }
                try (ResultSet keys = metadata.getImportedKeys(null, "PUBLIC", name)) {
                    while (keys.next()) fingerprint.add("F:" + name + ":" + keys.getString("FK_NAME") + ":"
                            + keys.getString("FKCOLUMN_NAME") + ":" + keys.getString("PKTABLE_NAME") + ":" + keys.getString("PKCOLUMN_NAME"));
                }
            }
        }
        fingerprint.sort(Comparator.naturalOrder());
        return fingerprint;
    }

    private static String quote(String identifier) { return "\"" + identifier.replace("\"", "\"\"") + "\""; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (Table table : tables) {
            try { table.insert.close(); } catch (SQLException ignored) { }
            table.rows.clear();
        }
        tables.clear();
        try { connection.close(); } catch (SQLException ignored) { }
    }

    private static final class Table {
        private final String name;
        private final List<Object[]> rows = new ArrayList<>();
        private PreparedStatement insert;
        private Table(String name) { this.name = name; }
    }
}

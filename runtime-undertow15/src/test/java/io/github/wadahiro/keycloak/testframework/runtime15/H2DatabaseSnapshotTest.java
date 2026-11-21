package io.github.wadahiro.keycloak.testframework.runtime15;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class H2DatabaseSnapshotTest {
    private Connection connection() throws Exception {
        Class.forName("org.h2.Driver");
        assertEquals(11, Runtime.version().feature());
        return DriverManager.getConnection("jdbc:h2:mem:" + UUID.randomUUID(), "sa", "");
    }
    @Test void restoresLobsForeignKeysIdentityAndSequenceAcrossRepeatedCycles() throws Exception {
        try (Connection db = connection(); Statement st = db.createStatement()) {
            st.execute("CREATE SEQUENCE ticket START WITH 7 INCREMENT BY 3");
            st.execute("CREATE TABLE parent (id BIGINT AUTO_INCREMENT PRIMARY KEY, txt CLOB, bytes BLOB, optional VARCHAR)");
            st.execute("CREATE TABLE child (id INT PRIMARY KEY, parent_id BIGINT REFERENCES parent(id))");
            String text = "long text ".repeat(2000);
            byte[] bytes = new byte[20000]; java.util.Arrays.fill(bytes, (byte) 73);
            try (PreparedStatement insert = db.prepareStatement("INSERT INTO parent(txt,bytes) VALUES(?,?)")) {
                insert.setString(1, text); insert.setBytes(2, bytes); insert.executeUpdate();
            }
            st.execute("INSERT INTO child VALUES(1,1)");
            try (H2DatabaseSnapshot snapshot = new H2DatabaseSnapshot(db)) {
                for (int i=0; i<3; i++) {
                    st.execute("DELETE FROM child"); st.execute("DELETE FROM parent");
                    st.execute("INSERT INTO parent(txt) VALUES('changed')");
                    st.executeQuery("SELECT NEXT VALUE FOR ticket").close();
                    snapshot.restore();
                    try (ResultSet rs = st.executeQuery("SELECT * FROM parent")) {
                        assertTrue(rs.next()); assertEquals(1,rs.getLong("id"));
                        assertEquals(text,rs.getString("txt")); assertArrayEquals(bytes,rs.getBytes("bytes"));
                        assertNull(rs.getString("optional")); assertFalse(rs.next());
                    }
                    try (ResultSet rs = st.executeQuery("SELECT NEXT VALUE FOR ticket")) { rs.next(); assertEquals(7,rs.getLong(1)); }
                    st.execute("INSERT INTO parent(txt) VALUES('next')");
                    try (ResultSet rs = st.executeQuery("SELECT MAX(id) FROM parent")) { rs.next(); assertEquals(2,rs.getLong(1)); }
                    assertThrows(SQLException.class, () -> st.execute("INSERT INTO child VALUES(2,999)"));
                }
            }
        }
    }
    @Test void rejectsSchemaChangesBeforeTruncatingAndPoisonsTheSnapshot() throws Exception {
        try (Connection db = connection(); Statement st = db.createStatement()) {
            st.execute("CREATE TABLE example(id INT PRIMARY KEY)"); st.execute("INSERT INTO example VALUES(1)");
            try (H2DatabaseSnapshot snapshot = new H2DatabaseSnapshot(db)) {
                st.execute("ALTER TABLE example ADD COLUMN extra INT");
                assertThrows(SQLException.class, snapshot::restore);
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM example")) { rs.next(); assertEquals(1,rs.getInt(1)); }
                assertThrows(IllegalStateException.class, snapshot::restore);
            }
        }
    }
    @Test void rejectsUncommittedWork() throws Exception {
        try (Connection db = connection(); Statement st = db.createStatement()) {
            st.execute("CREATE TABLE example(id INT)");
            try (Connection other = DriverManager.getConnection(db.getMetaData().getURL(), "sa", "")) {
                other.setAutoCommit(false); other.createStatement().execute("INSERT INTO example VALUES(1)");
                assertThrows(SQLException.class, () -> new H2DatabaseSnapshot(db));
                other.rollback();
                try (H2DatabaseSnapshot snapshot = new H2DatabaseSnapshot(db)) { snapshot.restore(); }
            }
        }
    }
    @Test void requestAndTimerBarrierWaitsForActiveWork() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var work = executor.submit(() -> RequestGate.read(() -> {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            }));
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            var reset = executor.submit(() -> RequestGate.exclusive(() -> "restored"));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> reset.get(50, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown(); work.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals("restored",reset.get(2,java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); executor.shutdownNow(); }
    }
}

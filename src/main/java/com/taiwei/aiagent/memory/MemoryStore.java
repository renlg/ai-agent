package com.taiwei.aiagent.memory;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.intellij.openapi.diagnostic.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * SQLite-backed persistence for {@link MemoryEntry}. All access goes through a single JDBC
 * connection guarded by {@code lock}, since SQLite connections aren't safe for concurrent use.
 */
public class MemoryStore implements AutoCloseable {

    private static final Logger LOG = Logger.getInstance(MemoryStore.class);
    private static final Gson GSON = new Gson();

    private final Connection connection;
    private final Object lock = new Object();

    /** Opens (or creates) a SQLite database file on disk, creating parent directories as needed. */
    public MemoryStore(Path dbFile) {
        this(jdbcUrlForFile(dbFile));
    }

    /** Opens a private in-memory SQLite database. Used by tests to avoid touching disk. */
    public MemoryStore() {
        this("jdbc:sqlite::memory:");
    }

    private MemoryStore(String jdbcUrl) {
        try {
            Class.forName("org.sqlite.JDBC");
            this.connection = DriverManager.getConnection(jdbcUrl);
            createSchema();
        } catch (ClassNotFoundException | SQLException e) {
            throw new IllegalStateException("Failed to open memory store: " + jdbcUrl, e);
        }
    }

    private static String jdbcUrlForFile(Path dbFile) {
        try {
            Path parent = dbFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create memory store directory: " + dbFile.getParent(), e);
        }
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    private void createSchema() throws SQLException {
        synchronized (lock) {
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS memories (" +
                        "id TEXT PRIMARY KEY," +
                        "content TEXT NOT NULL," +
                        "category TEXT NOT NULL," +
                        "tags TEXT NOT NULL," +
                        "importance INTEGER NOT NULL," +
                        "access_count INTEGER NOT NULL," +
                        "created_at INTEGER NOT NULL," +
                        "updated_at INTEGER NOT NULL," +
                        "last_accessed_at INTEGER NOT NULL" +
                        ")");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_category ON memories(category)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_importance ON memories(importance)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_last_accessed ON memories(last_accessed_at)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_memories_tags ON memories(tags)");
            }
        }
    }

    public void insert(MemoryEntry entry) {
        synchronized (lock) {
            String sql = "INSERT INTO memories " +
                    "(id, content, category, tags, importance, access_count, created_at, updated_at, last_accessed_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                ps.setString(1, entry.getId());
                bindMutableFields(ps, entry, 2);
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to insert memory: " + entry.getId(), e);
            }
        }
    }

    public void update(MemoryEntry entry) {
        synchronized (lock) {
            String sql = "UPDATE memories SET content=?, category=?, tags=?, importance=?, " +
                    "access_count=?, created_at=?, updated_at=?, last_accessed_at=? WHERE id=?";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                bindMutableFields(ps, entry, 1);
                ps.setString(9, entry.getId());
                ps.executeUpdate();
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to update memory: " + entry.getId(), e);
            }
        }
    }

    public boolean deleteById(String id) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("DELETE FROM memories WHERE id=?")) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to delete memory: " + id, e);
            }
        }
    }

    public List<MemoryEntry> findAll() {
        synchronized (lock) {
            List<MemoryEntry> result = new ArrayList<>();
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT * FROM memories")) {
                while (rs.next()) {
                    result.add(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query memories", e);
            }
            return result;
        }
    }

    public Optional<MemoryEntry> findById(String id) {
        synchronized (lock) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT * FROM memories WHERE id=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return Optional.of(mapRow(rs));
                }
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to query memory: " + id, e);
            }
            return Optional.empty();
        }
    }

    public int count() {
        synchronized (lock) {
            try (Statement st = connection.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM memories")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (SQLException e) {
                throw new IllegalStateException("Failed to count memories", e);
            }
        }
    }

    private void bindMutableFields(PreparedStatement ps, MemoryEntry entry, int startIndex) throws SQLException {
        int i = startIndex;
        ps.setString(i++, entry.getContent());
        ps.setString(i++, entry.getCategory().name());
        ps.setString(i++, GSON.toJson(entry.getTags()));
        ps.setInt(i++, entry.getImportance());
        ps.setInt(i++, entry.getAccessCount());
        ps.setLong(i++, entry.getCreatedAt());
        ps.setLong(i++, entry.getUpdatedAt());
        ps.setLong(i, entry.getLastAccessedAt());
    }

    private MemoryEntry mapRow(ResultSet rs) throws SQLException {
        String id = rs.getString("id");
        String content = rs.getString("content");
        MemoryCategory category = MemoryCategory.valueOf(rs.getString("category"));
        List<String> tags = GSON.fromJson(rs.getString("tags"), new TypeToken<List<String>>() {
        }.getType());
        int importance = rs.getInt("importance");
        int accessCount = rs.getInt("access_count");
        long createdAt = rs.getLong("created_at");
        long updatedAt = rs.getLong("updated_at");
        long lastAccessedAt = rs.getLong("last_accessed_at");
        return new MemoryEntry(id, content, category, tags, importance, accessCount, createdAt, updatedAt, lastAccessedAt);
    }

    @Override
    public void close() {
        synchronized (lock) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOG.warn("Failed to close memory store connection", e);
            }
        }
    }
}

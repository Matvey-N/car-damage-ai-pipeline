package com.cardamage.core.inspect;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Inspections of Mini App users: SQLite for the records (as planned in the TZ,
 * section 3), photo files next to it. One process, one writer: all methods
 * are synchronized.
 *
 *   inspections  one row per inspection (single photo set, or "before"/"after" of a rental)
 *   photos       one row per photo: the model's damages and, if the user edited them, the corrected list
 *   corrections  every user edit (before/after JSON): collected as extra labels for later training
 */
public class InspectionStore {

    public record Inspection(String id, long userId, long createdAt, String mode, String kind,
                             String title, String beforeId) {
    }

    /** vehicleVisible: false if the model saw no car on the photo; null if not asked (older records). */
    public record Photo(String inspectionId, int index, String view, String status, String error,
                        List<Damage> modelDamages, List<Damage> correctedDamages, Boolean vehicleVisible) {

        public Photo(String inspectionId, int index, String view, String status, String error,
                     List<Damage> modelDamages, List<Damage> correctedDamages) {
            this(inspectionId, index, view, status, error, modelDamages, correctedDamages, null);
        }

        /** What the user sees: the corrected list if there is one, else the model's. */
        public List<Damage> damages() {
            return correctedDamages != null ? correctedDamages : modelDamages;
        }

        public boolean edited() {
            return correctedDamages != null;
        }
    }

    private static final TypeReference<List<Damage>> DAMAGE_LIST = new TypeReference<>() { };

    private final String url;
    private final Path photoDir;
    private final ObjectMapper mapper;

    public InspectionStore(Path dir, ObjectMapper mapper) {
        try {
            Files.createDirectories(dir.resolve("photos"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create storage directory " + dir, e);
        }
        this.url = "jdbc:sqlite:" + dir.resolve("inspections.db").toAbsolutePath();
        this.photoDir = dir.resolve("photos");
        this.mapper = mapper;
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("""
                    CREATE TABLE IF NOT EXISTS inspections (
                      id TEXT PRIMARY KEY, user_id INTEGER NOT NULL, created_at INTEGER NOT NULL,
                      mode TEXT NOT NULL, kind TEXT NOT NULL, title TEXT, before_id TEXT)""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS photos (
                      inspection_id TEXT NOT NULL, idx INTEGER NOT NULL, view TEXT, status TEXT NOT NULL,
                      error TEXT, damages_json TEXT NOT NULL, corrected_json TEXT,
                      PRIMARY KEY (inspection_id, idx))""");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS corrections (
                      id INTEGER PRIMARY KEY AUTOINCREMENT, inspection_id TEXT NOT NULL, idx INTEGER NOT NULL,
                      user_id INTEGER NOT NULL, at INTEGER NOT NULL, before_json TEXT NOT NULL, after_json TEXT NOT NULL)""");
            s.execute("CREATE INDEX IF NOT EXISTS inspections_user ON inspections(user_id, created_at)");
            // added after the first release: databases created earlier get the column here
            boolean hasVehicleColumn = false;
            try (ResultSet rs = s.executeQuery("PRAGMA table_info(photos)")) {
                while (rs.next()) {
                    hasVehicleColumn |= "vehicle_visible".equals(rs.getString("name"));
                }
            }
            if (!hasVehicleColumn) {
                s.execute("ALTER TABLE photos ADD COLUMN vehicle_visible INTEGER");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot open the inspection database: " + e.getMessage(), e);
        }
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url);
    }

    public synchronized Inspection create(long userId, long createdAt, String mode, String kind, String title,
                                          String beforeId) {
        Inspection i = new Inspection(UUID.randomUUID().toString(), userId, createdAt, mode, kind, title, beforeId);
        sql("INSERT INTO inspections (id, user_id, created_at, mode, kind, title, before_id) VALUES (?,?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, i.id());
                    ps.setLong(2, userId);
                    ps.setLong(3, createdAt);
                    ps.setString(4, mode);
                    ps.setString(5, kind);
                    ps.setString(6, title);
                    ps.setString(7, beforeId);
                    ps.executeUpdate();
                    return null;
                });
        return i;
    }

    public synchronized void addPhoto(String inspectionId, int index, String view, byte[] image, String extension,
                                      DamageAssessment result) {
        try {
            Path dir = photoDir.resolve(inspectionId);
            Files.createDirectories(dir);
            Files.write(dir.resolve(index + "." + extension), image);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot save photo", e);
        }
        String json = toJson(result.isSuccess() ? result.getDamages() : List.of());
        sql("INSERT INTO photos (inspection_id, idx, view, status, error, damages_json, vehicle_visible) "
                + "VALUES (?,?,?,?,?,?,?)", ps -> {
            ps.setString(1, inspectionId);
            ps.setInt(2, index);
            ps.setString(3, view);
            ps.setString(4, result.getStatus());
            ps.setString(5, result.getErrorMessage());
            ps.setString(6, json);
            if (result.getVehicleVisible() == null) {
                ps.setNull(7, java.sql.Types.INTEGER);
            } else {
                ps.setInt(7, result.getVehicleVisible() ? 1 : 0);
            }
            ps.executeUpdate();
            return null;
        });
    }

    public synchronized Optional<Inspection> find(String id) {
        return sql("SELECT * FROM inspections WHERE id = ?", ps -> {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(inspection(rs)) : Optional.<Inspection>empty();
            }
        });
    }

    /** Newest first. */
    public synchronized List<Inspection> list(long userId) {
        return sql("SELECT * FROM inspections WHERE user_id = ? ORDER BY created_at DESC, rowid DESC", ps -> {
            ps.setLong(1, userId);
            List<Inspection> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(inspection(rs));
                }
            }
            return out;
        });
    }

    public synchronized List<Photo> photos(String inspectionId) {
        return sql("SELECT * FROM photos WHERE inspection_id = ? ORDER BY idx", ps -> {
            ps.setString(1, inspectionId);
            List<Photo> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String corrected = rs.getString("corrected_json");
                    int vehicle = rs.getInt("vehicle_visible");
                    Boolean vehicleVisible = rs.wasNull() ? null : vehicle == 1;
                    out.add(new Photo(inspectionId, rs.getInt("idx"), rs.getString("view"), rs.getString("status"),
                            rs.getString("error"), fromJson(rs.getString("damages_json")),
                            corrected == null ? null : fromJson(corrected), vehicleVisible));
                }
            }
            return out;
        });
    }

    public synchronized Optional<byte[]> photoBytes(String inspectionId, int index) {
        try (Stream<Path> files = Files.list(photoDir.resolve(inspectionId))) {
            Optional<Path> file = files.filter(p -> p.getFileName().toString().startsWith(index + ".")).findFirst();
            return file.isPresent() ? Optional.of(Files.readAllBytes(file.get())) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** Replaces what the user sees for one photo; the model's original answer is kept. */
    public synchronized void correct(String inspectionId, int index, long userId, long at, List<Damage> damages) {
        Photo photo = photos(inspectionId).stream().filter(p -> p.index() == index).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no photo " + index));
        String before = toJson(photo.damages());
        String after = toJson(damages);
        sql("UPDATE photos SET corrected_json = ? WHERE inspection_id = ? AND idx = ?", ps -> {
            ps.setString(1, after);
            ps.setString(2, inspectionId);
            ps.setInt(3, index);
            ps.executeUpdate();
            return null;
        });
        sql("INSERT INTO corrections (inspection_id, idx, user_id, at, before_json, after_json) VALUES (?,?,?,?,?,?)",
                ps -> {
                    ps.setString(1, inspectionId);
                    ps.setInt(2, index);
                    ps.setLong(3, userId);
                    ps.setLong(4, at);
                    ps.setString(5, before);
                    ps.setString(6, after);
                    ps.executeUpdate();
                    return null;
                });
    }

    public synchronized int corrections() {
        return sql("SELECT COUNT(*) FROM corrections", ps -> {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    public synchronized void delete(String inspectionId) {
        for (String table : List.of("photos", "corrections")) {
            sql("DELETE FROM " + table + " WHERE inspection_id = ?", ps -> {
                ps.setString(1, inspectionId);
                ps.executeUpdate();
                return null;
            });
        }
        sql("UPDATE inspections SET before_id = NULL WHERE before_id = ?", ps -> {
            ps.setString(1, inspectionId);
            ps.executeUpdate();
            return null;
        });
        sql("DELETE FROM inspections WHERE id = ?", ps -> {
            ps.setString(1, inspectionId);
            ps.executeUpdate();
            return null;
        });
        Path dir = photoDir.resolve(inspectionId);
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException ignored) {
                // files left behind are harmless; the records are gone
            }
        }
    }

    /** Keeps the newest `keep` inspections of the user, deletes the rest. */
    public synchronized void trim(long userId, int keep) {
        List<Inspection> all = list(userId);
        for (int i = keep; i < all.size(); i++) {
            delete(all.get(i).id());
        }
    }

    private Inspection inspection(ResultSet rs) throws SQLException {
        return new Inspection(rs.getString("id"), rs.getLong("user_id"), rs.getLong("created_at"),
                rs.getString("mode"), rs.getString("kind"), rs.getString("title"), rs.getString("before_id"));
    }

    private String toJson(List<Damage> damages) {
        try {
            return mapper.writeValueAsString(damages);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<Damage> fromJson(String json) {
        try {
            return mapper.readValue(json, DAMAGE_LIST);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(PreparedStatement ps) throws SQLException;
    }

    private <T> T sql(String query, Work<T> work) {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement(query)) {
            return work.run(ps);
        } catch (SQLException e) {
            throw new IllegalStateException("database error: " + e.getMessage(), e);
        }
    }
}

package com.cardamage.core.inspect;

import com.cardamage.core.model.Damage;
import com.cardamage.core.model.DamageAssessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InspectionStoreTest {

    private static Damage damage(String type, String part, double x, double y) {
        return new Damage(type, part, "minor", "repair", 0.8, List.of(x, y, 0.1, 0.1));
    }

    private static InspectionStore store() throws Exception {
        return new InspectionStore(Files.createTempDirectory("store"), new ObjectMapper());
    }

    @Test
    void inspectionWithPhotosIsStoredAndReadBack() throws Exception {
        InspectionStore s = store();
        InspectionStore.Inspection i = s.create(7, 1000, "whole", "single", "Моя машина", null);
        s.addPhoto(i.id(), 0, "front", new byte[]{1, 2, 3}, "jpg",
                DamageAssessment.success(List.of(damage("scratch", "door", 0.1, 0.1)), 20, 1));
        s.addPhoto(i.id(), 1, null, new byte[]{4}, "jpg", DamageAssessment.error("no answer", 3));

        assertEquals("Моя машина", s.find(i.id()).orElseThrow().title());
        List<InspectionStore.Photo> photos = s.photos(i.id());
        assertEquals(2, photos.size());
        assertEquals("front", photos.get(0).view());
        assertEquals("scratch", photos.get(0).damages().get(0).getDamageType());
        assertEquals(List.of(0.1, 0.1, 0.1, 0.1), photos.get(0).damages().get(0).getBoundingBox());
        assertEquals("error", photos.get(1).status());
        assertTrue(photos.get(1).damages().isEmpty());
        assertArrayEquals(new byte[]{1, 2, 3}, s.photoBytes(i.id(), 0).orElseThrow());
    }

    @Test
    void correctionKeepsTheModelAnswerAndIsLogged() throws Exception {
        InspectionStore s = store();
        InspectionStore.Inspection i = s.create(7, 1000, "whole", "single", null, null);
        s.addPhoto(i.id(), 0, null, new byte[]{1}, "jpg", DamageAssessment.success(List.of(
                damage("scratch", "door", 0.1, 0.1), damage("crack", "bumper", 0.5, 0.5)), 20, 1));
        s.correct(i.id(), 0, 7, 2000, List.of(damage("crack", "bumper", 0.5, 0.5)));

        InspectionStore.Photo p = s.photos(i.id()).get(0);
        assertTrue(p.edited());
        assertEquals(1, p.damages().size());
        assertEquals(2, p.modelDamages().size());
        assertEquals(1, s.corrections());
    }

    @Test
    void listIsPerUserNewestFirstAndTrimDeletesOldest() throws Exception {
        InspectionStore s = store();
        InspectionStore.Inspection a = s.create(1, 100, "whole", "single", "a", null);
        s.create(1, 200, "whole", "single", "b", null);
        s.create(1, 300, "whole", "single", "c", null);
        s.create(2, 400, "whole", "single", "other user", null);
        s.addPhoto(a.id(), 0, null, new byte[]{1}, "jpg", DamageAssessment.success(List.of(), 0, 1));

        assertEquals(List.of("c", "b", "a"), s.list(1).stream().map(InspectionStore.Inspection::title).toList());
        s.trim(1, 2);
        assertEquals(List.of("c", "b"), s.list(1).stream().map(InspectionStore.Inspection::title).toList());
        assertTrue(s.find(a.id()).isEmpty());
        assertTrue(s.photos(a.id()).isEmpty());
        assertEquals(1, s.list(2).size());
    }

    @Test
    void deletingAPickupInspectionUnlinksTheReturnOne() throws Exception {
        InspectionStore s = store();
        InspectionStore.Inspection before = s.create(1, 100, "whole", "before", null, null);
        InspectionStore.Inspection after = s.create(1, 200, "whole", "after", null, before.id());
        s.delete(before.id());
        assertNull(s.find(after.id()).orElseThrow().beforeId());
    }

    @Test
    void comparisonFindsOnlyNewDamagesOfTheSameView() throws Exception {
        InspectionStore.Photo beforeFront = new InspectionStore.Photo("b", 0, "front", "success", null,
                List.of(damage("scratch", "bumper", 0.20, 0.50)), null);
        InspectionStore.Photo afterFront = new InspectionStore.Photo("a", 0, "front", "success", null, List.of(
                damage("scratch", "bumper", 0.25, 0.52),   // same scratch, framed a bit differently
                damage("crack", "bumper", 0.20, 0.50),     // other type at the same place: new
                damage("scratch", "bumper", 0.70, 0.50)),  // same type, far away: new
                null);
        InspectionStore.Photo afterLeft = new InspectionStore.Photo("a", 1, "left", "success", null,
                List.of(damage("scratch", "door", 0.3, 0.3)), null);

        List<BeforeAfterComparator.Finding> f = BeforeAfterComparator.compare(List.of(beforeFront),
                List.of(afterFront, afterLeft));
        assertEquals(List.of(BeforeAfterComparator.Status.EXISTING, BeforeAfterComparator.Status.NEW,
                BeforeAfterComparator.Status.NEW, BeforeAfterComparator.Status.NOT_COMPARED),
                f.stream().map(BeforeAfterComparator.Finding::status).toList());
    }

    @Test
    void comparisonUsesTheUsersCorrections() throws Exception {
        InspectionStore.Photo before = new InspectionStore.Photo("b", 0, "rear", "success", null,
                List.of(), List.of(damage("crack", "bumper", 0.4, 0.4)));   // the user added nothing but corrected
        InspectionStore.Photo after = new InspectionStore.Photo("a", 0, "rear", "success", null,
                List.of(damage("crack", "bumper", 0.42, 0.41)), null);
        assertEquals(BeforeAfterComparator.Status.EXISTING,
                BeforeAfterComparator.compare(List.of(before), List.of(after)).get(0).status());
    }

    @Test
    void vehicleVisibilityIsStored() throws Exception {
        InspectionStore s = store();
        InspectionStore.Inspection i = s.create(7, 1000, "relook", "single", null, null);
        DamageAssessment noCar = DamageAssessment.success(List.of(), 0, 1);
        noCar.setVehicleVisible(false);
        s.addPhoto(i.id(), 0, null, new byte[]{1}, "jpg", noCar);
        s.addPhoto(i.id(), 1, null, new byte[]{1}, "jpg", DamageAssessment.success(List.of(), 0, 1));
        assertEquals(Boolean.FALSE, s.photos(i.id()).get(0).vehicleVisible());
        assertNull(s.photos(i.id()).get(1).vehicleVisible());
    }

    @Test
    void databaseOfTheFirstVersionIsUpgraded() throws Exception {
        Path dir = Files.createTempDirectory("store");
        try (java.sql.Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dir.resolve("inspections.db"));
             java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE photos (inspection_id TEXT NOT NULL, idx INTEGER NOT NULL, view TEXT, "
                    + "status TEXT NOT NULL, error TEXT, damages_json TEXT NOT NULL, corrected_json TEXT, "
                    + "PRIMARY KEY (inspection_id, idx))");
            st.execute("INSERT INTO photos VALUES ('old', 0, NULL, 'success', NULL, '[]', NULL)");
        }
        InspectionStore s = new InspectionStore(dir, new ObjectMapper());
        assertNull(s.photos("old").get(0).vehicleVisible());
        new InspectionStore(dir, new ObjectMapper());   // opening again must not fail
    }
}

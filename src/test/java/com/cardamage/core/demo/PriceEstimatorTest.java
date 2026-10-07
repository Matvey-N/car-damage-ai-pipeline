package com.cardamage.core.demo;

import com.cardamage.core.model.Damage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PriceEstimatorTest {

    private final PriceEstimator prices = PriceEstimator.fromClasspath(new ObjectMapper());

    private static Damage damage(String part, String action) {
        return new Damage("crack", part, "moderate", action, 0.8, null);
    }

    @Test
    void tableCoversEveryPartAndAction() {
        for (String part : com.cardamage.core.pipeline.ResponseFormatValidator.PARTS) {
            for (String action : com.cardamage.core.pipeline.ResponseFormatValidator.ACTIONS) {
                PriceEstimator.Range r = prices.priceOf(damage(part, action));
                assertNotNull(r, part + "/" + action);
                assertTrue(r.min() > 0 && r.min() <= r.max(), part + "/" + action);
            }
        }
    }

    @Test
    void unknownPartHasNoPrice() {
        assertNull(prices.priceOf(damage("spoiler", "repair")));
    }

    @Test
    void aPartIsReplacedOnlyOnceButRepairsAddUp() {
        PriceEstimator.Range one = prices.priceOf(damage("bumper", "replacement"));
        assertEquals(one, prices.total(List.of(damage("bumper", "replacement"), damage("bumper", "replacement"))));

        PriceEstimator.Range repair = prices.priceOf(damage("door", "repair"));
        assertEquals(new PriceEstimator.Range(2 * repair.min(), 2 * repair.max()),
                prices.total(List.of(damage("door", "repair"), damage("door", "repair"))));
    }

    @Test
    void emptyListCostsNothing() {
        assertEquals(new PriceEstimator.Range(0, 0), prices.total(List.of()));
    }
}

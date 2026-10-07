package com.cardamage.core.demo;

import com.cardamage.core.model.Damage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * DEMO only: a rough price range from a small reference table
 * (src/main/resources/demo/price_table.json). The prices are invented for
 * illustration; this is not part of the research and is not evaluated.
 *
 * Price of one damage = table[part][action]. A part that appears in
 * several damages with action "replacement" is counted once (a part is
 * replaced once); repairs are counted per damage.
 */
public class PriceEstimator {

    public static final String RESOURCE = "/demo/price_table.json";

    /** Inclusive price range in the table's currency. */
    public record Range(int min, int max) {
        public Range plus(Range other) {
            return new Range(min + other.min, max + other.max);
        }
    }

    private final JsonNode prices;
    private final String currency;

    public PriceEstimator(JsonNode table) {
        this.prices = table.path("prices");
        this.currency = table.path("currency").asText("EUR");
        if (!prices.isObject() || prices.isEmpty()) {
            throw new IllegalArgumentException("price table has no 'prices' object");
        }
    }

    public static PriceEstimator fromClasspath(ObjectMapper mapper) {
        try (InputStream in = PriceEstimator.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("price table not found: " + RESOURCE);
            }
            return new PriceEstimator(mapper.readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read price table " + RESOURCE, e);
        }
    }

    public String currency() {
        return currency;
    }

    /** @return price range for one damage, or null if the table has no entry for it */
    public Range priceOf(Damage damage) {
        JsonNode range = prices.path(String.valueOf(damage.getPart())).path(String.valueOf(damage.getAction()));
        if (!range.isArray() || range.size() != 2) {
            return null;
        }
        return new Range(range.get(0).asInt(), range.get(1).asInt());
    }

    public Range total(List<Damage> damages) {
        Range total = new Range(0, 0);
        java.util.Set<String> replacedParts = new java.util.HashSet<>();
        for (Damage d : damages) {
            Range r = priceOf(d);
            if (r == null) {
                continue;
            }
            if ("replacement".equals(d.getAction()) && !replacedParts.add(d.getPart())) {
                continue;
            }
            total = total.plus(r);
        }
        return total;
    }
}

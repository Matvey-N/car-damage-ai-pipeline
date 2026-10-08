package com.cardamage.core.model;

import java.util.Map;

/**
 * Russian names and colours of the answer values, shared by the bot and the PDF
 * report (the Mini App page has the same table in JavaScript).
 * Covers both taxonomies: the benchmark one is a subset of the general one.
 */
public final class Labels {

    public static final Map<String, String> TYPE = Map.of(
            "glass_shatter", "Повреждение стекла", "lamp_broken", "Разбитая фара/фонарь",
            "crack", "Трещина", "scratch", "Царапина", "dent", "Вмятина",
            "paint_chip", "Скол/отслоение краски", "rust", "Ржавчина", "broken_part", "Сломанная/оторванная деталь");

    /** RGB colours of the boxes, one per damage type. */
    public static final Map<String, Integer> TYPE_COLOR = Map.of(
            "glass_shatter", 0x2f8fde, "lamp_broken", 0xe08a1e, "crack", 0xb24bd6, "scratch", 0x2fae6a,
            "dent", 0xd6455d, "paint_chip", 0x17a2a2, "rust", 0x9a5b2a, "broken_part", 0x55606e);

    public static final Map<String, String> PART = Map.ofEntries(
            Map.entry("bumper", "бампер"), Map.entry("door", "дверь"), Map.entry("light", "фара/фонарь"),
            Map.entry("window", "боковое стекло"), Map.entry("windshield", "лобовое/заднее стекло"),
            Map.entry("hood", "капот"), Map.entry("fender", "крыло"), Map.entry("mirror", "зеркало"),
            Map.entry("wheel", "колесо/диск"), Map.entry("other", "другое"), Map.entry("trunk", "багажник"),
            Map.entry("roof", "крыша"), Map.entry("grille", "решётка радиатора"), Map.entry("sill", "порог"));

    public static final Map<String, String> SEVERITY = Map.of("minor", "лёгкое", "moderate", "среднее", "severe", "тяжёлое");

    public static final Map<String, String> ACTION = Map.of("repair", "ремонт", "replacement", "замена");

    private Labels() {
    }

    public static String type(String key) {
        return TYPE.getOrDefault(key, key);
    }

    public static String part(String key) {
        return PART.getOrDefault(key, key);
    }

    public static String severity(String key) {
        return SEVERITY.getOrDefault(key, key);
    }

    public static String action(String key) {
        return ACTION.getOrDefault(key, key);
    }

    public static int color(String type) {
        return TYPE_COLOR.getOrDefault(type, 0x888888);
    }
}

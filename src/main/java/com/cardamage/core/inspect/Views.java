package com.cardamage.core.inspect;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Standard views of guided capture. A photo taken for a view carries its key,
 * so "before" and "after" photos of the same view can be compared.
 * "extra" = an additional close-up; it is analyzed but not compared.
 */
public final class Views {

    public static final String EXTRA = "extra";

    /** key -> Russian name, in the order the user is guided through them. */
    public static final Map<String, String> NAMES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("front", "Спереди");
        m.put("front_left", "Спереди слева (угол)");
        m.put("left", "Левый бок");
        m.put("rear_left", "Сзади слева (угол)");
        m.put("rear", "Сзади");
        m.put("rear_right", "Сзади справа (угол)");
        m.put("right", "Правый бок");
        m.put("front_right", "Спереди справа (угол)");
        m.put(EXTRA, "Дополнительное фото");
        NAMES = java.util.Collections.unmodifiableMap(m);
    }

    private Views() {
    }

    public static boolean isKnown(String view) {
        return view != null && NAMES.containsKey(view);
    }

    public static String name(String view) {
        return view == null ? "Фото" : NAMES.getOrDefault(view, "Фото");
    }
}

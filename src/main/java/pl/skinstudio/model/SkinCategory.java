package pl.skinstudio.model;

import java.util.Locale;

/** Typ skina — BellItems (broń/zbroja), BellMount (mounty), ogólne. */
public enum SkinCategory {
    WEAPON,
    ARMOR,
    MOUNT,
    TOOL,
    UNKNOWN;

    public static SkinCategory fromConfig(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return UNKNOWN;
        }
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT);
    }
}

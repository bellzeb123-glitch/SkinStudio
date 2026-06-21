package pl.skinstudio.util;

import java.util.Collections;
import java.util.Map;
import java.util.logging.Logger;

/**
 * SPIKE — legacy CustomModelData → item_model (MC 1.21.4+).
 * <p>
 * Not part of MVP. ItemAdder packs that still use CMD overlays require either:
 * <ol>
 *   <li>Re-export via {@code /iazip} with modern item_model (preferred), or</li>
 *   <li>Offline conversion: read {@code assets/minecraft/models/item/*.json} overrides
 *       from the merged RPM zip, map {@code custom_model_data} predicates to
 *       {@code assets/&lt;ns&gt;/items/&lt;id&gt;.json} entries, rewrite BellItems/SkinStudio
 *       skin definitions with the new keys.</li>
 * </ol>
 * Blockers: CMD packs often share one base item with hundreds of predicates;
 * item_model requires one JSON per model. A full converter needs ItemsAdder YAML
 * cross-reference ({@code resource.model_id}) — planned P1 in skinstudio-integration.md.
 */
public final class LegacyModelConverter {

    private LegacyModelConverter() {}

    /**
     * Placeholder — returns empty map until P1 YAML + pack parser is implemented.
     *
     * @param mergedPackZip absolute path to RPM mixer output zip
     * @return CMD predicate → item_model key (never null)
     */
    public static Map<String, String> probeLegacyMappings(String mergedPackZip, Logger log) {
        log.fine("[LegacyModelConverter] SPIKE only — use /iazip + /skintoken scan for modern IA packs.");
        return Collections.emptyMap();
    }
}

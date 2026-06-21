package pl.skinstudio.converter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Wynik konwersji jednego packa ze skina.
 */
public record SkinConverterResult(
    String sourceName,
    int skinsAdded,
    int skinsSkipped,
    List<String> skinIds,
    List<String> errors,
    boolean success
) {
    public static SkinConverterResult failed(String source, String error) {
        return new SkinConverterResult(source, 0, 0, List.of(), List.of(error), false);
    }

    public static SkinConverterResult ok(String source, int added, int skipped, List<String> ids) {
        return new SkinConverterResult(source, added, skipped, ids, List.of(), true);
    }

    public static List<String> summarize(List<SkinConverterResult> results, Logger log) {
        List<String> lines = new ArrayList<>();
        int totalAdded = 0;
        for (SkinConverterResult r : results) {
            if (r.success()) {
                totalAdded += r.skinsAdded();
                if (r.skinsAdded() > 0) {
                    lines.add(r.sourceName() + ": +" + r.skinsAdded() + " skinów");
                } else if (r.skinsSkipped() > 0) {
                    lines.add(r.sourceName() + ": już w config (" + r.skinsSkipped() + " pominiętych) — pack przebudowany");
                } else {
                    lines.add(r.sourceName() + ": przetworzono — pack przebudowany");
                }
                if (log != null) {
                    log.info("Konwerter: " + r.sourceName() + " → +" + r.skinsAdded() + " skinów");
                }
            } else {
                lines.add(r.sourceName() + ": BŁĄD — " + String.join("; ", r.errors()));
                if (log != null) log.warning("Konwerter błąd " + r.sourceName() + ": " + r.errors());
            }
        }
        if (totalAdded > 0 && log != null) {
            log.info("Konwerter łącznie: " + totalAdded + " nowych skinów");
        }
        return lines;
    }
}

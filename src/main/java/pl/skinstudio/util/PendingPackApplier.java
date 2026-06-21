package pl.skinstudio.util;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Stosuje oczekujące *.skinstudio-fixed.zip gdy RPM nie pozwolił nadpisać ZIP w mixer.
 */
public final class PendingPackApplier {

    private static final String SUFFIX = ".skinstudio-fixed.zip";

    private PendingPackApplier() {}

    public record ApplyResult(int applied, List<String> appliedNames, List<String> failed) {}

    /** Szuka mixer/ w plugins/ i podmienia wszystkie oczekujące fixed packi. */
    public static ApplyResult applyAll(File pluginsFolder, String mixerRelativePath, Logger log) {
        File mixer = new File(pluginsFolder, mixerRelativePath);
        return applyInDirectory(mixer, log);
    }

    public static ApplyResult applyInDirectory(File mixerDir, Logger log) {
        if (mixerDir == null || !mixerDir.isDirectory()) {
            return new ApplyResult(0, List.of(), List.of());
        }

        File[] pending = mixerDir.listFiles((dir, name) ->
            name.toLowerCase().endsWith(SUFFIX));
        if (pending == null || pending.length == 0) {
            return new ApplyResult(0, List.of(), List.of());
        }

        List<String> applied = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        for (File fixed : pending) {
            String fixedName = fixed.getName();
            // Dark_Queen.zip.skinsstudio-fixed.zip → Dark_Queen.zip
            String targetName = fixedName.substring(0, fixedName.length() - SUFFIX.length());
            File target = new File(mixerDir, targetName);

            try {
                if (target.exists() && !target.delete()) {
                    failed.add(targetName + ": nie można usunąć starego pliku");
                    continue;
                }
                Files.move(fixed.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
                applied.add(targetName);
                log.info("Podmieniono pack w mixer: " + targetName + " (z " + fixedName + ")");
            } catch (IOException e) {
                failed.add(targetName + ": " + e.getMessage());
                log.warning("Nie udało się podmienić " + targetName + ": " + e.getMessage());
            }
        }

        return new ApplyResult(applied.size(), applied, failed);
    }
}

package pl.skinstudio.util;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PackMcmetaUtil {

    private static final Pattern PACK_FORMAT =
        Pattern.compile("\"pack_format\"\\s*:\\s*(\\d+(?:\\.\\d+)?)");

    private PackMcmetaUtil() {}

    public static String readFormat(String mcmetaJson) {
        if (mcmetaJson == null) return null;
        Matcher m = PACK_FORMAT.matcher(mcmetaJson);
        return m.find() ? m.group(1) : null;
    }

    /** Ustawia pack_format na wartość docelową; zwraca null gdy już OK. */
    public static byte[] fixFormatIfNeeded(byte[] mcmetaBytes, int targetFormat) {
        if (mcmetaBytes == null || mcmetaBytes.length == 0) {
            return defaultMcmeta(targetFormat).getBytes(StandardCharsets.UTF_8);
        }
        String json = new String(mcmetaBytes, StandardCharsets.UTF_8);
        String current = readFormat(json);
        String target = String.valueOf(targetFormat);
        if (target.equals(current)) return null;

        if (current != null) {
            String updated = PACK_FORMAT.matcher(json).replaceFirst("\"pack_format\":" + target);
            return updated.getBytes(StandardCharsets.UTF_8);
        }
        return defaultMcmeta(targetFormat).getBytes(StandardCharsets.UTF_8);
    }

    public static String defaultMcmeta(int packFormat) {
        return builtMcmeta(packFormat);
    }

    /** Unikalny opis wymusza nowy hash packa u RPM (klient pobiera ponownie). */
    public static String builtMcmeta(int packFormat) {
        long stamp = System.currentTimeMillis();
        return "{\"pack\":{\"pack_format\":" + packFormat
            + ",\"description\":\"SkinStudio skins #" + stamp + "\"}}";
    }
}

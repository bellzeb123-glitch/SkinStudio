package pl.skinstudio.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import pl.skinstudio.SkinStudio;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class LangManager {

    private final SkinStudio plugin;
    private YamlConfiguration lang;
    private String language;

    public LangManager(SkinStudio plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        language = plugin.getConfig().getString("language", "en");
        plugin.saveResource("lang/en.yml", false);
        plugin.saveResource("lang/pl.yml", false);

        String resourcePath = "lang/" + language + ".yml";
        InputStream baseStream = plugin.getResource(resourcePath);
        if (baseStream == null) baseStream = plugin.getResource("lang/en.yml");

        YamlConfiguration base = YamlConfiguration.loadConfiguration(
            new InputStreamReader(baseStream, StandardCharsets.UTF_8));

        File customFile = new File(plugin.getDataFolder(), resourcePath);
        if (customFile.exists()) {
            YamlConfiguration custom = YamlConfiguration.loadConfiguration(customFile);
            for (String key : custom.getKeys(true)) {
                if (!custom.isConfigurationSection(key)) {
                    base.set(key, custom.get(key));
                }
            }
        }
        this.lang = base;
    }

    public String getRaw(String key, Object... replacements) {
        String val = lang.getString(key, key);
        for (int i = 0; i < replacements.length - 1; i += 2) {
            val = val.replace("{" + replacements[i] + "}", String.valueOf(replacements[i + 1]));
        }
        return val;
    }

    public Component component(String key, Object... replacements) {
        return colorize(getRaw("prefix") + getRaw(key, replacements));
    }

    public Component raw(String key, Object... replacements) {
        return colorize(getRaw(key, replacements));
    }

    public List<String> getList(String key) {
        List<String> list = lang.getStringList(key);
        return list.isEmpty() ? List.of(key) : list;
    }

    private Component colorize(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}

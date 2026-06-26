package pl.skinstudio.integration;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import pl.bell.hub.api.ActionDef;
import pl.bell.hub.api.ActionField;
import pl.bell.hub.api.ActionResult;
import pl.bell.hub.api.Actor;
import pl.bell.hub.api.BellModule;
import pl.bell.hub.api.HubAction;
import pl.bell.hub.api.MapFilter;
import pl.bell.hub.api.MapMarker;
import pl.bell.hub.api.Stat;
import pl.skinstudio.SkinStudio;

import java.util.List;
import java.util.Map;

/** SkinStudio → panel BellHub (katalog, tokeny, pack pipeline). */
public final class BellHubModule implements BellModule {

    private final SkinStudio plugin;
    private final SkinStudioAdmin admin;

    private BellHubModule(SkinStudio plugin) {
        this.plugin = plugin;
        this.admin = new SkinStudioAdmin(plugin);
    }

    public static void register(SkinStudio plugin) {
        Bukkit.getServicesManager().register(
                BellModule.class, new BellHubModule(plugin), plugin, ServicePriority.Normal);
    }

    @Override public String id() { return "skinstudio"; }
    @Override public String displayName() { return "SkinStudio"; }
    @Override public String icon() { return "palette"; }
    @Override public String permission() { return "bellhub.module.skinstudio"; }

    @Override
    public List<Stat> dashboard() {
        try {
            int skins = plugin.getSkinConfig().getAllSkins().size();
            int tiers = plugin.getAdminGUI().getTierCount();
            int inbox = admin.inboxPendingCount();
            String lang = plugin.getLang().getLanguage();
            return List.of(
                    new Stat("Skiny", Integer.toString(skins), "gold"),
                    new Stat("Tiery", Integer.toString(tiers), "cyan"),
                    new Stat("Inbox", Integer.toString(inbox), inbox > 0 ? "orange" : "silver"),
                    new Stat("Język", lang.toUpperCase(), "silver"));
        } catch (Exception e) {
            return List.of(new Stat("SkinStudio", "?", "silver"));
        }
    }

    @Override
    public List<MapMarker> markers(MapFilter filter) {
        return List.of();
    }

    @Override
    public String view(String viewId, Map<String, String> params) {
        return switch (viewId) {
            case "overview" -> admin.viewOverview();
            case "skins" -> admin.viewSkins(params);
            case "skin" -> admin.viewSkin(params.get("id"));
            case "tiers" -> admin.viewTiers();
            case "settings" -> admin.viewSettings();
            default -> "{}";
        };
    }

    @Override
    public ActionResult invoke(HubAction action, Actor actor) {
        return admin.invoke(action, actor);
    }

    @Override
    public List<ActionDef> actions() {
        return List.of(
                ActionDef.of("token.give", "Daj token skina", "Tokeny",
                        ActionField.text("player", "Nick gracza"),
                        ActionField.text("skinId", "ID skina"),
                        ActionField.number("amount", "Ilość (1–64)")),
                ActionDef.of("token.giveRemove", "Daj token zmiany", "Tokeny",
                        ActionField.text("player", "Nick gracza"),
                        ActionField.number("amount", "Ilość (1–64)")),
                ActionDef.of("token.giveItem", "Daj przedmiot ze skinem", "Tokeny",
                        ActionField.text("player", "Nick gracza"),
                        ActionField.text("skinId", "ID skina")),
                ActionDef.of("pack.convert", "Konwertuj inbox", "Pack"),
                ActionDef.of("pack.build", "Zbuduj SkinStudio-skins.zip", "Pack"),
                ActionDef.of("pack.apply", "Zastosuj pending w mixer", "Pack"),
                ActionDef.of("pack.repush", "Wymuś push packa graczom", "Pack"),
                ActionDef.of("import.oraxen", "Import z Oraxen/source", "Import",
                        ActionField.text("namespace", "Namespace (all)"),
                        ActionField.bool("overwrite", "Nadpisz istniejące")),
                ActionDef.of("settings.language", "Język pluginu", "Ustawienia",
                        ActionField.select("value", "Język", List.of("pl", "en"))),
                ActionDef.of("settings.setBoolean", "Przełącznik config", "Ustawienia",
                        ActionField.text("key", "Klucz"),
                        ActionField.bool("enabled", "Włączone")),
                ActionDef.of("settings.reload", "Przeładuj katalog", "Ustawienia")
        );
    }
}

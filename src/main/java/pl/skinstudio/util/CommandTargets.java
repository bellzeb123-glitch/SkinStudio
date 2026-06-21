package pl.skinstudio.util;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.List;

/** Rozwiązywanie graczy z nazw i selektorów MC ({@code @s}, {@code @p}, …). */
public final class CommandTargets {

    private CommandTargets() {}

    public static Player resolvePlayer(CommandSender sender, String token) {
        if (token == null || token.isBlank()) return null;

        if ("@s".equalsIgnoreCase(token)) {
            return sender instanceof Player p ? p : null;
        }

        Player byName = Bukkit.getPlayer(token);
        if (byName != null) return byName;

        if (token.startsWith("@")) {
            try {
                List<Entity> entities = Bukkit.selectEntities(sender, token);
                for (Entity entity : entities) {
                    if (entity instanceof Player player) return player;
                }
            } catch (IllegalArgumentException ignored) {
                // nieprawidłowy selektor
            }
        }
        return null;
    }
}

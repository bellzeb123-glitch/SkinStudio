package pl.skinstudio.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.gui.AdminGUI;
import pl.skinstudio.gui.SkinStudioGUI;

import java.util.List;

public class SkinStudioCommand implements CommandExecutor, TabCompleter {

    private final SkinStudioGUI gui;
    private final AdminGUI adminGui;

    public SkinStudioCommand(SkinStudioGUI gui, AdminGUI adminGui) {
        this.gui = gui;
        this.adminGui = adminGui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(SkinStudio.getInstance().getLang().component("command.player-only"));
            return true;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("skinstudio.admin")) {
                player.sendMessage(SkinStudio.getInstance().getLang().component("command.no-permission"));
                return true;
            }
            adminGui.openFor(player);
            return true;
        }

        if (!player.hasPermission("skinstudio.use")) {
            player.sendMessage(SkinStudio.getInstance().getLang().component("command.no-permission"));
            return true;
        }

        gui.openFor(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission("skinstudio.admin")) {
            if ("admin".startsWith(args[0].toLowerCase())) return List.of("admin");
        }
        return List.of();
    }
}

package pl.skinstudio.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import pl.skinstudio.SkinStudio;
import pl.skinstudio.gui.SkinStudioGUI;

public class SkinStudioCommand implements CommandExecutor {

    private final SkinStudioGUI gui;

    public SkinStudioCommand(SkinStudio plugin, SkinStudioGUI gui) {
        this.gui = gui;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Ta komenda jest tylko dla graczy!");
            return true;
        }

        if (!player.hasPermission("skinstudio.use")) {
            player.sendMessage("§cNie masz uprawnień do tej komendy!");
            return true;
        }

        gui.openFor(player);
        return true;
    }
}

package coldplay.command.commands;

import coldplay.ColdPlay;
import coldplay.command.Command;
import coldplay.command.CommandManager;
import coldplay.friend.FriendManager;
import coldplay.util.ChatUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;

import java.util.Collection;
import java.util.Set;

public class FriendCommand extends Command {

    public FriendCommand() {
        super("friend", new String[]{"f"});
    }

    @Override
    public void execute(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String sub = args[0];
        if (sub.equalsIgnoreCase("add")) {
            changeFriend(args, true);
        } else if (sub.equalsIgnoreCase("remove") || sub.equalsIgnoreCase("rem") || sub.equalsIgnoreCase("del")) {
            changeFriend(args, false);
        } else if (sub.equalsIgnoreCase("clear")) {
            clear();
        } else if (sub.equalsIgnoreCase("team")) {
            team();
        } else if (sub.equalsIgnoreCase("list")) {
            list();
        } else if (sub.equalsIgnoreCase("color")) {
            color(args);
        } else {
            usage();
        }
    }

    private void changeFriend(String[] args, boolean add) {
        String verb = add ? "add" : "remove";
        if (args.length < 2) {
            ChatUtil.info("Usage: " + CommandManager.PREFIX + "friend " + verb + " <name>");
            return;
        }
        String name = args[1];
        boolean changed = add ? FriendManager.getInstance().add(name) : FriendManager.getInstance().remove(name);
        if (changed) {
            ColdPlay.getInstance().saveConfig();
            if (add) {
                ChatUtil.success("Added " + name + " to friend list!");
            } else {
                ChatUtil.error("Removed " + name + " from friend list!");
            }
        } else {
            ChatUtil.info(name + (add ? " is already a friend." : " is not a friend."));
        }
    }

    private void clear() {
        int removed = FriendManager.getInstance().clear();
        if (removed > 0) {
            ColdPlay.getInstance().saveConfig();
        }
        ChatUtil.info("Cleared " + removed + " friend" + (removed == 1 ? "" : "s") + ".");
    }

    private void team() {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        if (player == null || world == null) {
            ChatUtil.error("You must be in a world to use this.");
            return;
        }
        Scoreboard scoreboard = world.getScoreboard();
        ScorePlayerTeam team = scoreboard != null ? scoreboard.getPlayersTeam(player.getName()) : null;
        if (team == null) {
            ChatUtil.info("You are not on a team.");
            return;
        }
        Collection<String> members = team.getMembershipCollection();
        int added = 0;
        for (String member : members) {
            if (member == null || member.equalsIgnoreCase(player.getName())) {
                continue;
            }
            if (FriendManager.getInstance().add(member)) {
                added++;
            }
        }
        if (added > 0) {
            ColdPlay.getInstance().saveConfig();
        }
        ChatUtil.success("Added " + added + " teammate" + (added == 1 ? "" : "s")
                + " from team '" + team.getTeamName() + "'.");
    }

    private void list() {
        Set<String> friends = FriendManager.getInstance().getFriends();
        if (friends.isEmpty()) {
            ChatUtil.info("You have no friends added.");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (String name : friends) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            // 1.8.9 chat can't render arbitrary RGB, so the colour is shown as hex text.
            sb.append(name).append(String.format(" #%06X", FriendManager.getInstance().getColor(name) & 0xFFFFFF));
        }
        ChatUtil.info("Friends (" + friends.size() + "): " + sb);
    }

    private void color(String[] args) {
        if (args.length < 3) {
            ChatUtil.info("Usage: " + CommandManager.PREFIX + "friend color <name> <hex>");
            return;
        }
        String name = args[1];
        String hex = args[2].startsWith("#") ? args[2].substring(1) : args[2];
        int rgb;
        try {
            rgb = Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            ChatUtil.error("'" + args[2] + "' is not a hex colour (expected RRGGBB).");
            return;
        }
        if (FriendManager.getInstance().setColor(name, rgb)) {
            ColdPlay.getInstance().saveConfig();
            ChatUtil.success("Set " + name + "'s colour to #" + String.format("%06X", rgb & 0xFFFFFF) + ".");
        } else {
            ChatUtil.info(name + " is not a friend.");
        }
    }

    private void usage() {
        String p = CommandManager.PREFIX + "friend ";
        ChatUtil.info("Usage: " + p + "add <name> | " + p + "remove <name> | "
                + p + "clear | " + p + "team | " + p + "list | " + p + "color <name> <hex>");
    }
}

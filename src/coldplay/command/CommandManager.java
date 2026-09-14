package coldplay.command;

import coldplay.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Dispatches prefixed chat commands from EntityPlayerSP.sendChatMessage before the chat packet is queued. */
public class CommandManager {

    public static final String PREFIX = ".";

    private final List<Command> commands = new ArrayList<Command>();

    public CommandManager() {
        registerCommands();
    }

    private void registerCommands() {
        commands.add(new coldplay.command.commands.FriendCommand());
        commands.add(new coldplay.command.commands.HelpCommand());
    }

    /** Returns true when the line was consumed as a command and must not reach the server. */
    public boolean dispatch(String raw) {
        if (raw == null) {
            return false;
        }
        String trimmed = raw.trim();
        if (trimmed.length() <= PREFIX.length() || !trimmed.startsWith(PREFIX)) {
            return false;
        }

        String[] parts = trimmed.substring(PREFIX.length()).split("\\s+");
        if (parts.length == 0 || parts[0].isEmpty()) {
            return false;
        }

        Command command = find(parts[0]);
        if (command == null) {
            if (!Character.isLetter(parts[0].charAt(0))) {
                return false;
            }
            // Unknown but command-shaped lines are swallowed so a typo never reaches public chat.
            ChatUtil.error("Unknown command: " + PREFIX + parts[0] + " (type " + PREFIX + "help)");
            return true;
        }

        String[] args = Arrays.copyOfRange(parts, 1, parts.length);
        try {
            command.execute(args);
        } catch (Throwable t) {
            ChatUtil.error("Command '" + command.getName() + "' failed: " + t.getMessage());
        }
        return true;
    }

    private Command find(String token) {
        for (Command command : commands) {
            if (command.matches(token)) {
                return command;
            }
        }
        return null;
    }
}

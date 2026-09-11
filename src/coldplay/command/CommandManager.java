package coldplay.command;

import coldplay.util.ChatUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Dispatches prefixed commands from {@code EntityPlayerSP.sendChatMessage} on the main thread,
 * before the chat packet is queued.
 *
 * <p>Dispatch consumes any command-shaped line: a first token starting with a letter is never sent to
 * the server, preventing misspelled or failed commands from reaching public chat.
 * Lines such as {@code "..."} and {@code ".123"} remain ordinary chat.
 */
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

    /**
     * Handles a chat line. Returns {@code true} if it was a client command (and must NOT be sent to the
     * server), {@code false} if it should be sent as a normal chat message.
     */
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

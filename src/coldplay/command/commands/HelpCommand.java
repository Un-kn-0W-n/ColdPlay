package coldplay.command.commands;

import coldplay.command.Command;
import coldplay.util.ChatUtil;

public class HelpCommand extends Command {

    public HelpCommand() {
        super("help", new String[]{"?", "commands"});
    }

    @Override
    public void execute(String[] args) {
        ChatUtil.info("Commands (prefix \".\"):");
        ChatUtil.info(".help - this list");
        ChatUtil.info(".friend - manage friends (run .friend for usage)");
        ChatUtil.info("Open the GUI with Right Shift.");
        ChatUtil.info("In the GUI: left-click toggles a module, right-click opens its settings, middle-click binds a key.");
    }
}

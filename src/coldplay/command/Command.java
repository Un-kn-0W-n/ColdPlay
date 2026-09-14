package coldplay.command;

/** A chat command; execute receives the tokens after the name or alias. */
public abstract class Command {

    private final String name;
    private final String[] aliases;

    protected Command(String name, String[] aliases) {
        this.name = name;
        this.aliases = aliases != null ? aliases : new String[0];
    }

    public String getName() {
        return name;
    }

    public boolean matches(String token) {
        if (token == null) {
            return false;
        }
        if (token.equalsIgnoreCase(name)) {
            return true;
        }
        for (String alias : aliases) {
            if (token.equalsIgnoreCase(alias)) {
                return true;
            }
        }
        return false;
    }

    public abstract void execute(String[] args);
}

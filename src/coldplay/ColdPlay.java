package coldplay;

import coldplay.command.CommandManager;
import coldplay.config.ConfigManager;
import coldplay.input.InputManager;
import coldplay.hud.HudState;
import coldplay.module.ModuleManager;

public class ColdPlay {
    public static final String NAME = "ColdPlay";
    public static final String VERSION = "1.1.0";

    private static ColdPlay instance;

    private ModuleManager moduleManager;
    private EventHandler eventHandler;
    private ConfigManager configManager;
    private CommandManager commandManager;
    private final HudState hudState = new HudState();
    private boolean shutdown;

    public static ColdPlay getInstance() {
        if (instance == null) {
            instance = new ColdPlay();
        }
        return instance;
    }

    /** Called once from Minecraft.startGame(). */
    public void init() {
        eventHandler = new EventHandler();
        // Brokers register before modules. At equal priority, GameStateTracker must precede BedTracker.
        eventHandler.register(coldplay.broker.SlotGuard.getInstance());
        eventHandler.register(coldplay.broker.ActionGuard.getInstance());
        eventHandler.register(coldplay.broker.RotationManager.getInstance());
        eventHandler.register(coldplay.broker.PositionGuard.getInstance());
        eventHandler.register(coldplay.broker.GameStateTracker.getInstance());
        eventHandler.register(coldplay.broker.BedTracker.getInstance());
        eventHandler.register(coldplay.broker.NotificationManager.getInstance());
        configManager = new ConfigManager(hudState);
        moduleManager = new ModuleManager(eventHandler,
                coldplay.broker.NotificationManager.getInstance()::push, this::saveConfig,
                hudState, NAME, VERSION);
        eventHandler.register(moduleManager);
        eventHandler.register(new InputManager());
        commandManager = new CommandManager();
        configManager.load(moduleManager);
    }

    public static void post(coldplay.event.Event event) {
        getInstance().eventHandler.post(event);
    }

    public void saveConfig() {
        if (configManager != null && moduleManager != null) {
            configManager.save(moduleManager);
        }
    }

    public synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        try {
            saveConfig();
        } finally {
            if (moduleManager != null) {
                moduleManager.shutdown();
            }
        }
    }

    public ModuleManager getModuleManager() {
        return moduleManager;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public HudState getHudState() {
        return hudState;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }
}

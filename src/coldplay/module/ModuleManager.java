package coldplay.module;

import coldplay.EventHandler;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.hud.HudState;
import coldplay.broker.GameStateTracker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

public class ModuleManager {
    private static final double AUTO_OFF_DISTANCE = 32.0; // blocks

    private final EventHandler eventHandler;
    private final BiConsumer<String, Boolean> notifier;
    private final Runnable persistence;
    private final List<Module> modules = new ArrayList<>();
    private final List<Module> modulesView = Collections.unmodifiableList(modules);

    public ModuleManager(EventHandler eventHandler, BiConsumer<String, Boolean> notifier,
                         Runnable persistence, HudState hudState, String clientName,
                         String clientVersion) {
        this(eventHandler, notifier, persistence);
        registerModules(Objects.requireNonNull(hudState, "hudState"),
                Objects.requireNonNull(clientName, "clientName"),
                Objects.requireNonNull(clientVersion, "clientVersion"));
    }

    /** Test-only constructor that injects the module list. */
    ModuleManager(EventHandler eventHandler, BiConsumer<String, Boolean> notifier,
                  Runnable persistence, List<Module> modules) {
        this(eventHandler, notifier, persistence);
        this.modules.addAll(Objects.requireNonNull(modules, "modules"));
    }

    private ModuleManager(EventHandler eventHandler, BiConsumer<String, Boolean> notifier,
                          Runnable persistence) {
        this.eventHandler = Objects.requireNonNull(eventHandler, "eventHandler");
        this.notifier = Objects.requireNonNull(notifier, "notifier");
        this.persistence = Objects.requireNonNull(persistence, "persistence");
    }

    private void registerModules(HudState hudState, String clientName, String clientVersion) {
        register(new coldplay.module.combat.AimAssist());
        register(new coldplay.module.combat.AntiBot());
        register(new coldplay.module.combat.AntiFireBall());
        coldplay.module.combat.KillAura killAura = new coldplay.module.combat.KillAura();
        register(killAura);
        eventHandler.register(killAura.graph(hudState)); // stays placeable in the HUD editor while KillAura is off
        register(new coldplay.module.combat.AutoBlock(killAura::getTarget, killAura::canReachTarget));
        register(new coldplay.module.combat.Breaker(killAura::isWorking));
        register(new coldplay.module.combat.WTap());
        register(new coldplay.module.combat.AutoThrow());
        register(new coldplay.module.combat.AutoHeal());
        register(new coldplay.module.combat.AutoClicker());
        register(new coldplay.module.combat.AntiMiss());
        register(new coldplay.module.combat.Reach());
        register(new coldplay.module.combat.BackTrack());
        register(new coldplay.module.movement.Sprint());
        register(new coldplay.module.movement.BridgeAssist());
        register(new coldplay.module.movement.Scaffold());
        register(new coldplay.module.movement.NoFall());
        register(new coldplay.module.movement.AutoPearl());
        register(new coldplay.module.movement.InvMove());
        register(new coldplay.module.movement.NoSlow());
        register(new coldplay.module.movement.Velocity());
        register(new coldplay.module.visual.BlockAnimation());
        register(new coldplay.module.visual.HudModule(this::getModuleViews, hudState,
                clientName, clientVersion));
        register(new coldplay.module.visual.BlockCounter());
        register(new coldplay.module.visual.FullBright());
        register(new coldplay.module.visual.BlockESP());
        register(new coldplay.module.visual.EntityESP());
        register(new coldplay.module.visual.TargetHUD(hudState));
        register(new coldplay.module.visual.ArmorStatus(hudState));
        register(new coldplay.module.visual.Mirror(hudState));
        register(new coldplay.module.visual.Trajectory());
        coldplay.module.utility.InvManager invManager = new coldplay.module.utility.InvManager();
        register(new coldplay.module.utility.ChestStealer(invManager::autoCloses));
        register(new coldplay.module.utility.AutoTool());
        register(new coldplay.module.utility.FastPlace());
        register(new coldplay.module.utility.BedProtection());
        register(new coldplay.module.utility.BedNotification());
        register(new coldplay.module.utility.MiddleClickFriend(persistence));
        register(invManager);
        register(new coldplay.module.utility.HumanRecorder(hudState));
        register(new coldplay.module.utility.PacketLogger(clientVersion,
                () -> new coldplay.config.ConfigCodec().encodeProfile(this).toString()));
    }

    private void register(Module module) {
        modules.add(module);
    }

    public boolean toggle(Module module) {
        return module != null && setEnabled(module, !module.isEnabled());
    }

    public boolean setEnabled(Module module, boolean enabled) {
        return transition(module, enabled, true, null);
    }

    private boolean transition(Module module, boolean enabled, boolean notify, String reason) {
        if (!isManaged(module)) {
            return false;
        }
        if (module.isEnabled() == enabled) {
            return true;
        }

        if (enabled) {
            module.setEnabledState(true);
            try {
                module.onEnable();
                eventHandler.register(module);
            } catch (Throwable failure) {
                module.setEnabledState(false);
                try {
                    eventHandler.unregister(module);
                } catch (Throwable unregisterFailure) {
                    failure.addSuppressed(unregisterFailure);
                }
                try {
                    module.onDisable();
                } catch (Throwable cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                logFailure(module, "enable", failure);
                return false;
            }
        } else {
            Throwable failure = null;
            try {
                eventHandler.unregister(module);
            } catch (Throwable unregisterFailure) {
                failure = unregisterFailure;
            }
            module.setEnabledState(false);
            try {
                module.onDisable();
            } catch (Throwable cleanupFailure) {
                if (failure == null) {
                    failure = cleanupFailure;
                } else {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            if (failure != null) {
                logFailure(module, "disable", failure);
            }
        }

        coldplay.broker.PacketLog log = coldplay.broker.PacketLog.getInstance();
        if (log.isEnabled()) {
            log.note("ModuleState", module.getName() + "=" + module.isEnabled()
                    + "; cause=" + (reason == null ? coldplay.broker.PacketLog.caller() : reason));
        }
        if (notify) {
            notify(module, enabled);
        }
        return true;
    }

    private boolean isManaged(Module candidate) {
        if (candidate == null) {
            return false;
        }
        for (Module module : modules) {
            if (module == candidate) {
                return true;
            }
        }
        return false;
    }

    private void notify(Module module, boolean enabled) {
        try {
            notifier.accept(module.getName(), enabled);
        } catch (Throwable failure) {
            logFailure(module, "notify", failure);
        }
    }

    private static void logFailure(Module module, String action, Throwable failure) {
        System.err.println("[ColdPlay] Failed to " + action + " " + module.getName() + ": " + failure);
        failure.printStackTrace();
        // ChatUtil is safe to call before the GUI exists.
        coldplay.util.ChatUtil.error("Failed to " + action + " " + module.getName());
    }

    public void shutdown() {
        for (Module module : modules) {
            if (module.isEnabled()) {
                transition(module, false, false, "shutdown");
            }
        }
        eventHandler.unregister(this);
    }

    @EventTarget(priority = EventPriority.AUTO_OFF)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        GameStateTracker tracker = GameStateTracker.getInstance();
        if (!tracker.respawnThisTick() && !tracker.combatEndedThisTick()
                && tracker.jumpDistanceThisTick() <= AUTO_OFF_DISTANCE) {
            return;
        }
        boolean changed = false;
        String reason = "auto-off; respawn=" + tracker.respawnThisTick()
                + "; combatEnded=" + tracker.combatEndedThisTick()
                + "; jumpDistance=" + tracker.jumpDistanceThisTick();
        for (Module module : modules) {
            if (module.isEnabled() && module.isAutoOff()) {
                transition(module, false, true, reason);
                changed = true;
            }
        }
        if (changed) {
            try {
                persistence.run();
            } catch (Throwable failure) {
                System.err.println("[ColdPlay] Failed to persist Auto Off changes: " + failure);
                failure.printStackTrace();
            }
        }
    }

    public List<Module> getModules() {
        return modulesView;
    }

    public List<ModuleView> getModuleViews() {
        List<ModuleView> views = new ArrayList<>();
        for (Module module : modules) {
            views.add(new ModuleView(module.getName(), module.isEnabled(), module.getSuffix()));
        }
        return Collections.unmodifiableList(views);
    }

    public List<Module> getModulesInCategory(Category category) {
        List<Module> result = new ArrayList<>();
        for (Module module : modules) {
            if (module.getCategory() == category) {
                result.add(module);
            }
        }
        return Collections.unmodifiableList(result);
    }
}

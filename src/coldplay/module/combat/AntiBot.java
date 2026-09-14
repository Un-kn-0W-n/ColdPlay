package coldplay.module.combat;

import coldplay.broker.BotTracker;
import coldplay.event.EventPriority;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;

public class AntiBot extends Module {
    private final HeaderSetting tabHeader = add(new HeaderSetting("Tab List"));
    public final BooleanSetting notInTab = add(new BooleanSetting("Not In Tab", true)
            .describe("Flag players missing from the tab list."));
    public final BooleanSetting invalidName = add(new BooleanSetting("Invalid Name", true)
            .describe("Flag names that are empty, longer than 16 characters, or contain colour codes "
                    + "or characters outside A-Z, 0-9 and underscore."));
    public final BooleanSetting duplicateName = add(new BooleanSetting("Duplicate Name", true)
            .describe("Flag loaded copies of a name whose UUID does not match the tab list entry."));
    public final BooleanSetting zeroPing = add(new BooleanSetting("Zero Ping", false)
            .describe("Flag tab entries with 0 ms ping or no game mode. "
                    + "Off by default because many tab plugins report 0 ms for everyone."));
    private final HeaderSetting behaviorHeader = add(new HeaderSetting("Behavior"));
    public final BooleanSetting neverOnGround = add(new BooleanSetting("Never On Ground", false)
            .describe("Flag players that have not touched the ground since tracking started."));
    public final BooleanSetting invisibleSpawn = add(new BooleanSetting("Invisible Spawn", true)
            .describe("Flag players that spawned invisible next to you and are still invisible."));
    public final NumberSetting spawnRadius = add(new NumberSetting("Spawn Radius", 5.0, 1.0, 16.0, 0.5)
            .describe("How close an invisible spawn has to be, in blocks."));

    public AntiBot() {
        super("AntiBot", Category.COMBAT, "Flags server-spawned fake players so combat and visual modules ignore them.");
        spawnRadius.visibleWhen(invisibleSpawn::get).indent(1);
    }

    @Override
    protected void onEnable() {
        push();
    }

    @Override
    protected void onDisable() {
        BotTracker.getInstance().disable();
    }

    // Runs before the combat modules so they read this tick's verdicts rather than last tick's.
    @EventTarget(priority = EventPriority.STATE_TRACKING)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        push();
        Minecraft mc = Minecraft.getMinecraft();
        BotTracker.getInstance().tick(mc.thePlayer, mc.theWorld, mc.getNetHandler());
    }

    /** Re-pushed every tick so a toggle takes effect live, as Reach does. */
    private void push() {
        BotTracker.getInstance().configure(new BotTracker.Checks(notInTab.get(), invalidName.get(),
                duplicateName.get(), zeroPing.get(), neverOnGround.get(), invisibleSpawn.get(), spawnRadius.get()));
    }
}

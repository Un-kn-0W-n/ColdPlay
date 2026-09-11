package coldplay.module.combat;

import coldplay.event.EventRender;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.BooleanSetting;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import coldplay.broker.CombatManager;
import coldplay.util.RenderUtil;
import coldplay.broker.RotationManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;

public class AimAssist extends Module {
    private static final String MINIMAL = "Minimal";
    private static final String NORMAL = "Normal";
    private static final String AGGRESSIVE = "Aggressive";

    public final BooleanSetting players = add(new BooleanSetting("Players", true).describe("Aim at other players."));
    public final BooleanSetting mobs = add(new BooleanSetting("Mobs", false).describe("Aim at hostile mobs."));
    public final BooleanSetting animals = add(new BooleanSetting("Animals", false).describe("Aim at passive animals."));
    public final BooleanSetting invisible = add(new BooleanSetting("Invisible", false).describe("Also aim at invisible entities."));
    public final BooleanSetting npcs = add(new BooleanSetting("NPCs", false).describe("Also aim at NPCs (villagers and fake players)."));
    public final NumberSetting range = add(new NumberSetting("Range", 4.0, 1.0, 6.0, 0.1).describe("Max distance to a target, in blocks."));
    public final NumberSetting fov = add(new NumberSetting("FOV", 90.0, 1.0, 180.0, 1.0).describe("Only aim at targets within this view cone (degrees)."));
    public final ModeSetting level = add(new ModeSetting("Level", NORMAL, MINIMAL, NORMAL, AGGRESSIVE).describe("Turn speed: Minimal glides, Normal is standard, Aggressive snaps."));

    private Entity target;
    private long lastFrameNanos;

    public AimAssist() {
        super("AimAssist", Category.COMBAT, "Smoothly steers your view onto the nearest valid target within range and FOV.");
    }

    @Override
    protected void onDisable() {
        target = null;
        lastFrameNanos = 0L;
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null || Minecraft.getMinecraft().theWorld == null) {
            target = null;
            return;
        }
        target = CombatManager.getInstance().acquire(player, filters());
    }

    @EventTarget
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        Entity victim = target;
        if (player == null || victim == null || mc.currentScreen != null || !mc.inGameHasFocus) {
            // Don't carry a stale frame gap into the next target.
            lastFrameNanos = 0L;
            return;
        }

        float partialTicks = event.getPartialTicks();

        Vec3 eyes = player.getPositionEyes(partialTicks);
        double targetX = RenderUtil.interp(victim.lastTickPosX, victim.posX, partialTicks);
        double targetY = RenderUtil.interp(victim.lastTickPosY, victim.posY, partialTicks) + victim.height / 2.0;
        double targetZ = RenderUtil.interp(victim.lastTickPosZ, victim.posZ, partialTicks);

        float[] want = RotationManager.angleTo(eyes.xCoord, eyes.yCoord, eyes.zCoord,
                targetX, targetY, targetZ);
        float yawDiff = MathHelper.wrapAngleTo180_float(want[0] - player.rotationYaw);
        float pitchDiff = want[1] - player.rotationPitch;

        long now = System.nanoTime();
        String mode = level.get();
        double maxStep = (AGGRESSIVE.equals(mode) ? 180.0 : MINIMAL.equals(mode) ? 6.0 : 20.0)
                * RotationManager.elapsedTicksSince(lastFrameNanos, now);
        double pitchStep = maxStep / 3.0;
        lastFrameNanos = now;

        float appliedYaw = RotationManager.gcdSnap((float) MathHelper.clamp_double(yawDiff, -maxStep, maxStep));
        float appliedPitch = RotationManager.gcdSnap((float) MathHelper.clamp_double(pitchDiff, -pitchStep, pitchStep));

        player.setAngles(appliedYaw / 0.15F, -appliedPitch / 0.15F);
    }

    private CombatManager.Filters filters() {
        return new CombatManager.Filters(players.get(), mobs.get(), animals.get(), invisible.get(), npcs.get(),
                range.get(), fov.get(), false, false);
    }

}

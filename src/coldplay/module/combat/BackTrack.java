package coldplay.module.combat;

import coldplay.event.EventAttackPerformed;
import coldplay.event.EventRender3D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.friend.FriendManager;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ColorSetting;
import coldplay.setting.NumberSetting;
import coldplay.setting.RangeSetting;
import coldplay.broker.BotTracker;
import coldplay.broker.PositionGuard;
import coldplay.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.util.AxisAlignedBB;

public class BackTrack extends Module {

    private final RangeSetting delay = add(new RangeSetting("Delay", 25.0, 500.0, 25.0, 500.0, 25.0).unit("ms")
            .describe("Hold on the target's position updates that move it away from you, rolled between the thumbs."));

    private final NumberSetting combatTimeout = add(new NumberSetting("Combat Timeout", 3.0, 0.5, 10.0, 0.5)
            .describe("Seconds after your last hit that the victim stays the target."));

    private final ColorSetting ghostColor = add(new ColorSetting("Ghost Color", 0xFF5050)
            .describe("Box at the target's real position while its model lags behind."));

    private EntityLivingBase combatTarget;
    private long combatTargetAt;

    public BackTrack() {
        super("BackTrack", Category.COMBAT, "Delays the combat target's position updates while it moves away so it stays hittable from further; a box marks where it really is.");
        addAutoOff();
    }

    @Override
    public String getSuffix() {
        long lo = Math.round(delay.getLo());
        long hi = Math.round(delay.getHi());
        return (lo == hi ? String.valueOf(hi) : lo + "-" + hi) + delay.getUnit();
    }

    @Override
    protected void onDisable() {
        reset();
    }

    private void reset() {
        PositionGuard.getInstance().release();
        combatTarget = null;
    }

    @EventTarget
    public void onAttack(EventAttackPerformed event) {
        Entity hit = event.getTarget();
        if (!(hit instanceof EntityLivingBase)
                || FriendManager.getInstance().isFriend(hit.getName())
                || BotTracker.getInstance().isBot(hit)) {
            return;
        }
        combatTarget = (EntityLivingBase) hit;
        combatTargetAt = System.currentTimeMillis();
        PositionGuard.getInstance().setTarget(hit, (long) delay.getLo(), (long) delay.getHi());
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || mc.theWorld == null) {
            reset();
            return;
        }
        if (combatTarget != null
                && (combatTarget.isDead || combatTarget.worldObj != mc.theWorld
                || System.currentTimeMillis() - combatTargetAt > (long) (combatTimeout.get() * 1000.0))) {
            combatTarget = null;
        }
        if (combatTarget != null) {
            PositionGuard.getInstance().setTarget(combatTarget, (long) delay.getLo(), (long) delay.getHi());
        } else {
            PositionGuard.getInstance().release();
        }
    }

    @EventTarget
    public void onRender3D(EventRender3D event) {
        Entity target = PositionGuard.getInstance().getTarget();
        double[] real = PositionGuard.getInstance().getRealPos();
        if (target == null || real == null) {
            return;
        }

        AxisAlignedBB box = target.getEntityBoundingBox().offset(
                real[0] - target.posX, real[1] - target.posY, real[2] - target.posZ);

        RenderUtil.beginWorldOverlay(2.0F);
        RenderUtil.drawFilledBox(box, ghostColor.red(), ghostColor.green(), ghostColor.blue(), 60);
        RenderGlobal.drawOutlinedBoundingBox(box, 0, 0, 0, 255);
        RenderUtil.endWorldOverlay();
    }
}

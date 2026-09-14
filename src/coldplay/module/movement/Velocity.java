package coldplay.module.movement;

import coldplay.event.EventHurt;
import coldplay.event.EventPriority;
import coldplay.event.EventStrafe;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ModeSetting;
import coldplay.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.network.Packet;
import net.minecraft.network.play.INetHandlerPlayClient;
import net.minecraft.network.play.server.S12PacketEntityVelocity;

import java.util.ArrayDeque;
import java.util.Random;

public class Velocity extends Module {
    private static final String LEGIT = "Legit";
    private static final String HYPIXEL = "Hypixel";
    private static final int MAX_HOLD_TICKS = 3;
    private static Velocity instance;
    public final ModeSetting mode = add(new ModeSetting("Mode", LEGIT, LEGIT, HYPIXEL)
            .describe("Legit auto-jumps on knockback. Hypixel holds air knockback until you land, then jump-resets."));
    public final NumberSetting chance = add(new NumberSetting("Chance", 100.0, 10.0, 100.0, 1.0)
            .describe("% chance to auto-jump on each knockback you take."));

    private final Random random = new Random();
    private final ArrayDeque<Runnable> held = new ArrayDeque<>();
    private boolean pendingHit, released, releasing;
    private int heldTicks;

    public Velocity() {
        super("Velocity", Category.MOVEMENT, "Reduces the knockback you take by jumping as it lands.");
        chance.visibleWhen(this::legit).indent(1);
        instance = this;
    }

    private boolean legit() {
        return LEGIT.equals(mode.get());
    }

    /** Runs in the scheduled-task drain. Hypixel mode keeps our S12 and every packet after it, transactions included. */
    public static boolean hold(Packet<?> packet, Object handler, Runnable task) {
        Velocity m = instance;
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (m == null || m.releasing || !(handler instanceof INetHandlerPlayClient)) {
            return false;
        }
        if (m.held.isEmpty() && (!m.isEnabled() || m.legit() || player == null
                || !(packet instanceof S12PacketEntityVelocity)
                || ((S12PacketEntityVelocity) packet).getEntityID() != player.getEntityId())) {
            return false;
        }
        m.held.add(task);
        return true;
    }

    @Override
    protected void onDisable() {
        pendingHit = false;
        release(Minecraft.getMinecraft().thePlayer != null);
    }

    // ahead of every module so nothing is sent between our transaction replies and the drain they came from
    @EventTarget(priority = EventPriority.STATE_TRACKING + 1)
    public void onUpdate(EventUpdate event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (event.isPre() && !held.isEmpty()
                && (player == null || player.onGround || ++heldTicks >= MAX_HOLD_TICKS)) {
            release(player != null);
        }
    }

    private void release(boolean apply) {
        released = apply && !held.isEmpty();
        heldTicks = 0;
        releasing = true;
        try {
            for (Runnable task; (task = held.poll()) != null; ) {
                if (apply) {
                    task.run();
                }
            }
        } finally {
            releasing = false;
        }
    }

    @EventTarget
    public void onHurt(EventHurt event) {
        pendingHit = true;
    }

    @EventTarget
    public void onStrafe(EventStrafe event) {
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        boolean freshHit = legit() ? pendingHit : released;
        pendingHit = false;
        released = false;
        // Knockback leaves motionY ~+0.4; fall damage fires EventHurt too, but with motionY already zeroed.
        if (player == null || !freshHit || !player.onGround || player.motionY <= 0.0) {
            return;
        }
        if (legit()) {
            boolean moving = event.getForward() != 0.0F || event.getStrafe() != 0.0F;
            if (!moving || random.nextDouble() * 100.0 >= chance.get()) {
                return;
            }
        } else if (!player.isSprinting()) {
            // the jump only cuts horizontal knockback through the sprint boost
            return;
        }
        // vanilla's jump gate consumes this later in the same tick
        player.movementInput.jump = true;
    }
}

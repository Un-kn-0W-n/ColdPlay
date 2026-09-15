package coldplay.broker;

import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;

/** Keeps an AutoBlock sword block up without the use key, and holds KillAura's clicks between planned hits. */
public final class UseHold {
    private static final UseHold INSTANCE = new UseHold();

    private boolean held;
    private boolean attackAllowed = true;
    private Entity attackTarget;

    private UseHold() {
    }

    public static UseHold getInstance() {
        return INSTANCE;
    }

    public boolean isHeld() {
        return held;
    }

    public void setHeld(boolean held) {
        this.held = held;
    }

    public boolean allowsAttack(Entity target) {
        return attackAllowed && (attackTarget == null || attackTarget == target);
    }

    /** A null target allows any. */
    public void allowAttack(boolean allowed, Entity target) {
        attackAllowed = allowed;
        attackTarget = target;
    }

    public void clear() {
        held = false;
        attackAllowed = true;
        attackTarget = null;
    }

    /** Checked where vanilla stops using the item because the use key is up. */
    public boolean keeps(EntityPlayerSP player) {
        ItemStack using = player.getItemInUse();
        return held && using != null && using.getItem() instanceof ItemSword;
    }
}

package net.minecraft.util;

public class MovementInput
{
    public static final float SNEAK_SLOWDOWN = 0.3F;

    /**
     * The speed at which the player is strafing. Postive numbers to the left and negative to the right.
     */
    public float moveStrafe;

    /**
     * The speed at which the player is moving forward. Negative numbers will move backwards.
     */
    public float moveForward;
    public boolean jump;
    public boolean sneak;

    public void updatePlayerMoveState()
    {
    }

    public static float slowForSneak(float value)
    {
        return value * SNEAK_SLOWDOWN;
    }

    public void applySneakSlowdown()
    {
        this.moveStrafe = slowForSneak(this.moveStrafe);
        this.moveForward = slowForSneak(this.moveForward);
    }
}

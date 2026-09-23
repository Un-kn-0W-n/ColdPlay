package coldplay.gui;

/** Look of one glass surface for {@link GlassShader}. Lengths are GUI px. */
public final class Glass {

    // HUD: 50% smoke over a 14px CSS blur, no drop shadow
    public static final Glass SMOKE = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 0.0F, 0.0F, 0.0F);
    // Smoke Click GUI windows float, so they cast a shadow
    public static final Glass SMOKE_PANEL = new Glass(0x800E1015, 0x800E1015, 0x2EFFFFFF, 10.5F, 1.4F, 24.0F, 9.0F, 0.28F);
    public static final Glass MILK = new Glass(0x99F6F9FD, 0x99F6F9FD, 0xCCFFFFFF, 22.5F, 1.8F, 45.0F, 22.0F, 0.32F);

    public final int top;
    public final int bottom;
    public final int rim;
    public final float blur;       // backdrop blur sigma
    public final float saturation;
    public final float shadow;     // shadow spread
    public final float shadowOffset;
    public final float shadowAlpha;

    public Glass(int top, int bottom, int rim, float blur, float saturation,
                 float shadow, float shadowOffset, float shadowAlpha) {
        this.top = top;
        this.bottom = bottom;
        this.rim = rim;
        this.blur = blur;
        this.saturation = saturation;
        this.shadow = shadow;
        this.shadowOffset = shadowOffset;
        this.shadowAlpha = shadowAlpha;
    }
}

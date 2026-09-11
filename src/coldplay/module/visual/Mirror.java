package coldplay.module.visual;

import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.gui.Theme;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.NumberSetting;
import coldplay.util.RenderUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.src.Config;
import net.minecraft.util.MathHelper;

public final class Mirror extends Module {
    static final int WIDTH = 200, HEIGHT = 80;
    static final long INTERVAL_NANOS = 33_333_334L;

    private final NumberSetting width = add(new NumberSetting("Width", WIDTH, 80, 800, 1)
            .describe("Mirror width in GUI pixels. Drag any corner in Edit GUI."));
    private final NumberSetting height = add(new NumberSetting("Height", HEIGHT, 32, 400, 1)
            .describe("Mirror height in GUI pixels. Drag any corner in Edit GUI."));
    private final HudState hud;
    private Framebuffer framebuffer;
    private WorldClient world;
    private long lastCapture;
    private boolean hasFrame;
    private boolean failed;
    private int[] resizePreview;

    public Mirror(HudState hud) {
        super("Mirror", Category.VISUAL, "A live rear-view mirror. Move and resize it in Edit GUI; refreshes at up to 30 FPS.");
        this.hud = hud;
    }

    @Override
    protected void onEnable() {
        failed = false;
        lastCapture = 0L;
    }

    @Override
    protected void onDisable() {
        release();
        world = null;
    }

    /** Ticks keep running after a disconnect, where EventRender no longer fires to free the buffer. */
    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (event.isPre()) syncWorld();
    }

    @EventTarget(priority = EventPriority.DRAIN)
    public void onRender(EventRender event) {
        Minecraft mc = Minecraft.getMinecraft();
        syncWorld();
        if (unavailable() != null) {
            release();
            return;
        }
        if (failed || mc.skipRenderWorld || mc.isGamePaused()
                || (mc.gameSettings.hideGUI && !hud.isEditing())) {
            return;
        }
        long now = System.nanoTime();
        if (!captureDue(now, lastCapture, hasFrame)) {
            return;
        }
        int scale = new ScaledResolution(mc).getScaleFactor();
        // a mirror panel never needs more pixels than the display; cap the capture there.
        int captureWidth = Math.min(textureWidth(scale), mc.displayWidth);
        int captureHeight = Math.min(textureHeight(scale), mc.displayHeight);
        if (framebuffer != null && (framebuffer.framebufferWidth != captureWidth
                || framebuffer.framebufferHeight != captureHeight)) {
            release();
        }
        lastCapture = now;
        try {
            if (framebuffer == null) {
                framebuffer = new Framebuffer(captureWidth, captureHeight, true);
            }
            mc.entityRenderer.renderMirror(framebuffer, event.getPartialTicks(), scale);
            hasFrame = true;
        } catch (RuntimeException failure) {
            failed = true;
            release();
            System.err.println("[ColdPlay] Mirror capture failed; toggle Mirror to retry: " + failure);
            failure.printStackTrace();
        } finally {
            mc.getFramebuffer().bindFramebuffer(true);
        }
    }

    static boolean captureDue(long now, long lastCapture, boolean hasFrame) {
        return !hasFrame || now - lastCapture >= INTERVAL_NANOS;
    }

    public int getWidth() { return width.get().intValue(); }
    public int getHeight() { return height.get().intValue(); }

    public int clampWidth(int value) { return MathHelper.clamp_int(value, (int) width.getMin(), (int) width.getMax()); }
    public int clampHeight(int value) { return MathHelper.clamp_int(value, (int) height.getMin(), (int) height.getMax()); }

    public void resize(int width, int height) {
        this.width.set((double) width);
        this.height.set((double) height);
        resizePreview = null;
    }

    /** Stretch the existing texture while dragging; the capture only resizes on release. */
    public void previewResize(int x, int y, int width, int height) {
        resizePreview = new int[] {x, y, width, height};
    }

    int textureWidth(int scale) { return (getWidth() - 2) * scale; }
    int textureHeight(int scale) { return (getHeight() - 2) * scale; }

    private static String unavailable() {
        if (Config.isShaders()) return "Unavailable with shader packs";
        if (!OpenGlHelper.isFramebufferEnabled()) return "Requires FBO; Fast Render / AA off";
        return null;
    }

    private void syncWorld() {
        WorldClient current = Minecraft.getMinecraft().theWorld;
        if (world != current) {
            release();
            world = current;
        }
    }

    private void release() {
        if (framebuffer != null) {
            framebuffer.deleteFramebuffer();
            framebuffer = null;
            Minecraft.getMinecraft().renderGlobal.releaseMirrorOutlines();
        }
        hasFrame = false;
        lastCapture = 0L;
    }

    static int panelCoordinate(int anchor, int screenSize, int panelSize) {
        return MathHelper.clamp_int(anchor, 0, Math.max(0, screenSize - panelSize));
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null || (mc.gameSettings.hideGUI && !hud.isEditing())) return;
        ScaledResolution resolution = event.getResolution();
        int screenWidth = resolution.getScaledWidth(), screenHeight = resolution.getScaledHeight();
        int width = getWidth(), height = getHeight();
        HudState.Position position = hud.getOrCreate("Mirror", (screenWidth - width) / 2, 18,
                screenWidth, screenHeight);
        int x = panelCoordinate(position.x, screenWidth, width);
        int y = panelCoordinate(position.y, screenHeight, height);
        if (hud.isEditing() && resizePreview != null) {
            x = resizePreview[0];
            y = resizePreview[1];
            width = resizePreview[2];
            height = resizePreview[3];
        }
        String message = unavailable();
        if (message == null && !hasFrame) message = failed ? "Capture failed - toggle to retry" : "Mirror";
        if (message != null) {
            RenderUtil.rect(x, y, width, height, Theme.WELL);
            message = mc.fontRendererObj.trimStringToWidth(message, width - 8);
            mc.fontRendererObj.drawStringWithShadow(message,
                    x + (width - mc.fontRendererObj.getStringWidth(message)) / 2.0F, y + height / 2.0F - 4, Theme.TEXT_DIM);
        } else {
            GlStateManager.enableTexture2D();
            GlStateManager.disableLighting();
            GlStateManager.disableDepth();
            GlStateManager.disableBlend();
            // A world framebuffer's alpha is not panel transparency. Testing it punches holes
            // through the rear view and exposes the forward-facing world behind the HUD.
            GlStateManager.disableAlpha();
            GlStateManager.color(1, 1, 1, 1);
            framebuffer.bindFramebufferTexture();
            WorldRenderer vertices = Tessellator.getInstance().getWorldRenderer();
            vertices.begin(7, DefaultVertexFormats.POSITION_TEX);
            // Framebuffer V is bottom-up; reversed U makes the rear camera a mirror.
            vertices.pos(x + 1, y + height - 1, 0).tex(1, 0).endVertex();
            vertices.pos(x + width - 1, y + height - 1, 0).tex(0, 0).endVertex();
            vertices.pos(x + width - 1, y + 1, 0).tex(0, 1).endVertex();
            vertices.pos(x + 1, y + 1, 0).tex(1, 1).endVertex();
            Tessellator.getInstance().draw();
            framebuffer.unbindFramebufferTexture();
            GlStateManager.enableAlpha();
            GlStateManager.enableDepth();
        }
        Theme.contour(x, y, width, height);
        hud.report("Mirror", x, y, x + width, y + height);
    }
}

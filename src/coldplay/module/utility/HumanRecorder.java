package coldplay.module.utility;

import coldplay.event.EventMotion;
import coldplay.event.EventPriority;
import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ButtonSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.ChatUtil;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;

/** Records camera and sent rotations each tick for CSV export. */
public class HumanRecorder extends Module {

    private static final int COLOR_BOX = 0xF0101014;
    private static final int COLOR_BORDER = 0xFFB9B9C2;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_DIM = 0xFFB4B4BE;
    private static final int PAD = 4;
    private static final int GAP = 2;

    private static final int MAX_SAMPLES = 72_000; // ~1 hour at 20 tps

    private final ArrayDeque<float[]> samples = new ArrayDeque<float[]>(); // cameraYaw, cameraPitch, sentYaw, sentPitch
    private final HudState hud;
    private final NumberSetting scale;

    public HumanRecorder(HudState hud) {
        super("HumanRecorder", Category.UTILITY,
                "Records your camera and sent yaw/pitch each tick; export as CSV.");
        this.hud = hud;
        add(new ButtonSetting("Export CSV", this::exportCsv)
                .describe("Write all recorded samples to coldplay/recordings/ and start fresh."));
        scale = add(HudState.scaleSetting("Scale"));
        hud.registerScale("HumanRecorder", scale);
    }

    // DRAIN priority so the sample sees the rewritten rotation.
    @EventTarget(priority = EventPriority.DRAIN)
    public void onMotion(EventMotion event) {
        if (!event.isPre()) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player == null) {
            return;
        }
        samples.addLast(new float[]{player.rotationYaw, player.rotationPitch,
                event.getYaw(), event.getPitch()});
        if (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        Fonts.load(event.getResolution().getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;
        String title = "HumanRecorder";
        String count = samples.size() + " samples";

        int panelW = PAD + Math.max(font.getStringWidth(title), font.getStringWidth(count)) + PAD;
        int panelH = PAD + font.getHeight() * 2 + GAP + PAD;

        ScaledResolution resolution = event.getResolution();
        HudState.Position state = hud.getOrCreate("HumanRecorder", 3, 60,
                resolution.getScaledWidth(), resolution.getScaledHeight());

        float s = scale.get().floatValue();
        RenderUtil.pushScale(state.x, state.y, s);
        RenderUtil.drawBorderedRect(state.x, state.y, state.x + panelW, state.y + panelH,
                COLOR_BOX, COLOR_BORDER);
        font.drawStringWithShadow(title, state.x + PAD, state.y + PAD, COLOR_TEXT);
        font.drawStringWithShadow(count, state.x + PAD, state.y + PAD + font.getHeight() + GAP,
                COLOR_DIM);
        GlStateManager.popMatrix();

        if (hud.isEditing()) {
            hud.report("HumanRecorder", state.x, state.y, state.x + panelW, state.y + panelH,
                    state.x, state.y, s);
        }
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** Runs on the client thread; MAX_SAMPLES bounds the stall. */
    private void exportCsv() {
        if (samples.isEmpty()) {
            ChatUtil.info("No samples recorded.");
            return;
        }
        File directory = new File(new File(Minecraft.getMinecraft().mcDataDir, "coldplay"),
                "recordings");
        // mkdirs() returns false when the directory was created meanwhile.
        if (!directory.exists() && !directory.mkdirs() && !directory.exists()) {
            ChatUtil.error("Could not create " + directory.getPath());
            return;
        }
        File file = new File(directory,
                "rec-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss").format(new Date()) + ".csv");
        try (Writer out = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            out.write("cameraYaw,cameraPitch,sentYaw,sentPitch\n");
            for (float[] s : samples) {
                out.write(s[0] + "," + s[1] + "," + s[2] + "," + s[3] + "\n");
            }
        } catch (Exception exception) {
            ChatUtil.error("Export failed: " + exception.getMessage());
            return;
        }
        ChatUtil.success("Exported " + samples.size() + " samples to " + file.getName());
        samples.clear();
    }
}

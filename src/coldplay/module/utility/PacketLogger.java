package coldplay.module.utility;

import coldplay.broker.PacketLog;
import coldplay.event.EventPriority;
import coldplay.event.EventRender;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.setting.ButtonSetting;
import coldplay.util.ChatUtil;

import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.Writer;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;

/** Disabling stops capture but keeps the buffer for export. */
public class PacketLogger extends Module {
    private final AtomicBoolean exporting = new AtomicBoolean();
    private String lastConfig;
    private String lastSession;
    private boolean wasInGui;
    private final String clientVersion;
    private final Supplier<String> configSnapshot;

    public PacketLogger(String clientVersion, Supplier<String> configSnapshot) {
        super("PacketLogger", Category.UTILITY, "Logs every sent and received packet; export as CSV.");
        this.clientVersion = clientVersion;
        this.configSnapshot = configSnapshot;
        add(new ButtonSetting("Export CSV", this::exportCsv)
                .describe("Write all logged packets to coldplay/recordings/ and start fresh."));
    }

    @Override
    protected void onEnable() {
        PacketLog.getInstance().setEnabled(true);
        recordCaptureContext();
        recordSessionContext(true);
        recordConfigSnapshot(true);
    }

    private void recordCaptureContext() {
        Properties build = new Properties();
        try (InputStream in = PacketLogger.class.getResourceAsStream("/coldplay/build.properties")) {
            if (in != null) {
                build.load(in);
            }
        } catch (Exception ignored) { }
        PacketLog.getInstance().note("CaptureContext", "schema=3; client=" + clientVersion
                + "; sourceSha256=" + build.getProperty("sourceSha256", "unknown")
                + "; classesSha256=" + build.getProperty("classesSha256", "unknown")
                + "; OUT=dispatch before Netty write; IN=Netty entry before client processing"
                + "; sequence=enqueue order; tick/frame=capture-relative; DROP=failed final action validation"
                + "; settings=enable/GUI-tick/export snapshots; toggles=ModuleState with cause; SessionContext=identity/settings changes"
                + "; origin=explicit tag or module on producer stack, otherwise Unknown; IN origin=Server"
                + "; producer=gameplay send call site; context=frozen at first prepare, dispatch-fallback if unavailable"
                + "; rotationMode/Owner=observed broker state, camera is not proof of manual input"
                + "; chunk bytes omitted; payload hex capped at 65536 bytes", true);
    }

    @Override
    protected void onDisable() {
        recordConfigSnapshot(true);
        PacketLog.getInstance().note("CaptureStop", "capture disabled");
        PacketLog.getInstance().setEnabled(false);
    }

    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }
        PacketLog.getInstance().nextTick();
        recordSessionContext(false);
        boolean inGui = Minecraft.getMinecraft().currentScreen != null;
        // Only snapshot around GUI use; serializing the profile every tick is too slow.
        if (inGui || wasInGui) {
            recordConfigSnapshot(false);
        }
        wasInGui = inGui;
    }

    @EventTarget(priority = EventPriority.BROKER_RESET)
    public void onRender(EventRender event) {
        PacketLog.getInstance().nextFrame();
    }

    private void recordConfigSnapshot(boolean force) {
        try {
            String config = configSnapshot.get();
            if (force || !config.equals(lastConfig)) {
                PacketLog.getInstance().note("EffectiveConfig", config, force);
            }
            lastConfig = config;
        } catch (RuntimeException failure) {
            PacketLog.getInstance().note("ConfigSnapshotError", failure.toString());
        }
    }

    private void recordSessionContext(boolean force) {
        Minecraft mc = Minecraft.getMinecraft();
        String session = "player=" + (mc.thePlayer == null ? "none" : mc.thePlayer.getName())
                + "; playerUuid=" + (mc.thePlayer == null ? "none" : mc.thePlayer.getUniqueID())
                + "; entityId=" + (mc.thePlayer == null ? "none" : mc.thePlayer.getEntityId())
                + "; server=" + (mc.getCurrentServerData() == null ? "none" : mc.getCurrentServerData().serverIP)
                + "; gameType=" + (mc.playerController == null ? "none" : mc.playerController.getCurrentGameType())
                + "; sensitivity=" + mc.gameSettings.mouseSensitivity
                + "; fov=" + mc.gameSettings.fovSetting + "; maxFps=" + mc.gameSettings.limitFramerate
                + "; vsync=" + mc.gameSettings.enableVsync;
        if (force || !session.equals(lastSession)) {
            PacketLog.getInstance().note("SessionContext", session, force);
        }
        lastSession = session;
    }

    private void exportCsv() {
        if (!exporting.compareAndSet(false, true)) {
            return;
        }
        recordCaptureContext();
        recordSessionContext(true);
        recordConfigSnapshot(true);
        Thread worker = new Thread(() -> {
            try {
                writeExport();
            } finally {
                exporting.set(false);
            }
        }, "ColdPlay-PacketExport");
        // non-daemon so a requested export finishes during shutdown
        worker.setDaemon(false);
        worker.start();
    }

    private void writeExport() {
        File directory = new File(new File(Minecraft.getMinecraft().mcDataDir, "coldplay"),
                "recordings");
        directory.mkdirs();
        if (!directory.isDirectory()) {
            message("Could not create " + directory.getPath(), false);
            return;
        }
        File file = new File(directory,
                "packets-" + new SimpleDateFormat("yyyy-MM-dd_HH.mm.ss.SSS").format(new Date()) + ".csv");
        int count;
        try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            count = PacketLog.getInstance().writeCsv(out);
        } catch (Exception exception) {
            message("Export failed: " + exception.getMessage(), false);
            return;
        }
        message("Exported " + count + " rows to " + file.getName(), true);
    }

    private static void message(String message, boolean success) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            if (success) {
                ChatUtil.success(message);
            } else {
                ChatUtil.error(message);
            }
        });
    }
}

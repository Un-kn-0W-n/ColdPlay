package coldplay.module.utility;

import coldplay.event.EventRender2D;
import coldplay.event.EventTarget;
import coldplay.event.EventUpdate;
import coldplay.friend.FriendManager;
import coldplay.gui.Theme;
import coldplay.hud.HudState;
import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.broker.BedTracker;
import coldplay.setting.BooleanSetting;
import coldplay.setting.HeaderSetting;
import coldplay.setting.NumberSetting;
import coldplay.util.Animation;
import coldplay.util.EntityTargets;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.Fonts;
import coldplay.broker.GameStateTracker;
import net.minecraft.block.BlockBed;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** HUD panel of enemies near your bed during a BedWars match; a row flashes as its player crosses a closer band. */
public final class BedNotification extends Module {
    private static final int BAND_SIZE = 10;
    private static final long FLASH_MS = 900L;
    private static final double ANIM_SPEED = 13.0;
    private static final String TITLE = "Bed";

    private static final int COLOR_ALERT = 0xFFFF5555;
    private static final int COLOR_SAFE = 0xFF55FF55;

    // Layout dimensions use scaled GUI pixels.
    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final int ICON_GAP = 3;
    private static final int TEXT_GAP = 8; // min gap between left and right text
    private static final int SEP_GAP = 3;  // above and below the header line
    private static final int ROW_GAP = 3;
    private static final int BAR_GAP = 2;
    private static final int BAR_H = 2;
    private static final int MIN_W = 100;

    private static final Comparator<Row> ORDER =
            Comparator.comparing((Row row) -> row.band == null).thenComparingDouble(row -> row.distance);

    private final NumberSetting range = add(new NumberSetting("Range", 40, 20, 60, 10)
            .describe("How close, in blocks, an enemy has to get to your bed to show up."));
    private final BooleanSetting sound = add(new BooleanSetting("Sound", true)
            .describe("Ping each time an enemy gets another 10 blocks closer."));
    private final HeaderSetting displayHeader = add(new HeaderSetting("Display"));
    private final BooleanSetting alwaysShow = add(new BooleanSetting("Always Show", false)
            .describe("Keep the panel up for the whole match, even while your bed is safe."));
    private final BooleanSetting bars = add(new BooleanSetting("Distance Bars", true)
            .describe("Bar under each name that fills as the player closes in."));
    private final NumberSetting scale = add(HudState.scaleSetting("Scale"));

    private final HudState hud;
    private final ItemStack bedIcon = new ItemStack(Items.bed);
    private final Map<String, Row> rows = new LinkedHashMap<String, Row>();
    private final List<Row> preview = Arrays.asList(
            previewRow("Steve", COLOR_ALERT, 12.0D), previewRow("Alex", 0xFF5555FF, 27.0D));
    private final Animation width = new Animation(MIN_W, ANIM_SPEED);
    private final Animation height = new Animation(0.0, ANIM_SPEED);
    private boolean tracking;
    private WorldClient trackedWorld;
    private BlockPos trackedBedFoot;

    public BedNotification(HudState hud) {
        super("BedNotification", Category.UTILITY,
                "On-screen panel that warns when a non-friend player approaches your bed. Move it in Edit GUI.");
        this.hud = hud;
        hud.registerScale("BedNotification", scale);
    }

    @Override
    protected void onDisable() {
        reset();
        rows.clear();
        height.set(0.0);
    }

    @EventTarget
    public void onUpdate(EventUpdate event) {
        if (!event.isPre()) {
            return;
        }

        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayerSP player = mc.thePlayer;
        WorldClient world = mc.theWorld;
        BlockPos foot = BedTracker.getInstance().ownBedFoot();
        if (player == null || world == null || foot == null || !world.isBlockLoaded(foot, false)
                || !inBedWarsMatch(world.getScoreboard())) {
            reset();
            return;
        }

        IBlockState footState = world.getBlockState(foot);
        if (footState.getBlock() != Blocks.bed) {
            reset();
            return;
        }
        EnumFacing facing = (EnumFacing) footState.getValue(BlockBed.FACING);
        BlockPos head = foot.offset(facing);
        if (!world.isBlockLoaded(head, false) || world.getBlockState(head).getBlock() != Blocks.bed) {
            reset();
            return;
        }

        if (GameStateTracker.getInstance().transitionThisTick()
                || trackedWorld != null && world != trackedWorld
                || trackedBedFoot != null && !foot.equals(trackedBedFoot)) {
            reset();
            return;
        }
        trackedWorld = world;
        trackedBedFoot = foot;
        tracking = true;

        double bedX = (foot.getX() + head.getX() + 1.0D) * 0.5D;
        double bedY = foot.getY() + 0.28125D;
        double bedZ = (foot.getZ() + head.getZ() + 1.0D) * 0.5D;
        int maxDistance = range.get().intValue();
        Scoreboard scoreboard = world.getScoreboard();
        Set<String> seen = new HashSet<String>();
        Integer closest = null;

        for (EntityPlayer target : world.playerEntities) {
            if (!isEligible(player, target)) {
                continue;
            }
            double dx = target.posX - bedX;
            double dy = target.posY - bedY;
            double dz = target.posZ - bedZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            String name = target.getName();
            Row row = rows.get(name);
            Integer previous = row != null ? row.band : null;
            Integer band = nextBand(previous, distance, maxDistance);
            if (band == null) {
                continue;
            }
            if (row == null) {
                row = new Row(name);
                rows.put(name, row);
            }
            row.band = band;
            row.distance = distance;
            row.color = teamColor(scoreboard, name);
            seen.add(name);
            if (distance <= maxDistance && !band.equals(previous)) {
                row.flashAt = System.currentTimeMillis();
                closest = closest == null ? band : Math.min(closest, band);
            }
        }

        for (Row row : rows.values()) {
            if (!seen.contains(row.name)) {
                row.band = null;
            }
        }
        if (closest != null && sound.get()) {
            // pitch rises as the band gets closer
            player.playSound("note.pling", 1.0F, 2.0F - 0.25F * (closest / BAND_SIZE - 1));
        }
    }

    @EventTarget
    public void onRender2D(EventRender2D event) {
        Fonts.load(event.getResolution().getScaleFactor()); // lazy init, needs a live GL context
        if (!Fonts.isLoaded()) {
            return;
        }
        CustomFont font = Fonts.list;
        boolean editing = hud.isEditing();

        List<Row> list = new ArrayList<Row>(rows.values());
        list.sort(ORDER);
        int count = 0;
        for (Row row : list) {
            if (row.band != null) {
                count++;
            }
        }
        if (editing && count == 0) {
            // sample rows so the panel can be placed outside a match
            list = preview;
            count = preview.size();
        }
        boolean visible = editing || tracking && (count > 0 || alwaysShow.get());

        // Active rows sort closest first; leaving rows slide to the bottom while they fade.
        int maxDistance = range.get().intValue();
        for (int i = 0; i < list.size(); i++) {
            Row row = list.get(i);
            double closeness = closeness(row.distance, maxDistance);
            if (row.fresh) {
                row.slot.set(i);
                row.fill.set(closeness);
                row.fresh = false;
            }
            row.shown.update(row.band != null ? 1.0 : 0.0);
            row.slot.update(i);
            row.fill.update(closeness);
        }
        Iterator<Row> it = rows.values().iterator();
        while (it.hasNext()) {
            Row row = it.next();
            if (row.band == null && row.shown.get() < 0.01) {
                it.remove();
            }
        }

        int fontH = font.getHeight();
        int rowH = fontH + (bars.get() ? BAR_GAP + BAR_H : 0) + ROW_GAP;
        int rowsTop = PAD + ICON + SEP_GAP + 1 + SEP_GAP; // from the panel top
        String status = count > 0 ? count + " near" : "Safe";
        int statusW = font.getStringWidth(status);
        int distanceW = font.getStringWidth("00m");
        int contentW = ICON + ICON_GAP + font.getStringWidth(TITLE) + TEXT_GAP + statusW;
        for (Row row : list) {
            contentW = Math.max(contentW, font.getStringWidth(row.name) + TEXT_GAP + distanceW);
        }
        int targetW = Math.max(MIN_W, PAD + contentW + PAD);
        int targetH = count > 0 ? rowsTop + count * rowH - ROW_GAP + PAD : PAD + ICON + PAD;

        double h = height.update(visible ? targetH : 0.0);
        if (h < 1.0) {
            width.set(targetW);
            return;
        }
        int panelW = (int) Math.round(width.update(targetW));
        int panelH = (int) Math.round(h);

        ScaledResolution resolution = event.getResolution();
        HudState.Position pin = hud.getOrCreate(
                "BedNotification", resolution.getScaledWidth() / 2 - MIN_W / 2, 24,
                resolution.getScaledWidth(), resolution.getScaledHeight());
        int left = pin.x;
        int top = pin.y;
        int right = left + panelW;
        int bottom = top + panelH;
        float s = scale.get().floatValue();
        if (editing) {
            hud.report("BedNotification", left, top, right, bottom, left, top, s);
        }

        long now = System.currentTimeMillis();
        float alert = 0.0F;
        float rowsAlpha = 0.0F;
        for (Row row : list) {
            alert = Math.max(alert, flash(row, now) * (float) row.shown.get());
            rowsAlpha = Math.max(rowsAlpha, (float) row.shown.get());
        }

        RenderUtil.pushScale(left, top, s);
        // The panel wipes open from the top, so clip everything to its current height.
        RenderUtil.beginScissor(left, top, panelW * s, panelH * s, resolution.getScaleFactor());
        RenderUtil.drawBorderedRect(left, top, right, bottom, Theme.BODY, Theme.CONTOUR);
        if (alert > 0.0F) {
            RenderUtil.outline(left, top, right, bottom, 1, Theme.applyAlpha(COLOR_ALERT, alert));
        }

        int headerY = top + PAD;
        float textY = headerY + (ICON - fontH) / 2.0F;
        RenderUtil.drawItem(bedIcon, left + PAD, headerY);
        font.drawStringWithShadow(TITLE, left + PAD + ICON + ICON_GAP, textY, Theme.TEXT);
        font.drawStringWithShadow(status, right - PAD - statusW, textY, count > 0 ? COLOR_ALERT : COLOR_SAFE);
        RenderUtil.hLine(left + PAD, right - PAD, headerY + ICON + SEP_GAP, Theme.applyAlpha(Theme.SEP, rowsAlpha));

        int x = left + PAD;
        int innerW = panelW - PAD * 2;
        for (Row row : list) {
            float a = (float) row.shown.get();
            if (a < 0.05F) {
                continue;
            }
            int y = top + rowsTop + (int) Math.round(row.slot.get() * rowH);
            float f = flash(row, now);
            if (f > 0.0F) {
                RenderUtil.rectBounds(left + 1, y - 1, right - 1, y + rowH - ROW_GAP + 1,
                        Theme.applyAlpha(COLOR_ALERT, 0.3F * f * a));
            }
            int heat = heat((float) closeness(row.distance, maxDistance));
            String distance = Math.round(row.distance) + "m";
            font.drawStringWithShadow(row.name, x, y, Theme.applyAlpha(row.color, a));
            font.drawStringWithShadow(distance, x + innerW - font.getStringWidth(distance), y,
                    Theme.applyAlpha(heat, a));
            if (bars.get()) {
                int barY = y + fontH + BAR_GAP;
                RenderUtil.rectBounds(x, barY, x + innerW, barY + BAR_H, Theme.applyAlpha(Theme.WELL, a));
                RenderUtil.rectBounds(x, barY, x + (int) Math.round(innerW * row.fill.get()), barY + BAR_H,
                        Theme.applyAlpha(heat, a));
            }
        }

        RenderUtil.endScissor();
        GlStateManager.popMatrix();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableBlend();
    }

    /** Hypixel's match sidebar lists each team with a ✓ or ✘; lobbies never draw those. */
    private static boolean inBedWarsMatch(Scoreboard scoreboard) {
        ScoreObjective sidebar = scoreboard.getObjectiveInDisplaySlot(1);
        if (sidebar == null) {
            return false;
        }
        for (Score score : scoreboard.getSortedScores(sidebar)) {
            String name = score.getPlayerName();
            if (isTeamStatusLine(ScorePlayerTeam.formatPlayerName(scoreboard.getPlayersTeam(name), name))) {
                return true;
            }
        }
        return false;
    }

    static boolean isTeamStatusLine(String line) {
        return line.indexOf('✓') >= 0 || line.indexOf('✘') >= 0;
    }

    private static boolean isEligible(EntityPlayerSP player, EntityPlayer target) {
        return EntityTargets.isLivingTarget(player, target, false)
                && EntityTargets.classify(target) == EntityTargets.Type.PLAYER
                && !target.isSpectator()
                && !player.isOnSameTeam(target)
                && !FriendManager.getInstance().isFriend(target.getName());
    }

    /** Latches the closest band and holds it through the outer margin against jitter. */
    static Integer nextBand(Integer previous, double distance, int range) {
        if (distance > range + BAND_SIZE) {
            return null;
        }
        if (distance > range) {
            return previous;
        }
        int current = Math.max(BAND_SIZE, (int) Math.ceil(distance / BAND_SIZE) * BAND_SIZE);
        return previous == null || current < previous ? current : previous;
    }

    /** Hypixel tints names with the last colour code in the team prefix. */
    private static int teamColor(Scoreboard scoreboard, String name) {
        ScorePlayerTeam team = scoreboard.getPlayersTeam(name);
        String prefix = team != null ? team.getColorPrefix() : "";
        for (int i = prefix.length() - 2; i >= 0; i--) {
            char code = prefix.charAt(i + 1);
            if (prefix.charAt(i) == '§' && "0123456789abcdef".indexOf(code) >= 0) {
                return 0xFF000000 | Minecraft.getMinecraft().fontRendererObj.getColorCode(code);
            }
        }
        return Theme.TEXT;
    }

    private static double closeness(double distance, int range) {
        return MathHelper.clamp_double(1.0D - distance / range, 0.0D, 1.0D);
    }

    /** Green through yellow to red as the player closes in. */
    private static int heat(float closeness) {
        int red = closeness < 0.5F ? 0x55 + Math.round(0xAA * closeness * 2.0F) : 0xFF;
        int green = closeness < 0.5F ? 0xFF : 0xFF - Math.round(0xAA * (closeness - 0.5F) * 2.0F);
        return RenderUtil.rgb(red, green, 0x55);
    }

    /** Eased 1 to 0 over FLASH_MS after the row's last band crossing. */
    private static float flash(Row row, long now) {
        float f = Math.max(0.0F, 1.0F - (now - row.flashAt) / (float) FLASH_MS);
        return f * f;
    }

    private static Row previewRow(String name, int color, double distance) {
        Row row = new Row(name);
        row.band = BAND_SIZE;
        row.color = color;
        row.distance = distance;
        return row;
    }

    private void reset() {
        for (Row row : rows.values()) {
            row.band = null; // fade out instead of vanishing
        }
        tracking = false;
        trackedWorld = null;
        trackedBedFoot = null;
    }

    private static final class Row {
        final String name;
        final Animation shown = new Animation(0.0, ANIM_SPEED);
        final Animation slot = new Animation(0.0, ANIM_SPEED);
        final Animation fill = new Animation(0.0, ANIM_SPEED);
        Integer band; // null while the row fades out
        double distance;
        int color;
        long flashAt;
        boolean fresh = true;

        Row(String name) {
            this.name = name;
        }
    }
}

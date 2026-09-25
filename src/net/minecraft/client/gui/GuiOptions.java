package net.minecraft.client.gui;

import coldplay.gui.GlassMenuButton;
import coldplay.gui.GlassScreen;
import coldplay.gui.GlassShader;
import coldplay.gui.GlassUi;
import coldplay.gui.Icons;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.audio.SoundCategory;
import net.minecraft.client.audio.SoundEventAccessorComposite;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.EnumDifficulty;

import java.io.IOException;

public class GuiOptions extends GlassScreen implements GuiYesNoCallback {
	private static final String[] DIFFICULTIES = {"Peaceful", "Easy", "Normal", "Hard"};
	private static final float TRACK_W = 450.0F;
	private final GuiScreen field_146441_g;
	/** Reference to the GameSettings object. */
	private final GameSettings game_settings_1;
	private GuiButton lockButton;
	private boolean inWorld;
	private boolean difficultyEditable;
	private boolean draggingFov;
	private float fovY;
	private float difficultyY;
	private float segX;

	public GuiOptions(final GuiScreen p_i1046_1_, final GameSettings p_i1046_2_)
	{
		this.field_146441_g = p_i1046_1_;
		this.game_settings_1 = p_i1046_2_;
	}

	/**
	 * Adds the buttons (and other controls) to the screen in question. Called when the GUI is displayed
	 * and when the window resizes, the buttonList is cleared beforehand.
	 */
	@Override
	public void initGui() {
		this.inWorld = this.mc.theWorld != null;
		this.setCard(480.0F, this.inWorld ? 353.25F : 315.75F);
		this.addBack(200);
		this.fovY = this.cardY + 55.5F;
		float y = this.fovY + 42.0F;
		if (this.inWorld) {
			this.difficultyY = y;
			this.segX = this.cardX + this.cardW - PAD - 25.5F - 9.0F - 255.0F;
			boolean locked = this.mc.theWorld.getWorldInfo().isDifficultyLocked();
			boolean single = this.mc.isSingleplayer() && !this.mc.theWorld.getWorldInfo().isHardcoreModeEnabled();
			this.difficultyEditable = single && !locked;
			this.lockButton = new GlassMenuButton(109, this.cardX + this.cardW - PAD - 25.5F, y, 25.5F, 25.5F,
					"Lock", Icons.Icon.LOCK, GlassMenuButton.Style.ICON, false);
			this.lockButton.enabled = this.difficultyEditable;
			this.buttonList.add(this.lockButton);
			y += 37.5F;
		}
		float colW = (TRACK_W - 6.0F) / 2.0F;
		float left = this.cardX + PAD;
		float right = left + colW + 6.0F;
		y += 0.75F + 10.5F + 9.0F + 6.0F;
		this.row(101, left, y, "Video Settings", Icons.Icon.MONITOR);
		this.row(100, right, y, "Controls", Icons.Icon.KEYBOARD);
		this.row(106, left, y + 31.5F, "Music & Sounds", Icons.Icon.SPEAKER);
		this.row(105, right, y + 31.5F, "Resource Packs", Icons.Icon.LAYERS);
		y += 60.0F + 10.5F + 9.0F + 6.0F;
		this.row(110, left, y, "Skin Customization", Icons.Icon.SHIRT);
		this.row(103, right, y, "Chat Settings", Icons.Icon.CHAT);
		this.row(102, left, y + 31.5F, "Language", Icons.Icon.GLOBE);
		this.row(104, right, y + 31.5F, "Snooper Settings", Icons.Icon.PULSE);
		this.buttonList.add(new GlassMenuButton(8675309, left, y + 63.0F, colW, 28.5F, "Super Secret Settings",
				Icons.Icon.SPARKLE, GlassMenuButton.Style.ROW, false) {
			@Override
			public void playPressSound(final SoundHandler soundHandlerIn) {
				final SoundEventAccessorComposite soundeventaccessorcomposite = soundHandlerIn.getRandomSoundFromCategories(SoundCategory.ANIMALS, SoundCategory.BLOCKS, SoundCategory.MOBS, SoundCategory.PLAYERS, SoundCategory.WEATHER);
				if (soundeventaccessorcomposite != null) soundHandlerIn.playSound(PositionedSoundRecord.create(soundeventaccessorcomposite.getSoundEventLocation(), 0.5F));
			}
		});
	}

	private void row(final int id, final float x, final float y, final String label, final Icons.Icon icon) {
		this.buttonList.add(new GlassMenuButton(id, x, y, (TRACK_W - 6.0F) / 2.0F, 28.5F, label, icon,
				GlassMenuButton.Style.ROW, false));
	}

	@Override
	public void confirmClicked(final boolean result, final int id) {
		this.mc.displayGuiScreen(this);
		if (id == 109 && result && this.mc.theWorld != null) {
			this.mc.theWorld.getWorldInfo().setDifficultyLocked(true);
			this.lockButton.enabled = false;
			this.difficultyEditable = false;
		}
	}

	/**
	 * Called by the controls from the buttonList when activated. (Mouse pressed for buttons)
	 */
	@Override
	protected void actionPerformed(final GuiButton button) throws IOException {
		if (button.enabled) {
			if (button.id == 109) this.mc.displayGuiScreen(new GuiYesNo(this, (new ChatComponentTranslation("difficulty.lock.title")).getFormattedText(),
					(new ChatComponentTranslation("difficulty.lock.question", new ChatComponentTranslation(this.mc.theWorld.getWorldInfo().getDifficulty().getDifficultyResourceKey()))).getFormattedText(), 109));
			if (button.id == 110) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiCustomizeSkin(this));
			}
			if (button.id == 8675309) this.mc.entityRenderer.activateNextShader();
			if (button.id == 101) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiVideoSettings(this, this.game_settings_1));
			}
			if (button.id == 100) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiControls(this, this.game_settings_1));
			}
			if (button.id == 102) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiLanguage(this, this.game_settings_1, this.mc.getLanguageManager()));
			}
			if (button.id == 103) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new ScreenChatOptions(this, this.game_settings_1));
			}
			if (button.id == 104) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiSnooper(this, this.game_settings_1));
			}
			if (button.id == 200) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(this.field_146441_g);
			}
			if (button.id == 105) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiScreenResourcePacks(this));
			}
			if (button.id == 106) {
				this.mc.gameSettings.saveOptions();
				this.mc.displayGuiScreen(new GuiScreenOptionsSounds(this, this.game_settings_1));
			}
		}
	}

	@Override
	protected void drawCard(final int mouseX, final int mouseY, final float partialTicks) {
		this.drawHeader("Client", "Options");
		final float x = this.cardX + PAD;
		final CustomFont label = GlassUi.MEDIUM.get();
		final int fov = Math.round(this.game_settings_1.fovSetting);
		final String value = fov == 70 ? "Normal" : fov == 110 ? "Quake Pro" : String.valueOf(fov);
		final float textY = this.fovY + 3.375F + (12.0F - label.getHeight()) / 2.0F;
		label.drawString("FOV", x, textY, GlassUi.TEXT);
		final CustomFont mono = GlassUi.MONO.get();
		mono.drawString(value, x + TRACK_W - mono.getStringWidth(value), this.fovY + 3.375F + (12.0F - mono.getHeight()) / 2.0F, GlassUi.FROST);
		final float trackY = this.fovY + 24.375F;
		final float pct = (fov - 30.0F) / 80.0F;
		GlassShader.rect(x, trackY, TRACK_W, 2.25F, 1.125F, 0x24FFFFFF, 0x24FFFFFF);
		GlassShader.rect(x, trackY, TRACK_W * pct, 2.25F, 1.125F, GlassUi.FROST, GlassUi.FROST);
		final float knobX = x + TRACK_W * pct;
		GlassShader.rect(knobX - 6.75F, trackY + 1.125F - 6.75F, 13.5F, 13.5F, 6.75F, 0x5984D2E3, 0x5984D2E3);
		GlassShader.rect(knobX - 4.5F, trackY + 1.125F - 4.5F, 9.0F, 9.0F, 4.5F, 0xFFFFFFFF, 0xFFFFFFFF);

		float y = this.fovY + 42.0F;
		if (this.inWorld) {
			label.drawString("Difficulty", x, y + (25.5F - label.getHeight()) / 2.0F, GlassUi.TEXT);
			final boolean hover = this.difficultyEditable && RenderUtil.hovered(mouseX, mouseY, this.segX, y, 255.0F, 25.5F);
			GlassUi.segmented(this.segX, y, 255.0F, 25.5F, DIFFICULTIES, this.mc.theWorld.getDifficulty().getDifficultyId(),
					hover ? GlassUi.segmentAt(this.segX, 255.0F, 4, mouseX) : -1, this.difficultyEditable);
			y += 37.5F;
		}
		GlassUi.divider(x, y, TRACK_W);
		GlassUi.section("Game", x, y + 11.25F);
		GlassUi.section("More", x, y + 11.25F + 15.0F + 60.0F + 10.5F);
		this.drawButtons(mouseX, mouseY, partialTicks);
	}

	@Override
	protected void cardClicked(final int mouseX, final int mouseY, final int button) {
		if (button != 0) {
			return;
		}
		if (RenderUtil.hovered(mouseX, mouseY, this.cardX + PAD - 4.5F, this.fovY + 15.0F, TRACK_W + 9.0F, 15.0F)) {
			this.draggingFov = true;
			this.setFov(mouseX);
		} else if (this.inWorld && this.difficultyEditable && RenderUtil.hovered(mouseX, mouseY, this.segX, this.difficultyY, 255.0F, 25.5F)) {
			final int pick = GlassUi.segmentAt(this.segX, 255.0F, 4, mouseX);
			if (pick >= 0) {
				this.mc.theWorld.getWorldInfo().setDifficulty(EnumDifficulty.getDifficultyEnum(pick));
			}
		}
	}

	@Override
	protected void cardDragged(final int mouseX, final int mouseY, final int button) {
		if (this.draggingFov) {
			this.setFov(mouseX);
		}
	}

	@Override
	protected void cardReleased(final int mouseX, final int mouseY, final int button) {
		this.draggingFov = false;
	}

	private void setFov(final int mouseX) {
		final float pct = Math.max(0.0F, Math.min(1.0F, (mouseX - this.cardX - PAD) / TRACK_W));
		this.game_settings_1.setOptionFloatValue(GameSettings.Options.FOV, Math.round(30.0F + pct * 80.0F));
	}
}

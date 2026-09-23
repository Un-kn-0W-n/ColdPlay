package coldplay.gui.click;

import coldplay.gui.GlassShader;
import coldplay.module.Module;
import coldplay.util.RenderUtil;
import coldplay.util.font.CustomFont;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;
import net.minecraft.client.settings.GameSettings;
import org.lwjglx.input.Keyboard;

/** One module row inside a CategoryPanel. */
public class ModuleButton {

    private static final int PAD = 9;
    private static final float DOT = 3.75F;
    private static final float GLOW = 8.25F;
    private static final int BIND_GAP = 5;
    private static final int ON_FILL = 0x1784D2E3;
    private static final int SELECTED_FILL = 0x3884D2E3;
    private static final int HOVER_FILL = 0x0AFFFFFF;
    private static final int GLOW_FILL = 0x4084D2E3;
    private static final FontRef BIND = new FontRef(Fonts.GEIST_MONO, 7.5F);

    private final Module module;

    public ModuleButton(Module module) {
        this.module = module;
    }

    public Module getModule() {
        return module;
    }

    public void render(int x, int rowY, int width, int mouseX, int mouseY, boolean listening, boolean selected) {
        Skin skin = Skin.SMOKE;
        int h = ClickGuiScreen.ROW_HEIGHT;
        boolean on = module.isEnabled();
        if (selected || on) {
            int fill = selected ? SELECTED_FILL : ON_FILL;
            GlassShader.rect(x, rowY, width, h, 0.0F, fill, fill);
        }
        if (RenderUtil.hovered(mouseX, mouseY, x, rowY, width, h)) {
            GlassShader.rect(x, rowY, width, h, 0.0F, HOVER_FILL, HOVER_FILL);
        }

        CustomFont font = skin.label.get();
        font.drawString(module.getName(), x + PAD, rowY + (h - font.getHeight()) / 2.0F, on ? skin.strong : skin.dim);

        float dx = x + width - PAD - DOT;
        float dy = rowY + (h - DOT) / 2.0F;
        if (on) {
            float g = (GLOW - DOT) / 2.0F;
            GlassShader.rect(dx - g, dy - g, GLOW, GLOW, GLOW / 2.0F, GLOW_FILL, GLOW_FILL);
            GlassShader.rect(dx, dy, DOT, DOT, DOT / 2.0F, skin.accent, skin.accent);
        }
        String bind = listening ? "..." : bindName();
        if (bind != null) {
            CustomFont bf = BIND.get();
            bf.drawString(bind, dx - BIND_GAP - bf.getStringWidth(bind), rowY + (h - bf.getHeight()) / 2.0F,
                    listening ? skin.accent : skin.mute);
        }
    }

    private String bindName() {
        int key = module.getKeyBind();
        if (key == Keyboard.KEY_NONE) {
            return null;
        }
        String name = GameSettings.getKeyDisplayString(key);
        return name == null || name.isEmpty() ? null : name;
    }

    static int preferredWidth(Module module) {
        return Math.round(PAD * 2 + Skin.SMOKE.label.get().getStringWidth(module.getName()) + 8 + DOT
                + BIND_GAP + BIND.get().getStringWidth("RSHIFT"));
    }
}

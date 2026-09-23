package coldplay.gui;

import coldplay.gui.click.ClickGuiScreen;
import coldplay.gui.click.MilkGuiScreen;

import net.minecraft.client.gui.GuiScreen;

/** Which Click GUI the open key shows; switched from the GUI's own settings. */
public enum GuiStyle {
    SMOKE,
    MILK;

    public GuiScreen createScreen() {
        return this == MILK ? new MilkGuiScreen() : new ClickGuiScreen();
    }
}

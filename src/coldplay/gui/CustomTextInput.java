package coldplay.gui;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.ChatAllowedCharacters;
import org.lwjglx.input.Keyboard;

/** Shared append/backspace/delete/paste model for custom-font text fields. */
public final class CustomTextInput
{
    private CustomTextInput()
    {
    }

    public static EditResult edit(String current, char typedChar, int keyCode, int maxLength)
    {
        String value = current == null ? "" : current;
        if (keyCode == Keyboard.KEY_ESCAPE)
        {
            return new EditResult(value, false, false);
        }

        String edited = value;
        if (keyCode == Keyboard.KEY_BACK)
        {
            if (!edited.isEmpty())
            {
                edited = edited.substring(0, edited.length() - 1);
            }
        }
        else if (keyCode == Keyboard.KEY_DELETE)
        {
            edited = "";
        }
        else if (GuiScreen.isKeyComboCtrlV(keyCode))
        {
            edited += ChatAllowedCharacters.filterAllowedCharacters(GuiScreen.getClipboardString());
        }
        else if (ChatAllowedCharacters.isAllowedCharacter(typedChar))
        {
            edited += typedChar;
        }

        if (maxLength >= 0 && edited.length() > maxLength)
        {
            edited = edited.substring(0, maxLength);
        }
        return new EditResult(edited, true, !edited.equals(value));
    }

    public static final class EditResult
    {
        private final String value;
        private final boolean focused;
        private final boolean changed;

        private EditResult(String valueIn, boolean focusedIn, boolean changedIn)
        {
            this.value = valueIn;
            this.focused = focusedIn;
            this.changed = changedIn;
        }

        public String getValue()
        {
            return this.value;
        }

        public boolean isFocused()
        {
            return this.focused;
        }

        public boolean isChanged()
        {
            return this.changed;
        }
    }
}

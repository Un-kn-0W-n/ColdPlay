package coldplay.gui.click;

import coldplay.gui.Glass;
import coldplay.util.font.FontRef;
import coldplay.util.font.Fonts;

/** Colors, fonts and metrics a Click GUI style draws its settings, windows and tooltips with. GUI px. */
final class Skin {

    static final Skin SMOKE = smoke();
    static final Skin MILK = milk();

    boolean milk;

    int text;      // labels
    int strong;    // titles and anything that needs full contrast
    int dim;       // secondary labels and values
    int mute;      // captions and placeholders
    int accent;
    int onAccent;  // text and icons on the accent
    int line;      // hairlines
    int hover;
    int well;      // slider tracks, empty cells
    int field;     // text fields
    int fieldLine;

    Glass glass;   // floating windows: settings, config, tooltip, color picker
    float radius;

    FontRef label;
    FontRef value;
    FontRef title;
    FontRef heading; // popup window titles
    FontRef section;
    float sectionTracking;

    int header;    // window title band
    int rowH;
    int sliderH;
    int sectionH;
    int pad;
    int indent;

    private Skin() {
    }

    private static Skin smoke() {
        Skin s = new Skin();
        s.text = 0xDBFFFFFF;
        s.strong = 0xFFFFFFFF;
        s.dim = 0x9EFFFFFF;
        s.mute = 0x73FFFFFF;
        s.accent = 0xFF84D2E3;
        s.onAccent = 0xFF0B1A1E;
        s.line = 0x14FFFFFF;
        s.hover = 0x0DFFFFFF;
        s.well = 0x24FFFFFF;
        s.field = 0x14FFFFFF;
        s.fieldLine = 0x1FFFFFFF;
        s.glass = Glass.SMOKE_PANEL;
        s.radius = 6.0F;
        s.label = new FontRef(Fonts.GEIST, 9.375F);
        s.value = new FontRef(Fonts.GEIST_MONO, 8.625F);
        s.title = new FontRef(Fonts.GEIST_SEMIBOLD, 9.75F);
        s.heading = s.title;
        s.section = new FontRef(Fonts.GEIST_MEDIUM, 7.5F);
        s.sectionTracking = 0.75F;
        s.header = 24;
        s.rowH = 19;
        s.sliderH = 28;
        s.sectionH = 19;
        s.pad = 9;
        s.indent = 7;
        return s;
    }

    private static Skin milk() {
        Skin s = new Skin();
        s.milk = true;
        s.text = 0xFF10151D;
        s.strong = 0xFF10151D;
        s.dim = 0xFF3A4452;
        s.mute = 0xFF5A6474;
        s.accent = 0xFF2D5BE3;
        s.onAccent = 0xFFFFFFFF;
        s.line = 0x12101520;
        s.hover = 0x0A101520;
        s.well = 0x1F101520;
        s.field = 0xB3FFFFFF;
        s.fieldLine = 0x1A101520;
        s.glass = new Glass(0xE6F6F9FD, 0xE6F6F9FD, 0xCCFFFFFF, 22.5F, 1.8F, 24.0F, 12.0F, 0.25F);
        s.radius = 10.5F;
        s.label = new FontRef(Fonts.JAKARTA_MEDIUM, 9.75F);
        s.value = new FontRef(Fonts.JAKARTA_MEDIUM, 9.75F);
        s.title = new FontRef(Fonts.JAKARTA_BOLD, 15.0F);
        s.heading = new FontRef(Fonts.JAKARTA_BOLD, 11.25F);
        s.section = new FontRef(Fonts.JAKARTA_SEMIBOLD, 8.25F);
        s.sectionTracking = 0.66F;
        s.header = 0; // the Milk window draws its own
        s.rowH = 20;
        s.sliderH = 27;
        s.sectionH = 15;
        s.pad = 11;
        s.indent = 8;
        return s;
    }
}

package coldplay.module.visual;

import coldplay.module.Category;
import coldplay.module.Module;
import coldplay.util.GalaxySky;

public class Ambience extends Module {
    public Ambience() {
        super("Ambience", Category.VISUAL, "Replaces the sky with a dark galaxy and hides clouds. Visual only.");
    }

    @Override
    protected void onEnable() {
        GalaxySky.set(true);
    }

    @Override
    protected void onDisable() {
        GalaxySky.set(false);
    }
}

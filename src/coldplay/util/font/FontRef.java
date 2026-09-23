package coldplay.util.font;

/** A bundled face at a fixed GUI size; rebakes whenever {@link Fonts} rebakes for a new GUI scale. */
public final class FontRef {

    private final String face;
    private final float size;
    private CustomFont font;
    private int generation = -1;

    public FontRef(String face, float size) {
        this.face = face;
        this.size = size;
    }

    /** Call after {@link Fonts#load()} has succeeded. */
    public CustomFont get() {
        if (generation != Fonts.generation()) {
            font = Fonts.bake(face, size);
            generation = Fonts.generation();
        }
        return font;
    }
}

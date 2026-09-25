package coldplay.gui;

/** Scroll state of a list clipped to a box inside a glass card. GUI px. */
public final class GlassList {

    public float x;
    public float y;
    public float w;
    public float h;
    private float scroll;
    private float content;

    public void place(float x, float y, float w, float h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    public void setContent(float height) {
        this.content = height;
        this.scroll = Math.max(0.0F, Math.min(this.scroll, maxScroll()));
    }

    /** Where content y 0 is drawn. */
    public float top() {
        return this.y - this.scroll;
    }

    public boolean contains(int mouseX, int mouseY) {
        return mouseX >= this.x && mouseX < this.x + this.w && mouseY >= this.y && mouseY < this.y + this.h;
    }

    public void wheel(int dWheel, float step) {
        this.scroll -= Math.signum(dWheel) * step;
        setContent(this.content);
    }

    /** Scrolls just enough to show content from {@code from} to {@code to}. */
    public void reveal(float from, float to) {
        if (from < this.scroll) {
            this.scroll = from;
        } else if (to > this.scroll + this.h) {
            this.scroll = to - this.h;
        }
        setContent(this.content);
    }

    public void drawScrollbar() {
        float max = maxScroll();
        if (max <= 0.0F) {
            return;
        }
        float thumb = Math.max(15.0F, this.h * this.h / this.content) - 6.0F;
        float ty = this.y + 3.0F + (this.h - 6.0F - thumb) * this.scroll / max;
        GlassShader.rect(this.x + this.w - 4.5F, ty, 1.5F, thumb, 0.75F, 0x40FFFFFF, 0x40FFFFFF);
    }

    private float maxScroll() {
        return Math.max(0.0F, this.content - this.h);
    }
}

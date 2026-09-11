package coldplay.gui;

import net.minecraft.util.MathHelper;

/** Immutable geometry shared by centered full-screen panel layouts. */
public final class CenteredPanelLayout
{
    private final int left;
    private final int top;
    private final int right;
    private final int bottom;

    private CenteredPanelLayout(int leftIn, int topIn, int rightIn, int bottomIn)
    {
        this.left = leftIn;
        this.top = topIn;
        this.right = rightIn;
        this.bottom = bottomIn;
    }

    public static CenteredPanelLayout create(int screenWidth, int screenHeight,
                                             int maxWidth, int horizontalMargin, int verticalMargin)
    {
        int panelWidth = MathHelper.clamp_int(screenWidth - horizontalMargin * 2, 0, maxWidth);
        int left = (screenWidth - panelWidth) / 2;
        return new CenteredPanelLayout(left, verticalMargin, left + panelWidth,
                Math.max(verticalMargin, screenHeight - verticalMargin));
    }

    public int getLeft()
    {
        return this.left;
    }

    public int getTop()
    {
        return this.top;
    }

    public int getRight()
    {
        return this.right;
    }

    public int getBottom()
    {
        return this.bottom;
    }

    public int getWidth()
    {
        return this.right - this.left;
    }

    public Rect insetHorizontal(int padding, int y, int height)
    {
        return new Rect(this.left + padding, y, Math.max(0, this.getWidth() - padding * 2), height);
    }

    public Rect bottomButton(int padding, int height, int bottomInset)
    {
        return this.insetHorizontal(padding, this.bottom - bottomInset - height, height);
    }

    public static final class Rect
    {
        public final int x;
        public final int y;
        public final int width;
        public final int height;

        private Rect(int xIn, int yIn, int widthIn, int heightIn)
        {
            this.x = xIn;
            this.y = yIn;
            this.width = widthIn;
            this.height = heightIn;
        }
    }
}

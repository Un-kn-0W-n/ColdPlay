package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.util.AxisAlignedBB;

import org.lwjgl.BufferUtils;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Captures the world camera in the world pass, then projects points, box edges and box outlines to framebuffer px
 * so the HUD pass can draw them anti-aliased.
 */
public final class ScreenProjector {
    private final FloatBuffer modelview = BufferUtils.createFloatBuffer(16);
    private final FloatBuffer projection = BufferUtils.createFloatBuffer(16);
    private final IntBuffer viewport = BufferUtils.createIntBuffer(16);
    private final FloatBuffer scratch = BufferUtils.createFloatBuffer(3);
    private final float[] segment = new float[4];
    private double viewerX, viewerY, viewerZ;

    /** Call before beginWorldOverlay, whose viewer translate would otherwise be counted twice. */
    public void capture() {
        ProjectionUtil.captureMatrices(modelview, projection, viewport);
        RenderManager view = Minecraft.getMinecraft().getRenderManager();
        viewerX = view.viewerPosX;
        viewerY = view.viewerPosY;
        viewerZ = view.viewerPosZ;
    }

    public ProjectionUtil.Point point(double x, double y, double z) {
        return ProjectionUtil.projectPoint(x, y, z, viewerX, viewerY, viewerZ, modelview, projection, viewport, scratch);
    }

    public double distance(double x, double y, double z) {
        double dx = x - viewerX, dy = y - viewerY, dz = z - viewerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public double viewerX() {
        return viewerX;
    }

    public double viewerZ() {
        return viewerZ;
    }

    /** Appends the visible part of a to b as {ax, ay, bx, by}; returns the new length of out. */
    public int segment(double ax, double ay, double az, double bx, double by, double bz, float[] out, int n) {
        if (!ProjectionUtil.projectSegment(ax - viewerX, ay - viewerY, az - viewerZ, bx - viewerX, by - viewerY, bz - viewerZ,
                modelview, projection, viewport, segment)) {
            return n;
        }
        System.arraycopy(segment, 0, out, n, 4);
        return n + 4;
    }

    /** The box's 12 edges, or 8 without the bottom ring. Needs 48 floats of room. */
    public int edges(AxisAlignedBB b, boolean bottom, float[] out, int n) {
        for (int i = 0; i < 8; i++) {
            for (int k = 0; k < 3; k++) {
                int j = i | (1 << k);
                if (j == i || (!bottom && (j & 2) == 0)) {
                    continue;
                }
                n = segment(cx(b, i), cy(b, i), cz(b, i), cx(b, j), cy(b, j), cz(b, j), out, n);
            }
        }
        return n;
    }

    /** Only the edges of faces turned toward the viewer, so the box reads solid rather than as a wire cube. */
    public int visibleEdges(AxisAlignedBB b, float[] out, int n) {
        for (int i = 0; i < 8; i++) {
            for (int k = 0; k < 3; k++) {
                int j = i | (1 << k);
                if (j != i && edgeSeen(b, i, k, viewerX, viewerY, viewerZ)) {
                    n = segment(cx(b, i), cy(b, i), cz(b, i), cx(b, j), cy(b, j), cz(b, j), out, n);
                }
            }
        }
        return n;
    }

    /** The edge from corner i along axis k shows when either face it borders faces the viewer at x, y, z. */
    public static boolean edgeSeen(AxisAlignedBB b, int i, int k, double x, double y, double z) {
        return k != 0 && faceSeen(i & 1, x, b.minX, b.maxX)
                || k != 1 && faceSeen(i & 2, y, b.minY, b.maxY)
                || k != 2 && faceSeen(i & 4, z, b.minZ, b.maxZ);
    }

    private static boolean faceSeen(int side, double v, double min, double max) {
        return side == 0 ? v < min : v > max;
    }

    /** Points of the box's screen outline, clockwise, as x,y pairs; 0 when a corner is behind the camera. */
    public int hull(AxisAlignedBB b, float[] out) {
        float[] xs = new float[8];
        float[] ys = new float[8];
        for (int i = 0; i < 8; i++) {
            ProjectionUtil.Point p = point(cx(b, i), cy(b, i), cz(b, i));
            if (p == null) {
                return 0;
            }
            xs[i] = p.x;
            ys[i] = p.y;
        }
        return hull(xs, ys, 8, out);
    }

    /** Monotone chain over n points; writes the outline to out as x,y pairs and returns its point count. */
    public static int hull(float[] xs, float[] ys, int n, float[] out) {
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> xs[a] != xs[b] ? Float.compare(xs[a], xs[b]) : Float.compare(ys[a], ys[b]));
        int[] h = new int[2 * n];
        int k = 0;
        for (int s = 0; s < n; s++) {
            int p = order[s];
            while (k >= 2 && cross(xs, ys, h[k - 2], h[k - 1], p) <= 0) {
                k--;
            }
            h[k++] = p;
        }
        for (int s = n - 2, lower = k + 1; s >= 0; s--) {
            int p = order[s];
            while (k >= lower && cross(xs, ys, h[k - 2], h[k - 1], p) <= 0) {
                k--;
            }
            h[k++] = p;
        }
        int count = k - 1;
        for (int i = 0; i < count; i++) {
            out[i * 2] = xs[h[i]];
            out[i * 2 + 1] = ys[h[i]];
        }
        return count;
    }

    private static float cross(float[] xs, float[] ys, int o, int a, int b) {
        return (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o]);
    }

    private static double cx(AxisAlignedBB b, int i) {
        return (i & 1) == 0 ? b.minX : b.maxX;
    }

    private static double cy(AxisAlignedBB b, int i) {
        return (i & 2) == 0 ? b.minY : b.maxY;
    }

    private static double cz(AxisAlignedBB b, int i) {
        return (i & 4) == 0 ? b.minZ : b.maxZ;
    }
}

package coldplay.util;

import net.minecraft.util.AxisAlignedBB;
import org.lwjgl.opengl.GL11;
import org.lwjglx.util.glu.Project;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/** Shared world-to-screen projection helpers for camera-relative points and hitboxes. */
public final class ProjectionUtil {

    private ProjectionUtil() {
    }

    /** Snapshot the world camera so points can still be projected after the matrices change. */
    public static void captureMatrices(FloatBuffer modelview, FloatBuffer projection, IntBuffer viewport) {
        GL11.glGetFloatv(GL11.GL_MODELVIEW_MATRIX, modelview);
        GL11.glGetFloatv(GL11.GL_PROJECTION_MATRIX, projection);
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
    }

    public static Point projectPoint(double worldX, double worldY, double worldZ,
                                     double viewerX, double viewerY, double viewerZ,
                                     FloatBuffer modelview, FloatBuffer projection,
                                     IntBuffer viewport, FloatBuffer scratch) {
        if (!projectRelativeIntoScratch(worldX - viewerX, worldY - viewerY, worldZ - viewerZ,
                modelview, projection, viewport, scratch)) {
            return null;
        }
        return new Point(scratch.get(0), scratch.get(1), scratch.get(2));
    }

    /**
     * The part of the camera-relative segment from a to b that lies in front of the near plane, in framebuffer
     * px with y down, as {ax, ay, bx, by}. False when all of it is behind. The ends may still be off screen.
     */
    public static boolean projectSegment(double ax, double ay, double az, double bx, double by, double bz,
                                         FloatBuffer modelview, FloatBuffer projection, IntBuffer viewport,
                                         float[] out) {
        double[] a = clip(ax, ay, az, modelview, projection);
        double[] b = clip(bx, by, bz, modelview, projection);
        // in front of the near plane when z >= -w
        double da = a[2] + a[3], db = b[2] + b[3];
        if (da < 0.0 && db < 0.0) {
            return false;
        }
        if (da < 0.0) {
            a = lerp(a, b, da / (da - db));
        } else if (db < 0.0) {
            b = lerp(b, a, db / (db - da));
        }
        toScreen(a, viewport, out, 0);
        toScreen(b, viewport, out, 2);
        return true;
    }

    private static double[] clip(double x, double y, double z, FloatBuffer modelview, FloatBuffer projection) {
        double[] eye = new double[4];
        double[] clip = new double[4];
        for (int row = 0; row < 4; row++) {
            eye[row] = modelview.get(row) * x + modelview.get(4 + row) * y + modelview.get(8 + row) * z + modelview.get(12 + row);
        }
        for (int row = 0; row < 4; row++) {
            clip[row] = projection.get(row) * eye[0] + projection.get(4 + row) * eye[1]
                    + projection.get(8 + row) * eye[2] + projection.get(12 + row) * eye[3];
        }
        return clip;
    }

    private static double[] lerp(double[] from, double[] to, double t) {
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = from[i] + (to[i] - from[i]) * t;
        }
        return out;
    }

    private static void toScreen(double[] clip, IntBuffer viewport, float[] out, int offset) {
        double w = Math.max(clip[3], 1.0E-6);
        out[offset] = (float) (viewport.get(0) + (clip[0] / w + 1.0) / 2.0 * viewport.get(2));
        out[offset + 1] = (float) (viewport.get(3) - (viewport.get(1) + (clip[1] / w + 1.0) / 2.0 * viewport.get(3)));
    }

    /** Projects all eight corners once. */
    public static AabbProjection projectAabb(AxisAlignedBB box,
                                             double viewerX, double viewerY, double viewerZ,
                                             FloatBuffer modelview, FloatBuffer projection,
                                             IntBuffer viewport, FloatBuffer scratch) {
        float left = Float.MAX_VALUE;
        float top = Float.MAX_VALUE;
        float right = -Float.MAX_VALUE;
        float bottom = -Float.MAX_VALUE;
        int visibleCorners = 0;
        boolean rejectedCorner = false;
        int framebufferHeight = viewport.get(3);

        for (int i = 0; i < 8; i++) {
            double x = ((i & 4) == 0 ? box.minX : box.maxX) - viewerX;
            double y = ((i & 2) == 0 ? box.minY : box.maxY) - viewerY;
            double z = ((i & 1) == 0 ? box.minZ : box.maxZ) - viewerZ;
            if (!projectRelativeIntoScratch(x, y, z, modelview, projection, viewport, scratch)) {
                rejectedCorner = true;
                continue;
            }
            float screenX = scratch.get(0);
            float screenY = scratch.get(1);
            visibleCorners++;
            left = Math.min(left, screenX);
            top = Math.min(top, screenY);
            right = Math.max(right, screenX);
            bottom = Math.max(bottom, screenY);
        }

        return new AabbProjection(left, top, right, bottom, visibleCorners, rejectedCorner,
                viewport.get(2), framebufferHeight);
    }

    private static boolean projectRelativeIntoScratch(double relativeX, double relativeY, double relativeZ,
                                                       FloatBuffer modelview, FloatBuffer projection,
                                                       IntBuffer viewport, FloatBuffer scratch) {
        if (!Project.gluProject((float) relativeX, (float) relativeY, (float) relativeZ,
                modelview, projection, viewport, scratch)) {
            return false;
        }
        float depth = scratch.get(2);
        float screenX = scratch.get(0);
        float screenY = viewport.get(3) - scratch.get(1);
        if (depth <= 0.0F || depth >= 1.0F || !finite(screenX) || !finite(screenY)) {
            return false;
        }
        scratch.put(0, screenX);
        scratch.put(1, screenY);
        return true;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    public static final class Point {
        public final float x;
        public final float y;
        public final float depth;

        private Point(float x, float y, float depth) {
            this.x = x;
            this.y = y;
            this.depth = depth;
        }
    }

    public static final class AabbProjection {
        public final float left;
        public final float top;
        public final float right;
        public final float bottom;
        private final int visibleCorners;
        private final boolean rejectedCorner;
        private final int framebufferWidth;
        private final int framebufferHeight;

        private AabbProjection(float left, float top, float right, float bottom,
                               int visibleCorners, boolean rejectedCorner,
                               int framebufferWidth, int framebufferHeight) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.visibleCorners = visibleCorners;
            this.rejectedCorner = rejectedCorner;
            this.framebufferWidth = framebufferWidth;
            this.framebufferHeight = framebufferHeight;
        }

        public boolean hasCompleteBounds() {
            return visibleCorners == 8 && !rejectedCorner;
        }

        /** Boxes straddling the near plane count as visible only within two blocks of the camera. */
        public boolean overlapsViewport(AxisAlignedBB box, double viewerX, double viewerZ) {
            if (visibleCorners == 0) {
                return false;
            }
            if (rejectedCorner) {
                double dx = Math.max(0.0, Math.max(box.minX - viewerX, viewerX - box.maxX));
                double dz = Math.max(0.0, Math.max(box.minZ - viewerZ, viewerZ - box.maxZ));
                if (dx * dx + dz * dz < 4.0) {
                    return true;
                }
            }
            return right >= 0.0F && left <= framebufferWidth
                    && bottom >= 0.0F && top <= framebufferHeight;
        }
    }
}

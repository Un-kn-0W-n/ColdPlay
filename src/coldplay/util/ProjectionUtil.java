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
     * Projects all eight hitbox corners once. The result can service both strict 2D bounding boxes and
     * permissive viewport-overlap checks for off-screen indicators.
     */
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

    /** Projects one camera-relative point and writes screen X/Y/depth into {@code scratch}. */
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

        /** A strict 2D box is safe only when every corner projected inside the depth range. */
        public boolean hasCompleteBounds() {
            return visibleCorners == 8 && !rejectedCorner;
        }

        /**
         * Returns whether any part of the box is on-screen. Near-plane straddling boxes count as visible
         * only when genuinely close to the camera; otherwise the surviving projected corners decide.
         */
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

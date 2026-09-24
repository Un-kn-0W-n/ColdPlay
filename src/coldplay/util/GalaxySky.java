package coldplay.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.WorldRenderer;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;

import java.awt.image.BufferedImage;
import java.util.Random;

/** Galaxy sky drawn by RenderGlobal.renderSky in place of the vanilla overworld sky while Ambience is on. */
public final class GalaxySky {
    private static final double RADIUS = 100.0D;
    private static final int STACKS = 32;
    private static final int SLICES = 64;
    private static final int STARS = 4000;
    // milky way plane normal, in sky space
    private static final double NX = 0.40D;
    private static final double NY = 0.35D;
    private static final double NZ = 0.85D;
    // white, blue-white, pale yellow, pink
    private static final float[][] TINTS = {{1.0F, 1.0F, 1.0F}, {0.75F, 0.85F, 1.0F}, {1.0F, 0.92F, 0.75F}, {1.0F, 0.75F, 0.9F}};

    private static final float[] SPHERE = new float[(STACKS + 1) * (SLICES + 1) * 5];
    private static final float[] STAR_POS = new float[STARS * 12];
    private static final float[] STAR_RGB = new float[STARS * 3];
    private static final float[] STAR_BRIGHT = new float[STARS];
    private static final float[] STAR_PHASE = new float[STARS];
    private static final float[] STAR_SPEED = new float[STARS];

    private static boolean active;
    private static int texture = -1;

    static {
        int k = 0;
        for (int i = 0; i <= STACKS; i++) {
            for (int j = 0; j <= SLICES; j++) {
                double[] d = direction((double) j / SLICES, (double) i / STACKS);
                SPHERE[k++] = (float) (d[0] * RADIUS);
                SPHERE[k++] = (float) (d[1] * RADIUS);
                SPHERE[k++] = (float) (d[2] * RADIUS);
                SPHERE[k++] = (float) j / SLICES;
                SPHERE[k++] = (float) i / STACKS;
            }
        }
        buildStars();
    }

    public static void set(boolean v) {
        active = v;
    }

    public static boolean active() {
        return active;
    }

    public static void render(float partialTicks) {
        if (texture == -1) {
            texture = TextureUtil.uploadTextureImageAllocate(TextureUtil.glGenTextures(), nebula(1024, 512), true, false);
        }
        Tessellator tessellator = Tessellator.getInstance();
        WorldRenderer worldrenderer = tessellator.getWorldRenderer();
        GlStateManager.depthMask(false);
        GlStateManager.disableFog();
        GlStateManager.disableCull();
        GlStateManager.disableAlpha();
        GlStateManager.pushMatrix();
        GlStateManager.rotate(-90.0F, 0.0F, 1.0F, 0.0F);
        GlStateManager.rotate(Minecraft.getMinecraft().theWorld.getCelestialAngle(partialTicks) * 360.0F, 1.0F, 0.0F, 0.0F);

        GlStateManager.enableTexture2D();
        GlStateManager.bindTexture(texture);
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        worldrenderer.begin(7, DefaultVertexFormats.POSITION_TEX);
        for (int i = 0; i < STACKS; i++) {
            for (int j = 0; j < SLICES; j++) {
                sphereVertex(worldrenderer, i, j);
                sphereVertex(worldrenderer, i, j + 1);
                sphereVertex(worldrenderer, i + 1, j + 1);
                sphereVertex(worldrenderer, i + 1, j);
            }
        }
        tessellator.draw();

        GlStateManager.disableTexture2D();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(770, 1, 1, 0);
        double time = System.currentTimeMillis() / 1000.0D;
        worldrenderer.begin(7, DefaultVertexFormats.POSITION_COLOR);
        for (int s = 0; s < STARS; s++) {
            float r = STAR_RGB[s * 3];
            float g = STAR_RGB[s * 3 + 1];
            float b = STAR_RGB[s * 3 + 2];
            float a = STAR_BRIGHT[s] * (float) (0.7D + 0.3D * Math.sin(time * STAR_SPEED[s] + STAR_PHASE[s]));
            for (int c = 0; c < 4; c++) {
                int p = s * 12 + c * 3;
                worldrenderer.pos(STAR_POS[p], STAR_POS[p + 1], STAR_POS[p + 2]).color(r, g, b, a).endVertex();
            }
        }
        tessellator.draw();
        GlStateManager.popMatrix();

        GlStateManager.disableBlend();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.enableTexture2D();
        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.depthMask(true);
    }

    /** Equirectangular nebula map; u runs around the sky, v from top to bottom. */
    public static BufferedImage nebula(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        double[] n = normal();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                double[] d = direction((x + 0.5D) / width, (y + 0.5D) / height);
                image.setRGB(x, y, nebulaColor(d[0], d[1], d[2], n));
            }
        }
        return image;
    }

    private static int nebulaColor(double x, double y, double z, double[] n) {
        double warp = fbm(x * 2.0D + 5.1D, y * 2.0D, z * 2.0D, 3) - 0.5D;
        double plane = x * n[0] + y * n[1] + z * n[2] + warp * 0.3D;
        double band = Math.exp(-plane * plane / 0.05D);
        double core = Math.exp(-plane * plane / 0.006D);

        double clouds = smooth(0.35D, 0.8D, fbm(x * 3.0D, y * 3.0D, z * 3.0D, 5));
        double wisps = smooth(0.55D, 0.85D, fbm(x * 4.0D + 31.7D, y * 4.0D, z * 4.0D, 5));
        double dust = smooth(0.45D, 0.7D, fbm(x * 9.0D + 71.3D, y * 9.0D, z * 9.0D, 4)) * core;
        double hue = smooth(0.3D, 0.7D, fbm(x * 1.6D + 13.9D, y * 1.6D, z * 1.6D, 3));

        double density = (band * (0.25D + 0.75D * clouds) + 0.3D * wisps * (1.0D - band)) * (1.0D - 0.8D * dust);
        // blue -> violet -> magenta
        double r = hue < 0.5D ? lerp(hue * 2.0D, 0.10D, 0.42D) : lerp(hue * 2.0D - 1.0D, 0.42D, 0.70D);
        double g = hue < 0.5D ? lerp(hue * 2.0D, 0.22D, 0.14D) : lerp(hue * 2.0D - 1.0D, 0.14D, 0.12D);
        double b = hue < 0.5D ? lerp(hue * 2.0D, 0.65D, 0.65D) : lerp(hue * 2.0D - 1.0D, 0.65D, 0.45D);
        double glow = core * (1.0D - 0.8D * dust) * 0.12D;

        return 0xFF000000
                | channel(0.012D + r * density * 0.6D + glow * 0.8D) << 16
                | channel(0.008D + g * density * 0.6D + glow * 0.7D) << 8
                | channel(0.030D + b * density * 0.6D + glow);
    }

    private static void buildStars() {
        Random random = new Random(20260923L);
        double[] n = normal();
        // two axes spanning the milky way plane
        double[] p = normalize(new double[] {n[2], 0.0D, -n[0]});
        double[] q = cross(n, p);
        for (int s = 0; s < STARS; s++) {
            double[] d;
            if (random.nextInt(3) == 0) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                double off = random.nextGaussian() * 0.12D;
                d = normalize(new double[] {
                        p[0] * Math.cos(angle) + q[0] * Math.sin(angle) + n[0] * off,
                        p[1] * Math.cos(angle) + q[1] * Math.sin(angle) + n[1] * off,
                        p[2] * Math.cos(angle) + q[2] * Math.sin(angle) + n[2] * off});
            } else {
                d = normalize(new double[] {random.nextGaussian(), random.nextGaussian(), random.nextGaussian()});
            }

            double roll = random.nextFloat();
            double size = roll < 0.7D ? 0.07D + random.nextDouble() * 0.06D
                    : roll < 0.96D ? 0.13D + random.nextDouble() * 0.09D
                    : 0.22D + random.nextDouble() * 0.13D;
            double[] t = normalize(Math.abs(d[1]) > 0.99D ? new double[] {1.0D, 0.0D, 0.0D} : new double[] {-d[2], 0.0D, d[0]});
            double[] u = cross(d, t);
            double spin = random.nextDouble() * Math.PI * 2.0D;
            for (int c = 0; c < 4; c++) {
                double a = spin + c * Math.PI / 2.0D;
                double ca = Math.cos(a) * size;
                double sa = Math.sin(a) * size;
                for (int axis = 0; axis < 3; axis++) {
                    STAR_POS[s * 12 + c * 3 + axis] = (float) (d[axis] * RADIUS + t[axis] * ca + u[axis] * sa);
                }
            }

            System.arraycopy(TINTS[random.nextInt(TINTS.length)], 0, STAR_RGB, s * 3, 3);
            STAR_BRIGHT[s] = 0.35F + random.nextFloat() * 0.65F;
            STAR_PHASE[s] = random.nextFloat() * (float) Math.PI * 2.0F;
            STAR_SPEED[s] = 1.0F + random.nextFloat() * 2.5F;
        }
    }

    private static void sphereVertex(WorldRenderer worldrenderer, int stack, int slice) {
        int k = (stack * (SLICES + 1) + slice) * 5;
        worldrenderer.pos(SPHERE[k], SPHERE[k + 1], SPHERE[k + 2]).tex(SPHERE[k + 3], SPHERE[k + 4]).endVertex();
    }

    private static double[] direction(double u, double v) {
        double theta = Math.PI * v;
        double phi = Math.PI * 2.0D * u;
        return new double[] {Math.sin(theta) * Math.cos(phi), Math.cos(theta), Math.sin(theta) * Math.sin(phi)};
    }

    private static double[] normal() {
        return normalize(new double[] {NX, NY, NZ});
    }

    private static double[] normalize(double[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        return new double[] {v[0] / len, v[1] / len, v[2] / len};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double fbm(double x, double y, double z, int octaves) {
        double sum = 0.0D;
        double amp = 0.5D;
        double total = 0.0D;
        for (int i = 0; i < octaves; i++) {
            sum += amp * noise(x, y, z);
            total += amp;
            x = x * 2.0D + 17.3D;
            y = y * 2.0D + 9.1D;
            z = z * 2.0D + 3.7D;
            amp *= 0.5D;
        }
        return sum / total;
    }

    private static double noise(double x, double y, double z) {
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        int iz = (int) Math.floor(z);
        double fx = fade(x - ix);
        double fy = fade(y - iy);
        double fz = fade(z - iz);
        double x00 = lerp(fx, hash(ix, iy, iz), hash(ix + 1, iy, iz));
        double x10 = lerp(fx, hash(ix, iy + 1, iz), hash(ix + 1, iy + 1, iz));
        double x01 = lerp(fx, hash(ix, iy, iz + 1), hash(ix + 1, iy, iz + 1));
        double x11 = lerp(fx, hash(ix, iy + 1, iz + 1), hash(ix + 1, iy + 1, iz + 1));
        return lerp(fz, lerp(fy, x00, x10), lerp(fy, x01, x11));
    }

    private static double hash(int x, int y, int z) {
        int h = x * 374761393 + y * 668265263 + z * 1440662683;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xFFFF) / 65535.0D;
    }

    private static double fade(double t) {
        return t * t * (3.0D - 2.0D * t);
    }

    private static double smooth(double lo, double hi, double v) {
        double t = Math.max(0.0D, Math.min(1.0D, (v - lo) / (hi - lo)));
        return fade(t);
    }

    private static double lerp(double t, double a, double b) {
        return a + (b - a) * t;
    }

    private static int channel(double v) {
        return (int) (Math.max(0.0D, Math.min(1.0D, v)) * 255.0D);
    }

    private GalaxySky() {
    }
}

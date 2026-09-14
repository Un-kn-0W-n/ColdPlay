package org.lwjglx.opengl;

import org.lwjgl.glfw.*;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjglx.LWJGLException;
import org.lwjglx.input.Keyboard;
import org.lwjglx.input.Mouse;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

public class Display {

    private static final DisplayMode initial_mode;
    private static final long window;
    private static DisplayMode current_mode;
    private static String title = "Minecraft 1.8.9";
    private static boolean created;
    private static boolean resized;
    private static boolean focused;
    private static boolean vsync;
    private static int windowedX, windowedY, windowedWidth, windowedHeight;


    static {
        glfwSetErrorCallback(GLFWErrorCallback.createPrint(System.err));

        if (!glfwInit())
            throw new RuntimeException("Failed to initialize GLFW");

        GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        int bpp = vidMode.redBits() + vidMode.greenBits() + vidMode.blueBits();
        current_mode = initial_mode = new DisplayMode(vidMode.width(), vidMode.height(), bpp, vidMode.refreshRate());

        //INITIAL SETUP
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        //glfwWindowHint(GLFW_DOUBLEBUFFER, GLFW_FALSE);
        glfwWindowHint(GLFW_DECORATED, GLFW_TRUE);


        window = glfwCreateWindow(current_mode.getWidth(), current_mode.getHeight(), title, NULL, NULL);
        if (window == NULL)
            throw new RuntimeException("Failed to create the GLFW window");

        glfwMakeContextCurrent(window);

        GL.createCapabilities();
    }

    public static DisplayMode getDisplayMode() {
        return current_mode;
    }

    public static void setDisplayMode(DisplayMode displayMode) {
        current_mode = displayMode;
        glfwSetWindowSize(window, displayMode.getWidth(), displayMode.getHeight());
        glfwSetWindowPos(
                window,
                (initial_mode.getWidth() - displayMode.getWidth()) / 2,
                (initial_mode.getHeight() - displayMode.getHeight()) / 2
        );
    }

    public static void setFullscreen(boolean fullscreen) {
        if (fullscreen == (glfwGetWindowMonitor(window) != NULL)) return; // already there
        if (fullscreen) {
            int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
            glfwGetWindowPos(window, x, y);
            glfwGetWindowSize(window, w, h);
            windowedX = x[0]; windowedY = y[0]; windowedWidth = w[0]; windowedHeight = h[0];
            long monitor = glfwGetPrimaryMonitor();
            GLFWVidMode mode = glfwGetVideoMode(monitor);
            // borderless-fullscreen (desktop mode, no resolution switch); exclusive mode only if custom resolutions ever land
            glfwSetWindowMonitor(window, monitor, 0, 0, mode.width(), mode.height(), mode.refreshRate());
            current_mode = getDesktopDisplayMode();
        } else {
            glfwSetWindowMonitor(window, NULL, windowedX, windowedY, windowedWidth, windowedHeight, GLFW_DONT_CARE);
            current_mode = new DisplayMode(windowedWidth, windowedHeight);
        }
    }

    public static void setResizable(boolean resizable) {
        glfwWindowHint(GLFW_RESIZABLE, resizable ? 1 : 0);
    }

    public static void setTitle(String titleIn) {
        title = titleIn;
        glfwSetWindowTitle(window, titleIn);
    }

    public static void setIcon(ByteBuffer[] byteBuffers) {//This read correctly byte buffers
        GLFWImage.Buffer imagebf = GLFWImage.malloc(2);
        for (int b = 0; b < byteBuffers.length; b++) {
            System.out.println("loaded icon index -> " + b);

            ByteBuffer image = byteBuffers[b];
            System.out.println("Setting icon " + image);
            int width, height;

            if (b == 0) {
                width = 16;
                height = 16;
            } else {
                width = 32;
                height = 32;
            }

            System.out.println("Width: " + width + ", Height: " + height);

            GLFWImage imagegl = GLFWImage.malloc();
            imagegl.set(width, height, image);
            imagebf.put(imagegl);
        }

        glfwSetWindowIcon(window, imagebf);
        imagebf.free();
    }


    public static void setVSyncEnabled(boolean vsyncIn) {
        if (glfwGetCurrentContext() != 0) {
            glfwSwapInterval(vsyncIn ? GLFW_TRUE : GLFW_FALSE);
        }
        vsync = vsyncIn;
    }

    public static void destroy() {
        glfwDestroyWindow(window);
        created = false;
    }

    public static DisplayMode[] getAvailableDisplayModes() {
        GLFWVidMode.Buffer m = glfwGetVideoModes(glfwGetPrimaryMonitor());
        ArrayList<DisplayMode> modes = new ArrayList<>();
        while (m.hasRemaining()) {
            GLFWVidMode vidMode = m.get();
            int bpp = vidMode.redBits() + vidMode.greenBits() + vidMode.blueBits();
            modes.add(new DisplayMode(vidMode.width(), vidMode.height(), bpp, vidMode.refreshRate()));
        }
        return modes.toArray(new DisplayMode[modes.size()]);
    }

    public static DisplayMode getDesktopDisplayMode() {
        GLFWVidMode vidMode = glfwGetVideoMode(glfwGetPrimaryMonitor());
        int bpp = vidMode.redBits() + vidMode.greenBits() + vidMode.blueBits();
        return new DisplayMode(vidMode.width(), vidMode.height(), bpp, vidMode.refreshRate());
    }

    public static boolean isCreated() {
        return created;
    }

    public static boolean isCloseRequested() {
        return glfwWindowShouldClose(window);
    }

    public static void sync(int fps) {
        Sync.sync(fps);
    }

    public static void update() {
        if (glfwGetWindowAttrib(window, GLFW_VISIBLE) == 1) {
            resized = false;
            glfwSwapBuffers(window);
            Mouse.poll_scrollY = 0;
            glfwPollEvents();
            Mouse.createEvent();
        }
    }

    public static boolean wasResized() {
        return resized;
    }

    public static int getWidth() {
        return current_mode.getWidth();
    }

    public static int getHeight() {
        return current_mode.getHeight();
    }

    public static boolean isActive() {
        return focused;
    }

    public static long getWindow() {
        return window;
    }

    public static void releaseContext() {
        glfwSetWindowShouldClose(window, true);
    }

    public static void create() throws LWJGLException {
        create(new PixelFormat(), new ContextAttribs(3, 3).withProfileCore(true));
    }

    public static void create(PixelFormat pf) throws LWJGLException {
        create(pf, new ContextAttribs(3, 3).withProfileCore(true));
    }

    public static void create(PixelFormat pf, ContextAttribs attributes) throws LWJGLException {
        created = true;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer pWidth = stack.mallocInt(1); // int*
            IntBuffer pHeight = stack.mallocInt(1); // int*

            // Get the window size passed to glfwCreateWindow
            glfwGetWindowSize(window, pWidth, pHeight);

            // Center the window
            glfwSetWindowPos(
                    window,
                    (initial_mode.getWidth() - pWidth.get(0)) / 2,
                    (initial_mode.getHeight() - pHeight.get(0)) / 2
            );
        }
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, attributes.getVersion_major());
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, attributes.getVersion_minor());
        if (attributes.isProfileCore()) glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);

        glfwSetWindowSizeCallback(window, new GLFWWindowSizeCallback() {
            @Override
            public void invoke(long window, int width, int height) {
                current_mode = new DisplayMode(width, height);
                resized = true;
            }
        });
        glfwSetWindowFocusCallback(window, new GLFWWindowFocusCallback() {
            @Override
            public void invoke(long l, boolean focused) {
                Display.focused = focused;
                Mouse.poll_xPos = Mouse.last_x;
                Mouse.poll_yPos = Mouse.last_y;
            }
        });

        Keyboard.create();
        Keyboard.pollGLFW();
        Mouse.create();
        Mouse.pollGLFW();

        //Use raw input, better for 3D camera
        if (glfwRawMouseMotionSupported())
            glfwSetInputMode(window, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);

        glfwSwapInterval(vsync ? GLFW_TRUE : GLFW_FALSE);
        glfwShowWindow(window);
        glfwMaximizeWindow(window);
        glfwPollEvents();
    }
}

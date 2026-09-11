package net.minecraft.client.shader;

import com.google.common.collect.Maps;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.resources.IResourceManager;
import net.minecraft.client.util.JsonException;
import net.minecraft.util.ResourceLocation;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.lwjglx.BufferUtils;

public class ShaderLoader
{
    private final ShaderType shaderType;
    private final String shaderFilename;
    private int shader;
    private int shaderAttachCount = 0;

    private ShaderLoader(ShaderType type, int shaderId, String filename)
    {
        this.shaderType = type;
        this.shader = shaderId;
        this.shaderFilename = filename;
    }

    public void attachShader(ShaderManager manager)
    {
        ++this.shaderAttachCount;
        OpenGlHelper.glAttachShader(manager.getProgram(), this.shader);
    }

    public void deleteShader(ShaderManager manager)
    {
        --this.shaderAttachCount;

        if (this.shaderAttachCount <= 0)
        {
            OpenGlHelper.glDeleteShader(this.shader);
            this.shaderType.getLoadedShaders().remove(this.shaderFilename);
        }
    }

    public String getShaderFilename()
    {
        return this.shaderFilename;
    }

    public static ShaderLoader loadShader(IResourceManager resourceManager, ShaderType type, String filename) throws IOException
    {
        ShaderLoader shaderloader = (ShaderLoader)type.getLoadedShaders().get(filename);

        if (shaderloader == null)
        {
            ResourceLocation resourcelocation = new ResourceLocation("shaders/program/" + filename + type.getShaderExtension());
            BufferedInputStream bufferedinputstream = new BufferedInputStream(resourceManager.getResource(resourcelocation).getInputStream());
            byte[] abyte = toByteArray(bufferedinputstream);
            ByteBuffer bytebuffer = BufferUtils.createByteBuffer(abyte.length);
            bytebuffer.put(abyte);
            bytebuffer.position(0);
            int i = compile(type, bytebuffer, resourcelocation.getResourcePath());

            shaderloader = new ShaderLoader(type, i, filename);
            type.getLoadedShaders().put(filename, shaderloader);
        }

        return shaderloader;
    }

    /**
     * Compiles an intentional inline shader through the same path as resource-backed programs.
     * The caller owns the returned shader id and must delete it after linking or on failure.
     */
    public static int compileSource(ShaderType type, String filename, String source) throws JsonException
    {
        byte[] bytes = source.getBytes(StandardCharsets.US_ASCII);
        ByteBuffer bytebuffer = BufferUtils.createByteBuffer(bytes.length);
        bytebuffer.put(bytes);
        bytebuffer.position(0);
        return compile(type, bytebuffer, filename);
    }

    private static int compile(ShaderType type, ByteBuffer source, String filename) throws JsonException
    {
        int shaderId = OpenGlHelper.glCreateShader(type.getShaderMode());
        OpenGlHelper.glShaderSource(shaderId, source);
        OpenGlHelper.glCompileShader(shaderId);

        if (OpenGlHelper.glGetShaderi(shaderId, OpenGlHelper.GL_COMPILE_STATUS) == 0)
        {
            String log = StringUtils.trim(OpenGlHelper.glGetShaderInfoLog(shaderId, 32768));
            OpenGlHelper.glDeleteShader(shaderId);
            JsonException exception = new JsonException(
                    "Couldn\'t compile " + type.getShaderName() + " program: " + log);
            exception.func_151381_b(filename);
            throw exception;
        }

        return shaderId;
    }

    protected static byte[] toByteArray(BufferedInputStream p_177064_0_) throws IOException
    {
        byte[] abyte;

        try
        {
            abyte = IOUtils.toByteArray((InputStream)p_177064_0_);
        }
        finally
        {
            p_177064_0_.close();
        }

        return abyte;
    }

    public static enum ShaderType
    {
        VERTEX("vertex", ".vsh", OpenGlHelper.GL_VERTEX_SHADER),
        FRAGMENT("fragment", ".fsh", OpenGlHelper.GL_FRAGMENT_SHADER);

        private final String shaderName;
        private final String shaderExtension;
        private final int shaderMode;
        private final Map<String, ShaderLoader> loadedShaders = Maps.<String, ShaderLoader>newHashMap();

        private ShaderType(String p_i45090_3_, String p_i45090_4_, int p_i45090_5_)
        {
            this.shaderName = p_i45090_3_;
            this.shaderExtension = p_i45090_4_;
            this.shaderMode = p_i45090_5_;
        }

        public String getShaderName()
        {
            return this.shaderName;
        }

        protected String getShaderExtension()
        {
            return this.shaderExtension;
        }

        protected int getShaderMode()
        {
            return this.shaderMode;
        }

        protected Map<String, ShaderLoader> getLoadedShaders()
        {
            return this.loadedShaders;
        }
    }
}

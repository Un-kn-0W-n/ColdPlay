package net.minecraft.client.shader;

import java.io.IOException;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.util.JsonException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ShaderLinkHelper
{
    private static final Logger logger = LogManager.getLogger();
    private static ShaderLinkHelper staticShaderLinkHelper;

    public static void setNewStaticShaderLinkHelper()
    {
        staticShaderLinkHelper = new ShaderLinkHelper();
    }

    public static ShaderLinkHelper getStaticShaderLinkHelper()
    {
        return staticShaderLinkHelper;
    }

    public void deleteShader(ShaderManager p_148077_1_)
    {
        p_148077_1_.getFragmentShaderLoader().deleteShader(p_148077_1_);
        p_148077_1_.getVertexShaderLoader().deleteShader(p_148077_1_);
        OpenGlHelper.glDeleteProgram(p_148077_1_.getProgram());
    }

    public int createProgram() throws JsonException
    {
        int i = OpenGlHelper.glCreateProgram();

        if (i <= 0)
        {
            throw new JsonException("Could not create shader program (returned program ID " + i + ")");
        }
        else
        {
            return i;
        }
    }

    /** Links caller-owned shader objects into a program, failing loudly on a bad link. */
    public void linkProgram(int program, int vertexShader, int fragmentShader,
                            String vertexName, String fragmentName) throws JsonException
    {
        OpenGlHelper.glAttachShader(program, vertexShader);
        OpenGlHelper.glAttachShader(program, fragmentShader);
        String log = this.linkAndGetError(program);
        if (log != null)
        {
            throw new JsonException("Couldn\'t link program containing VS " + vertexName
                    + " and FS " + fragmentName + ": " + log);
        }
    }

    public void linkProgram(ShaderManager manager) throws IOException
    {
        manager.getFragmentShaderLoader().attachShader(manager);
        manager.getVertexShaderLoader().attachShader(manager);
        String log = this.linkAndGetError(manager.getProgram());
        if (log != null)
        {
            logger.warn("Error encountered when linking program containing VS " + manager.getVertexShaderLoader().getShaderFilename() + " and FS " + manager.getFragmentShaderLoader().getShaderFilename() + ". Log output:");
            logger.warn(log);
        }
    }

    /** Performs the one canonical link/status/log sequence; callers choose throw versus log policy. */
    private String linkAndGetError(int program)
    {
        OpenGlHelper.glLinkProgram(program);
        return OpenGlHelper.glGetProgrami(program, OpenGlHelper.GL_LINK_STATUS) == 0
                ? OpenGlHelper.glGetProgramInfoLog(program, 32768)
                : null;
    }
}

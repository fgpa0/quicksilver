package dev.quicksilver.shader;

/**
 * Link between the shader pipeline and the terrain renderer. While a provider is set, terrain is meshed in the extended vertex format
 * shader packs need (normals, light map coordinates, block ids, mid texture coordinates, tangents) and the VBO
 * renderer can draw terrain for a shadow pass. Without a provider nothing here is used.
 */
public final class ShaderBridge {
    private ShaderBridge() {}

    /** Generic vertex attribute locations of the extended format (bound by the shader pipeline before linking). */
    public static final int ATTRIB_ENTITY = 10, ATTRIB_MID_TEX = 11, ATTRIB_TANGENT = 12;

    public interface Provider {
        /** mc_Entity.x of a block (called on mesh worker threads). */
        int blockId(int id, int meta, boolean snowAbove);

        /** Keep vanilla's directional face shading in the vertex colours (OptiFine "oldLighting"). */
        boolean oldLighting();

        /** Ambient occlusion in vertex colour alpha instead of multiplied into the colour (OptiFine "separateAo"). */
        boolean separateAo();
    }

    /** Box test in camera-relative coordinates, for the shadow pass. */
    public interface BoxTest {
        boolean visible(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
    }

    private static volatile Provider provider;

    public static Provider provider() {
        return provider;
    }

    /** True while terrain uses (or is switching to) the extended vertex format. */
    public static boolean extended() {
        return provider != null;
    }

    /**
     * Install or remove the provider. Main thread; the caller must reload the world renderer afterwards
     * (all terrain is rebuilt in the new format).
     */
    public static void setProvider(Provider p) {
        provider = p;
    }

    /** Draw terrain of one pass for the shadow map (current GL program and matrices). Returns sections drawn. */
    public static int drawShadowTerrain(int pass, double camX, double camY, double camZ, BoxTest test) {
        return dev.quicksilver.render.vbo.VboRenderer.renderShadow(pass, camX, camY, camZ, test);
    }

    /** True if the VBO terrain renderer is in use (shadow terrain needs it). */
    public static boolean vboActive() {
        return dev.quicksilver.render.vbo.VboRenderer.active();
    }
}

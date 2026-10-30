package com.nebysse.minetomesh.sable;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import java.awt.image.BufferedImage;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes captured faces as Wavefront OBJ + MTL, with each source texture copied out of the GPU.
 *
 * <p>OBJ carries no atlas, no parent and no vertex colour, so three things are decided here
 * instead of in the file:
 *
 * <ul>
 *   <li><b>UVs stay in atlas space.</b> The captured coordinates are what the game samples with,
 *       and every material points at the whole atlas image. Re-normalising them per sprite would
 *       require cropping the atlas, and a face whose model draws from two sprites would then be
 *       written with the wrong one - the atlas has no per-face identity in OBJ.</li>
 *   <li><b>One image per texture the render types name.</b> The block sheets name none (the chunk
 *       renderer binds the atlas itself), so they all fall back to the block atlas, which is read
 *       back with {@code glGetTexImage} - the only way to get a stitched, mipmapped, modded atlas
 *       out of the client, since the image that was stitched never exists as one file.</li>
 *   <li><b>Objects carry the structure.</b> One object per coordinate space: {@code world} for the
 *       ordinary level and {@code subNN_name} for each Sable sub-level, so a hull arrives as a
 *       selectable object rather than as thirty-eight texture buckets.</li>
 * </ul>
 *
 * <p>Axes are converted to Blender's: minecraft is Y-up / Z-forward and Blender is Z-up / -Y
 * forward, so {@code (x, y, z)} becomes {@code (x, z, -y)} and {@code v} becomes {@code 1 - v}.
 */
final class RegionObjWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionObjWriter.class);

    /**
     * One material: a name, the texture it draws with, and how its alpha is meant.
     *
     * @param alpha {@code opaque}, {@code mask} or {@code blend}
     */
    record Material(String name, String textureId, String alpha) {
    }

    /** One face: {@code count} vertices, in minecraft axes and atlas UV space. */
    record Face(String object, String material, float[] position, float[] uv, int count) {
        Face {
            position = position.clone();
            uv = uv.clone();
        }

        @Override
        public float[] position() {
            return position.clone();
        }

        @Override
        public float[] uv() {
            return uv.clone();
        }
    }

    private RegionObjWriter() {
    }

    /**
     * Writes {@code name.obj}, {@code name.mtl} and the textures they reference.
     *
     * @return the path of the written {@code .obj}
     */
    static Path write(Path directory, String name, List<Face> faces,
                      Map<String, Material> materials) throws IOException {
        Files.createDirectories(directory);
        Path textureDirectory = directory.resolve("textures");
        Files.createDirectories(textureDirectory);

        // One file per distinct texture id, so three materials over the block atlas share one PNG.
        Map<String, String> textureFiles = new LinkedHashMap<>();
        for (Material material : materials.values()) {
            String id = material.textureId();
            if (id == null || textureFiles.containsKey(id)) {
                continue;
            }
            textureFiles.put(id, writeTexture(textureDirectory, id));
        }

        Map<String, List<Face>> byObject = new LinkedHashMap<>();
        for (Face face : faces) {
            byObject.computeIfAbsent(face.object(), key -> new ArrayList<>()).add(face);
        }

        // Deduplicate vertices on (position, uv, normal). Sharing them is what turns the export
        // from a cloud of loose triangles into a connected surface in Blender.
        Map<String, Integer> vertexIds = new LinkedHashMap<>();
        List<String> vertexLines = new ArrayList<>();
        List<String> uvLines = new ArrayList<>();
        List<String> normalLines = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        int written = 0;

        for (Map.Entry<String, List<Face>> objectEntry : byObject.entrySet()) {
            body.append("\no ").append(objectEntry.getKey()).append('\n');
            Map<String, List<Face>> byMaterial = new LinkedHashMap<>();
            for (Face face : objectEntry.getValue()) {
                byMaterial.computeIfAbsent(face.material(), key -> new ArrayList<>()).add(face);
            }
            for (Map.Entry<String, List<Face>> materialEntry : byMaterial.entrySet()) {
                body.append("usemtl ").append(materialEntry.getKey()).append('\n');
                for (Face face : materialEntry.getValue()) {
                    float[] position = face.position();
                    float[] uv = face.uv();
                    float[] normal = normalOf(position, face.count());
                    StringBuilder indices = new StringBuilder();
                    for (int vertex = 0; vertex < face.count(); vertex++) {
                        float x = position[vertex * 3];
                        float y = position[vertex * 3 + 2];
                        float z = -position[vertex * 3 + 1];
                        float u = uv[vertex * 2];
                        float v = 1.0f - uv[vertex * 2 + 1];
                        String key = String.format(Locale.ROOT,
                                "%.5f|%.5f|%.5f|%.6f|%.6f|%.3f|%.3f|%.3f",
                                x, y, z, u, v, normal[0], normal[1], normal[2]);
                        Integer id = vertexIds.get(key);
                        if (id == null) {
                            id = vertexIds.size() + 1;
                            vertexIds.put(key, id);
                            vertexLines.add(String.format(Locale.ROOT, "v %.5f %.5f %.5f", x, y, z));
                            uvLines.add(String.format(Locale.ROOT, "vt %.6f %.6f", u, v));
                            normalLines.add(String.format(Locale.ROOT, "vn %.4f %.4f %.4f",
                                    normal[0], normal[1], normal[2]));
                        }
                        if (vertex > 0) {
                            indices.append(' ');
                        }
                        indices.append(id).append('/').append(id).append('/').append(id);
                    }
                    body.append("f ").append(indices).append('\n');
                    written++;
                }
            }
        }

        Path obj = directory.resolve(name + ".obj");
        Path mtl = directory.resolve(name + ".mtl");
        try (BufferedWriter writer = Files.newBufferedWriter(obj)) {
            writer.write("# Sable region export: " + name);
            writer.write("\n# read from the live world through the game's own block model,");
            writer.write("\n# block-entity and atlas pipeline, so every mod part resolves as it");
            writer.write("\n# does in game");
            writer.write("\n# 1 unit = 1 block, Blender axes (X right, Y forward, Z up)");
            writer.write("\n# UVs are in block-atlas space; every material uses the atlas image");
            writer.write("\n# " + vertexIds.size() + " vertices / " + written + " faces");
            writer.write("\nmtllib " + mtl.getFileName() + "\n\n");
            for (String line : vertexLines) {
                writer.write(line);
                writer.write('\n');
            }
            for (String line : uvLines) {
                writer.write(line);
                writer.write('\n');
            }
            for (String line : normalLines) {
                writer.write(line);
                writer.write('\n');
            }
            writer.write(body.toString());
        }

        try (BufferedWriter writer = Files.newBufferedWriter(mtl)) {
            writer.write("# Sable region export: " + name + "\n");
            for (Material material : materials.values()) {
                String file = textureFiles.get(material.textureId());
                writer.write("\nnewmtl " + material.name() + "\n");
                writer.write("Ka 1.000000 1.000000 1.000000\n");
                writer.write("Kd 1.000000 1.000000 1.000000\n");
                writer.write("Ks 0.000000 0.000000 0.000000\n");
                // OBJ has no per-face alpha; the image's own channel decides, so the alpha modes
                // only say which image a viewer should read alpha from.
                writer.write("d 1.000000\n");
                writer.write("illum 2\n");
                if (file != null) {
                    writer.write("map_Kd " + file + "\n");
                    if (!"opaque".equals(material.alpha())) {
                        writer.write("map_d " + file + "\n");
                    }
                } else {
                    writer.write("# no image could be read for " + material.textureId() + "\n");
                }
            }
        }
        LOGGER.info("region: wrote {} ({} vertices, {} faces, {} materials)", obj,
                vertexIds.size(), written, materials.size());
        return obj;
    }

    /** The face normal in Blender axes, computed from the winding so a rotated frame needs no fix. */
    private static float[] normalOf(float[] position, int count) {
        if (position.length < 9 || count < 3) {
            return new float[] {0.0f, 0.0f, 1.0f};
        }
        float ax = position[3] - position[0];
        float ay = position[4] - position[1];
        float az = position[5] - position[2];
        float bx = position[6] - position[0];
        float by = position[7] - position[1];
        float bz = position[8] - position[2];
        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;
        // Minecraft Y-up to Blender Z-up, the same swap the positions go through.
        float bxAxis = nx;
        float byAxis = nz;
        float bzAxis = -ny;
        float length = (float) Math.sqrt(bxAxis * bxAxis + byAxis * byAxis + bzAxis * bzAxis);
        if (!Float.isFinite(length) || length < 1.0e-6f) {
            return new float[] {0.0f, 0.0f, 1.0f};
        }
        return new float[] {bxAxis / length, byAxis / length, bzAxis / length};
    }

    /**
     * Copies one texture out of the GPU and writes it as a PNG.
     *
     * <p>This is the only route to a stitched atlas: the image the game samples is assembled on the
     * GPU from every model, block-entity and modded sprite at load time, and no file on disk holds
     * it. The read is the same one the glTF path uses - bind, ask the level size, download level 0 -
     * and it only works on the render thread, which is where a client command runs.
     *
     * @return the path relative to the OBJ, or {@code null} when the image could not be read
     */
    private static String writeTexture(Path directory, String textureId) {
        int texture = textureId(textureId);
        if (texture <= 0) {
            LOGGER.info("region: texture unreadable id={} reason=not-allocated", textureId);
            return null;
        }
        int previousBinding = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        int previousAlignment = GlStateManager._getInteger(GL11.GL_PACK_ALIGNMENT);
        String fileName = sanitize(textureId) + ".png";
        Path output = directory.resolve(fileName);
        try {
            GlStateManager._bindTexture(texture);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, 1);
            int width = GlStateManager._getTexLevelParameter(
                    GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int height = GlStateManager._getTexLevelParameter(
                    GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (width <= 0 || height <= 0) {
                LOGGER.info("region: texture unreadable id={} reason=size {}x{}", textureId,
                        width, height);
                return null;
            }
            try (NativeImage image = new NativeImage(width, height, false)) {
                image.downloadTexture(0, false);
                BufferedImage buffered = new BufferedImage(width, height,
                        BufferedImage.TYPE_INT_ARGB);
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int abgr = image.getPixelRGBA(x, y);
                        int alpha = abgr >>> 24 & 0xFF;
                        int blue = abgr >>> 16 & 0xFF;
                        int green = abgr >>> 8 & 0xFF;
                        int red = abgr & 0xFF;
                        buffered.setRGB(x, y, alpha << 24 | red << 16 | green << 8 | blue);
                    }
                }
                if (!ImageIO.write(buffered, "PNG", output.toFile())) {
                    LOGGER.info("region: texture unwritable id={} reason=no-png-writer", textureId);
                    return null;
                }
            }
            LOGGER.info("region: texture saved={} size={}x{} bytes={}", fileName, width, height,
                    Files.size(output));
            return "textures/" + fileName;
        } catch (Throwable throwable) {
            LOGGER.info("region: texture unreadable id={} reason={}", textureId,
                    throwable.toString());
            return null;
        } finally {
            GlStateManager._bindTexture(previousBinding);
            GlStateManager._pixelStore(GL11.GL_PACK_ALIGNMENT, previousAlignment);
        }
    }

    /** The GL name behind a texture resource id, or {@code 0} when there is none. */
    private static int textureId(String textureId) {
        try {
            AbstractTexture texture = Minecraft.getInstance().getTextureManager()
                    .getTexture(ResourceLocation.parse(textureId));
            return texture == null ? 0 : texture.getId();
        } catch (Throwable throwable) {
            return 0;
        }
    }

    private static String sanitize(String value) {
        return value == null || value.isBlank()
                ? "atlas"
                : value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }
}

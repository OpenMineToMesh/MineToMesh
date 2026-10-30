package com.nebysse.minetomesh.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nebysse.minetomesh.capture.CapturingVertexConsumer;
import com.nebysse.minetomesh.output.ExportName;
import com.nebysse.minetomesh.output.OutputTransaction;
import com.nebysse.minetomesh.scene.Vertex;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.extensions.IBakedModelExtension;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code /minetomesh sable <name>}: export the blocks inside the area the Sable blueprint tool has
 * selected, including the structures Sable keeps in sub-levels.
 *
 * <p>A saved-blueprint export has a format in between: whatever that format does not carry - a
 * caterpillar track, a wheel, a sub-level's own assembly - has to be guessed at afterwards. This
 * path reads the live world instead. The selection is the one the Sable blueprint tool is already
 * showing ({@link RegionSelection}), and the blocks are the ones the client is drawing, with the
 * block state and block-entity instance the world itself is holding.
 *
 * <p>Three coordinate facts drive the implementation, and each of them was a defect before it was
 * one:
 *
 * <ul>
 *   <li>a sub-level's blocks live in a plot region twenty million blocks away from where the
 *       structure is drawn, so the selection box cannot be used as a block range there: the box is
 *       mapped through the inverse pose to find the blocks, and each block's world position decides
 *       whether it is really inside the selection ({@link SubLevelPositions});</li>
 *   <li>the frame that turns a space's blocks into export coordinates must be composed in
 *       {@code double} and narrowed once, or every space keeps its own few blocks of error and two
 *       structures of one selection stop lining up ({@link SubLevelFrames});</li>
 *   <li>a block entity whose appearance lives in a Flywheel visual writes no vertices at all until
 *       the visual engine's answer is turned off for the pass ({@link FlywheelGate}).</li>
 * </ul>
 *
 * <p>The geometry is written as Wavefront OBJ + MTL with the block atlas copied out of the GPU,
 * because that is what the downstream Blender pipeline consumes. UVs stay in atlas space and every
 * material points at the same atlas image, so a face is textured exactly where the game draws it
 * even when a model uses several sprites.
 *
 * <p>Known boundaries are listed in {@code README-SABLE-EXPORT.md} beside this package: no entity
 * (contraption/visual) export, no per-block tint, and a block whose model has no quads falls back to
 * the skip log rather than to a bounding box.
 */
public final class RegionExport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionExport.class);

    /** How many blocks one export may cover: 64 x 64 x 64. */
    public static final int MAX_BLOCKS = 64 * 64 * 64;

    /** How many {@code region: skip} lines one export may write. */
    private static final int SKIP_LINES = 20;

    /** How many {@code region-block} / {@code region-ber} lines one export may write, each. */
    private static final int DIAG_LINES = 40;

    /** How far a space's geometry may sit from its own blocks before it is called drift. */
    private static final float SPACE_TOLERANCE = 0.5f;

    /** The block atlas, used for every material whose render type names no texture of its own. */
    private static final String BLOCK_ATLAS =
            ResourceLocation.withDefaultNamespace("textures/atlas/blocks.png").toString();

    /** Every direction plus the null one, which is where a model keeps its unculled quads. */
    private static final List<Direction> DIRECTIONS = directions();

    private RegionExport() {
    }

    /**
     * Runs the export, answering in chat and in the log rather than by throwing.
     *
     * @param source the command source, so a refusal reaches the player
     * @param name   file name stem of the export
     */
    public static int run(CommandSourceStack source, String name) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            LOGGER.info("region: refused reason=no-world");
            source.sendFailure(Component.literal(
                    "minetomesh sable: join a world first (no world loaded)"));
            return 0;
        }
        if (!minecraft.isSameThread()) {
            // Baked models, block-entity renderers and the atlas readback are client-thread state.
            LOGGER.info("region: refused reason=wrong-thread");
            source.sendFailure(Component.literal(
                    "minetomesh sable: run this from the client thread"));
            return 0;
        }

        RegionSelection.Box selection = RegionSelection.read();
        if (selection == null) {
            LOGGER.info("region: refused reason=no-selection tool-present={} class={}",
                    RegionSelection.available(), RegionSelection.SESSION_CLASS);
            source.sendFailure(Component.literal(
                    "minetomesh sable: no selection - select a region with the Sable blueprint tool"
                            + " (left click two corners) and run the command again"));
            return 0;
        }
        if (!selection.bothCorners()) {
            LOGGER.info("region: refused reason=one-corner aabb={}", selection.describe());
            source.sendFailure(Component.literal("minetomesh sable: only one corner is set ("
                    + selection.describe() + ") - click the opposite corner"));
            return 0;
        }
        long volume = selection.volume();
        if (volume <= 0 || volume > MAX_BLOCKS) {
            LOGGER.info("region: refused reason=too-large aabb={} volume={} limit={}",
                    selection.describe(), volume, MAX_BLOCKS);
            source.sendFailure(Component.literal("minetomesh sable: selection is too large ("
                    + volume + " blocks > limit " + MAX_BLOCKS + ")"));
            return 0;
        }
        ExportName exportName;
        try {
            exportName = ExportName.parse(name);
        } catch (IllegalArgumentException exception) {
            LOGGER.info("region: refused reason=unsafe-name name={} ({})", name,
                    exception.getMessage());
            source.sendFailure(Component.literal("minetomesh sable: " + exception.getMessage()));
            return 0;
        }

        try {
            Result result = export(minecraft, exportName, selection);
            source.sendSuccess(() -> Component.literal("minetomesh sable: " + result.obj()
                    + " (" + result.faces() + " faces, " + result.blocks() + " blocks, "
                    + result.spaces() + " spaces)"), false);
            return 1;
        } catch (Throwable throwable) {
            LOGGER.error("region: export failed name={}", exportName.value(), throwable);
            source.sendFailure(Component.literal("minetomesh sable failed: " + throwable));
            return 0;
        }
    }

    /** What one export produced, for the command's answer. */
    public record Result(Path obj, int faces, int blocks, int spaces) {
    }

    // ------------------------------------------------------------------ the export

    private static Result export(Minecraft minecraft, ExportName name, RegionSelection.Box selection)
            throws IOException {
        Level level = Objects.requireNonNull(minecraft.level, "level");
        AABB box = selection.aabb();
        int minX = (int) Math.floor(box.minX);
        int minY = (int) Math.floor(box.minY);
        int minZ = (int) Math.floor(box.minZ);
        int maxX = Math.max(minX, (int) Math.ceil(box.maxX) - 1);
        int maxY = Math.max(minY, (int) Math.ceil(box.maxY) - 1);
        int maxZ = Math.max(minZ, (int) Math.ceil(box.maxZ) - 1);
        long volume = (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        if (volume > MAX_BLOCKS) {
            throw new IOException("region too large: " + volume + " blocks, limit " + MAX_BLOCKS);
        }

        // Everything is written around the selection's own corner: a world-coordinate mesh a few
        // thousand blocks from the origin is unusable in Blender, and the numbers lose precision.
        Vec3 origin = new Vec3(minX, minY, minZ);
        BlockPos originPos = new BlockPos(minX, minY, minZ);
        Tally tally = new Tally();

        List<Space> spaces = new ArrayList<>();
        Space world = new Space("world", "world", 0, SubLevelFrames.world(originPos), originPos,
                level);
        scanWorld(level, minX, minY, minZ, maxX, maxY, maxZ, world, tally);
        spaces.add(world);
        scanSubLevels(level, box, origin, spaces, tally);

        LOGGER.info("region: selection source={} reader={} aabb=({}, {}, {})..({}, {}, {})"
                        + " volume={} blocks={}", RegionSelection.SESSION_CLASS, selection.detail(),
                minX, minY, minZ, maxX, maxY, maxZ, volume, tally.examined);

        BlockRenderDispatcher blockRenderer = minecraft.getBlockRenderer();
        BlockEntityRenderDispatcher blockEntityRenderer = minecraft.getBlockEntityRenderDispatcher();
        Collection collection = new Collection();

        for (Space space : spaces) {
            logSpace(space);
            modelPass(space, blockRenderer, collection, tally);
        }
        // The gate is taken around the whole block-entity pass rather than per renderer call: the
        // backend is one static field, and every renderer in the pass has to see the same answer.
        boolean gated = FlywheelGate.engage();
        try {
            for (Space space : spaces) {
                berPass(space, blockEntityRenderer, collection, tally);
            }
        } finally {
            // Released before anything else: the visual engine's own answer has to be true again
            // for the rest of the frame, whatever a renderer did.
            FlywheelGate.release(gated);
        }

        List<RegionObjWriter.Face> faces = collection.finish(spaces);
        if (faces.isEmpty()) {
            throw new IOException("nothing to export: blocks=" + tally.examined
                    + ", block entities=" + tally.blockEntities + ", selection=" + minX + ","
                    + minY + "," + minZ + ".." + maxX + "," + maxY + "," + maxZ);
        }
        checkSpaces(spaces, faces);

        Path exportRoot = minecraft.gameDirectory.toPath().resolve("minetomesh-exports");
        Path obj;
        try (OutputTransaction transaction = OutputTransaction.begin(exportRoot, name)) {
            Path directory = transaction.temporaryDirectory();
            obj = RegionObjWriter.write(directory, name.value(), faces, collection.materials());
            Path published = transaction.publish();
            obj = published.resolve(directory.relativize(obj));
        }

        LOGGER.info("region: baked blocks={} examined={} quads={} entities=0 ber={} materials={}"
                        + " skipped={} fluids={} spaces={} reasons={}",
                tally.produced, tally.examined, faces.size(), tally.berDrawn,
                collection.materials().size(), tally.skipped, tally.fluids, spaces.size(),
                tally.reasons);
        return new Result(obj, faces.size(), tally.produced, spaces.size());
    }

    /** One coordinate space the export draws from: the world, or one sub-level's storage. */
    private static final class Space {
        final String label;
        /** The structure's own name, for the object line; {@code world} for the ordinary world. */
        final String name;
        /** Object index in the .obj; 0 is the world, sub-levels follow. */
        final int index;
        /** Base-relative coordinates of this space to export coordinates. */
        final SubLevelFrames.Frame frame;
        /** The corner subtracted from every block position before the frame. */
        final BlockPos base;
        /** The level the blocks are read from. */
        final Level level;
        final Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();
        /** Blocks the model pass drew nothing for; the renderer pass may still draw them. */
        final List<Pending> pending = new ArrayList<>();
        /** Whether any renderer in this space wrote a vertex. */
        int berVertices;

        Space(String label, String name, int index, SubLevelFrames.Frame frame, BlockPos base,
              Level level) {
            this.label = label;
            this.name = name == null || name.isBlank() ? label : name;
            this.index = index;
            this.frame = frame == null ? SubLevelFrames.world(base) : frame;
            this.base = base;
            this.level = level;
        }

        BlockPos relative(BlockPos pos) {
            return pos.subtract(base);
        }

        String object() {
            // The object name is what a Blender outliner shows, so it carries the structure's own
            // name once rather than the log label (which repeats the index as `sub1:name`).
            return index == 0 ? "world"
                    : "sub" + String.format(Locale.ROOT, "%02d", index) + "_" + sanitize(name);
        }
    }

    /** A block the model pass drew nothing for, and why. */
    private record Pending(BlockPos pos, String block, String reason) {
    }

    // ------------------------------------------------------------------ scanning

    /** The world-space blocks inside the selection, read from the client level as it stands. */
    private static void scanWorld(Level level, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ, Space space, Tally tally) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state;
                    try {
                        state = level.getBlockState(cursor);
                    } catch (Throwable throwable) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    // Water is a block with no geometry of its own; a selection beside a river
                    // would otherwise fill the skip log with it.
                    if (!state.getFluidState().isEmpty()) {
                        tally.fluids++;
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    space.states.put(pos, state);
                    tally.examined++;
                    try {
                        BlockEntity blockEntity = level.getBlockEntity(pos);
                        if (blockEntity != null) {
                            space.blockEntities.put(pos, blockEntity);
                            tally.blockEntities++;
                        }
                    } catch (Throwable ignored) {
                        // a block entity that cannot be read contributes its model only
                    }
                }
            }
        }
    }

    /**
     * Every sub-level that intersects the selection, mapped into its own storage coordinates.
     *
     * <p>The box is mapped through the inverse pose and the resulting range is what gets walked,
     * because the storage range and the world range have nothing to do with each other. Each
     * block's own world position then decides whether it is inside the selection, which is what
     * makes a rotated or scaled sub-level come out right.
     */
    private static void scanSubLevels(Level level, AABB box, Vec3 origin, List<Space> spaces,
                                      Tally tally) {
        List<SubLevelPositions.Handle> handles = SubLevelPositions.intersecting(level, box);
        if (handles.isEmpty()) {
            LOGGER.info("region: sub-levels=0 sable={} (no Sable structure intersects the selection)",
                    SubLevelPositions.available());
            return;
        }
        int index = 1;
        for (SubLevelPositions.Handle handle : handles) {
            try {
                Space space = scanOneSubLevel(level, box, origin, handle, index, tally);
                if (space != null) {
                    spaces.add(space);
                    index++;
                }
            } catch (Throwable throwable) {
                LOGGER.warn("region: sub-level skipped ({}): {}", handle.name(), throwable.toString());
            }
        }
    }

    private static Space scanOneSubLevel(Level level, AABB box, Vec3 origin,
                                         SubLevelPositions.Handle handle, int index, Tally tally) {
        // The box as seen from inside the sub-level. Eight corners are enough: the pose is affine,
        // so the image of an axis-aligned box is a parallelepiped whose bounds are the bounds of
        // its image corners.
        double minX = Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        for (int corner = 0; corner < 8; corner++) {
            double x = (corner & 1) == 0 ? box.minX : box.maxX;
            double y = (corner & 2) == 0 ? box.minY : box.maxY;
            double z = (corner & 4) == 0 ? box.minZ : box.maxZ;
            Vector3d local = handle.toLocal(x, y, z);
            if (!Double.isFinite(local.x) || !Double.isFinite(local.y) || !Double.isFinite(local.z)) {
                return null;
            }
            minX = Math.min(minX, local.x);
            minY = Math.min(minY, local.y);
            minZ = Math.min(minZ, local.z);
            maxX = Math.max(maxX, local.x);
            maxY = Math.max(maxY, local.y);
            maxZ = Math.max(maxZ, local.z);
        }
        BlockPos base = new BlockPos((int) Math.floor(minX), (int) Math.floor(minY),
                (int) Math.floor(minZ));
        SubLevelFrames.Frame frame = SubLevelFrames.compose(
                new Vector3d(origin.x, origin.y, origin.z), handle.pose(), base);
        String label = "sub" + index + ":" + handle.name();
        Space space = new Space(label, handle.name(), index, frame, base, level);

        int fromX = (int) Math.floor(minX) - 1;
        int fromY = (int) Math.floor(minY) - 1;
        int fromZ = (int) Math.floor(minZ) - 1;
        int toX = (int) Math.ceil(maxX) + 1;
        int toY = (int) Math.ceil(maxY) + 1;
        int toZ = (int) Math.ceil(maxZ) + 1;
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    cursor.set(x, y, z);
                    BlockState state;
                    try {
                        // The block may be in the plot level or in the root level; the helper
                        // asks every level that could hold it.
                        state = SubLevelPositions.blockState(level, cursor);
                    } catch (Throwable throwable) {
                        continue;
                    }
                    if (state == null || state.isAir()) {
                        continue;
                    }
                    Vector3d centre = handle.toWorld(x + 0.5, y + 0.5, z + 0.5);
                    if (!box.contains(centre.x, centre.y, centre.z)) {
                        continue;
                    }
                    if (!state.getFluidState().isEmpty()) {
                        tally.fluids++;
                        continue;
                    }
                    BlockPos pos = cursor.immutable();
                    space.states.put(pos, state);
                    tally.examined++;
                    try {
                        BlockEntity blockEntity = SubLevelPositions.blockEntity(level, pos);
                        if (blockEntity != null) {
                            space.blockEntities.put(pos, blockEntity);
                            tally.blockEntities++;
                        }
                    } catch (Throwable ignored) {
                        // model only, as above
                    }
                }
            }
        }
        return space.states.isEmpty() ? null : space;
    }

    // ------------------------------------------------------------------ geometry

    /**
     * The model pass for one space: every block's own baked model, drawn through the same vertex
     * formats the game uses, with one verdict per block.
     */
    private static void modelPass(Space space, BlockRenderDispatcher dispatcher,
                                  Collection collection, Tally tally) {
        RandomSource random = RandomSource.create();
        for (Map.Entry<BlockPos, BlockState> entry : space.states.entrySet()) {
            BlockPos pos = entry.getKey();
            BlockState state = entry.getValue();
            String block = blockId(state);
            if (state.getRenderShape() != RenderShape.MODEL) {
                // The geometry lives in a renderer, which the block-entity pass runs at the end.
                space.pending.add(new Pending(pos, block, "unsupported"));
                continue;
            }
            BakedModel model;
            try {
                model = dispatcher.getBlockModel(state);
            } catch (Throwable throwable) {
                space.pending.add(new Pending(pos, block, "exception"));
                continue;
            }
            if (model == null) {
                space.pending.add(new Pending(pos, block, "no-model"));
                continue;
            }
            IBakedModelExtension extension =
                    model instanceof IBakedModelExtension candidate ? candidate : null;
            ModelData modelData = ModelData.EMPTY;
            if (extension != null) {
                // A plot block's model data lives on the level that holds the block entity, which
                // is not always the level this space was opened with.
                BlockEntity blockEntity = space.blockEntities.get(pos);
                Level dataLevel = blockEntity == null || blockEntity.getLevel() == null
                        ? space.level : blockEntity.getLevel();
                try {
                    modelData = extension.getModelData(dataLevel, pos, state, ModelData.EMPTY);
                } catch (Throwable throwable) {
                    modelData = ModelData.EMPTY;
                }
            }
            List<RenderType> layers = new ArrayList<>();
            if (extension != null) {
                try {
                    for (RenderType layer : extension.getRenderTypes(state, random, modelData)) {
                        layers.add(layer);
                    }
                } catch (Throwable ignored) {
                    // a model that cannot answer keeps the state's own sheet below
                }
            }
            if (layers.isEmpty()) {
                RenderType single = null;
                try {
                    single = ItemBlockRenderTypes.getChunkRenderType(state);
                } catch (Throwable ignored) {
                    // no sheet to ask for
                }
                layers.add(single == null ? RenderType.solid() : single);
            }

            // `frame . T(block - base)`: the frame first, so the pose a quad goes through is the
            // product and never the float-composed translation on its own.
            PoseStack pose = new PoseStack();
            pose.mulPose(space.frame.render());
            BlockPos relative = space.relative(pos);
            pose.translate(relative.getX(), relative.getY(), relative.getZ());
            PoseStack.Pose last = pose.last();

            int produced = 0;
            boolean failed = false;
            for (RenderType layer : layers) {
                VertexConsumer consumer = collection.consumer(space.index, layer);
                for (Direction direction : DIRECTIONS) {
                    List<BakedQuad> quads;
                    try {
                        quads = extension == null
                                ? model.getQuads(state, direction, random)
                                : extension.getQuads(state, direction, random, modelData, layer);
                    } catch (Throwable throwable) {
                        continue;
                    }
                    if (quads == null) {
                        continue;
                    }
                    for (BakedQuad quad : quads) {
                        if (quad == null) {
                            continue;
                        }
                        try {
                            // Full brightness: the export carries no lighting, and a renderer that
                            // multiplies by the packed light comes out black at zero.
                            consumer.putBulkData(last, quad, 1.0f, 1.0f, 1.0f, 1.0f,
                                    LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                            produced++;
                        } catch (Throwable throwable) {
                            failed = true;
                        }
                    }
                }
            }
            if (produced > 0) {
                tally.produced++;
            } else {
                space.pending.add(new Pending(pos, block, failed ? "exception" : "no-model"));
            }
            if (tally.wantBlockLine() || tally.firstBlockKind(block)) {
                LOGGER.info("region-block: id={} state={} shape={} quads={} be={}",
                        block, state.toString(), produced > 0 ? "baked-quads"
                                : "entity-or-empty", produced,
                        space.blockEntities.containsKey(pos));
            }
        }
    }

    /**
     * The block-entity pass for one space.
     *
     * <p>The renderer is handed an empty pose: it writes relative to its own block and follows
     * whatever pose it is given, and the consumer adds {@code frame . T(block - base)}. Giving it
     * both would count the placement twice - the mistake that made sub-level geometry span four
     * units instead of twenty-one.
     *
     * <p>It is called directly rather than through
     * {@link BlockEntityRenderDispatcher#render}, because the dispatcher asks
     * {@code shouldRender(blockEntity, camera)} first and the default answer is "within view
     * distance of the camera". A sub-level's blocks sit twenty million blocks from where they are
     * drawn, so every one of them would be culled - and so would half of a large world selection.
     * The dispatcher is still used for the renderer lookup, which is a plain type map.
     */
    private static void berPass(Space space, BlockEntityRenderDispatcher dispatcher,
                                Collection collection, Tally tally) {
        if (space.blockEntities.isEmpty()) {
            resolvePending(space, false, tally);
            return;
        }
        float partialTick = partialTick();
        PoseStack pose = new PoseStack();
        for (Map.Entry<BlockPos, BlockEntity> entry : space.blockEntities.entrySet()) {
            BlockEntity blockEntity = entry.getValue();
            BlockEntityRenderer<BlockEntity> renderer = null;
            try {
                renderer = dispatcher.getRenderer(blockEntity);
            } catch (Throwable throwable) {
                LOGGER.info("region-ber: id={} reason=lookup-failed ({})",
                        blockId(blockEntity.getBlockState()), throwable.getClass().getSimpleName());
            }
            if (renderer == null) {
                if (tally.wantBerLine()) {
                    LOGGER.info("region-ber: id={} ber=- ok=false quads=0 reason=no-renderer",
                            blockId(blockEntity.getBlockState()));
                }
                continue;
            }
            BlockPos relative = space.relative(entry.getKey());
            Matrix4f placement = new Matrix4f(space.frame.render())
                    .translate(relative.getX(), relative.getY(), relative.getZ());
            BerSource source = new BerSource(collection, space.index, placement);
            try {
                pose.pushPose();
                // Full brightness rather than 0, and NO_OVERLAY rather than 0: zero is a real
                // overlay value (a hurt flash), not "none".
                renderer.render(blockEntity, partialTick, pose, source,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
                space.berVertices += source.vertices;
                if (source.vertices > 0) {
                    tally.berDrawn++;
                } else if (tally.wantBerLine()) {
                    LOGGER.info("region-ber: id={} ber={} ok=false quads=0 reason=drew-nothing",
                            blockId(blockEntity.getBlockState()),
                            renderer.getClass().getSimpleName());
                }
            } catch (Throwable throwable) {
                try {
                    pose.popPose();
                } catch (Throwable ignored) {
                    // the stack is rebuilt per block anyway
                }
                if (tally.wantBerLine()) {
                    LOGGER.info("region-ber: id={} ber={} ok=false reason={}: {}",
                            blockId(blockEntity.getBlockState()),
                            renderer.getClass().getSimpleName(),
                            throwable.getClass().getSimpleName(), throwable.getMessage());
                }
            }
        }
        resolvePending(space, space.berVertices > 0, tally);
    }

    /**
     * Settles the blocks the model pass drew nothing for.
     *
     * <p>A block whose shape lives in its renderer emits no model geometry and is drawn by the
     * block-entity pass, so reporting it as skipped would say the export lost a part that is in
     * the file.
     */
    private static void resolvePending(Space space, boolean rendererDrew, Tally tally) {
        for (Pending pending : space.pending) {
            if (rendererDrew && space.blockEntities.containsKey(pending.pos())) {
                tally.produced++;
            } else {
                tally.skip(pending.block(), pending.reason());
            }
        }
        space.pending.clear();
    }

    /**
     * The {@code region-space-check} lines: where a space's geometry landed, against where its own
     * blocks say it should land.
     *
     * <p>Both boxes are in export coordinates and the verdict is the distance between their
     * centres. A space whose frame translation is a few blocks out reads that error here even
     * though its own parts agree with each other - which is the multi-structure fault exactly.
     */
    private static void checkSpaces(List<Space> spaces, List<RegionObjWriter.Face> faces) {
        for (Space space : spaces) {
            try {
                float[] blockBox = space.frame.blockBounds(space.states.keySet(), space.base);
                float[] vertexBox = vertexBounds(faces, space.object());
                if (blockBox == null && vertexBox == null) {
                    continue;
                }
                float worst = 0f;
                if (blockBox != null && vertexBox != null) {
                    for (int axis = 0; axis < 3; axis++) {
                        worst = Math.max(worst,
                                Math.abs(centre(vertexBox, axis) - centre(blockBox, axis)));
                    }
                }
                LOGGER.info("region-space-check: space={} name={} frame-t=({}) drift=({})"
                                + " blocks={} verts-bbox=({})..({}) block-bbox=({})..({}) verdict={}",
                        space.index, space.label, triple(space.frame.render().m30(),
                                space.frame.render().m31(), space.frame.render().m32()),
                        triple(space.frame.drift()), space.states.size(),
                        triple(vertexBox, 0), triple(vertexBox, 3),
                        triple(blockBox, 0), triple(blockBox, 3),
                        blockBox == null ? "no-reference"
                                : vertexBox == null ? "no-geometry"
                                : worst <= SPACE_TOLERANCE ? "ok" : "drift");
            } catch (Throwable throwable) {
                LOGGER.info("region-space-check: space={} name={} verdict=unavailable ({})",
                        space.index, space.label, throwable.getClass().getSimpleName());
            }
        }
    }

    private static void logSpace(Space space) {
        LOGGER.info("region: space index={} name={} blocks={} block-entities={} frame-t=({}, {}, {})",
                space.index, space.label, space.states.size(), space.blockEntities.size(),
                format(space.frame.render().m30()), format(space.frame.render().m31()),
                format(space.frame.render().m32()));
    }

    // ------------------------------------------------------------------ materials

    /**
     * The quads of every {@code (space, render type)} bucket, and the materials they belong to.
     *
     * <p>One bucket per render type is what puts a face in front of the image the game draws it
     * with, and what lets the writer group {@code usemtl} the way the renderer groups its state.
     */
    private static final class Collection {
        private final Map<Bucket, CapturingVertexConsumer> consumers = new LinkedHashMap<>();
        private final Map<String, RegionObjWriter.Material> materials = new LinkedHashMap<>();

        VertexConsumer consumer(int space, RenderType renderType) {
            return consumers.computeIfAbsent(new Bucket(space, renderType),
                    key -> new CapturingVertexConsumer());
        }

        Map<String, RegionObjWriter.Material> materials() {
            return materials;
        }

        List<RegionObjWriter.Face> finish(List<Space> spaces) {
            Map<Integer, String> objects = new LinkedHashMap<>();
            for (Space space : spaces) {
                objects.put(space.index, space.object());
            }
            List<RegionObjWriter.Face> faces = new ArrayList<>();
            int discarded = 0;
            for (Map.Entry<Bucket, CapturingVertexConsumer> entry : consumers.entrySet()) {
                Bucket bucket = entry.getKey();
                List<Vertex> vertices;
                try {
                    vertices = entry.getValue().finish();
                } catch (Throwable throwable) {
                    continue;
                }
                int stride = stride(bucket.renderType());
                if (stride == 0) {
                    discarded += vertices.size();
                    LOGGER.info("region: skip render-type={} reason=primitive-mode stage={}",
                            renderTypeName(bucket.renderType()), bucket.renderType().mode());
                    continue;
                }
                if (vertices.isEmpty()) {
                    continue;
                }
                RegionObjWriter.Material material = material(bucket.renderType());
                String object = objects.getOrDefault(bucket.space(), "world");
                int whole = vertices.size() - vertices.size() % stride;
                for (int index = 0; index < whole; index += stride) {
                    float[] positions = new float[stride * 3];
                    float[] uvs = new float[stride * 2];
                    for (int vertex = 0; vertex < stride; vertex++) {
                        Vertex source = vertices.get(index + vertex);
                        positions[vertex * 3] = source.position().x();
                        positions[vertex * 3 + 1] = source.position().y();
                        positions[vertex * 3 + 2] = source.position().z();
                        uvs[vertex * 2] = source.uv().x();
                        uvs[vertex * 2 + 1] = source.uv().y();
                    }
                    faces.add(new RegionObjWriter.Face(object, material.name(), positions, uvs,
                            stride));
                }
                if (vertices.size() % stride != 0) {
                    discarded += vertices.size() % stride;
                }
            }
            if (discarded > 0) {
                LOGGER.info("region: vertices discarded={} reason=primitive-mode-or-partial",
                        discarded);
            }
            return faces;
        }

        private RegionObjWriter.Material material(RenderType renderType) {
            String name = renderTypeName(renderType);
            String texture = renderTypeTexture(renderType).orElse(BLOCK_ATLAS);
            String key = "M_" + sanitize(name) + "_" + sanitize(texture);
            return materials.computeIfAbsent(key,
                    value -> new RegionObjWriter.Material(value, texture, alphaMode(name)));
        }

        /** {@code 4} for a quad, {@code 3} for a triangle, {@code 0} for a mode OBJ cannot carry. */
        private static int stride(RenderType renderType) {
            try {
                return switch (renderType.mode()) {
                    case QUADS -> 4;
                    case TRIANGLES -> 3;
                    default -> 0;
                };
            } catch (Throwable throwable) {
                return 4;
            }
        }
    }

    private record Bucket(int space, RenderType renderType) {
    }

    /**
     * A {@link MultiBufferSource} for one block entity: every render type gets a consumer that
     * applies {@code frame . T(block - base)} and counts what the renderer wrote.
     */
    private static final class BerSource implements MultiBufferSource {
        private final Collection collection;
        private final int space;
        private final Matrix4f placement;
        int vertices;

        BerSource(Collection collection, int space, Matrix4f placement) {
            this.collection = collection;
            this.space = space;
            this.placement = placement;
        }

        @Override
        public VertexConsumer getBuffer(RenderType renderType) {
            return new PosedCapture(collection.consumer(space, renderType), placement, this);
        }
    }

    /**
     * Applies the current pose and then the space's placement to every vertex.
     *
     * <p>{@link CapturingVertexConsumer} deliberately ignores the pose it is handed, because on the
     * block path the pose is already baked into the numbers. A block-entity renderer is the other
     * case: it does not apply the stack itself and expects the consumer to, so this wrapper is what
     * makes a barrel's pitch or a bearing's angle visible in the export at all.
     */
    private static final class PosedCapture implements VertexConsumer {
        private final VertexConsumer delegate;
        private final Matrix4f placement;
        private final BerSource counter;

        PosedCapture(VertexConsumer delegate, Matrix4f placement, BerSource counter) {
            this.delegate = delegate;
            this.placement = placement;
            this.counter = counter;
        }

        private void place(Matrix4f pose, float x, float y, float z) {
            Vector3f point = new Vector3f(x, y, z);
            if (pose != null) {
                pose.transformPosition(point);
            }
            placement.transformPosition(point);
            counter.vertices++;
            delegate.addVertex(point.x, point.y, point.z);
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            place(null, x, y, z);
            return this;
        }

        @Override
        public VertexConsumer addVertex(Matrix4f pose, float x, float y, float z) {
            place(pose, x, y, z);
            return this;
        }

        @Override
        public VertexConsumer addVertex(PoseStack.Pose pose, float x, float y, float z) {
            place(pose == null ? null : pose.pose(), x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int red, int green, int blue, int alpha) {
            delegate.setColor(red, green, blue, alpha);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    // ------------------------------------------------------------------ render type introspection

    /**
     * A render type's name.
     *
     * <p>Read by reflection because {@code RenderStateShard#name} is {@code protected} in the
     * shipped classes and only the development environment widens it. The fallback is the render
     * type's own {@code toString()}, which every composite type builds from the same name.
     */
    private static String renderTypeName(RenderType renderType) {
        try {
            Field field = RenderStateShard.class.getDeclaredField("name");
            field.setAccessible(true);
            Object value = field.get(renderType);
            if (value instanceof String name && !name.isBlank()) {
                return name;
            }
        } catch (Throwable ignored) {
            // fall through
        }
        try {
            return String.valueOf(renderType);
        } catch (Throwable ignored) {
            return "render_type";
        }
    }

    /**
     * The texture a render type draws with, or empty when it has none of its own.
     *
     * <p>The chunk sheets ({@code solid}, {@code cutout}, {@code translucent}) name no texture -
     * the chunk renderer binds the block atlas itself - so an empty answer means "the block atlas"
     * to every caller here. Entity sheets do name one, and that is the image their faces belong to.
     */
    private static Optional<String> renderTypeTexture(RenderType renderType) {
        try {
            Method state = renderType.getClass().getDeclaredMethod("state");
            state.setAccessible(true);
            Object composite = state.invoke(renderType);
            if (composite == null) {
                return Optional.empty();
            }
            Field textureState = composite.getClass().getDeclaredField("textureState");
            textureState.setAccessible(true);
            Object shard = textureState.get(composite);
            if (shard == null) {
                return Optional.empty();
            }
            Method cutoutTexture = shard.getClass().getDeclaredMethod("cutoutTexture");
            cutoutTexture.setAccessible(true);
            Object value = cutoutTexture.invoke(shard);
            if (value instanceof Optional<?> optional
                    && optional.orElse(null) instanceof ResourceLocation location) {
                return Optional.of(location.toString());
            }
        } catch (Throwable ignored) {
            // a render type with no composite state draws with the atlas
        }
        return Optional.empty();
    }

    /** {@code opaque}, {@code mask} or {@code blend}, from the render type's own name. */
    private static String alphaMode(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("translucent") || lower.contains("glint") || lower.contains("additive")
                || lower.contains("lightning")) {
            return "blend";
        }
        if (lower.contains("cutout")) {
            return "mask";
        }
        return "opaque";
    }

    // ------------------------------------------------------------------ small helpers

    private static List<Direction> directions() {
        List<Direction> all = new ArrayList<>(Arrays.asList(Direction.values()));
        all.add(null);
        return java.util.Collections.unmodifiableList(all);
    }

    private static float partialTick() {
        try {
            return Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
        } catch (Throwable throwable) {
            return 0.0f;
        }
    }

    private static String blockId(BlockState state) {
        try {
            return String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        } catch (Throwable throwable) {
            return "?";
        }
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static String format(float value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String triple(float a, float b, float c) {
        return String.format(Locale.ROOT, "%.2f, %.2f, %.2f", a, b, c);
    }

    private static String triple(float[] values) {
        return values == null || values.length < 3 ? "-"
                : triple(values[0], values[1], values[2]);
    }

    private static String triple(float[] values, int from) {
        if (values == null || from < 0 || from + 2 >= values.length) {
            return "-";
        }
        return triple(values[from], values[from + 1], values[from + 2]);
    }

    private static float centre(float[] box, int axis) {
        return (box[axis] + box[axis + 3]) * 0.5f;
    }

    /** The bounds of every vertex of the faces belonging to one object, as {@code min, max}. */
    private static float[] vertexBounds(List<RegionObjWriter.Face> faces, String object) {
        float[] out = null;
        for (RegionObjWriter.Face face : faces) {
            if (!face.object().equals(object)) {
                continue;
            }
            float[] positions = face.position();
            for (int index = 0; index + 2 < positions.length; index += 3) {
                float x = positions[index];
                float y = positions[index + 1];
                float z = positions[index + 2];
                if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                    continue;
                }
                if (out == null) {
                    out = new float[] {x, y, z, x, y, z};
                } else {
                    out[0] = Math.min(out[0], x);
                    out[1] = Math.min(out[1], y);
                    out[2] = Math.min(out[2], z);
                    out[3] = Math.max(out[3], x);
                    out[4] = Math.max(out[4], y);
                    out[5] = Math.max(out[5], z);
                }
            }
        }
        return out;
    }

    /** Counts one export's blocks, and the reasons any of them contributed nothing. */
    private static final class Tally {
        int examined;
        int produced;
        int skipped;
        int fluids;
        int blockEntities;
        int berDrawn;
        final Map<String, Integer> reasons = new LinkedHashMap<>();
        private int lines;
        private int blockLines;
        private int berLines;
        private final java.util.Set<String> blockKinds = new java.util.LinkedHashSet<>();
        private final java.util.Set<String> berKinds = new java.util.LinkedHashSet<>();

        boolean firstBlockKind(String id) {
            return firstKind(id, blockKinds);
        }

        boolean wantBlockLine() {
            if (blockLines >= DIAG_LINES) {
                return false;
            }
            blockLines++;
            return true;
        }

        boolean wantBerLine() {
            if (berLines >= DIAG_LINES) {
                return false;
            }
            berLines++;
            return true;
        }

        private boolean firstKind(String id, java.util.Set<String> seen) {
            if (id == null || id.isEmpty() || seen.size() >= 128) {
                return false;
            }
            return seen.add(id);
        }

        void skip(String block, String reason) {
            skipped++;
            reasons.merge(reason, 1, Integer::sum);
            if (lines < SKIP_LINES) {
                lines++;
                LOGGER.info("region: skip block={} reason={}", block, reason);
            }
        }
    }
}

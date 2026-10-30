package com.nebysse.minetomesh.sable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block lookups and coordinate maths that survive Sable sub-levels, reached by reflection.
 *
 * <p>A structure's blocks are stored in the plot region its sub-level owns, up to twenty million
 * blocks away from where the structure is drawn, and the {@link Level} a caller happens to hold is
 * not always the level a block can be read from. These helpers ask every level that could hold the
 * block and turn a stored position into the world position the structure is seen at, so the export
 * walks and measures where the player sees the ship instead of in the plot.
 *
 * <p>Nothing here links against Sable. The mod is not a dependency of this project, so the entry
 * points are looked up once by name and every call is wrapped: a pack without Sable answers "no
 * sub-levels", which is exactly what the world-only export needs, and a Sable that renamed a
 * method degrades to the same answer with one line in the log.
 */
public final class SubLevelPositions {
    private static final Logger LOGGER = LoggerFactory.getLogger(SubLevelPositions.class);

    private static final String SABLE_CLASS = "dev.ryanhcode.sable.Sable";
    private static final String BOUNDING_BOX_CLASS = "dev.ryanhcode.sable.companion.math.BoundingBox3d";
    private static final String SUB_LEVEL_CLASS = "dev.ryanhcode.sable.sublevel.SubLevel";
    private static final String CONTAINER_CLASS = "dev.ryanhcode.sable.api.sublevel.SubLevelContainer";

    private static boolean resolved;
    private static boolean usable;
    private static Object helper;
    private static Method allIntersecting;
    private static Method getContaining;
    private static Method projectOutOfSubLevel;
    private static Constructor<?> boundingBox;
    private static Method containerOf;
    private static Method containerLevel;

    private SubLevelPositions() {
    }

    /**
     * One sub-level the selection touches, with the pieces the export needs.
     *
     * @param handle  Sable's own object; never dereferenced outside this class
     * @param name    the structure's name, or a synthetic one when it has none
     * @param plot    the level the blocks are stored in
     * @param pose    the storage-to-world mapping, composed in {@code double}
     * @param rawPose Sable's pose object, kept for the exact inverse
     */
    public record Handle(Object handle, String name, Level plot, Matrix4d pose, Object rawPose) {
        public Handle {
            pose = pose == null ? new Matrix4d() : new Matrix4d(pose);
        }

        /** A stored position, in world coordinates. */
        public Vector3d toWorld(double x, double y, double z) {
            Vector3d point = new Vector3d(x, y, z);
            return pose.transformPosition(point);
        }

        /** A world position, in this sub-level's stored coordinates. */
        public Vector3d toLocal(double x, double y, double z) {
            return inverse(rawPose, pose, new Vector3d(x, y, z));
        }
    }

    /** Whether Sable answered the lookups this module needs. */
    public static boolean available() {
        resolve();
        return usable;
    }

    /**
     * Every sub-level whose world-space bounds intersect the box.
     *
     * <p>The box is handed to Sable as its own {@code BoundingBox3d}, built by reflection. An
     * empty list is the ordinary answer on a pack without Sable, and is not an error.
     */
    public static List<Handle> intersecting(Level level, AABB box) {
        resolve();
        if (!usable || level == null || box == null || allIntersecting == null) {
            return List.of();
        }
        List<Handle> found = new ArrayList<>();
        try {
            Object bounds = boundingBox(box);
            if (bounds == null) {
                return List.of();
            }
            Object answer = allIntersecting.invoke(helper, level, bounds);
            if (!(answer instanceof Iterable<?> iterable)) {
                return List.of();
            }
            for (Object element : iterable) {
                Handle handle = handle(element);
                if (handle != null) {
                    found.add(handle);
                }
            }
        } catch (Throwable throwable) {
            LOGGER.info("region: sub-level query unavailable ({}); exporting the world space only",
                    throwable.toString());
            return List.of();
        }
        return found;
    }

    /** The main level behind a plot level; the level itself when nothing answers. */
    public static Level rootLevel(Level level) {
        resolve();
        if (!usable || level == null || containerOf == null || containerLevel == null) {
            return level;
        }
        try {
            Object container = containerOf.invoke(null, level);
            if (container == null) {
                return level;
            }
            Object root = containerLevel.invoke(container);
            return root instanceof Level found ? found : level;
        } catch (Throwable throwable) {
            return level;
        }
    }

    /** The plot level of the sub-level that contains the position, or {@code null}. */
    public static Level plotLevel(Level level, BlockPos pos) {
        Object subLevel = containing(level, pos);
        if (subLevel == null) {
            return null;
        }
        Object plot = invokeNoThrow(subLevel, "getLevel");
        return plot instanceof Level found ? found : null;
    }

    /** The block state at the position, from the first level that has one. */
    public static BlockState blockState(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockState state = stateIn(level, pos);
        if (state != null) {
            return state;
        }
        Level plot = plotLevel(level, pos);
        if (plot != null) {
            state = stateIn(plot, pos);
            if (state != null) {
                return state;
            }
        }
        Level root = rootLevel(level);
        if (root != null && root != level && root != plot) {
            state = root.getBlockState(pos);
            if (state != null && !state.isAir()) {
                return state;
            }
        }
        return state;
    }

    /** The block entity at the position, from the first level that has it. */
    public static BlockEntity blockEntity(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return null;
        }
        BlockEntity found = entityIn(level, pos);
        if (found != null) {
            return found;
        }
        Level plot = plotLevel(level, pos);
        if (plot != null) {
            found = entityIn(plot, pos);
            if (found != null) {
                return found;
            }
        }
        Level root = rootLevel(level);
        if (root != null && root != level && root != plot) {
            found = entityIn(root, pos);
        }
        return found;
    }

    /** World position of a position that may sit inside a sub-level. */
    public static Vec3 worldPosition(Level level, Vec3 position) {
        resolve();
        if (!usable || level == null || position == null || projectOutOfSubLevel == null) {
            return position;
        }
        try {
            Object projected = projectOutOfSubLevel.invoke(helper, level, position);
            if (projected instanceof Vec3 world && finite(world)) {
                return world;
            }
        } catch (Throwable throwable) {
            // A projection that fails leaves the position where it was; callers compare.
        }
        return position;
    }

    /** Whether a position is stored in plot space instead of world space. */
    public static boolean inPlotSpace(Level level, Vec3 position) {
        if (level == null || position == null) {
            return false;
        }
        return worldPosition(level, position).distanceToSqr(position) > 1.0E-6;
    }

    // ------------------------------------------------------------------ internals

    private static Object containing(Level level, BlockPos pos) {
        resolve();
        if (!usable || level == null || pos == null || getContaining == null) {
            return null;
        }
        Object found = invokeNoThrow(helper, getContaining, level, pos);
        if (found == null || removed(found)) {
            Level root = rootLevel(level);
            if (root != null && root != level) {
                found = invokeNoThrow(helper, getContaining, root, pos);
            }
        }
        return found == null || removed(found) ? null : found;
    }

    private static Handle handle(Object element) {
        if (element == null || removed(element)) {
            return null;
        }
        Object rawPose = invokeNoThrow(element, "logicalPose");
        if (rawPose == null) {
            return null;
        }
        Matrix4d pose = new Matrix4d();
        try {
            Method bake = findMethod(rawPose.getClass(), "bakeIntoMatrix", Matrix4d.class);
            if (bake == null) {
                return null;
            }
            bake.setAccessible(true);
            bake.invoke(rawPose, pose);
            if (!finite(pose)) {
                return null;
            }
        } catch (Throwable throwable) {
            return null;
        }
        Object name = invokeNoThrow(element, "getName");
        String label = name instanceof String text && !text.isBlank() ? text.replace(' ', '_') : "sub";
        Object plot = invokeNoThrow(element, "getLevel");
        return new Handle(element, label, plot instanceof Level found ? found : null, pose, rawPose);
    }

    private static Vector3d inverse(Object rawPose, Matrix4d pose, Vector3d point) {
        if (rawPose != null) {
            try {
                Method method = findMethod(rawPose.getClass(), "transformPositionInverse",
                        Vector3d.class, Vector3d.class);
                if (method != null) {
                    method.setAccessible(true);
                    Vector3d local = new Vector3d();
                    method.invoke(rawPose, point, local);
                    if (finite(local)) {
                        return local;
                    }
                }
                Method inPlace = findMethod(rawPose.getClass(), "transformPositionInverse",
                        Vector3d.class);
                if (inPlace != null) {
                    inPlace.setAccessible(true);
                    Vector3d local = new Vector3d(point);
                    inPlace.invoke(rawPose, local);
                    if (finite(local)) {
                        return local;
                    }
                }
            } catch (Throwable throwable) {
                // fall through to the matrix inverse
            }
        }
        try {
            return new Matrix4d(pose).invert().transformPosition(new Vector3d(point));
        } catch (Throwable degenerate) {
            return new Vector3d(point);
        }
    }

    private static boolean removed(Object element) {
        Object value = invokeNoThrow(element, "isRemoved");
        return value instanceof Boolean flag && flag;
    }

    private static BlockState stateIn(Level level, BlockPos pos) {
        try {
            BlockState state = level.getBlockState(pos);
            return state != null && !state.isAir() ? state : null;
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static BlockEntity entityIn(Level level, BlockPos pos) {
        try {
            return level.getBlockEntity(pos);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Object boundingBox(AABB box) {
        if (boundingBox == null) {
            return null;
        }
        try {
            boundingBox.setAccessible(true);
            if (boundingBox.getParameterCount() == 1) {
                return boundingBox.newInstance(box);
            }
            return boundingBox.newInstance(box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Object invokeNoThrow(Object target, String name) {
        resolve();
        if (target == null || !usable) {
            return null;
        }
        return invokeNoThrow(target, findMethod(target.getClass(), name), new Object[0]);
    }

    private static Object invokeNoThrow(Object target, Method method, Object... arguments) {
        if (target == null || method == null) {
            return null;
        }
        try {
            method.setAccessible(true);
            return method.invoke(target, arguments);
        } catch (Throwable throwable) {
            return null;
        }
    }

    /**
     * The declared or inherited method with this name and these argument types.
     *
     * <p>Exact types first, then anything the arguments can be assigned to: Sable's own signatures
     * use concrete classes ({@code BlockPos}, {@code Vec3}, its pose type), and a hierarchy match
     * is what lets this class survive a parameter widened to an interface.
     */
    private static Method findMethod(Class<?> owner, String name, Class<?>... argumentTypes) {
        if (owner == null) {
            return null;
        }
        try {
            return owner.getMethod(name, argumentTypes);
        } catch (NoSuchMethodException ignored) {
            // fall through to the hierarchy match
        }
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != argumentTypes.length) {
                continue;
            }
            Class<?>[] parameters = method.getParameterTypes();
            boolean matches = true;
            for (int index = 0; index < parameters.length; index++) {
                if (!parameters[index].isAssignableFrom(argumentTypes[index])
                        && !argumentTypes[index].isAssignableFrom(parameters[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            ClassLoader loader = SubLevelPositions.class.getClassLoader();
            Class<?> sable = Class.forName(SABLE_CLASS, false, loader);
            Field helperField;
            try {
                helperField = sable.getField("HELPER");
            } catch (NoSuchFieldException missing) {
                helperField = sable.getDeclaredField("HELPER");
            }
            helperField.setAccessible(true);
            helper = helperField.get(null);
            if (helper == null) {
                LOGGER.info("region: sub-level helper absent");
                return;
            }
            Class<?> owner = helper.getClass();
            Class<?> boundingBoxType = Class.forName(BOUNDING_BOX_CLASS, false, loader);
            boundingBox = findConstructor(boundingBoxType);
            allIntersecting = findMethod(owner, "getAllIntersecting", Level.class, boundingBoxType);
            getContaining = findMethod(owner, "getContaining", Level.class, BlockPos.class);
            projectOutOfSubLevel = findMethod(owner, "projectOutOfSubLevel", Level.class, Vec3.class);
            try {
                Class<?> container = Class.forName(CONTAINER_CLASS, false, loader);
                containerOf = findMethod(container, "getContainer", Level.class);
                containerLevel = findMethod(container, "getLevel");
            } catch (Throwable ignored) {
                // Without the container, the root level is the level a caller already holds.
            }
            usable = allIntersecting != null || getContaining != null;
            LOGGER.info("region: sub-level support {} (intersecting={} containing={} project={})",
                    usable ? "ready" : "unavailable", allIntersecting != null,
                    getContaining != null, projectOutOfSubLevel != null);
            if (usable) {
                // Kept so a class rename in Sable fails here, once, rather than per block.
                Class.forName(SUB_LEVEL_CLASS, false, loader);
            }
        } catch (Throwable throwable) {
            usable = false;
            LOGGER.info("region: sub-level support unavailable ({})", throwable.toString());
        }
    }

    private static Constructor<?> findConstructor(Class<?> owner) {
        Constructor<?> sixDoubles = null;
        for (Constructor<?> constructor : owner.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 1 && parameters[0].isAssignableFrom(AABB.class)) {
                return constructor;
            }
            if (parameters.length == 6) {
                boolean allNumeric = true;
                for (Class<?> parameter : parameters) {
                    if (parameter != double.class && parameter != float.class) {
                        allNumeric = false;
                        break;
                    }
                }
                if (allNumeric) {
                    sixDoubles = constructor;
                }
            }
        }
        return sixDoubles;
    }

    private static boolean finite(Vec3 vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(Vector3d vector) {
        return Double.isFinite(vector.x) && Double.isFinite(vector.y) && Double.isFinite(vector.z);
    }

    private static boolean finite(Matrix4d matrix) {
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                if (!Double.isFinite(matrix.get(column, row))) {
                    return false;
                }
            }
        }
        return true;
    }
}

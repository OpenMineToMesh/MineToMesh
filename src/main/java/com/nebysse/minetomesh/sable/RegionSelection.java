package com.nebysse.minetomesh.sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The area a player drew with the Sable blueprint tool, read without linking against that mod.
 *
 * <p>"Export the region I selected" has to consume the <em>same</em> selection the blueprint tool
 * itself saves, or the two disagree about what a region is. That selection lives in
 * {@code dev.rew1nd.sableschematicapi.tool.client.session.BlueprintToolClientSession}:
 *
 * <pre>
 *   private static Vec3 start;                                        // corner 1
 *   private static Vec3 end;                                          // corner 2
 *   public static boolean hasRange()                                  // both corners set
 *   public static AABB selectionBox()                                 // block-aligned, max + 1
 * </pre>
 *
 * <p>Read out of the shipped {@code sable-photomancy-1.0.1.jar} (mod id
 * {@code sable_schematic_api}): {@code hasRange()} is {@code start != null && end != null},
 * {@code selectionBox()} builds {@code new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)}
 * out of {@code BlockPos.containing(start/end)}, and the corners are set by two left clicks in the
 * tool's library mode (shift-left-click clears them). Its corners are client-side static state, not
 * saved data, so an empty answer after a blueprint was saved is ordinary.
 *
 * <p>Everything about the tool is reached by reflection with {@code initialize = false} and wrapped
 * in try/catch: this module has to keep loading on a pack with no blueprint tool at all, and a
 * selection read that fails is a line in the log rather than a crash in a command. A missing tool
 * answers {@code null}; {@link #available()} says which of the two it was.
 */
public final class RegionSelection {
    private static final Logger LOGGER = LoggerFactory.getLogger(RegionSelection.class);

    /** The session class that owns the selection. */
    public static final String SESSION_CLASS =
            "dev.rew1nd.sableschematicapi.tool.client.session.BlueprintToolClientSession";

    private RegionSelection() {
    }

    /**
     * One reading of the tool's selection.
     *
     * @param aabb        the box, block-aligned with the max edge pushed out by one
     * @param bothCorners whether both corners were set (a half-drawn selection is one corner)
     * @param detail      which reader answered, for the {@code region: selection source=} line
     */
    public record Box(AABB aabb, boolean bothCorners, String detail) {
        public Box {
            if (aabb == null) {
                throw new IllegalArgumentException("aabb");
            }
        }

        /** Volume in blocks, counting the max edge the way the tool does. */
        public long volume() {
            long dx = Math.max(0L, (long) Math.floor(aabb.maxX) - (long) Math.floor(aabb.minX));
            long dy = Math.max(0L, (long) Math.floor(aabb.maxY) - (long) Math.floor(aabb.minY));
            long dz = Math.max(0L, (long) Math.floor(aabb.maxZ) - (long) Math.floor(aabb.minZ));
            return dx * dy * dz;
        }

        /** {@code (minX, minY, minZ)..(maxX, maxY, maxZ)} with the max edge made inclusive. */
        public String describe() {
            return String.format(java.util.Locale.ROOT, "(%d, %d, %d)..(%d, %d, %d)",
                    (int) Math.floor(aabb.minX), (int) Math.floor(aabb.minY),
                    (int) Math.floor(aabb.minZ),
                    Math.max((int) Math.floor(aabb.minX), (int) Math.ceil(aabb.maxX) - 1),
                    Math.max((int) Math.floor(aabb.minY), (int) Math.ceil(aabb.maxY) - 1),
                    Math.max((int) Math.floor(aabb.minZ), (int) Math.ceil(aabb.maxZ) - 1));
        }
    }

    /**
     * The tool's selection, or {@code null} when there is none or the tool is not installed.
     *
     * <p>The public readers are tried first; the two private corner fields are the fallback, kept
     * because the public readers are a courtesy of the tool's author and could be renamed while
     * the fields stay - they are what its own save path reads.
     */
    public static Box read() {
        Class<?> session = load();
        if (session == null) {
            return null;
        }
        Box viaApi = readViaApi(session);
        if (viaApi != null) {
            return viaApi;
        }
        return readViaFields(session);
    }

    /** Whether the blueprint tool is on this classpath at all. */
    public static boolean available() {
        return load() != null;
    }

    private static Class<?> load() {
        try {
            ClassLoader loader = RegionSelection.class.getClassLoader();
            return Class.forName(SESSION_CLASS, false, loader);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static Box readViaApi(Class<?> session) {
        try {
            java.lang.reflect.Method hasRange = session.getMethod("hasRange");
            java.lang.reflect.Method selectionBox = session.getMethod("selectionBox");
            Object raw = selectionBox.invoke(null);
            if (!(raw instanceof AABB aabb)) {
                return null;
            }
            boolean both = hasRange.invoke(null) instanceof Boolean value && value;
            return new Box(aabb, both, both
                    ? "two-corners via selectionBox()"
                    : "start-only via selectionBox()");
        } catch (Throwable throwable) {
            LOGGER.info("region: selection api unavailable ({}) - falling back to the corner fields",
                    throwable.toString());
            return null;
        }
    }

    private static Box readViaFields(Class<?> session) {
        try {
            Vec3 start = staticVec3(session, "start");
            Vec3 end = staticVec3(session, "end");
            if (start == null) {
                return null;
            }
            BlockPos first = BlockPos.containing(start);
            BlockPos second = BlockPos.containing(end == null ? start : end);
            double minX = Math.min(first.getX(), second.getX());
            double minY = Math.min(first.getY(), second.getY());
            double minZ = Math.min(first.getZ(), second.getZ());
            double maxX = Math.max(first.getX(), second.getX()) + 1.0;
            double maxY = Math.max(first.getY(), second.getY()) + 1.0;
            double maxZ = Math.max(first.getZ(), second.getZ()) + 1.0;
            return new Box(new AABB(minX, minY, minZ, maxX, maxY, maxZ), end != null,
                    end == null
                            ? "start-only via the corner fields"
                            : "two-corners via the corner fields");
        } catch (Throwable throwable) {
            LOGGER.info("region: selection unreadable ({})", throwable.toString());
            return null;
        }
    }

    private static Vec3 staticVec3(Class<?> owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Object value = field.get(null);
        return value instanceof Vec3 vec ? vec : null;
    }
}

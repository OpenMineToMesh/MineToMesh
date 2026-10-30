package com.nebysse.minetomesh.sable;

import com.nebysse.minetomesh.backend.FlywheelBackendAdapter;
import com.nebysse.minetomesh.backend.RenderBackendAdapter;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns Flywheel's "the visual engine draws this block entity" answer off for one export.
 *
 * <p>Some block entities are rendered by a visual: the renderer asks
 * {@code VisualizationManager.supportsVisualization(level)}, returns without writing a vertex, and
 * the part is uploaded to the GPU as an instance instead. That is invisible to a capture that
 * reads vertices - a cannon mount, an encased cogwheel or a mechanical bearing comes out with its
 * block model and no body at all, which reads as "the exporter loses the important parts".
 *
 * <p>For the length of the pass the backend is swapped for {@code OFF_BACKEND}, which is the state
 * a client without Flywheel runs in: the renderer takes the plain {@code VertexConsumer} path and
 * writes the geometry this export collects. The previous backend is restored in a {@code finally},
 * so the visual engine is untouched for the rest of the frame.
 *
 * <p>The swap is a write to one static field of {@code BackendManagerImpl}, reached through the
 * same adapter the rest of this mod uses ({@link FlywheelBackendAdapter}). Everything about it is
 * best effort: a pack without Flywheel, a renamed field or a read-only module makes
 * {@link #engage()} answer {@code false}, and the export behaves exactly as it did before -
 * {@code region: flywheel gate unavailable}.
 *
 * <p>Nested calls are counted: an export that wraps a pass which itself renders through this gate
 * restores the backend only when the outermost caller releases it.
 */
public final class FlywheelGate {
    private static final Logger LOGGER = LoggerFactory.getLogger(FlywheelGate.class);

    /** Whether the adapter has been looked for; the answer is cached for the session. */
    private static boolean resolved;

    /** The adapter, or {@code null} when Flywheel is not on this classpath. */
    private static FlywheelBackendAdapter adapter;

    /** The open scope of the outermost caller, {@code null} while the gate is not held. */
    private static RenderBackendAdapter.Scope scope;

    /** How many callers hold the gate. */
    private static int depth;

    private FlywheelGate() {
    }

    /** Whether Flywheel is present and the backend field could be reached. */
    public static boolean available() {
        resolve();
        return adapter != null;
    }

    /**
     * Takes the gate, if it can be taken.
     *
     * @return {@code true} when the caller must pass this value to {@link #release(boolean)}
     */
    public static boolean engage() {
        resolve();
        if (adapter == null) {
            return false;
        }
        if (depth == 0) {
            try {
                scope = adapter.enter();
            } catch (Throwable throwable) {
                // A pack whose Flywheel refuses the swap is a pack this gate cannot help, and the
                // export must still run: the answer is logged once and the gate stops trying.
                LOGGER.info("region: flywheel gate failed ({}); block entities may export empty",
                        throwable.toString());
                adapter = null;
                scope = null;
                return false;
            }
        }
        depth = Math.addExact(depth, 1);
        return true;
    }

    /**
     * Releases the gate.
     *
     * @param engaged exactly the value {@link #engage()} returned
     */
    public static void release(boolean engaged) {
        if (!engaged) {
            return;
        }
        if (depth > 0) {
            depth--;
        }
        if (depth > 0 || scope == null) {
            return;
        }
        RenderBackendAdapter.Scope closing = scope;
        scope = null;
        try {
            closing.close();
        } catch (Throwable throwable) {
            LOGGER.info("region: flywheel gate restore failed ({})", throwable.toString());
            adapter = null;
        }
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Optional<FlywheelBackendAdapter> found =
                    FlywheelBackendAdapter.discover(FlywheelGate.class.getClassLoader());
            adapter = found.orElse(null);
            LOGGER.info("region: flywheel gate {}", adapter == null ? "unavailable" : "ready");
        } catch (Throwable throwable) {
            adapter = null;
            LOGGER.info("region: flywheel gate unavailable ({})", throwable.toString());
        }
    }
}

package com.nebysse.minetomesh.sable;

import net.minecraft.core.BlockPos;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Vector3d;

/**
 * The matrix that turns one coordinate space's blocks into export coordinates, composed in
 * {@code double} and narrowed exactly once.
 *
 * <p>A Sable sub-level keeps its blocks in a plot region whose coordinates are unrelated to where
 * the structure is drawn: the pose's translation and the storage corner both sit around twenty
 * million blocks out. The mapping this export needs is
 *
 * <pre>
 *   frame = T(-origin) . pose . T(base)
 * </pre>
 *
 * <p>where {@code origin} is the selection's own corner (so the mesh is written around the origin,
 * where Blender's camera starts) and {@code base} is the storage corner of the space (so the
 * numbers handed to a {@code float} matrix are small).
 *
 * <h2>Why composing this in {@code float} is wrong</h2>
 *
 * <p>A thirty-two bit mantissa runs out at 16,777,216. At 2.05e7 the representable step is already
 * two whole blocks, so every intermediate value of the product above - the pose's translation, the
 * base, the difference of the two - is rounded to a two-block grid <em>before</em> the large terms
 * cancel. The result keeps one to three blocks of error per axis.
 *
 * <p>That error is invisible for a single sub-level: a rigid shift of a few blocks looks like the
 * structure simply being where it was drawn. It becomes a defect the moment a selection holds two
 * sub-levels, because each has its own pose and its own corner, so each is displaced by a
 * <em>different</em> few blocks - the parts are all present and no longer line up with each other.
 *
 * <p>Composing the same product in {@code double} keeps the cancellation exact; narrowing the
 * already-small result to {@code float} costs about 1e-5 of a block, which is the matrix every
 * vertex actually goes through. {@link Frame#drift()} keeps the difference between the two
 * compositions so an export can print the defect it no longer has.
 */
public final class SubLevelFrames {
    private SubLevelFrames() {
    }

    /**
     * One space's mapping, in both precisions.
     *
     * @param render      the {@code float} matrix every vertex is transformed by
     * @param exact       the same mapping composed in {@code double}, for measurement and queries
     * @param localToWorld the space's blocks in world coordinates (the sub-level's own pose)
     * @param worldToLocal the inverse of {@link #localToWorld}
     * @param drift       {@code floatOnly - exact} per axis, in blocks, for the diagnostic line
     */
    public record Frame(
            Matrix4f render,
            Matrix4d exact,
            Matrix4d localToWorld,
            Matrix4d worldToLocal,
            float[] drift) {
        public Frame {
            drift = drift.length == 3 ? drift.clone() : new float[] {0f, 0f, 0f};
        }

        @Override
        public float[] drift() {
            return drift.clone();
        }

        /** A point of this space's own coordinates, in the world. */
        public Vector3d toWorld(double x, double y, double z) {
            return localToWorld.transformPosition(new Vector3d(x, y, z));
        }

        /** A world point expressed in this space's own coordinates. */
        public Vector3d toLocal(double x, double y, double z) {
            return worldToLocal.transformPosition(new Vector3d(x, y, z));
        }

        /**
         * Where this space's own blocks should land in export coordinates, as {@code min, max}.
         *
         * <p>Eight corners per block through {@link #exact}, because the frame may carry a
         * rotation: mapping only a block's minimum corner would understate the box by up to a
         * block on two axes. Computed in {@code double} on purpose - the whole point of the check
         * that reads it is to be the exact answer the float pipeline is measured against.
         *
         * @return {@code {minX, minY, minZ, maxX, maxY, maxZ}}, or {@code null} for an empty space
         */
        public float[] blockBounds(Iterable<BlockPos> positions, BlockPos base) {
            float[] out = null;
            Vector3d corner = new Vector3d();
            for (BlockPos pos : positions) {
                for (int index = 0; index < 8; index++) {
                    corner.set(
                            pos.getX() - base.getX() + ((index & 1) == 0 ? 0.0 : 1.0),
                            pos.getY() - base.getY() + ((index & 2) == 0 ? 0.0 : 1.0),
                            pos.getZ() - base.getZ() + ((index & 4) == 0 ? 0.0 : 1.0));
                    exact.transformPosition(corner);
                    if (out == null) {
                        out = new float[] {(float) corner.x, (float) corner.y, (float) corner.z,
                                (float) corner.x, (float) corner.y, (float) corner.z};
                    } else {
                        out[0] = Math.min(out[0], (float) corner.x);
                        out[1] = Math.min(out[1], (float) corner.y);
                        out[2] = Math.min(out[2], (float) corner.z);
                        out[3] = Math.max(out[3], (float) corner.x);
                        out[4] = Math.max(out[4], (float) corner.y);
                        out[5] = Math.max(out[5], (float) corner.z);
                    }
                }
            }
            return out;
        }
    }

    /**
     * The frame of the ordinary world: no rotation, no pose, and every block offset taken from
     * {@code base}, so the numbers handed to the vertex pipeline stay small.
     *
     * <p>The same product as {@link #compose} with an identity pose: {@code T(-base) . I . T(base)}
     * is the identity, which is why the returned {@link Frame#render()} is a unit matrix.
     */
    public static Frame world(BlockPos base) {
        return new Frame(new Matrix4f(), new Matrix4d(), new Matrix4d(), new Matrix4d(),
                new float[] {0f, 0f, 0f});
    }

    /**
     * The frame of a sub-level.
     *
     * @param origin the selection's minimum corner, in world coordinates
     * @param pose   the sub-level's own pose, composed in {@code double}
     * @param base   the storage corner every block position of this space is made relative to
     */
    public static Frame compose(Vector3d origin, Matrix4d pose, BlockPos base) {
        Matrix4d baked = pose == null ? new Matrix4d() : new Matrix4d(pose);
        Matrix4d exact = new Matrix4d()
                .translation(-origin.x, -origin.y, -origin.z)
                .mul(baked)
                .translate(base.getX(), base.getY(), base.getZ());
        Matrix4f render = new Matrix4f(exact);

        // The composition this class exists to replace, kept only so the export can print the
        // drift it used to have. Nothing is ever drawn through this matrix.
        Matrix4f floatOnly = new Matrix4f()
                .translation((float) -origin.x, (float) -origin.y, (float) -origin.z)
                .mul(new Matrix4f(baked))
                .translate(base.getX(), base.getY(), base.getZ());
        float[] drift = {
                floatOnly.m30() - (float) exact.m30(),
                floatOnly.m31() - (float) exact.m31(),
                floatOnly.m32() - (float) exact.m32()};

        Matrix4d worldToLocal;
        try {
            worldToLocal = new Matrix4d(baked).invert();
        } catch (Throwable degenerate) {
            // An inverted or zero-scale pose leaves the space in its own coordinates; every caller
            // re-tests containment, so an identity here costs a wide query rather than a wrong
            // answer.
            worldToLocal = new Matrix4d();
        }
        return new Frame(render, exact, baked, worldToLocal, drift);
    }
}

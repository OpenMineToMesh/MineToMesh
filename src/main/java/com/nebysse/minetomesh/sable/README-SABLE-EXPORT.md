# Sable region export (`/minetomesh sable <name>`)

This package exports **the region a player selected with the Sable blueprint tool**, reading the
blocks and block entities that are actually in the world — including the parts Sable keeps inside
sub-levels (plot storage) and the parts Flywheel draws as visuals.

It is self-contained: nothing here links against Sable, Flywheel or the blueprint tool. All three
are reached by reflection and all three are optional. A pack without them exports the ordinary
world and says so in the log.

```
/minetomesh sable <name>
```

Output: `<gameDir>/minetomesh-exports/<name>/`

```
<name>.obj      geometry, one object per coordinate space (`world`, `sub01_<name>`, …)
<name>.mtl      one material per render type, all pointing at the atlas image
textures/*.png  the block atlas (and any entity sheet a renderer named), read back from the GPU
```

## Why a second export path

The existing exporters consume a **saved blueprint**. A blueprint format is a reimplementation of
somebody else's save format, and everything that format does not carry — caterpillar tracks,
tyres, bearings, a sub-level's own assembly — has to be guessed at afterwards, which is exactly
where those exports came out wrong. A region export has no format in between: the blocks it draws
are the blocks that are there, with the `BlockState` and `BlockEntity` instances the world itself is
holding, so every mod model, `ModelData` and block-entity renderer resolves the way it does in game.

## How it works

1. **Selection** — `RegionSelection` reads
   `dev.rew1nd.sableschematicapi.tool.client.session.BlueprintToolClientSession` by reflection:
   `hasRange()` / `selectionBox()` first, the two private corner fields as a fallback. `null` means
   "no selection or no tool", and the command refuses with the reason instead of exporting an empty
   box.
2. **Coordinate spaces** — `RegionExport` builds one `Space` for the ordinary world and one per
   intersecting Sable sub-level (`SubLevelPositions.intersecting`, reflection over
   `Sable.HELPER.getAllIntersecting(Level, BoundingBox3d)`).
   * The world is walked directly, with the selection's corner subtracted so the numbers stay small.
   * A sub-level's blocks live in a plot region up to ~20.48 million blocks away, so the selection
     box is mapped **through the inverse pose** to find the block range, and each block's own world
     centre is then tested against the selection — that is what makes a rotated or scaled sub-level
     come out right. Blocks are read with a plot → root fallback, because the level a caller holds
     is not always the level the block can be read from.
3. **Frames** — `SubLevelFrames` composes
   `frame = T(-origin) · pose · T(base)` in `double` and narrows **once** to `Matrix4f`.
4. **Geometry** — blocks go through `BlockRenderDispatcher#getBlockModel` and the model's own
   render types (`IBakedModelExtension#getRenderTypes/getQuads`), and block entities through their
   `BlockEntityRenderer`. Both write into one `CapturingVertexConsumer` per
   `(space, render type)`, so quads are grouped by `RenderType` all the way to `usemtl`.
5. **Flywheel gate** — `FlywheelGate` swaps `BackendManagerImpl.backend` for `OFF_BACKEND` for the
   length of the block-entity pass (nested-counted, no-op when Flywheel is absent).
6. **Writing** — `RegionObjWriter` deduplicates vertices, converts to Blender axes, writes OBJ+MTL
   and copies every referenced texture out of the GPU with `glGetTexImage`.

### Why the frame must be composed in `double`

A `float` mantissa runs out at 16,777,216. At 2.05e7 the representable step is already **two whole
blocks**, so composing `T(-origin) · pose · T(base)` in `float` rounds the pose's translation and
the storage corner *before* they cancel. The result keeps **1–3 blocks of error per axis**.

Per space, that error is invisible: a rigid shift of a few blocks just looks like the structure
being where it was drawn. With two sub-levels in one selection it is a visible defect, because each
space has its own pose and its own corner and is therefore displaced by a *different* few blocks —
the parts are all present and no longer line up. Composing in `double` and narrowing the
already-small result costs ~1e-5 of a block. `SubLevelFrames` keeps the float-only composition
around only to print the drift it no longer has.

### Why the flywheel gate exists

Flywheel answers *"this world draws its block entities with the visual engine"*. A renderer that
asks then **returns without writing a single vertex**, because the part is uploaded to the GPU as an
instance instead. A capture that reads vertices therefore gets the block model and no body: no
cannon mount, no encased cogwheel, no bearing. Turning the answer off for the pass makes the
renderer take the path a client without Flywheel runs, which writes into the `VertexConsumer` this
export hands it. The previous backend is restored in a `finally`.

The same applies to **entity-driven contraptions**: an entity renderer that asks Flywheel the same
question needs the gate too. This module exports block entities only (see boundaries), so its gate
wraps the block-entity pass; an entity pass would have to engage it as well.

## Criteria logs

One export writes, in order:

```
region: selection source=<session class> reader=<two-corners via selectionBox()|…> aabb=(x, y, z)..(x, y, z) volume=<n> blocks=<n>
region: sub-levels=<n> (no Sable structure intersects the selection)          <- only when 0
region: space index=<i> name=<world|subN:name> blocks=<n> block-entities=<n> frame-t=(x, y, z)
region-space-check: space=<i> name=<…> frame-t=(…) drift=(…) blocks=<n> verts-bbox=(…)..(…) block-bbox=(…)..(…) verdict=<ok|drift|no-geometry|no-reference>
region-block: id=<block id> state=<state> shape=<baked-quads|entity-or-empty> quads=<n> be=<bool>
region-ber: id=<block id> ber=<renderer class> ok=<bool> reason=<…>                     <- when it fails
region: skip block=<id> reason=<unsupported|no-model|exception>                        (≤20 lines)
region: flywheel gate <ready|unavailable>
region: texture saved=<file> size=<w>x<h> bytes=<n>
region: wrote <path> (<v> vertices, <f> faces, <m> materials)
region: baked blocks=<n> examined=<n> quads=<n> entities=0 ber=<n> materials=<n> skipped=<n> fluids=<n> spaces=<n> reasons={…}
```

Refusals:

```
region: refused reason=<no-world|wrong-thread|no-selection|one-corner|too-large|unsafe-name>
```

How to read them:

| line | question it answers |
|---|---|
| `region: selection` | did the command consume the selection the tool shows? (`reader=` says which reader) |
| `region: space` | how many spaces, how many blocks and block entities in each, and where the frame puts it |
| `region-space-check … verdict=` | did the geometry land where the space's own blocks say it should? `drift` means the frame is wrong, `no-reference` means the space holds no blocks |
| `region-block … shape=` | `baked-quads` = model geometry; `entity-or-empty` = nothing from the model, so the renderer decides |
| `region-ber … ok=false` | a renderer that threw or drew nothing, named per block id |
| `region: skip … reason=` | `unsupported` = the shape lives in a renderer that drew nothing; `no-model` = a `MODEL` block whose `getQuads` was empty; `exception` = the model or quad threw |
| `region: baked …` | the totals; `fluids` are counted separately so a selection beside water does not fill the skip budget |

`skipped` deliberately excludes blocks whose geometry is drawn by their renderer: those are
`produced`, not lost.

## Known boundaries

* **Blocks and block entities only.** No entity export (armour stands, item frames, mobs, Create
  contraptions carried by entities). The `entities=0` field in the summary line is therefore a
  fact, not a failure.
* **No per-block tint.** OBJ has no vertex colour this writer emits, so a tinted block (grass,
  leaves, water) comes out with its texture untinted.
* **Non-quad primitives are dropped.** `RenderType`s whose mode is not `QUADS` or `TRIANGLES`
  (lines, strips) are reported as `skip render-type=… reason=primitive-mode` rather than mangled.
* **A `MODEL` block with no quads is skipped, not boxed.** There is no voxel-shape fallback here;
  it appears in the skip log as `no-model` so a missing part is never silent.
* **Flywheel gate is one static field, best effort.** A pack whose Flywheel renamed the field makes
  the gate report `unavailable` and the export runs as if Flywheel were absent — which means the
  visual-drawn parts are missing again, and the log says so.
* **Atlas UVs, not per-sprite crops.** Every material points at the same atlas image. A viewer that
  cannot handle a 2k+ atlas will show blurry textures; cropping per sprite is left to the importer.
* **`regionMaxBlocks` is a constant** (64×64×64 = 131,072 blocks). A dense 64-cube can produce a
  very large face count; select a hull, not a city block.
* **The selection is client-side static state.** The blueprint tool clears it after a blueprint is
  saved, so an empty selection right after a blueprint export is ordinary.
* **`Selection` volume is counted the way the tool counts** (max edge inclusive), which is why a
  one-block selection has `volume=1`.

## Attribution

This is the region-export core of the **火控之眼 (Fire Control Eye)** mod's field implementation
(`/sableexport region <name>`), rewritten for this project: the same double-precision frame, the
same Flywheel gate, the same plot-space walk. Author: QKDream.

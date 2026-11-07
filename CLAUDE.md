# Quicksilver

Performance mod with built-in shader pack support for Minecraft Beta 1.7.3 on Babric (Fabric). Client only, single
player. Standalone: no dependencies on or references to other mods.

## Build and release

```bash
python3 build.py   # JDK 17+ and Python 3 -> build/libs/quicksilver-<version>.jar
```

- `build.py` downloads the b1.7.3 client and pinned libraries (SHA-256 checked) into `build/cache/`, remaps the
  client to the names in `mappings/named.tiny` (BIN mappings, intermediary -> named), compiles, and remaps the jar
  back to intermediary with tiny-remapper `--mixin`. The `Cannot remap field_xxx` warnings are normal.
- Library versions match a Babric instance (Fabric Loader 0.19.5, sponge-mixin 0.17.4+mixin.0.8.7, LWJGL
  2.9.4+legacyfabric.17, log4j 2.16.0). Changing one means a new SHA-256 in `build.py`.
- When a mixin targets something new, check the built jar with `javap -v` that the annotation targets ended up as
  intermediary names; unremapped targets crash the game at launch.
- Release: bump `version` in `src/main/resources/fabric.mod.json`, add a `CHANGELOG.md` entry
  (`## x.y.z (YYYY-MM-DD)`, newest on top, a few plain bullets for players), commit, push a tag `v<version>`.
  `.github/workflows/build.yml` builds it and publishes a GitHub release (pre-release when the version has a `-`).

## Working rules

- Performance work: measure first, review the numbers, and propose changes before building them.
- Keep scope contained; suggest extras instead of building them.
- No references to other mods (no compatibility checks by mod id, no integrations).

## Architecture

- `QsConfig`: every public static field is an option in `config/quicksilver.properties` (`@Desc`, `@Range`,
  `@Choices`, `@Restart`); `gui/QsConfigScreen` builds the settings screen from them (Video Settings > Quicksilver...).
- `render/`: `ChunkScheduler` decides which sections to build; `MeshWorkers` mesh them on worker threads from a
  `SectionSnapshot` with vanilla's `BlockRenderer` (block-render code must stay thread-safe); `MeshUploader` uploads
  within the frame budget. `render/vbo/`: VBO regions and multi-draw (`VboRenderer`), compact vertex format
  (`CompactMesh`), GPU time of day (`TerrainShader`). `CaveCuller` + `VisGraph`: cave culling.
- `world/`: `ChunkLoadManager` (progressive loading), `AsyncWorldGen`, `AsyncDecoration` (decorates a copy of four
  chunks on a background thread and applies the result on the game thread; falls back to vanilla when unsure),
  `FastLight` / `LightBudget` / `Relighter` (lighting). `io/RegionIO`: chunk prefetch and async saving.
- `client/`: frame pacing (`FramePacer`, `MonitorSync`), render/fog distance, `PerfLog` (F3 and benchmark counters).
- `shader/`: shader packs. `Shaders` owns the pipeline and config (`config/quicksilver-shaders.properties`); `pack/`
  reads packs (preprocessor, options, block ids); `pipeline/` runs the frame (gbuffers, shadow, composite);
  `ShaderBridge` switches terrain meshing to the extended vertex format while a pack is active. Mixins in
  `mixin/shader/` (own config `quicksilver-shaders.mixins.json`). Only Complementary Reimagined r5.9.3 is supported.
- Fluid flow and falling blocks also run on decoration threads: world mixins must not spawn entities off the game
  thread (`Quicksilver.isClientThread()`).

## Debug switches (JVM `-D` flags)

| Flag | Effect |
|---|---|
| `quicksilver.verifyDecoration=1\|2` | Compare background decoration with vanilla (all / sampled); logs `[decorate-verify]` |
| `quicksilver.verifyLight` | Check the fast lighting engine against vanilla |
| `quicksilver.lightDupStats` | Log duplicate light updates in the queue |
| `quicksilver.verifyTicks`, `verifyTicksStrict`, `noTickSkip` | Random tick timing / skip checks |
| `quicksilver.noSameLightSkip` | Disable skipping light updates that change nothing |
| `quicksilver.traceLoads`, `quicksilver.debugLog` | Chunk loading trace / periodic loader stats |
| `quicksilver.noSkyShader`, `quicksilver.noIndirect` | Disable GPU time of day / indirect multi-draw |
| `quicksilver.debugNoTerrainDraw` | Skip terrain draw calls (emulates an infinitely fast GPU) |
| `quicksilver.shaders.gpuTimers` | Log GPU time per shader stage |
| `quicksilver.shaders.dumpFrame=N`, `shaders.dumpNow` | Write every buffer of a frame to `shader-debug/` |
| `quicksilver.shaders.noShadowEntities` | Leave entities out of the shadow pass |

## Known issues

- With `verifyDecoration`, a few mismatches between background and vanilla decoration remain, mostly around lava.

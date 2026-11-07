# Quicksilver

A performance mod for Minecraft Beta 1.7.3 on [Babric](https://github.com/babric) (Fabric for b1.7.3), with
built-in shader pack support. Client only; works in single player.

## Features

**Rendering**
- Terrain drawn from GPU vertex buffers, one multi-draw call per 128-block region, back faces skipped.
- Chunk meshes built on worker threads; finished meshes are uploaded within a per-frame time budget.
- Cave culling: sections hidden behind walls and caves are not drawn.
- Time of day applied on the GPU, so sunrise and sunset don't rebuild every lit section.
- Grass and leaf biome colours cached per section.
- Optional fade-in of newly appearing terrain.
- Render distance up to 48 chunks and a separate fog distance slider (Video Settings).

**World**
- Chunks load and generate nearest first within a per-frame time budget, instead of 361 at once.
- Overworld terrain generation and chunk decoration (ores, trees, lakes...) run on background threads; results are
  identical to vanilla.
- Faster lighting engine (same results as vanilla) with a per-frame budget, plus a fix for the pitch black shadows
  in freshly generated terrain.
- Chunks are read ahead and saved on background threads; far chunks are unloaded in single player.

**Frame pacing**
- Frame rate cap, VSync, or "Match Monitor" pacing to the refresh rate of the monitor the window is on.
- Skips per-frame `glGetError` calls and hardware occlusion queries that stall the GPU.

**Shaders**
- Loads OptiFine-format shader packs (1.7.10-era features: deferred and composite passes, shadow map, custom
  uniforms, shader options screen) from the `shaderpacks/` folder: Video Settings > Shaders...
- **Only one shader pack is supported for now: [Complementary Reimagined](https://www.complementary.dev/) r5.9.3.**
  Other packs may load but are untested and may render incorrectly.
- Shaders need the VBO renderer (on by default).

## Installation

1. Set up a Babric b1.7.3 instance (for example in PrismLauncher) with Fabric Loader 0.14 or newer.
2. Download `quicksilver-<version>.jar` from the [releases](https://github.com/fgpa0/quicksilver/releases) and put it
   in the instance's `mods/` folder.

No other mods are needed.

## Settings

Video Settings > Quicksilver... opens the settings screen; every option has a description, and changes apply right
away (options marked "restart" after restarting the game). They are stored in `config/quicksilver.properties`.
Shader settings are in `config/quicksilver-shaders.properties`, per-pack options in `shaderpacks/<pack>.txt`.

## Building

Needs a JDK 17+ and Python 3.8+:

```bash
python3 build.py    # -> build/libs/quicksilver-<version>.jar
```

The script downloads the Minecraft b1.7.3 client and the build libraries once into `build/cache/` (all checked by
SHA-256), compiles against the readable names in `mappings/named.tiny` and remaps the jar back to Babric
intermediary. No Gradle needed.

Releases are built by GitHub Actions: pushing a tag `v<version>` (matching `version` in
`src/main/resources/fabric.mod.json`) builds the jar and attaches it to a GitHub release.

## Known issues

- **Not compatible with StationAPI.** Both change the same core parts of the game (chunk storage, rendering, world
  ticking); the launcher refuses to start with both installed. Mods that don't need StationAPI work as usual.

- With `-Dquicksilver.verifyDecoration=1`, a few differences between background and vanilla decoration are still
  reported, mostly around lava. Normal play is not affected.
- Mods that change block rendering or world generation may conflict: unknown blocks are meshed on the game thread,
  but there is no special support for other mods.

## License

MIT, see [LICENSE](LICENSE). `mappings/named.tiny` is based on the
[BIN mappings](https://github.com/calmilamsy/BIN-Mappings).

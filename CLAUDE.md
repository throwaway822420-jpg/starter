# Hydrophobic Collapse: handoff notes for Claude Code

Claude Code reads this file automatically. It summarises the whole project so far: what exists, how it fits together, how to build and test it, and the conventions to keep. The user-facing docs are in `README.md`.

## What this is

This is a protein-folding simulation. The user runs it as a live wallpaper on a Samsung Galaxy Tab S11. It comes in four forms that share the same ideas:

| Form | Where | Status |
|---|---|---|
| Web version (single file) | `index.html` (3D). `classic.html` is the original 2D HP model | Done. It no longer gets new features. |
| **Android live-wallpaper app** | `android/app` | Main product. It has all the features. |
| **Windows desktop app** (Swing) | `android/desktop` | Built and packaged by CI. Tested on the user's Windows 10 PC (1920×1080), including wallpaper mode. |
| Shared engine and renderer | `android/core` (Kotlin/JVM, no Android dependencies) | Used by both apps |

Distribution: GitHub Actions publishes the prerelease `wallpaper-latest` (https://github.com/throwaway822420-jpg/starter/releases/tag/wallpaper-latest) with:
- `HydrophobicCollapse.apk` (sideloaded on the tablet)
- `HydrophobicCollapse-Windows.zip` (a jpackage app image containing `HydrophobicCollapse.exe` and a bundled JRE)

## The user

- They are not a professional developer. Give plain-English explanations and say how to install or try things.
- They like new features and "cool" extras. They asked for a cheat mode, extreme speed, and so on.
- **Preference:** when a message starts with "q uick fire", answer in a few sentences at most. They may ask what specific words mean; if so, define them briefly.
- Do not open PRs unless they ask. Commit and push to the working branch. CI builds the release from the branch push.

## Repo layout

```
index.html, classic.html        Web versions (standalone)
tools/extract_native.py         Downloads PDB files and regenerates core/.../NativeStructures.kt (Cα coordinates of the presets)
.github/workflows/android.yml   CI: Linux job (tests, APK, release) → Windows job (jpackage zip, uploaded to the same release)
android/                        Gradle root (Gradle 8.14.3, AGP 8.13.2, Kotlin 2.2.20, JVM 17)
  settings.gradle.kts           include(":core", ":app", ":desktop")
  core/src/main/java/com/hydrophobiccollapse/
    ProteinEngine.kt   (~1200 lines) The physics: Langevin dynamics, forces, chemistry, Gō model, Q/RMSD, chaperone cage
    Simulation.kt      (~1400 lines) Owns the engine, the physics thread, camera, input, replay, sparks and ALL drawing (via gfx shim)
    Recording.kt       Ring buffer of recent frames (replay + trajectory export) and PdbWriter (Cα-only PDB, multi-MODEL)
    Proteins.kt        Presets, random proteins, custom-protein JSON, Settings data class + KeyValueStore persistence
    StructureIO.kt     Parses PDB/mmCIF and downloads from RCSB / AlphaFold (org.json is compileOnly in core)
    NativeStructures.kt  GENERATED. Do not edit by hand
    gfx/Gfx.kt         A small copy of android.graphics (Canvas interface, Paint, Path, Color.hsvToColor, RadialGradient,
                       DashPathEffect, Typeface, PointerEvent, TextMeasurer). Each platform supplies a Canvas and a measurer.
  core/src/test/.../ProteinEngineTest.kt   Physics checks (seeded, deterministic)
  app/  (Android)
    AndroidPlatform.kt     Adapters: AndroidCanvas(android Canvas), AndroidCanvas.install(), MotionEvent.toPointer(),
                           Settings load/save to SharedPreferences, Settings.sendCommand (heat or reset through prefs)
    FoldingWallpaperService.kt  WallpaperService engine (Choreographer loop, screen-on → new random protein)
    FoldingView.kt         Live preview view in the settings screen
    SettingsActivity.kt    The whole control UI (programmatic views, no XML layouts)
    wallpaper-debug.keystore   Fixed signing key so updates install over each other (personal sideload only)
  app/src/test/.../RenderSnapshotTest.kt  Robolectric: renders frames to app/build/snapshots/*.png plus timing checks
  desktop/
    Java2DCanvas.kt   Graphics2D implementation of the gfx Canvas. Caches strokes/colours/fonts, paints the background gradient
                      once into an image, fills rects without AA (fonts: Segoe UI or Cascadia Mono/Consolas, with fallbacks)
    Surface.kt        SimSurface: heavyweight AWT Canvas + BufferStrategy, frame painted into a BufferedImage then blitted.
                      Animator: the "hc-render" thread that calls sim.update + render, paced to the monitor refresh (≤120 Hz,
                      30 fps when lowFrameRate). -Dhc.debug prints fps and per-frame simulation/painting/present times.
    WinDesktop.kt     JNA Win32: attach a window behind the desktop icons (WorkerW; Win11 24H2 layout handled but untested),
                      restore the old wallpaper, timeBeginPeriod(1), "desktop hidden" check, HKCU Run key for start-up
    IconFile.kt       Writes the app icon as .ico (CI: MainKt --write-icon <file>, then jpackage --icon)
    Main.kt           Swing + FlatLaf dark. MainWindow (control side panel matching the Android settings, Scrollable so it
                      tracks the viewport width; notes are wrapping JTextAreas), WallpaperWindow, tray icon, PDB save dialogs,
                      a KeyEventDispatcher for shortcuts, ProteinEditor dialog, FileStore → %APPDATA%\HydrophobicCollapse\settings.properties
    src/test/.../RenderTest.kt  Headless render → desktop/build/render/desktop.png (checks the frame isn't blank)
```

About 9,500 lines in total (Kotlin about 6,700, HTML about 2,200).

## Simulation model (core)

The model is a coarse-grained Cα chain. Units are Å, kcal/mol, K and ps-ish time.
- **Dynamics:** Langevin, with friction `GAMMA=0.15` and uniform noise. Neighbour lists use a Verlet list on a cell grid.
- **Bonded terms:** bonds, angles and dihedrals.
- **Nonbonded terms:**
  - hydrophobic attraction and excluded volume
  - Debye–Hückel electrostatics (ionic strength slider)
  - H-bond-ish backbone helix and strand propensities
- **Chemistry:**
  - Constant-pH Monte Carlo protonation.
  - Disulfides: oxidation, reduction and shuffling, controlled by a redox setting.
  - Salt bridges are counted.
- **Structure-based (Gō) bias toward real structures** when a preset has native coordinates (NativeStructures) or a fetched PDB or AlphaFold model. The parameters are:
  - native contacts use a 12-10 potential with cutoffs of 7.5 Å (local) and 10 Å (long-range and interchain), and `EPS_NATIVE 0.6`
  - native angle and dihedral terms
  - interchain docking pull (`EPS_DOCK 0.2`, width 6)
  - a `DISSOCIATE 1.4` factor
- **Measures:**
  - Q (fraction of native contacts) and RMSD, via Horn quaternion superposition with a Jacobi eigen solver
  - Rg, energy, helix and strand %
  - "Folded %" progress
- **Crowding:** a confining sphere set by volume fraction (Off, 3%, 12%, 25%) keeps multichain systems close enough to interact.
- **Performance modes** (`Settings.performance`): 0 = saver, 1 = normal, 2 = extreme. In extreme mode, physics runs on its own thread under a fair ReentrantLock, and drawing uses a lock-free snapshot. `lowFrameRate()` (performance != 1) caps drawing at 30 fps.
- **Cheat and experiment features:**
  - **Folding assist:** a targeted pull toward the superposed native structure, `k = [0, 0.03, 0.1, 0.4]`. With no structure, it squeezes the chain and makes contacts stickier. Tuning showed that pull-only works best. "Funnel smoothing" was tried and removed.
  - **Urea denaturant:** contacts are scaled by `1 − 0.06·M`.
  - **Optical tweezers:** pull in pN, where 1 pN = 0.01439 kcal/mol/Å. The sphere widens while pulling.
  - **Residue inspector** (tap a residue).
  - **Folding funnel plot:** energy vs Q.
- **Views:**
  - beads, with bold per-chain colours
  - cartoon: Catmull–Rom ribbons for helices, arrows for strands
  - backbone
  - beads + cartoon overlay
- **Chaperone cage** (`Settings.chaperone`, engine `chaperone`): GroEL-like cylinder around the origin along y, open end at −y
  (drawn at the top of the screen). Phases CAGE_CAPTURE 3 s (hydrophobic lining well + pull of the chain into the cage),
  CAGE_ENCLOSED 8 s (lid on, polar walls, cavity ×1.1), CAGE_RELEASE 2.5 s (push toward −y). The clock is `advanceClock`,
  i.e. wall time. Camera centres on the cage while it is on.
- **Ribosome mode** (`Settings.ribosome`, default off; `ribosomeSpeed` 0–2 → 400/150/50 steps per residue): `load()`
  starts the chain N-terminus first out of a 30-residue exit tunnel at `exitY = −0.5·rBox` (tunnel runs along −y,
  drawn at the top). Residues `[0, released)` are free; `[released, made)` are pinned in zigzag tunnel slots and slide
  out with `elongation`; the rest are parked and ignored. Multi-chain proteins are made one chain after another.
  Only free residues (plus the `MOUTH_SLOTS` nearest the mouth) enter the neighbour lists; bonded terms need one free
  residue; no centre pull while translating (it stretched the chain); cage/tweezers wait. Termination slides the
  tail out at the base pace: faster rammed the free chain and tore bonds. `planPauses()` finds domain
  boundaries in the native contact map (few contacts across a cut) and the ribosome pauses 60× once a domain clears
  the tunnel. Fair 6-seed survey (fixed bar, translation time counted): normal start folded 17/30, ribosome 13/30 and
  slower. Only all-helix myoglobin clearly gains. So it's an experiment, not a folding aid.
  Recording frames store `made`/`released`. The render tests pin `ribosome = false` except the ribosome frames.
- **Time-lapse replay:** `Recording` keeps a frame every 0.25 s (2 min, less for huge proteins). During a replay the engine
  and Extreme thread wait; `showReplayFrame` interpolates into the snapshot arrays; drag rotates (also in wallpaperMode),
  tap pauses, the bar scrubs. Speed = min(setting, recording/4 s), easing to a tenth of that by the end (slow-motion finish). Android: `Settings.CMD_REPLAY` through prefs.
- **Drawing reads snapshots:** `snapSS`, `snapDisulfides`, `snapCage*` (not `eng.ss` etc.) so replays and Extreme mode draw
  consistent data. Contacts and salt bridges are live-only (hidden in replays).
- **Effects** (`Settings.effects`, ≤1200 residues): fake depth of field (halos and fading behind the front) and sparks on
  newly formed long-range contacts (with a 4 s per-pair cooldown).
- **Proteins:**
  - many presets (chignolin, trp-cage, GB1, villin, BPTI, insulin, ubiquitin, lysozyme, myoglobin, GFP, GCN4, hemoglobin, sickle Hb, …)
  - random mode (a new protein each time the screen turns on)
  - custom proteins (JSON in settings)
  - thousands of residues supported

## Build and test

Run everything from `android/`. JDK 17+ is required. The Android parts also need the SDK: in the original cloud container it was at `/root/android-sdk`, with `local.properties` or `ANDROID_HOME` pointing to it. Robolectric was run offline with `ROBOLECTRIC_DEPS_DIR=/root/robolectric-deps`. On a fresh machine, normal online resolution works.

```bash
./gradlew :core:test                 # physics tests (fast, no Android needed)
./gradlew :desktop:test              # headless Java2D render → desktop/build/render/desktop.png
./gradlew :desktop:run               # run the desktop app (any OS with a display)
./gradlew testDebugUnitTest          # Android Robolectric render snapshots → app/build/snapshots/
./gradlew assembleDebug              # APK
./gradlew :desktop:installDist       # desktop jars in desktop/build/install/HydrophobicCollapse/lib
```

CI (`.github/workflows/android.yml`) runs on pushes that touch `android/**` or the workflow file:
1. **build** (ubuntu): runs `:core:test testDebugUnitTest :desktop:test`, then assembleDebug. It deletes and recreates the `wallpaper-latest` release with the APK.
2. **windows** (windows-latest, `needs: build`): runs `installDist`, then `jpackage --type app-image --main-jar desktop.jar --main-class com.hydrophobiccollapse.desktop.MainKt`, zips the result and uploads it with `gh release upload --clobber`.

Last CI run (commit 58cb4af): both jobs green.

To look at rendering after a change, run the snapshot or render tests and open the PNGs. That is how every visual change so far has been checked.

## Conventions and gotchas

- **All drawing lives in `Simulation.kt` and goes through the `gfx` shim.** Never import `android.*` in `core`. If you need a new drawing primitive, add it to `gfx/Gfx.kt`, `AndroidCanvas` and `Java2DCanvas` together.
- **Settings:** add a field to the `Settings` data class, to `Settings.save/load(KeyValueStore)` in `Proteins.kt`, to the Android UI (`SettingsActivity.kt`) and to the desktop control panel (`desktop/Main.kt`). Keep the two UIs matching. Windows-only preferences (pause when covered, readout on the wallpaper) live in the FileStore under `desktop.*` keys, not in `Settings`.
- **Desktop threading:** `sim.update` and `sim.draw` both run on the Animator thread; Swing callbacks (EDT) only call locked
  Simulation methods. Don't call `sim.draw` from the EDT.
- **Heavyweight canvas:** popups are forced heavyweight (`JPopupMenu.setDefaultLightWeightPopupEnabled(false)`) so they show over it.
- Input goes through `sim.onPointer(PointerEvent)`, using Android action codes. The desktop maps mouse events to it.
- Inside Swing `.apply {}` blocks, `name` resolves to `Component.name`. That is why the editor field is called `nameField`.
- Tests are seeded. Do not add unseeded randomness to tests, because it made them flaky before.
- Multithreaded force computation was tried and gave no gain, so it was removed. The extreme-mode speedup comes from the dedicated thread and bigger steps.
- Sign conventions that caused bugs before: the dihedral gradient sign, and the funnel plot's energy axis (lower energy is down).
- `NativeStructures.kt` is generated: regenerate it with `python3 tools/extract_native.py` from the repo root.
- Commit messages: a descriptive summary plus body. Keep model names out of repo files.

## Local build on the user's Windows PC

No JDK or Android SDK is installed system-wide. A session can download a portable Temurin 17 and the Android command-line
tools into its scratchpad and point `JAVA_HOME` and `local.properties` (`sdk.dir`, git-ignored) at them.

## Ideas not yet done

- Windows follow-ups: an MSI installer (`--type msi` needs WiX), wallpaper on every monitor (now primary only), testing
  wallpaper mode on Windows 11 24H2, pinch-zoom on Android.
- The web version (`index.html`) lacks the newer features (cartoon, assist, tweezers, …).
- More ideas the user might like: protein–ligand binding, a membrane slab, chaperone cage, sharing or exporting a trajectory as a PDB file.

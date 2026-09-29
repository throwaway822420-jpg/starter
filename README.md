# Hydrophobic Collapse

An interactive live wallpaper that folds real protein sequences in 3D, with real solution chemistry. The whole thing is one file, `index.html`. The original 2D version is kept in `classic.html`.

## Install on your tablet (Android app)

The `android/` folder is a native live-wallpaper app running the same physics and chemistry. GitHub builds it automatically on every push.

1. On the tablet, open this repo's **Releases** page on GitHub and download **HydrophobicCollapse.apk** from the release named "Hydrophobic Collapse wallpaper (latest build)".
2. Open the downloaded file. Android will ask you to allow installs from your browser (or My Files). Allow it once, then tap **Install**.
3. Open **Hydrophobic Collapse** from your apps. You get a live preview with all the controls. Tap **Set as wallpaper**, then choose Home screen, Lock screen or both.

To update, download the newest APK and install it over the old one. Your settings carry over.

On the home screen:
- drag a residue to pull it
- tap to stir the water
- double-tap to heat and unfold
- swipe between pages to turn the molecule

Open the app any time to change the protein or the conditions. The wallpaper updates live.

The app is signed with a fixed debug-style key kept in the repo (`android/app/wallpaper-debug.keystore`), so every build can install over the last one. That's fine for a personal sideloaded app, but don't reuse the key for anything published.

**Building it yourself:** from `android/`, run `./gradlew assembleDebug` (needs JDK 17+ and the Android SDK). `./gradlew testDebugUnitTest` runs the physics checks and renders sample frames to `app/build/snapshots/`.

## Web version

Open `index.html` in any browser to run the same simulation there, with the same controls.

## What it simulates

Each bead is one amino acid, placed at its Cα atom. The chain moves under Langevin dynamics in implicit water, which means random thermal kicks plus friction. Units are real: Å, kcal/mol and kelvin.

**Physics**
- Cα–Cα bonds of 3.8 Å.
- Virtual bond-angle and dihedral preferences for α-helix (91°, +50°) and β-strand (120°, −170°). Each residue's preference is weighted by its Chou–Fasman propensity.
- An i→i+4 helical hydrogen-bond term. Proline weakens it, because proline breaks helices.
- Residue-specific contact attraction, scaled from Kyte–Doolittle hydropathy, with residue sizes.
- Debye–Hückel screened electrostatics in water (ε = 80). The screening length follows the salt concentration.

**Chemistry (Monte Carlo)**
- **Constant-pH titration:** Asp, Glu, His, Cys, Tyr, Lys, Arg and both chain ends gain and lose protons. Each change is accepted or rejected using the pKa, the pH and the local electrostatic field, so buried or crowded charges shift on their own.
- **Disulfide chemistry:** two cysteines within reach can oxidize into a disulfide. This needs a thiolate (a deprotonated cysteine), so it depends on pH. The redox buffer reduces disulfides back. A free thiolate can attack an existing disulfide and swap partners. This thiol–disulfide shuffling is how BPTI finds its three native bonds.

**Limits:** this is a teaching-grade coarse-grained model, not a structure predictor. Proteins collapse, form helices and strands, and make and break disulfides with the right trends, but they won't reliably reach their true native fold. Atom-level simulation of folding needs supercomputers.

## Proteins

| Protein | Residues | Why it's interesting |
| --- | --- | --- |
| BPTI | 58 | Three native disulfides (5–55, 14–38, 30–51); the classic disulfide folding study |
| Villin headpiece HP35 | 35 | A fast-folding three-helix bundle |
| Trp-cage TC5b | 20 | A designed miniprotein |
| GB1 hairpin | 16 | A β-hairpin that folds on its own |
| Amyloid-β 1–42 | 42 | The Alzheimer's peptide, mostly disordered |
| Ubiquitin | 76 | Mixed helix and sheet |

## On screen

- **Residue colours:**
  - amber: hydrophobic
  - teal: polar
  - blue with +: positive
  - rose with −: negative
  - yellow: cysteine
  - grey: Gly and Pro

  Colours follow each residue's current charge, so changing the pH visibly recolours the protein.
- **Backbone:**
  - lavender: helix
  - mint: strand
  - yellow link: disulfide
  - dashed line: salt bridge
- **Flashes:**
  - small rings: proton transfers
  - yellow bursts: disulfide reactions
- **Readout:**
  - conditions: temperature, pH and salt
  - the sequence, with helix and strand underlines
  - energy, radius of gyration, helix and strand content, net charge, salt bridges and disulfides
  - a 60-second energy trace
  - a contact map. Upper triangle: contacts now. Lower triangle: recent contact frequency. Dim yellow: native disulfide pairs.
  - a feed of reactions and heating events

## Controls

| Gesture | What it does |
| --- | --- |
| Drag a residue | Pull it |
| Drag open space | Rotate the molecule |
| Tap | Stir the water |
| Double-tap | Heat to unfold |
| Long-press | Open settings |

In settings you can pick the protein and set the temperature, pH, salt, redox buffer, speed and heat cycle, show or hide the readout, and turn on battery saver.

Add `#clean` to the end of the URL to hide the readout and the settings button.

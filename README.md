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

## Windows app

`android/desktop/` is a standalone Windows app. It shares the physics, chemistry and renderer with the Android app (the `android/core/` module) and has the same controls in a side panel.

1. From the same release, download **HydrophobicCollapse-Windows.zip**.
2. Unzip it anywhere and run **HydrophobicCollapse.exe**. It bundles its own Java runtime, so nothing else needs installing. Windows SmartScreen may warn about an unsigned app: click **More info → Run anyway**.

Keyboard shortcuts:

| Key | Action |
|---|---|
| F11 | Full screen |
| Esc | Leave full screen |
| Tab | Hide or show the panel |
| Space | Heat to unfold |
| R | Start over |

The mouse works like touch: drag a residue to pull it, click to stir, double-click to heat. Settings are saved in `%APPDATA%\HydrophobicCollapse`.

**Building it yourself:** from `android/`, run `./gradlew :desktop:run` (needs only JDK 17+).

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

**Several chains:** chains interact through exactly the same contact, electrostatic and disulfide terms as residues within one chain. That's how insulin's chains pair up, how hemoglobin subunits stick together, and how amyloid peptides clump. The readout counts the contacts between chains and the size of the largest complex.

**Crowding:** chains only interact if they meet. With several chains, they're kept in a sphere sized so the folded protein fills a set share of it. The default, **cell-like**, is 25%, since the cytoplasm is 20–40% protein. **Crowded** is 12% and **dilute** is 3%. The effect, measured over 60 seconds at desktop speed:
- **Amyloid-β ×6:** before this, only 3 of 6 peptides ever clumped; now all 6 do within 20 seconds.
- **Two GCN4 chains without guidance:** before, they never met; now they find each other within 30 seconds.
- **Hemoglobin:** before, only 2 subunits came together; now all 4 do, and within 2–3 minutes they settle to about 8 Å from the real tetramer.

Single chains keep the roomier sphere they need to unfold.

**Scaling:** non-bonded pairs come from neighbour lists rebuilt on a spatial grid, so the cost grows roughly linearly with size. A 3000-residue chain takes about 1 ms per step on a desktop CPU.

**Real structures (Android app):** most presets come with their experimental structure from the Protein Data Bank. The Cα positions are baked in by `tools/extract_native.py`, which aligns each PDB chain to the UniProt-checked sequence and prints a coverage and identity report. With a structure, a structure-based ("Gō-model") layer steers folding toward it:
- residue pairs that touch in the real structure attract, with a well centred on their real distance. Cutoffs are 7.5 Å for pairs close in sequence and 10 Å for distant pairs and pairs across chains, because packed helices sit 8–11 Å apart at their Cα atoms.
- bond angles and twists are pulled toward their real values.
- a weak long-range pull between residues on either side of a real interface. It stands in for the diffusion and electrostatic steering that bring partners together, which would take far too long to simulate.
- complexes start dissociated, each chain unfolded around its real position, pushed 40% further out, inside the crowding sphere.

The **Native-structure guidance** slider blends from generic physics (0%) to fully guided (100%, the default). The readout shows:
- **native contacts (Q):** the share of the real structure's contacts that have formed.
- **RMSD:** how far the shape is from the real one after the best superposition, in Å.

Measured at desktop speed, starting from an unfolded chain:
- **Small and medium proteins:** Trp-cage, chignolin, villin, ubiquitin and myoglobin usually reach their crystal structures (RMSD 1–3 Å) within 20–60 seconds. BPTI often does.
- **Small complexes:** insulin's two chains and the GCN4 dimer assemble correctly within about 40 seconds.
- **Larger proteins:** lysozyme, GFP and the hemoglobin tetramer take several minutes and can get stuck. With cell-like crowding, hemoglobin reaches about 8 Å from the real tetramer in 2–3 minutes. For those, set "Heat to unfold every" to 5 minutes or Never.

On a tablet everything runs slower than these figures.

**Load any protein:** in **Create a protein…**, type:
- a PDB ID, e.g. `1UBQ`
- a PDB ID with chosen chains, e.g. `2HHB:A,B`
- a UniProt ID for an AlphaFold prediction, e.g. `P69905`

The app downloads the structure, reads its Cα trace (PDB or mmCIF), splits chains wherever residues are missing, and folds it toward that structure. The app only uses the internet for this.

**Limits:** without a known structure (random proteins, typed-in sequences, amyloid-β) the generic physics collapses chains and forms helices and strands with the right trends, but won't reliably find the true fold. With a structure, the model folds toward a shape it was given; it doesn't predict one. The web version has neither.

## Experiments and extras (Android app)

- **Folding assist (the cheat):** Off, Gentle, Strong or Maximum. With a known structure, the real structure is laid over the chain every 20 steps (best superposition), and each residue is pulled toward its spot. Because the target follows the chain's position and orientation, it only pushes toward the right shape. Without a structure, it squeezes the chain toward its centre and makes contacts stickier. The progress bar says "assisted" while it's on.

  Median over three runs from an unfolded chain, desktop speed:

  | Protein | No assist | Gentle | Strong | Maximum |
  | --- | --- | --- | --- | --- |
  | Myoglobin | ~30 s | 4 s | 2 s | 2 s |
  | Lysozyme | didn't fold in 40 s | didn't fold in 40 s (closer) | 4 s | 2 s |
  | Hemoglobin tetramer | — | — | — | 10 s |

  Strengthening native contacts instead was tried and made folding slower, so the assist is purely the pull.

- **Tap a residue to inspect it:**
  - its type and what that means
  - its charge, pKa and protonation state
  - helix, strand or loop
  - how buried it is
  - its disulfide partner
  - how many of its real-structure contacts have formed
- **Folding funnel:** with a known structure, the readout plots energy against the fraction of native contacts. An unfolded chain starts high on the left and slides down to the folded state at the bottom right, the classic picture from folding theory.
- **Urea:** a chemical denaturant, 0–8 M. It weakens every attractive contact by 6% per molar, roughly like measured m-values. At 8 M, ubiquitin falls from about 80% of native contacts to about 40%.
- **Optical tweezers:** a constant force (0–300 pN) pulls the first and last residues apart, with arrows and a live end-to-end distance. The sphere widens so the chain can reach full length. At 200 pN ubiquitin unfolds and stretches to about 225 Å, in line with the 100–300 pN range where single-molecule experiments unfold proteins.

## Proteins

The app has these presets. Natural sequences are checked against UniProt.

| Group | Proteins |
| --- | --- |
| Small and fast | Chignolin, Trp-cage, GB1 hairpin, villin headpiece HP35 |
| Hormones and peptides | Oxytocin, melittin, glucagon, GLP-1 |
| Disulfide-bonded | Insulin (A + B chains), BPTI, hen lysozyme |
| Classic folds | Amyloid-β 1–42, ubiquitin, myoglobin, GFP |
| Interactions (several chains) | GCN4 leucine-zipper dimer, hemoglobin α₂β₂ (574 residues), sickle hemoglobin (β Glu6Val), amyloid-β ×6, amyloid-β ×24 (1008 residues) |

**Your own proteins:** tap **Create a protein…**. Paste one-letter codes or FASTA, and use `/` or separate FASTA records for multiple chains. You can also add up to 24 copies to watch them interact. The limit is 3000 residues in total.

**Random proteins:** choose **Random protein** for a new sequence each time the screen turns on. Lengths run from 20 to 3000 residues. Styles:
- natural amino-acid composition
- designed helix bundle
- designed α/β mix

Big proteins run slower. The readout shows the step rate for anything over 300 residues.

The web version (`index.html`) has the original six proteins and doesn't have these new features.

## On screen

**Folding progress:** a bar at the top shows how close the protein is to folded. It can be switched off in settings.
- **With a known structure:** it counts native contacts formed and how close the RMSD is to the real structure, both measured from the unfolded start. It shows the lower of the two, so 100% needs 90% of native contacts and under 2 Å RMSD.
- **Without one:** it's labelled "Collapsed" instead. It measures how far the chain has shrunk toward the typical size of a folded protein that long (radius of gyration ≈ 2.2·N^0.38 Å).

The first time the bar reaches 100%, the event feed logs how long folding took.

**Performance (settings):**
- **Battery saver:** 30 fps with light computing.
- **Balanced:** the default.
- **Extreme:** runs the simulation nonstop on its own thread, drawing at 30 fps from a snapshot so the display never holds it up. The speed slider no longer applies.

At desktop speed, including drawing, Extreme ran villin 41× faster than Balanced and the hemoglobin tetramer 1.7× faster. It only computes while the wallpaper or app is visible, but then uses much more battery.


**Views (Android app, in settings under View):**
- **Style:**
  - **Beads** draws every residue.
  - **Cartoon** draws a smooth spline through the backbone. Helices become twisting ribbons that read as spirals, strands become flat arrows, and loops become thin tubes. Each ribbon is oriented from the local Cα geometry and shaded by which way it faces.
  - **Backbone trace** is just the tube.
  - **Beads + cartoon** draws the ribbons over smaller, see-through beads.
- **Colour by:**
  - **Chemistry** uses the colours below.
  - **Chain** colours each chain, beads included, in one of eight bold colours. A single chain gets the rainbow from blue (N-terminus) to red (C-terminus) that structure viewers use.
  - **Auto** (the default) picks chains when there are several.


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

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

**Several chains:** chains interact through exactly the same contact, electrostatic and disulfide terms as residues within one chain. That's how insulin's chains pair up, how hemoglobin subunits stick together, and how amyloid peptides clump. The readout counts the contacts between chains and the size of the largest complex.

**Scaling:** non-bonded pairs come from neighbour lists rebuilt on a spatial grid, so the cost grows roughly linearly with size. A 3000-residue chain takes about 1 ms per step on a desktop CPU.

**Real structures (Android app):** most presets come with their experimental structure from the Protein Data Bank. The Cα positions are baked in by `tools/extract_native.py`, which aligns each PDB chain to the UniProt-checked sequence and prints a coverage and identity report. With a structure, a structure-based ("Gō-model") layer steers folding toward it:
- residue pairs that touch in the real structure attract, with a well centred on their real distance. Cutoffs are 7.5 Å for pairs close in sequence and 10 Å for distant pairs and pairs across chains, because packed helices sit 8–11 Å apart at their Cα atoms.
- bond angles and twists are pulled toward their real values.
- a weak long-range pull between residues on either side of a real interface. It stands in for the diffusion and electrostatic steering that bring partners together, which would take far too long to simulate.
- complexes start dissociated, each chain unfolded around its real position, pushed 40% further out.

The **Native-structure guidance** slider blends from generic physics (0%) to fully guided (100%, the default). The readout shows:
- **native contacts (Q):** the share of the real structure's contacts that have formed.
- **RMSD:** how far the shape is from the real one after the best superposition, in Å.

Measured at desktop speed, starting from an unfolded chain:
- **Small and medium proteins:** Trp-cage, chignolin, villin, ubiquitin and myoglobin usually reach their crystal structures (RMSD 1–3 Å) within 20–60 seconds. BPTI often does.
- **Small complexes:** insulin's two chains and the GCN4 dimer assemble correctly within about 40 seconds.
- **Larger proteins:** lysozyme, GFP and the hemoglobin tetramer take several minutes and can get stuck. For those, set "Heat to unfold every" to 5 minutes or Never.

On a tablet everything runs slower than these figures.

**Load any protein:** in **Create a protein…**, type:
- a PDB ID, e.g. `1UBQ`
- a PDB ID with chosen chains, e.g. `2HHB:A,B`
- a UniProt ID for an AlphaFold prediction, e.g. `P69905`

The app downloads the structure, reads its Cα trace (PDB or mmCIF), splits chains wherever residues are missing, and folds it toward that structure. The app only uses the internet for this.

**Limits:** without a known structure (random proteins, typed-in sequences, amyloid-β) the generic physics collapses chains and forms helices and strands with the right trends, but won't reliably find the true fold. With a structure, the model folds toward a shape it was given; it doesn't predict one. The web version has neither.

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

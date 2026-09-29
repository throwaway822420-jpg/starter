# Hydrophobic Collapse

An interactive live wallpaper that folds a protein in real time. The whole thing is one file, `index.html`.

## What it simulates

This is a 2D **HP model**, a classic simplified model of protein folding. Each bead is one residue:

- **H (amber):** hydrophobic. H beads attract each other, so they pack into a core away from water.
- **P (teal rings):** polar. P beads only repel, so they end up on the surface as loops.

The chain moves under Langevin dynamics, which means random thermal kicks from the solvent plus friction. The model uses:

- bond springs
- mild backbone stiffness
- Lennard-Jones attraction between H beads

About once a minute it heats the chain until it unfolds, then cools it so it folds again.

The sequences are the standard 2D HP benchmark set (20 to 64 residues), plus a random one. This is a teaching model, not real protein chemistry.

The readout (bottom-left) shows:

- **Energy:** the energy from residue contacts, in ε units. Lower means more folded.
- **H–H contacts:** how many hydrophobic pairs are touching.
- **Rg:** radius of gyration, meaning how compact the chain is, in bead diameters (σ).
- **Energy, last 60 s:** a trace of the energy. The dashed line is the lowest energy since the last unfold.
- **Contact map:** the upper-right triangle shows contacts right now. The lower-left triangle shows how often each contact has formed recently.

## Controls

| Gesture | What it does |
| --- | --- |
| Drag a bead | Pull the chain |
| Tap open space | Stir the solvent |
| Double-tap | Unfold now |
| Long-press | Open settings |

In settings you can pick the sequence and simulation speed, set how often it unfolds, hide the readout, and turn on battery saver (30 fps).

Add `#clean` to the end of the URL to hide the readout and the settings button. This mode is meant for the wallpaper. Long-press still opens settings.

## Setting it as a wallpaper on a Galaxy Tab S11

1. Install an HTML/web live wallpaper app from the Play Store. Search for "web live wallpaper" or "HTML live wallpaper".
2. Give the app the page. You can do this in one of two ways:
   - **URL:** turn on GitHub Pages for this repo (Settings → Pages → deploy from this branch, root folder). Then use `https://<your-username>.github.io/starter/#clean`.
   - **Local file:** download `index.html` to the tablet and choose it in the app, if the app supports local files.
3. Set it as the home screen wallpaper. Many wallpaper apps pass taps but not drags, so tapping and double-tapping always work, while dragging a bead depends on the app.

It pauses when the wallpaper isn't visible. Battery saver halves the frame rate if you want to save more power.

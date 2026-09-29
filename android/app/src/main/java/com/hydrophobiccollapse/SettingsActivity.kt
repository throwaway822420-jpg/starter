package com.hydrophobiccollapse

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** The app's own screen: a live preview behind the controls, and a button to set the wallpaper. */
class SettingsActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var preview: FoldingView
    private lateinit var panel: ScrollView
    private lateinit var toggle: TextView
    private var s = Settings()
    private val density by lazy { resources.displayMetrics.density }
    private fun dp(v: Int) = (v * density).roundToInt()

    private val colText = 0xFFE4E8F3.toInt()
    private val colHaze = 0xFF8A94B0.toInt()
    private val colHydro = 0xFFF0A44B.toInt()
    private val colInk = 0xFF0A0F1D.toInt()
    private val colLine = 0x29A0B0D6

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Settings.prefs(this)
        s = Settings.load(prefs)

        val root = FrameLayout(this)
        preview = FoldingView(this)
        preview.sim.applySettings(s)
        root.addView(preview, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        panel = ScrollView(this).apply {
            background = GradientDrawable().apply { setColor(0xF20D1324.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), colLine) }
            isVerticalScrollBarEnabled = false
            clipToPadding = false
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(18)) }
        panel.addView(col)
        buildControls(col)
        val panelW = minOf(dp(340), resources.displayMetrics.widthPixels - dp(32))
        root.addView(panel, FrameLayout.LayoutParams(panelW, WRAP_CONTENT, Gravity.END or Gravity.TOP))

        toggle = TextView(this).apply {
            text = "Hide controls"
            setTextColor(colText); textSize = 13f
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply { setColor(0xC20D1324.toInt()); cornerRadius = dp(20).toFloat(); setStroke(dp(1), colLine) }
            setOnClickListener {
                val show = panel.visibility != View.VISIBLE
                panel.visibility = if (show) View.VISIBLE else View.GONE
                text = if (show) "Hide controls" else "Show controls"
            }
        }
        root.addView(toggle, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.START or Gravity.TOP))

        // Keep the controls clear of the status and navigation bars
        root.setOnApplyWindowInsetsListener { _, insets ->
            val top: Int; val bottom: Int; val left: Int; val right: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                top = i.top; bottom = i.bottom; left = i.left; right = i.right
            } else {
                @Suppress("DEPRECATION")
                run { top = insets.systemWindowInsetTop; bottom = insets.systemWindowInsetBottom; left = insets.systemWindowInsetLeft; right = insets.systemWindowInsetRight }
            }
            (panel.layoutParams as FrameLayout.LayoutParams).setMargins(dp(16), top + dp(16), right + dp(16), bottom + dp(16))
            (toggle.layoutParams as FrameLayout.LayoutParams).setMargins(left + dp(16), top + dp(16), dp(16), dp(16))
            panel.requestLayout(); toggle.requestLayout()
            preview.sim.bottomInset = bottom.toFloat()
            preview.sim.topInset = top.toFloat() + dp(40)   // below the "Hide controls" button
            insets
        }
        setContentView(root)
    }

    override fun onResume() { super.onResume(); preview.start() }
    override fun onPause() { preview.stop(); super.onPause() }

    private fun update(next: Settings) {
        s = next
        s.save(prefs)
        preview.sim.applySettings(s)
    }

    // ---------- Controls ----------
    private fun buildControls(col: LinearLayout) {
        col.addView(TextView(this).apply {
            text = "Hydrophobic Collapse"; setTextColor(colText); textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        col.addView(body("Real protein sequences folding in 3D, with pH-driven proton transfers and disulfide chemistry."), margins(top = 4))
        col.addView(button("Set as wallpaper", primary = true) { setWallpaper() }, margins(top = 14))

        buildProteinSection(col)

        col.addView(section("Solution"))
        col.addView(slider("Temperature", 270f, 420f, 5f, s.temp.toFloat(), { "${it.roundToInt()} K · ${it.roundToInt() - 273} °C" }) { update(s.copy(temp = it.roundToInt())) })
        col.addView(slider("pH", 1f, 13f, 0.5f, s.ph, { "%.1f".format(it) }) { update(s.copy(ph = it)) })
        col.addView(slider("Salt (NaCl)", 0f, 1000f, 25f, s.salt.toFloat(), {
            val debye = 3.04 / kotlin.math.sqrt(maxOf(it.toDouble(), 1.0) / 1000)
            "${it.roundToInt()} mM · λD ${if (debye < 100) "%.1f".format(debye) else ">96"} Å"
        }) { update(s.copy(salt = it.roundToInt())) })
        col.addView(slider("Redox buffer", -1f, 1f, 0.1f, s.redox, { redoxLabel(it) }) { update(s.copy(redox = it)) })
        col.addView(label("Crowding (when there are several chains)"), margins(top = 10))
        val crowdingNames = listOf("Dilute: protein fills 3% of the space", "Crowded: 12%", "Cell-like: 25%, as in cytoplasm")
        col.addView(spinner(crowdingNames, s.crowding.coerceIn(0, 2)) { idx ->
            if (idx != s.crowding) update(s.copy(crowding = idx))
        }, margins(top = 4))

        col.addView(section("View"))
        col.addView(label("Style"), margins(top = 6))
        col.addView(spinner(listOf("Beads: every residue", "Cartoon: helix spirals, strand arrows", "Backbone trace", "Beads + cartoon overlay"), s.viewStyle.coerceIn(0, 3)) { idx ->
            if (idx != s.viewStyle) update(s.copy(viewStyle = idx))
        }, margins(top = 4))
        col.addView(label("Colour by"), margins(top = 10))
        col.addView(spinner(listOf("Auto: chains if several, else chemistry", "Chemistry", "Chain (one chain: rainbow N → C)"), s.colorBy.coerceIn(0, 2)) { idx ->
            if (idx != s.colorBy) update(s.copy(colorBy = idx))
        }, margins(top = 4))

        col.addView(section("Playback"))
        col.addView(slider("Simulation speed", 0.25f, 3f, 0.25f, s.speed, { "${it}×" }) { update(s.copy(speed = it)) })
        col.addView(label("Heat to unfold every"), margins(top = 10))
        val cycles = listOf(45 to "45 seconds", 90 to "90 seconds", 180 to "3 minutes", 300 to "5 minutes", 0 to "Never")
        col.addView(spinner(cycles.map { it.second }, cycles.indexOfFirst { it.first == s.cycle }.coerceAtLeast(1)) { idx ->
            if (cycles[idx].first != s.cycle) update(s.copy(cycle = cycles[idx].first))
        }, margins(top = 4))
        col.addView(switch("Show readout", s.hud) { update(s.copy(hud = it)) }, margins(top = 10))
        col.addView(label("Performance"), margins(top = 10))
        val perfNames = listOf("Battery saver: 30 fps, light computing", "Balanced", "Extreme: fold as fast as possible")
        val perfNote = body("").apply { textSize = 12f }
        fun perfText(p: Int) = if (p == 2) "Runs the simulation nonstop on its own thread, and uses several cores for big proteins. The speed slider no longer applies. Uses much more battery, and only while visible." else ""
        col.addView(spinner(perfNames, s.performance.coerceIn(0, 2)) { idx ->
            if (idx != s.performance) { update(s.copy(performance = idx)); perfNote.text = perfText(idx); perfNote.visibility = if (idx == 2) View.VISIBLE else View.GONE }
        }, margins(top = 4))
        perfNote.text = perfText(s.performance); perfNote.visibility = if (s.performance == 2) View.VISIBLE else View.GONE
        col.addView(perfNote, margins(top = 4))
        col.addView(switch("Show folding progress", s.showProgress) { update(s.copy(showProgress = it)) }, margins(top = 6))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Heat to unfold", primary = false) {
            preview.sim.heat(); Settings.sendCommand(prefs, Settings.CMD_HEAT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(button("Start over", primary = false) {
            preview.sim.reset(); Settings.sendCommand(prefs, Settings.CMD_RESET); refreshProteinUi()
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row, margins(top = 14))

        col.addView(TextView(this).apply { text = colourKey(); textSize = 12f; setLineSpacing(0f, 1.35f) }, margins(top = 16))
        col.addView(body("Drag a residue to pull it. Drag open space here to turn the molecule; on the home screen, swiping between pages turns it. Tap to stir the water. Double-tap to heat and unfold."), margins(top = 12))
    }

    // ---------- Protein picker, random mode and your own proteins ----------
    private class Entry(val id: String, val label: String)
    private lateinit var proteinSpinner: Spinner
    private lateinit var proteinNote: TextView
    private lateinit var editRow: LinearLayout
    private lateinit var randomBox: LinearLayout
    private lateinit var guidanceBox: LinearLayout
    private var entries = listOf<Entry>()

    private fun proteinEntries(): List<Entry> {
        val list = ArrayList<Entry>()
        list.add(Entry(Proteins.RANDOM_ID, "Random protein (new each time)"))
        for (p in Proteins.customs(s.customJson)) list.add(Entry(p.id, "★ ${p.name} (${p.length})"))
        for (p in Proteins.presets) list.add(Entry(p.id, "${p.name} (${p.length})"))
        return list
    }

    private fun buildProteinSection(col: LinearLayout) {
        col.addView(section("Protein"))
        proteinSpinner = Spinner(this).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply { setColor(colInk); cornerRadius = dp(6).toFloat(); setStroke(dp(1), colLine) }
            setPopupBackgroundDrawable(GradientDrawable().apply { setColor(0xFF141B2E.toInt()); cornerRadius = dp(6).toFloat() })
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    (view as? TextView)?.setTextColor(colText)
                    val e = entries.getOrNull(position) ?: return
                    if (e.id != s.protein) { update(s.copy(protein = e.id)); refreshProteinUi() }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        col.addView(proteinSpinner, margins(top = 6))
        proteinNote = body("")
        col.addView(proteinNote, margins(top = 6))

        // How strongly to steer toward the real structure, when there is one
        guidanceBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        guidanceBox.addView(slider("Native-structure guidance", 0f, 1f, 0.05f, s.nativeBias, { "${(it * 100).roundToInt()} %" }) {
            update(s.copy(nativeBias = it))
        })
        guidanceBox.addView(body("100 % folds toward the real structure. 0 % uses only generic physics, which collapses but rarely finds the real fold.").apply { textSize = 12f }, margins(top = 2))
        col.addView(guidanceBox)

        // Random mode
        randomBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        randomBox.addView(logSlider("Random length", 20, Proteins.MAX_RESIDUES, s.randomLength) { update(s.copy(randomLength = it)); refreshProteinUi() })
        randomBox.addView(label("Style"), margins(top = 10))
        randomBox.addView(spinner(Proteins.RANDOM_STYLES, s.randomStyle.coerceIn(0, 2)) { idx ->
            if (idx != s.randomStyle) { update(s.copy(randomStyle = idx)); refreshProteinUi() }
        }, margins(top = 4))
        randomBox.addView(switch("New protein each time the screen turns on", s.randomOnWake) { update(s.copy(randomOnWake = it)) }, margins(top = 8))
        col.addView(randomBox, margins(top = 4))

        // Your own proteins
        val create = button("Create a protein…", primary = false) { openEditor(null) }
        col.addView(create, margins(top = 10))
        editRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        editRow.addView(button("Edit", primary = false) { openEditor(s.protein) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        editRow.addView(button("Delete", primary = false) { confirmDelete(s.protein) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(editRow, margins(top = 8))
        refreshProteinUi()
    }

    private fun refreshProteinUi() {
        entries = proteinEntries()
        val labels = entries.map { it.label }
        proteinSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, labels).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val idx = entries.indexOfFirst { it.id == s.protein }.let { if (it < 0) entries.indexOfFirst { e -> e.id == "bpti" } else it }
        proteinSpinner.setSelection(idx, false)
        val isRandom = s.protein == Proteins.RANDOM_ID
        val isCustom = s.protein.startsWith("custom:")
        randomBox.visibility = if (isRandom) View.VISIBLE else View.GONE
        editRow.visibility = if (isCustom) View.VISIBLE else View.GONE
        val p = if (isRandom) preview.sim.protein else Proteins.byId(s.protein, s.customJson)
        val structure = if (p.ca != null) " Folds toward its real structure (${p.structureSource})." else if (isRandom) "" else " No experimental structure, so generic physics only."
        proteinNote.text = when {
            isRandom -> "Now showing: ${p.name}. ${p.note}${slowHint(p.length)}"
            else -> p.note + structure + slowHint(p.length)
        }
        guidanceBox.visibility = if (p.ca != null) View.VISIBLE else View.GONE
    }

    private fun slowHint(len: Int) = when {
        len > 1500 -> " Very large: it will fold slowly on a tablet."
        len > 600 -> " Large: expect slower motion."
        else -> ""
    }

    private fun openEditor(existingId: String?) {
        val entry = existingId?.let { Proteins.customJsonEntry(s.customJson, it) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(4)) }

        // A structure fetched from the PDB or AlphaFold, kept while the sequence still matches it
        var fetched: StructureIO.Loaded? = null
        var existingCa = entry?.optString("ca")?.ifEmpty { null }
        val existingSource = entry?.optString("source")?.ifEmpty { null }
        box.addView(label("Load from the PDB or AlphaFold (optional)"))
        val fetchRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val query = android.widget.EditText(this).apply {
            hint = "1UBQ, 2HHB:A,B or P69905"; setSingleLine(); setTextColor(colText); typeface = Typeface.MONOSPACE
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        fetchRow.addView(query, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val fetchBtn = button("Fetch", primary = false) {}
        fetchRow.addView(fetchBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(8) })
        box.addView(fetchRow, margins(top = 2))
        val fetchStatus = TextView(this).apply { textSize = 12f; setTextColor(colHaze); text = "PDB ID for an experimental structure (add :A,B to pick chains), or a UniProt ID for an AlphaFold prediction." }
        box.addView(fetchStatus, margins(top = 4))

        val name = android.widget.EditText(this).apply {
            hint = "Name"; setSingleLine(); setTextColor(colText)
            setText(entry?.optString("name") ?: "")
        }
        box.addView(name)
        val seqField = android.widget.EditText(this).apply {
            hint = "Paste one-letter codes or FASTA.\nUse / between chains."
            typeface = Typeface.MONOSPACE; textSize = 14f; setTextColor(colText)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            minLines = 4; maxLines = 10; gravity = Gravity.TOP or Gravity.START
            setText(entry?.optJSONArray("chains")?.let { a -> (0 until a.length()).joinToString(" / ") { a.getString(it) } } ?: "")
        }
        box.addView(seqField, margins(top = 8))
        fetchBtn.setOnClickListener {
            val q = query.text.toString().trim()
            StructureIO.queryProblem(q)?.let { fetchStatus.setTextColor(0xFFFF7D8E.toInt()); fetchStatus.text = it; return@setOnClickListener }
            fetchBtn.isEnabled = false; fetchStatus.setTextColor(colHaze); fetchStatus.text = "Downloading $q…"
            Thread {
                val result = try { Result.success(StructureIO.fetch(q)) } catch (e: Exception) { Result.failure(e) }
                runOnUiThread {
                    fetchBtn.isEnabled = true
                    result.onSuccess { loaded ->
                        fetched = loaded; existingCa = null
                        name.setText(loaded.title.take(60))
                        seqField.setText(loaded.chains.joinToString(" / ") { it.seq })
                        fetchStatus.setTextColor(colHaze)
                        fetchStatus.text = "Loaded ${loaded.source}: ${loaded.chains.size} chain${if (loaded.chains.size > 1) "s" else ""}, ${loaded.length} residues with known positions."
                    }.onFailure { e ->
                        fetchStatus.setTextColor(0xFFFF7D8E.toInt())
                        fetchStatus.text = when (e) {
                            is java.net.UnknownHostException -> "No internet connection. Connect and try again."
                            is java.io.IOException -> e.message ?: "The download failed. Try again."
                            else -> "That file couldn't be read. Try another ID."
                        }
                    }
                }
            }.start()
        }
        var copies = entry?.optInt("copies", 1) ?: 1
        val copiesOut = TextView(this).apply { setTextColor(colText); textSize = 12f; typeface = Typeface.MONOSPACE }
        val status = TextView(this).apply { textSize = 12f; setLineSpacing(0f, 1.2f) }
        fun validate(): Proteins.Parsed {
            val parsed = Proteins.parse(seqField.text.toString())
            val total = parsed.chains.sumOf { it.length } * copies
            copiesOut.text = if (copies == 1) "1 copy" else "$copies copies"
            when {
                parsed.error != null -> { status.setTextColor(0xFFFF7D8E.toInt()); status.text = parsed.error }
                total > Proteins.MAX_RESIDUES -> { status.setTextColor(0xFFFF7D8E.toInt()); status.text = "$total residues in total. The limit is ${Proteins.MAX_RESIDUES}; shorten it or use fewer copies." }
                else -> {
                    val nCh = parsed.chains.size * copies
                    status.setTextColor(colHaze)
                    val f = fetched
                    val structure = when {
                        f == null -> ""
                        f.chains.map { it.seq } == parsed.chains -> " Folds toward ${f.source}."
                        else -> " The sequence no longer matches ${f.source}, so its structure won't be used."
                    }
                    status.text = "$total residues · $nCh chain${if (nCh > 1) "s" else ""}.$structure${slowHint(total)}"
                }
            }
            return parsed
        }
        val copiesHead = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        copiesHead.addView(label("Copies in the box (to watch them interact)"), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        copiesHead.addView(copiesOut)
        box.addView(copiesHead, margins(top = 12))
        box.addView(SeekBar(this).apply {
            max = Proteins.MAX_COPIES - 1; progress = copies - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { copies = p + 1; validate() }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }, margins(top = 2))
        val helpers = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        helpers.addView(button("Random fill", primary = false) {
            seqField.setText(Proteins.random(60, 1).chains[0]); if (name.text.isBlank()) name.setText("My helix bundle")
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        helpers.addView(button("Clear", primary = false) { seqField.setText("") }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        box.addView(helpers, margins(top = 10))
        box.addView(status, margins(top = 10))
        seqField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(e: android.text.Editable?) { validate() }
            override fun beforeTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(t: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        validate()
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (entry != null) "Edit protein" else "Create a protein")
            .setView(scroll)
            .setPositiveButton("Save and fold", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setTextColor(colHydro)
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).setTextColor(colHaze)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val parsed = validate()
                val total = parsed.chains.sumOf { it.length } * copies
                if (parsed.error != null || total > Proteins.MAX_RESIDUES) return@setOnClickListener
                val title = name.text.toString().trim().ifEmpty { "My protein" }
                // Keep the structure only if the sequence still matches it exactly
                val f = fetched
                var ca: FloatArray? = null; var source: String? = null
                if (f != null && f.chains.map { it.seq } == parsed.chains) { ca = f.ca; source = f.source }
                else if (f == null && existingCa != null && existingSource != null) {
                    val oldChains = entry?.optJSONArray("chains")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                    if (oldChains == parsed.chains) { ca = CaCodec.decode(existingCa!!, parsed.chains.sumOf { it.length }); source = existingSource }
                }
                val (json, id) = Proteins.saveCustom(s.customJson, existingId, title, parsed.chains, copies, ca, source)
                update(s.copy(customJson = json, protein = id))
                refreshProteinUi()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(id: String) {
        val p = Proteins.customs(s.customJson).firstOrNull { it.id == id } ?: return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Delete ${p.name}?")
            .setMessage("This removes it from your proteins. It can't be undone.")
            .setPositiveButton("Delete") { _, _ ->
                update(s.copy(customJson = Proteins.deleteCustom(s.customJson, id), protein = "bpti"))
                refreshProteinUi()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** A slider on a log scale, for lengths from tens to thousands. */
    private fun logSlider(name: String, min: Int, max: Int, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = margins(top = 10) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label(name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val out = TextView(this).apply { setTextColor(colText); textSize = 12f; typeface = Typeface.MONOSPACE }
        head.addView(out)
        box.addView(head)
        val ratio = max.toDouble() / min
        fun toLen(p: Int): Int {
            val raw = min * Math.pow(ratio, p / 1000.0)
            val step = if (raw < 100) 5 else if (raw < 1000) 10 else 50
            return ((raw / step).roundToInt() * step).coerceIn(min, max)
        }
        fun label(v: Int) = "$v residues"
        out.text = label(value)
        box.addView(SeekBar(this).apply {
            this.max = 1000
            progress = (1000 * Math.log(value.toDouble() / min) / Math.log(ratio)).roundToInt().coerceIn(0, 1000)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) { out.text = label(toLen(p)) }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                // Apply on release: every change makes a new protein
                override fun onStopTrackingTouch(sb: SeekBar?) { onChange(toLen(sb?.progress ?: 0)) }
            })
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }

    private fun redoxLabel(r: Float) = when {
        r <= -0.6f -> "Strongly reducing"; r < -0.15f -> "Reducing"; r <= 0.15f -> "Balanced"; r < 0.6f -> "Oxidizing"; else -> "Strongly oxidizing"
    }

    private fun colourKey(): CharSequence {
        val items = listOf(
            0xFFF0A44B to "Hydrophobic", 0xFF74CFC0 to "Polar", 0xFF8EA2FF to "Positive", 0xFFFF7D8E to "Negative",
            0xFFF3D34A to "Cysteine", 0xFF9AA3B8 to "Gly / Pro", 0xFFC4B1FF to "Helix", 0xFF9FE3A8 to "Strand",
        )
        val sb = SpannableStringBuilder()
        items.forEachIndexed { k, (c, name) ->
            val start = sb.length
            sb.append(if (k >= 6) "▬ " else "● ")
            sb.setSpan(ForegroundColorSpan(c.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val t = sb.length
            sb.append(name.padEnd(14))
            sb.setSpan(ForegroundColorSpan(colHaze), t, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (k % 2 == 1 && k < items.size - 1) sb.append("\n")
        }
        return sb
    }

    private fun setWallpaper() {
        val component = ComponentName(this, FoldingWallpaperService::class.java)
        try {
            startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: ActivityNotFoundException) {
                Toast.makeText(this, "Open Settings → Wallpaper and choose Hydrophobic Collapse.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // ---------- Small view builders ----------
    private fun margins(top: Int = 0) = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun body(t: String) = TextView(this).apply { text = t; setTextColor(colHaze); textSize = 13f; setLineSpacing(0f, 1.25f) }
    private fun label(t: String) = TextView(this).apply { text = t; setTextColor(colHaze); textSize = 12f }
    private fun section(t: String) = TextView(this).apply {
        text = t.uppercase(); setTextColor(colHaze); textSize = 10f; letterSpacing = 0.1f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        layoutParams = margins(top = 22)
    }
    private fun button(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; textSize = 14f
        setTextColor(if (primary) colInk else colText)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            if (primary) setColor(colHydro) else { setColor(0); setStroke(dp(1), 0x4DA0B0D6) }
        }
        stateListAnimator = null
        minHeight = dp(44)
        setOnClickListener { onClick() }
    }
    private fun spinner(items: List<String>, selected: Int, onSelect: (Int) -> Unit) = Spinner(this).apply {
        adapter = ArrayAdapter(this@SettingsActivity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(selected, false)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        background = GradientDrawable().apply { setColor(colInk); cornerRadius = dp(6).toFloat(); setStroke(dp(1), colLine) }
        setPopupBackgroundDrawable(GradientDrawable().apply { setColor(0xFF141B2E.toInt()); cornerRadius = dp(6).toFloat() })
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                (view as? TextView)?.setTextColor(colText)
                onSelect(position)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }
    @Suppress("UseSwitchCompatOrMaterialCode")
    private fun switch(t: String, checked: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = t; setTextColor(colText); textSize = 14f; isChecked = checked
        minHeight = dp(40)
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }
    private fun slider(name: String, min: Float, max: Float, step: Float, value: Float, fmt: (Float) -> String, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = margins(top = 10) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(label(name), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        val out = TextView(this).apply { setTextColor(colText); textSize = 12f; typeface = Typeface.MONOSPACE; text = fmt(value) }
        head.addView(out)
        box.addView(head)
        val steps = ((max - min) / step).roundToInt()
        val bar = SeekBar(this).apply {
            this.max = steps
            progress = ((value - min) / step).roundToInt().coerceIn(0, steps)
            setPadding(dp(4), dp(8), dp(4), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = ((min + p * step) * 100).roundToInt() / 100f
                    out.text = fmt(v)
                    if (fromUser) onChange(v)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        box.addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return box
    }
}

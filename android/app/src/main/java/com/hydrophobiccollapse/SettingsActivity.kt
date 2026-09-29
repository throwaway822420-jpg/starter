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

        col.addView(section("Protein"))
        val note = body(Proteins.byId(s.protein).note)
        val names = Proteins.all.map { "${it.name} (${it.seq.length})" }
        col.addView(spinner(names, Proteins.all.indexOfFirst { it.id == s.protein }.coerceAtLeast(0)) { idx ->
            val p = Proteins.all[idx]
            if (p.id != s.protein) { note.text = p.note; update(s.copy(protein = p.id)) }
        }, margins(top = 6))
        col.addView(note, margins(top = 6))

        col.addView(section("Solution"))
        col.addView(slider("Temperature", 270f, 420f, 5f, s.temp.toFloat(), { "${it.roundToInt()} K · ${it.roundToInt() - 273} °C" }) { update(s.copy(temp = it.roundToInt())) })
        col.addView(slider("pH", 1f, 13f, 0.5f, s.ph, { "%.1f".format(it) }) { update(s.copy(ph = it)) })
        col.addView(slider("Salt (NaCl)", 0f, 1000f, 25f, s.salt.toFloat(), {
            val debye = 3.04 / kotlin.math.sqrt(maxOf(it.toDouble(), 1.0) / 1000)
            "${it.roundToInt()} mM · λD ${if (debye < 100) "%.1f".format(debye) else ">96"} Å"
        }) { update(s.copy(salt = it.roundToInt())) })
        col.addView(slider("Redox buffer", -1f, 1f, 0.1f, s.redox, { redoxLabel(it) }) { update(s.copy(redox = it)) })

        col.addView(section("Playback"))
        col.addView(slider("Simulation speed", 0.25f, 3f, 0.25f, s.speed, { "${it}×" }) { update(s.copy(speed = it)) })
        col.addView(label("Heat to unfold every"), margins(top = 10))
        val cycles = listOf(45 to "45 seconds", 90 to "90 seconds", 180 to "3 minutes", 300 to "5 minutes", 0 to "Never")
        col.addView(spinner(cycles.map { it.second }, cycles.indexOfFirst { it.first == s.cycle }.coerceAtLeast(1)) { idx ->
            if (cycles[idx].first != s.cycle) update(s.copy(cycle = cycles[idx].first))
        }, margins(top = 4))
        col.addView(switch("Show readout", s.hud) { update(s.copy(hud = it)) }, margins(top = 10))
        col.addView(switch("Battery saver (30 fps)", s.saver) { update(s.copy(saver = it)) }, margins(top = 2))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button("Heat to unfold", primary = false) {
            preview.sim.heat(); Settings.sendCommand(prefs, Settings.CMD_HEAT)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(button("New chain", primary = false) {
            preview.sim.reset(); Settings.sendCommand(prefs, Settings.CMD_RESET)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        col.addView(row, margins(top = 14))

        col.addView(TextView(this).apply { text = colourKey(); textSize = 12f; setLineSpacing(0f, 1.35f) }, margins(top = 16))
        col.addView(body("Drag a residue to pull it. Drag open space here to turn the molecule; on the home screen, swiping between pages turns it. Tap to stir the water. Double-tap to heat and unfold."), margins(top = 12))
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

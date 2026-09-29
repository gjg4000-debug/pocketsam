package com.example.pocketsam

import android.app.DatePickerDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Pocket SAM — feed-rate logger.
 * Data layout (saved on the phone):
 *   { "County/City": { "Site name": { field: value, ..., "_history": [ {date, field: value, ...}, ... ] } } }
 * Same file format as the web version, so backups move between them.
 */
class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("pocket_sam", Context.MODE_PRIVATE) }
    private var db = JSONObject()

    private var county: String? = null
    private var site: String? = null
    private var dirty = false      // unsaved edits on screen
    private var loading = false    // true while we fill fields ourselves
    private var readingDate = today()

    private lateinit var countyBtn: Button
    private lateinit var siteBtn: Button
    private lateinit var status: TextView
    private lateinit var latestView: TextView
    private lateinit var completeChk: CheckBox
    private lateinit var chemResult: TextView
    private lateinit var bioResult: TextView
    private lateinit var dateBtn: Button
    private lateinit var historyBtn: Button
    private val fields = linkedMapOf<String, EditText>()

    // File pickers (save to phone storage / Google Drive, or open one)
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) exportTo(uri) }
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) importFrom(uri) }
    private val csvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri -> if (uri != null) exportCsv(uri) }

    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val HIST = "_history"

    private data class F(val key: String, val label: String, val hint: String, val numeric: Boolean)
    private data class Calc(val gpd: Double?, val bioGpd: Double?, val days: Double?, val runMin: Double, val timer: Boolean)

    private val chemFields = listOf(
        F("p1Rate", "Pump1 rate mL/min", "Pump1 mL/min", true),
        F("p1Hrs", "Pump1 timer hrs/day", "blank = 24", true),
        F("p2Rate", "Pump2 rate mL/min", "Pump2 mL/min", true),
        F("p2Hrs", "Pump2 timer hrs/day", "blank = 24", true),
    )
    private val chemInfo = F("chemInfo", "Chem feed info", "Pump type/size/brand, tank level", false)

    private val bioFields = listOf(
        F("tankGal", "Nutrient tank gallons", "Tank gal", true),
        F("jugs", "Nutrient jugs per tank", "Jugs", true),
        F("bioGph", "Nutrient pump max GPH (label)", "GPH", true),
        F("speed", "Nutrient pump speed %", "Speed %", true),
        F("stroke", "Nutrient pump stroke %", "Stroke %", true),
        F("onSec", "Timer ON (seconds)", "e.g. 11", true),
        F("offMin", "Timer OFF (minutes)", "e.g. 14", true),
    )
    private val bioInfo = F("bioInfo", "Biofilter info", "e.g. belt size", false)
    private val allDefs by lazy { chemFields + chemInfo + bioFields + bioInfo }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        db = try { JSONObject(prefs.getString("db", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

        // Draw edge-to-edge and pad for status bar / nav bar / keyboard ourselves.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }

        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
        }
        scroll.addView(col)
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        col.addView(TextView(this).apply {
            text = "Pocket SAM"; textSize = 24f; setTypeface(null, Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        })

        // County / Site pickers
        val pickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        countyBtn = Button(this).apply { isAllCaps = false; setOnClickListener { pickCounty() } }
        siteBtn = Button(this).apply { isAllCaps = false; setOnClickListener { pickSite() } }
        pickRow.addView(countyBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        pickRow.addView(siteBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(pickRow, LinearLayout.LayoutParams(MATCH, WRAP))
        status = TextView(this).apply { setPadding(0, dp(4), 0, 0) }
        col.addView(status)
        latestView = TextView(this).apply { setPadding(0, 0, 0, dp(4)) }
        col.addView(latestView)
        completeChk = CheckBox(this).apply {
            text = "Complete"; textSize = 18f; setTypeface(null, Typeface.BOLD)
            setOnCheckedChangeListener { _, _ -> if (!loading) { dirty = true; updateStatus() } }
        }
        val completeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        completeRow.addView(completeChk, LinearLayout.LayoutParams(0, WRAP, 1f))
        completeRow.addView(Button(this).apply { text = "SAVE"; setOnClickListener { save() } },
            LinearLayout.LayoutParams(WRAP, WRAP))
        col.addView(completeRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Chemical feed
        col.addView(header("Chemical feed"))
        chemFields.forEach { addField(col, it) }
        chemResult = resultView(); col.addView(chemResult)
        addField(col, chemInfo)

        // Biofilter
        col.addView(header("Biofilter / Nutrient pump"))
        bioFields.forEach { addField(col, it) }
        bioResult = resultView(); col.addView(bioResult)
        addField(col, bioInfo)

        // Reading date
        col.addView(header("Readings"))
        val dateRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        dateRow.addView(TextView(this).apply { text = "Date"; setTypeface(null, Typeface.BOLD) },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        dateBtn = Button(this).apply { isAllCaps = false; setOnClickListener { pickDate() } }
        dateRow.addView(dateBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(dateRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Save / Cancel / Delete
        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf<Pair<String, () -> Unit>>(
            "SAVE" to { save() },
            "CANCEL" to { cancel() },
            "DELETE" to { delete() },
        ).forEach { (t, fn) ->
            btnRow.addView(Button(this).apply { text = t; setOnClickListener { fn() } },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        col.addView(btnRow, LinearLayout.LayoutParams(MATCH, WRAP))
        historyBtn = Button(this).apply { text = "HISTORY"; setOnClickListener { showHistory() } }
        col.addView(historyBtn, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(TextView(this).apply {
            text = "SAVE also keeps a copy under the date above, so HISTORY can show past months. " +
                    "Saving again on the same date just updates that day."
            textSize = 13f
            setPadding(0, dp(4), 0, 0)
        })

        // Backup / Import / CSV
        col.addView(header("Backup & sharing"))
        val backupRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        backupRow.addView(Button(this).apply {
            text = "EXPORT BACKUP"
            setOnClickListener { exportLauncher.launch("PocketSAM-backup-${today()}.json") }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        backupRow.addView(Button(this).apply {
            text = "IMPORT"
            setOnClickListener { guardUnsaved { importLauncher.launch(arrayOf("*/*")) } }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        backupRow.addView(Button(this).apply {
            text = "EXPORT CSV"
            setOnClickListener { csvLauncher.launch("PocketSAM-${today()}.csv") }
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        col.addView(backupRow, LinearLayout.LayoutParams(MATCH, WRAP))

        // Back button: warn about unsaved changes
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                guardUnsaved {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        // Survive screen rotation
        if (savedInstanceState != null) {
            county = savedInstanceState.getString("county")
            site = savedInstanceState.getString("site")
            readingDate = savedInstanceState.getString("readingDate") ?: today()
            loading = true
            fields.forEach { (k, e) -> e.setText(savedInstanceState.getString("f_$k", "")) }
            loading = false
            dirty = savedInstanceState.getBoolean("dirty")
        }
        updateDateBtn()
        compute()
        refreshUi()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("county", county)
        outState.putString("site", site)
        outState.putString("readingDate", readingDate)
        outState.putBoolean("dirty", dirty)
        fields.forEach { (k, e) -> outState.putString("f_$k", e.text.toString()) }
    }

    // ---------- UI building ----------

    private fun addField(parent: LinearLayout, f: F) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val label = TextView(this).apply { text = f.label; setTypeface(null, Typeface.BOLD) }
        val edit = EditText(this).apply {
            hint = f.hint
            isSingleLine = f.numeric
            if (!f.numeric) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
            inputType = if (f.numeric)
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            else
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    if (!loading) { dirty = true; updateStatus(); compute() }
                }
            })
        }
        row.addView(label, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(edit, LinearLayout.LayoutParams(0, WRAP, 1f))
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        fields[f.key] = edit
    }

    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 18f; setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(16), 0, dp(4))
    }

    private fun resultView() = TextView(this).apply {
        textSize = 16f; setTypeface(null, Typeface.BOLD)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun refreshUi() {
        countyBtn.text = county ?: "1. Select County"
        siteBtn.text = site ?: "2. Select Site"
        val h = history(siteRec())
        latestView.text = if (h.isEmpty()) ""
            else "${h.size} reading(s) · latest ${fmtDate(h.last().optString("date"))}"
        historyBtn.isEnabled = h.isNotEmpty()
        updateStatus()
    }

    private fun updateStatus() {
        status.text = when {
            county == null -> "Pick or add a county / city to start."
            site == null -> "$county — pick or add a site."
            dirty -> "$county › $site   (unsaved changes)"
            else -> "$county › $site"
        }
    }

    private fun updateDateBtn() { dateBtn.text = fmtDate(readingDate) }

    // ---------- Dates ----------

    private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun fmtDate(iso: String): String = try {
        SimpleDateFormat("MMM d, yyyy", Locale.US).format(SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!)
    } catch (e: Exception) { iso }

    private fun pickDate() {
        val cal = Calendar.getInstance()
        try { SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(readingDate)?.let { cal.time = it } } catch (e: Exception) {}
        DatePickerDialog(this, { _, y, m, d ->
            readingDate = String.format(Locale.US, "%04d-%02d-%02d", y, m + 1, d)
            updateDateBtn()
        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
    }

    // ---------- Calculations ----------

    private fun fmt(v: Double, places: Int) = String.format(Locale.US, "%.${places}f", v)

    private fun calc(get: (String) -> String?): Calc {
        fun n(k: String) = get(k)?.trim()?.toDoubleOrNull()
        // Chem: mL/min × 60 min/hr × hrs/day = mL/day;  ÷ 3785.41 = gal/day
        val r1 = n("p1Rate") ?: 0.0
        val t1 = n("p1Hrs") ?: 24.0
        val r2 = n("p2Rate") ?: 0.0
        val t2 = n("p2Hrs") ?: 24.0
        val gpd = if (r1 == 0.0 && r2 == 0.0) null else (r1 * 60 * t1 + r2 * 60 * t2) / 3785.41
        // Nutrient pump: max GPH × speed% × stroke% × run hrs/day = gal/day;  tank ÷ gal/day = days until empty
        // Timer: ON seconds then OFF minutes, repeating all day -> run hrs = 24 × ON / (ON + OFF).
        // Old records without a timer use their hrs/day (or 24).
        val gph = n("bioGph")
        val speed = n("speed")
        val stroke = n("stroke")
        val onS = n("onSec")
        val offM = n("offMin")
        val timer = onS != null && offM != null && onS + offM * 60 > 0
        val runHrs = if (timer) 24.0 * onS!! / (onS + offM!! * 60) else (n("bioHrs") ?: 24.0)
        val tank = n("tankGal")
        val bioGpd = if (gph != null && speed != null && stroke != null)
            gph * (speed / 100.0) * (stroke / 100.0) * runHrs else null
        val days = if (tank != null && bioGpd != null && bioGpd > 0) tank / bioGpd else null
        return Calc(gpd, bioGpd, days, runHrs * 60, timer)
    }

    private fun compute() {
        val c = calc { k -> fields[k]?.text?.toString() }
        chemResult.text = if (c.gpd == null) "CHEM FEED GPD: --" else "CHEM FEED GPD: ${fmt(c.gpd, 2)}"
        bioResult.text = if (c.bioGpd == null) {
            "Nutrient feed: enter pump GPH, speed % and stroke %"
        } else {
            var s = "Nutrient feed: ${fmt(c.bioGpd, 2)} gal/day"
            s += if (c.timer) "\nPump runs about ${fmt(c.runMin, 1)} min/day" else "\nNo timer entered, figured as running 24 hrs"
            if (c.days != null) s += "\nTank empty in about ${fmt(c.days, 1)} days"
            s
        }
    }

    // ---------- Data ----------

    private fun saveDb() = prefs.edit().putString("db", db.toString()).apply()

    private fun countyNames(): List<String> =
        db.keys().asSequence().toList().sortedBy { it.lowercase() }

    private fun siteNames(c: String): List<String> =
        db.optJSONObject(c)?.keys()?.asSequence()?.toList()?.sortedBy { it.lowercase() } ?: emptyList()

    private fun siteRec(): JSONObject? {
        val c = county ?: return null
        val s = site ?: return null
        return db.optJSONObject(c)?.optJSONObject(s)
    }

    private fun history(rec: JSONObject?): List<JSONObject> {
        val a = rec?.optJSONArray(HIST) ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun thisMonth(): String = today().substring(0, 7)

    private fun isDone(c: String, s: String): Boolean =
        db.optJSONObject(c)?.optJSONObject(s)?.optString("completeMonth") == thisMonth()

    private fun clearFields() {
        loading = true
        fields.values.forEach { it.setText("") }
        completeChk.isChecked = false
        completeChk.text = "Complete"
        loading = false
        compute()
    }

    private fun fillFrom(rec: JSONObject) {
        loading = true
        fields.forEach { (k, e) -> e.setText(rec.optString(k, "")) }
        val done = rec.optString("completeMonth") == thisMonth()
        completeChk.isChecked = done
        val on = rec.optString("completedOn")
        completeChk.text = if (done && on.isNotEmpty()) "Complete  (done ${fmtDate(on).substringBeforeLast(',')})" else "Complete"
        loading = false
        compute()
    }

    private val green = 0xFF2E7D32.toInt()

    private fun greenLabel(text: String): CharSequence {
        val sp = SpannableString("✓ $text")
        sp.setSpan(ForegroundColorSpan(green), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, sp.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sp
    }

    private fun selectCounty(c: String) {
        county = c; site = null
        clearFields(); dirty = false; refreshUi()
    }

    private fun loadSite(s: String) {
        val c = county ?: return
        site = s
        fillFrom(db.optJSONObject(c)?.optJSONObject(s) ?: JSONObject())
        dirty = false
        refreshUi()
    }

    /** Writes the on-screen values to the current site, keeping its history. */
    private fun writeCurrent(): JSONObject {
        val c = county!!
        val s = site!!
        val old = db.optJSONObject(c)?.optJSONObject(s)
        val rec = JSONObject()
        fields.forEach { (k, e) -> rec.put(k, e.text.toString().trim()) }
        // Store the calculated results too, so they show up in the backup file
        val k = calc { key -> rec.optString(key, "") }
        rec.put("chemGpd", k.gpd?.let { fmt(it, 2) } ?: "")
        rec.put("bioGalPerDay", k.bioGpd?.let { fmt(it, 2) } ?: "")
        rec.put("tankDaysLeft", k.days?.let { fmt(it, 1) } ?: "")
        if (old != null && old.optString("completeMonth").isNotEmpty()) {
            rec.put("completeMonth", old.optString("completeMonth"))
            rec.put("completedOn", old.optString("completedOn"))
        }
        old?.optJSONArray(HIST)?.let { rec.put(HIST, it) }
        val cObj = db.optJSONObject(c) ?: JSONObject().also { db.put(c, it) }
        cObj.put(s, rec)
        return rec
    }

    // ---------- Actions ----------

    private fun pickCounty() = guardUnsaved {
        val names = countyNames()
        val items: Array<CharSequence> = (names.map { c ->
            val all = siteNames(c)
            if (all.isEmpty()) c else "$c   (${all.count { isDone(c, it) }} of ${all.size} done)"
        } + "+ Add new county / city…").toTypedArray<CharSequence>()
        AlertDialog.Builder(this)
            .setTitle("Select county / city")
            .setItems(items) { _, i ->
                if (i == names.size) {
                    askName("New county / city") { n ->
                        if (!db.has(n)) { db.put(n, JSONObject()); saveDb() }
                        selectCounty(n)
                    }
                } else selectCounty(names[i])
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun pickSite() {
        val c = county
        if (c == null) { toast("Pick a county / city first"); return }
        guardUnsaved {
            val names = siteNames(c)
            val items: Array<CharSequence> = (names.map<String, CharSequence> { s -> if (isDone(c, s)) greenLabel(s) else s } +
                    "+ Add new site…").toTypedArray<CharSequence>()
            AlertDialog.Builder(this)
                .setTitle("Sites in $c")
                .setItems(items) { _, i ->
                    if (i == names.size) {
                        askName("New site name") { n ->
                            if (db.optJSONObject(c)?.has(n) == true) loadSite(n)
                            else {
                                site = n; clearFields(); dirty = true; refreshUi()
                                toast("Fill in the fields, then SAVE")
                            }
                        }
                    } else loadSite(names[i])
                }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun save() {
        if (county == null || site == null) { toast("Pick a county and a site first"); return }
        val date = readingDate
        val rec = writeCurrent()
        val month = date.substring(0, 7)
        if (completeChk.isChecked) {
            rec.put("completeMonth", month); rec.put("completedOn", date)
        } else if (rec.optString("completeMonth") == month) {
            rec.remove("completeMonth"); rec.remove("completedOn")
        }
        // Only add a dated reading when some numbers or notes are filled in; a blank save just marks Complete.
        if (fields.keys.any { rec.optString(it, "").isNotEmpty() }) {
            val entry = JSONObject()
            entry.put("date", date)
            entry.put("complete", completeChk.isChecked)
            entry.put("savedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()))
            (fields.keys + listOf("chemGpd", "bioGalPerDay", "tankDaysLeft"))
                .forEach { k -> entry.put(k, rec.optString(k, "")) }
            val list = history(rec).filter { it.optString("date") != date }.toMutableList()
            list.add(entry)
            list.sortBy { it.optString("date") }
            rec.put(HIST, JSONArray(list))
        }
        saveDb()
        loading = true
        val done = rec.optString("completeMonth") == thisMonth()
        completeChk.isChecked = done
        completeChk.text = if (done) "Complete  (done ${fmtDate(rec.optString("completedOn")).substringBeforeLast(',')})" else "Complete"
        loading = false
        dirty = false; refreshUi()
        toast("Saved $site (${fmtDate(date)})" + if (done) " · Complete" else "")
    }

    private fun showHistory() {
        val h = history(siteRec()).reversed()
        if (h.isEmpty()) { toast("No readings yet"); return }
        val items = h.map { e ->
            val c = calc { k -> e.optString(k, "") }
            "${fmtDate(e.optString("date"))}  —  " + (if (c.gpd == null) "GPD --" else "${fmt(c.gpd, 2)} GPD")
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("$site — history")
            .setItems(items) { _, i -> showReading(h[i]) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showReading(e: JSONObject) {
        val c = calc { k -> e.optString(k, "") }
        val sb = StringBuilder()
        allDefs.forEach { f -> sb.append(f.label).append(":  ").append(e.optString(f.key, "").ifEmpty { "—" }).append('\n') }
        sb.append('\n')
        sb.append("CHEM FEED GPD:  ").append(c.gpd?.let { fmt(it, 2) } ?: "—").append('\n')
        sb.append("Nutrient feed gal/day:  ").append(c.bioGpd?.let { fmt(it, 2) } ?: "—").append('\n')
        sb.append("Tank empty in (days):  ").append(c.days?.let { fmt(it, 1) } ?: "—")
        val date = e.optString("date")
        AlertDialog.Builder(this)
            .setTitle("$site — ${fmtDate(date)}")
            .setMessage(sb.toString())
            .setPositiveButton("Close", null)
            .setNeutralButton("Copy to form") { _, _ ->
                guardUnsaved {
                    fillFrom(e)
                    dirty = true; updateStatus()
                    toast("Loaded — edit, then SAVE")
                }
            }
            .setNegativeButton("Delete") { _, _ ->
                confirm("Delete the ${fmtDate(date)} reading?", "Delete") {
                    val rec = siteRec() ?: return@confirm
                    val left = history(rec).filter { it.optString("date") != date }
                    if (left.isEmpty()) rec.remove(HIST) else rec.put(HIST, JSONArray(left))
                    saveDb(); refreshUi()
                    toast("Reading deleted")
                }
            }
            .show()
    }

    private fun cancel() {
        if (!dirty) { toast("No changes to cancel"); return }
        val s = site
        if (s != null && siteRec() != null) {
            loadSite(s)   // put back the last saved values
        } else {
            site = null; clearFields(); dirty = false; refreshUi()
        }
        toast("Changes discarded")
    }

    private fun delete() {
        val c = county
        if (c == null) { toast("Nothing selected"); return }
        val s = site
        if (s != null) {
            confirm("Delete site \"$s\" from $c, including its history?", "Delete") {
                db.optJSONObject(c)?.remove(s); saveDb()
                site = null; clearFields(); dirty = false; refreshUi()
                toast("Deleted $s")
            }
        } else {
            val n = siteNames(c).size
            confirm("Delete \"$c\" and its $n site(s)?", "Delete") {
                db.remove(c); saveDb()
                county = null; clearFields(); dirty = false; refreshUi()
                toast("Deleted $c")
            }
        }
    }

    // ---------- Backup / CSV ----------

    private fun exportTo(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(db.toString(2).toByteArray()) }
            val n = countyNames().sumOf { siteNames(it).size }
            toast("Exported $n site(s)")
        } catch (e: Exception) {
            toast("Export failed: ${e.message}")
        }
    }

    /** History: same date -> imported reading wins; different dates -> both kept.
     *  Current numbers come from the newest dated reading; with no history, imported numbers win. */
    private fun mergeSite(local: JSONObject?, inc: JSONObject): JSONObject {
        if (local == null) return inc
        val byDate = linkedMapOf<String, JSONObject>()
        history(local).forEach { e -> e.optString("date").takeIf { it.isNotEmpty() }?.let { byDate[it] = e } }
        history(inc).forEach { e -> e.optString("date").takeIf { it.isNotEmpty() }?.let { byDate[it] = e } }
        val h = byDate.values.sortedBy { it.optString("date") }
        val out = JSONObject()
        if (h.isNotEmpty()) {
            val latest = h.last()
            latest.keys().forEach { k -> if (k != "date" && k != "savedAt") out.put(k, latest.opt(k)) }
            out.put(HIST, JSONArray(h))
        } else {
            local.keys().forEach { k -> out.put(k, local.opt(k)) }
            inc.keys().forEach { k -> out.put(k, inc.opt(k)) }
        }
        // Keep the most recent "Complete" mark from either phone
        out.remove("completeMonth"); out.remove("completedOn")
        listOf(local, inc).filter { it.optString("completeMonth").isNotEmpty() }
            .maxByOrNull { it.optString("completedOn").ifEmpty { it.optString("completeMonth") } }
            ?.let { m ->
                out.put("completeMonth", m.optString("completeMonth"))
                m.optString("completedOn").takeIf { it.isNotEmpty() }?.let { out.put("completedOn", it) }
            }
        return out
    }

    /** Site list: plain text or CSV. Lines can be "Apopka, LS 12", a heading "Apopka:",
     *  or just a site name (goes under the last heading, or the county on screen). */
    private fun importSiteList(text: String) {
        val list = mutableListOf<Pair<String, String>>()
        var cur: String? = null
        var skipped = 0
        val header = Regex("^\"?(county|city|county\\s*/\\s*city)\"?\\s*[,\t]\\s*\"?site\"?$", RegexOption.IGNORE_CASE)
        fun clean(v: String) = v.trim().removeSurrounding("\"").trim()
        for (raw in text.removePrefix("\uFEFF").lines()) {
            val line = raw.trim()
            if (line.isEmpty() || header.matches(line)) continue
            if (line.endsWith(":")) { cur = line.dropLast(1).trim(); continue }
            val parts = line.split(',', '\t')
            val c: String?
            val s: String
            if (parts.size >= 2) { c = clean(parts[0]); s = clean(parts[1]) } else { c = cur ?: county; s = clean(line) }
            if (c.isNullOrEmpty() || s.isEmpty()) { skipped++; continue }
            list.add(c to s)
        }
        if (list.isEmpty()) {
            toast(if (county != null) "No site names found in that file" else "No sites found. Pick a county first, or use 'County, Site' lines")
            return
        }
        val fresh = list.distinct().filter { (c, s) -> db.optJSONObject(c)?.has(s) != true }
        val msg = if (fresh.isEmpty()) "All of these sites are already on this phone. Nothing to add."
        else "Adds ${fresh.size} new site(s): " +
                fresh.groupingBy { it.first }.eachCount().entries.joinToString(", ") { "${it.key} (${it.value})" } +
                ". ${list.distinct().size - fresh.size} already on this phone and left as they are." +
                (if (skipped > 0) " $skipped line(s) skipped." else "")
        val b = AlertDialog.Builder(this).setTitle("Import site list?").setMessage(msg)
        if (fresh.isEmpty()) b.setPositiveButton("OK", null)
        else b.setPositiveButton("Add sites") { _, _ ->
            fresh.forEach { (c, s) -> (db.optJSONObject(c) ?: JSONObject().also { db.put(c, it) }).put(s, JSONObject()) }
            saveDb(); refreshUi()
            toast("Added ${fresh.size} site(s)")
        }.setNegativeButton("Cancel", null)
        b.show()
    }

    private fun importFrom(uri: Uri) {
        val text = try {
            contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        } catch (e: Exception) {
            toast("Couldn't open that file")
            return
        }
        val incoming = try { JSONObject(text) } catch (e: Exception) {
            importSiteList(text)
            return
        }
        var sites = 0
        incoming.keys().forEach { c -> incoming.optJSONObject(c)?.let { sites += it.length() } }
        if (sites == 0) { toast("No sites found in that file"); return }

        AlertDialog.Builder(this)
            .setTitle("Import $sites site(s)?")
            .setMessage("Readings are merged by date. A reading on the same site and date is replaced by the imported one; readings on other dates are kept.")
            .setPositiveButton("Import") { _, _ ->
                incoming.keys().forEach { c ->
                    val src = incoming.optJSONObject(c) ?: return@forEach
                    val dst = db.optJSONObject(c) ?: JSONObject().also { db.put(c, it) }
                    src.keys().forEach { s ->
                        src.optJSONObject(s)?.let { inc -> dst.put(s, mergeSite(dst.optJSONObject(s), inc)) }
                    }
                }
                saveDb()
                val s = site
                if (s != null && siteRec() != null) loadSite(s) else refreshUi()
                toast("Imported $sites site(s)")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportCsv(uri: Uri) {
        fun esc(v: String): String =
            if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"" + v.replace("\"", "\"\"") + "\"" else v
        val rows = mutableListOf<List<String>>()
        rows.add(listOf("County/City", "Site", "Reading date") + allDefs.map { it.label } +
                listOf("Chem feed GPD", "Nutrient feed gal/day", "Tank empty in days", "Complete"))
        for (c in countyNames()) for (s in siteNames(c)) {
            val rec = db.optJSONObject(c)?.optJSONObject(s) ?: continue
            val h = history(rec)
            if (h.isEmpty() && allDefs.none { rec.optString(it.key, "").isNotEmpty() }) continue
            val entries = if (h.isEmpty()) listOf(rec) else h
            for (e in entries) {
                val k = calc { key -> e.optString(key, "") }
                val date = if (h.isEmpty()) "(current)" else e.optString("date")
                rows.add(listOf(c, s, date) + allDefs.map { e.optString(it.key, "") } + listOf(
                    k.gpd?.let { fmt(it, 2) } ?: "",
                    k.bioGpd?.let { fmt(it, 2) } ?: "",
                    k.days?.let { fmt(it, 1) } ?: "",
                    if (e.optBoolean("complete", false)) "Yes" else ""))
            }
        }
        try {
            val text = "﻿" + rows.joinToString("\r\n") { r -> r.joinToString(",") { esc(it) } }
            contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            toast("Exported ${rows.size - 1} row(s)")
        } catch (e: Exception) {
            toast("CSV export failed: ${e.message}")
        }
    }

    // ---------- Helpers ----------

    private fun guardUnsaved(action: () -> Unit) {
        if (!dirty) { action(); return }
        AlertDialog.Builder(this)
            .setTitle("Unsaved changes")
            .setMessage("Discard your changes?")
            .setPositiveButton("Discard") { _, _ -> dirty = false; action() }
            .setNegativeButton("Keep editing", null)
            .show()
    }

    private fun askName(title: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirm(msg: String, okText: String, onYes: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(msg)
            .setPositiveButton(okText) { _, _ -> onYes() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

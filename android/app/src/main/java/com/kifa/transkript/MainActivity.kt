package com.kifa.transkript

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min
import kotlin.math.sqrt

class MainActivity : AppCompatActivity(), Controller.Ui {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var parasView: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var btnRecord: MaterialButton
    private lateinit var timer: TextView
    private lateinit var levelBar: ProgressBar
    private lateinit var statusView: TextView
    private lateinit var interimView: TextView
    private lateinit var meta: TextView
    private lateinit var empty: TextView
    private lateinit var modelInfo: TextView
    private lateinit var modelProgress: ProgressBar
    private lateinit var btnDownload: MaterialButton
    private lateinit var titleView: EditText
    private var downloading = false

    private val c = Controller
    private lateinit var sheetView: View
    private var sheet: BottomSheetDialog? = null
    private fun <T : View> sv(id: Int): T = sheetView.findViewById(id)
    private val engines = listOf(
        "nemo" to "Anlık · NVIDIA Nemotron (cihazda, internetsiz)",
        "local" to "Bu cihazda · Whisper (internetsiz)",
        "cloud" to "Bulut · Cloudflare (en doğru)",
    )
    private val trDirs = listOf("en-tr" to "İngilizce → Türkçe", "tr-en" to "Türkçe → İngilizce")
    private lateinit var swTranslate: com.google.android.material.materialswitch.MaterialSwitch
    private val langs = listOf("tr" to "Türkçe", "en" to "İngilizce", "auto" to "Otomatik (dil algılama)")
    private val spks = listOf(0 to "Otomatik", 1 to "Tek konuşmacı (ayrım yok)", 2 to "2", 3 to "3", 4 to "4", 5 to "5", 6 to "6")
    private val senses = listOf("tol" to "Toleranslı (aynı kişiyi bölmez)", "norm" to "Normal", "sens" to "Hassas (benzer sesleri ayırır)")

    private val saveTxt = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { it.write(Transcript.plainText().toByteArray(Charsets.UTF_8)) }
            toast("TXT kaydedildi")
        }
    }
    private val savePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        if (uri != null) {
            contentResolver.openOutputStream(uri)?.use { writePdf(it) }
            toast("PDF kaydedildi")
        }
    }
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) startRecording() else toast("Kayıt için mikrofon izni gerekli")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        // üst bant her temada koyu: durum çubuğu simgeleri açık renk
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = !night
        c.init(this)
        sheetView = layoutInflater.inflate(R.layout.sheet_settings, null)

        parasView = findViewById(R.id.paras); scroll = findViewById(R.id.scroll)
        btnRecord = findViewById(R.id.btnRecord); timer = findViewById(R.id.timer); levelBar = findViewById(R.id.level)
        statusView = findViewById(R.id.status); interimView = findViewById(R.id.interim); meta = findViewById(R.id.meta)
        empty = findViewById(R.id.empty); modelInfo = sv(R.id.modelInfo)
        modelProgress = sv(R.id.modelProgress); btnDownload = sv(R.id.btnDownload)
        titleView = findViewById(R.id.title)
        swTranslate = findViewById(R.id.swTranslate)
        swTranslate.isChecked = c.trOn
        updateTranslateLabel()
        swTranslate.setOnCheckedChangeListener { _, on ->
            if (on == c.trOn) return@setOnCheckedChangeListener
            c.prefs.edit().putBoolean("trOn", on).apply()
            updateEngineLabel()
            if (on) ensureTranslator(null)
        }
        if (c.trOn) ensureTranslator(null)
        setupDropdown(sv(R.id.spTrDir), trDirs.map { it.second }, trDirs.indexOfFirst { it.first == c.trDir }) {
            c.prefs.edit().putString("trDir", trDirs[it].first).apply()
            updateTranslateLabel(); updateEngineLabel()
            if (c.trOn) ensureTranslator(null)
        }

        setupDropdown(sv(R.id.spEngine), engines.map { it.second }, engines.indexOfFirst { it.first == (if (c.cloud) "cloud" else if (c.nemo) "nemo" else "local") }) {
            c.prefs.edit().putString("engine", engines[it].first).apply(); applyEngineUi()
        }
        bindPref(sv(R.id.cloudUrl), "cloudUrl")
        bindPref(sv(R.id.cloudCode), "cloudCode")
        sv<View>(R.id.btnCloudTest).setOnClickListener { testCloud() }
        sv<android.widget.CheckBox>(R.id.showCode).setOnCheckedChangeListener { _, on ->
            val et = sv<EditText>(R.id.cloudCode)
            et.inputType = InputType.TYPE_CLASS_TEXT or (if (on) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
            et.setSelection(et.text.length)
        }
        setupDropdown(sv(R.id.spLang), langs.map { it.second }, langs.indexOfFirst { it.first == c.lang }) {
            c.prefs.edit().putString("lang", langs[it].first).apply()
        }
        setupDropdown(sv(R.id.spModel), Models.list.map { it.label }, Models.list.indexOfFirst { it.key == c.model }) {
            c.prefs.edit().putString("model", Models.list[it].key).apply(); applyEngineUi()
        }
        setupDropdown(sv(R.id.spSpk), spks.map { it.second }, spks.indexOfFirst { it.first == c.maxSpk }) {
            c.prefs.edit().putInt("maxSpk", spks[it].first).apply(); afterSpeakerSetting()
        }
        setupDropdown(sv(R.id.spSens), senses.map { it.second }, senses.indexOfFirst { it.first == c.sens }) {
            c.prefs.edit().putString("sens", senses[it].first).apply(); afterSpeakerSetting()
        }
        findViewById<View>(R.id.btnSettings).setOnClickListener { openSettings() }
        findViewById<View>(R.id.engineLabel).setOnClickListener { openSettings() }

        btnDownload.setOnClickListener { downloadModel(false) }
        btnRecord.setOnClickListener { if (Engine.recording) Engine.stop() else requestAndStart() }
        findViewById<View>(R.id.btnTxt).setOnClickListener { saveTxt.launch(fileBase() + ".txt") }
        findViewById<View>(R.id.btnPdf).setOnClickListener { savePdf.launch(fileBase() + ".pdf") }
        findViewById<View>(R.id.btnShare).setOnClickListener { share() }
        findViewById<View>(R.id.btnMore).setOnClickListener { moreMenu(it) }

        titleView.setText(Transcript.title)
        titleView.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (Transcript.title != s.toString()) { Transcript.title = s.toString(); c.persist() }
            }
        })
        rerender()
        applyEngineUi()
    }

    // ---------- ayarlar menüsü (alttan açılır) ----------
    private fun openSettings() {
        val d = sheet ?: BottomSheetDialog(this).also { sheet = it }
        (sheetView.parent as? ViewGroup)?.removeView(sheetView)
        d.setContentView(sheetView)
        d.behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        d.behavior.skipCollapsed = true
        applyEngineUi()
        d.show()
    }

    // ---------- "Diğer" menüsü ----------
    private fun moreMenu(anchor: View) {
        val m = android.widget.PopupMenu(this, anchor)
        m.menu.add(0, 1, 0, "Kopyala")
        if (c.diarOn) m.menu.add(0, 2, 1, "Konuşmacıları düzenle").isEnabled = !(Engine.recording || Engine.working)
        m.menu.add(0, 3, 2, "Temizle")
        m.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("eFAM Transkript", Transcript.plainText()))
                    toast("Panoya kopyalandı")
                }
                2 -> {
                    val go = {
                        Transcript.manual = false
                        toast(if (c.regroup()) "Konuşmacılar yeniden düzenlendi" else "Değişiklik gerekmedi")
                    }
                    if (Transcript.manual) confirm("Elle yaptığınız konuşmacı değişiklikleri ve verdiğiniz adlar sıfırlanacak. Devam edilsin mi?", go) else go()
                }
                3 -> confirm("Tüm metin silinsin mi?") { c.clear(); titleView.setText("") }
            }
            true
        }
        m.show()
    }

    // ---------- bulut motoru ----------
    private fun bindPref(et: EditText, key: String) {
        et.setText(c.prefs.getString(key, ""))
        et.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: Editable?) { c.prefs.edit().putString(key, s.toString().trim()).apply() }
        })
    }

    private fun applyEngineUi() {
        val cloud = c.cloud
        sv<View>(R.id.cloudBox).visibility = if (cloud) View.VISIBLE else View.GONE
        sv<View>(R.id.localBox).visibility = if (cloud || c.nemo) View.GONE else View.VISIBLE
        updateEngineLabel()
        if (cloud) {
            modelInfo.text = "Bulut motorunda ses parçaları sizin Cloudflare sunucunuza gider; Whisper large-v3-turbo ile çevrilir. Konuşma algılama ve konuşmacı ayrımı telefonda yapılır."
            modelProgress.visibility = View.GONE; btnDownload.visibility = View.GONE
        } else showModel()
    }

    private fun testCloud() {
        val info = sv<TextView>(R.id.cloudInfo)
        val url = c.cloudUrl; val code = c.cloudCode
        if (url.isBlank() || code.isBlank()) { info.text = "Sunucu adresini ve erişim kodunu girin."; return }
        info.text = "Deneniyor…"
        Thread {
            val msg = try { "✓ " + Cloud.check(url, code) } catch (e: Exception) { "✗ " + (e.message ?: "Bağlantı kurulamadı") }
            handler.post { info.text = msg }
        }.start()
    }

    override fun onStart() {
        super.onStart()
        c.ui = this
        rerender()
        status(c.lastStatus, c.lastStatusError)
        stateChanged()
    }

    override fun onStop() {
        if (c.ui === this) c.ui = null
        handler.removeCallbacks(tick)
        c.saveNow()
        super.onStop()
    }

    // ---------- ayarlar ----------
    /** Modern açılır kutu (Material "exposed dropdown"): yalnızca seçim gerçekten değişince haber verir. */
    private fun setupDropdown(v: MaterialAutoCompleteTextView, items: List<String>, sel: Int, onSel: (Int) -> Unit) {
        v.setSimpleItems(items.toTypedArray())
        var current = maxOf(0, sel)
        v.setText(items[current], false)
        v.setOnItemClickListener { _, _, pos, _ ->
            if (pos != current) { current = pos; onSel(pos) }
        }
    }

    // ---------- canlı çeviri ----------
    private fun updateTranslateLabel() { swTranslate.text = "Canlı çeviri · " + Translate.label(c.trDir) }

    /** Çeviri modelini hazırla (ilk seferde indirir); hazır olunca [then]. */
    private fun ensureTranslator(then: (() -> Unit)?) {
        if (Translate.isReady(c.trDir) && then == null) return
        status("Çeviri modeli hazırlanıyor (ilk seferde ~30 MB indirilir)…", false)
        swTranslate.isEnabled = false
        Translate.ensure(c.trDir) { err ->
            swTranslate.isEnabled = !Engine.recording
            if (err != null) {
                status("Çeviri hazırlanamadı: $err", true)
                c.prefs.edit().putBoolean("trOn", false).apply(); swTranslate.isChecked = false; updateEngineLabel()
            } else {
                status("Canlı çeviri hazır · " + Translate.label(c.trDir), false)
                then?.invoke()
            }
        }
    }

    /** Banttaki küçük etiket: hangi motor seçili, model hazır mı */
    private fun updateEngineLabel() {
        val name = when { c.cloud -> "Bulut · Cloudflare"; c.nemo -> "Anlık · NVIDIA Nemotron"; else -> "Cihazda · Whisper" }
        val ready = c.cloud || Models.isReady(this, c.modelKey)
        val tr = if (c.trOn) "  · " + (if (c.trDir == "tr-en") "TR→EN" else "EN→TR") else ""
        findViewById<TextView>(R.id.engineLabel).text = name + (if (ready) "  ✓" else "  · model indirilmedi") + tr + "  ▾"
    }

    private fun afterSpeakerSetting() {
        if (c.settingsChanged()) toast("Konuşmacılar yeni ayara göre düzenlendi") else rerender()
    }

    private fun lockSettings(on: Boolean) {
        listOf(R.id.spEngineBox, R.id.cloudUrl, R.id.cloudCode, R.id.btnCloudTest, R.id.spLangBox, R.id.spModelBox, R.id.spSpkBox, R.id.spSensBox, R.id.spTrDirBox)
            .forEach { sv<View>(it).isEnabled = !on }
        swTranslate.isEnabled = !on
        btnDownload.isEnabled = !on && !downloading
    }

    // ---------- model ----------
    private fun showModel() {
        if (downloading || c.cloud) return
        val ready = Models.isReady(this, c.modelKey)
        modelProgress.visibility = View.GONE
        btnDownload.visibility = if (ready) View.GONE else View.VISIBLE
        modelInfo.text = if (ready) "✓ Model hazır (%d MB)".format(Models.sizeOnDisk(this, c.modelKey) / 1048576) +
            (if (c.nemo) " · NVIDIA Nemotron: metin konuşurken yazılır" else "")
        else "Seçili model henüz indirilmedi. Toplantıdan önce Wi-Fi’da indirin."
    }

    private fun downloadModel(thenStart: Boolean) {
        if (downloading) return
        downloading = true
        val key = c.modelKey
        btnDownload.isEnabled = false
        modelProgress.visibility = View.VISIBLE; modelProgress.progress = 0
        modelInfo.text = "İndirme başlıyor…"
        val appCtx = applicationContext
        Thread {
            try {
                Models.download(appCtx, key, { done, total ->
                    handler.post {
                        modelProgress.progress = if (total > 0) (done * 1000 / total).toInt() else 0
                        modelInfo.text = "Model indiriliyor: %d / %d MB".format(done / 1048576, total / 1048576)
                    }
                }, { false })
                handler.post { downloading = false; btnDownload.isEnabled = true; applyEngineUi(); sheet?.dismiss(); if (thenStart) requestAndStart() }
            } catch (e: Exception) {
                handler.post {
                    downloading = false; btnDownload.isEnabled = true; modelProgress.visibility = View.GONE
                    modelInfo.text = "İndirme başarısız: ${e.message}. İnternet bağlantısını kontrol edip tekrar deneyin."
                }
            }
        }.start()
    }

    // ---------- kayıt ----------
    private fun requestAndStart() {
        if (c.trOn && !Translate.isReady(c.trDir)) { ensureTranslator { requestAndStart() }; return }
        if (c.cloud && (c.cloudUrl.isBlank() || c.cloudCode.isBlank())) {
            toast("Bulut motoru için ayarlardan sunucu adresini ve erişim kodunu girin")
            return
        }
        if (!c.cloud && !Models.isReady(this, c.modelKey)) {
            confirm("Seçili model henüz indirilmedi. Şimdi indirilsin mi? (Wi-Fi önerilir)") { downloadModel(true) }
            return
        }
        val need = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) need += Manifest.permission.POST_NOTIFICATIONS
        val missing = need.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) startRecording() else askPerms.launch(missing.toTypedArray())
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        if (c.start(this)) RecordService.start(this)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!Engine.recording) return
            val s = (SystemClock.elapsedRealtime() - Engine.startedAt) / 1000
            timer.text = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
            handler.postDelayed(this, 500)
        }
    }

    // ---------- Controller.Ui ----------
    override fun stateChanged() {
        val rec = Engine.recording
        btnRecord.text = if (rec) "Durdur" else "Başlat"
        btnRecord.setIconResource(if (rec) R.drawable.ic_stop else R.drawable.ic_mic)
        btnRecord.setBackgroundColor(ContextCompat.getColor(this, if (rec) R.color.rec else R.color.accent))
        lockSettings(rec || Engine.working)
        handler.removeCallbacks(tick)
        if (rec) handler.post(tick)
    }

    override fun level(level: Float) { levelBar.progress = min(100, (sqrt(level) * 260).toInt()) }

    override fun status(text: String, error: Boolean) {
        statusView.text = text
        statusView.setTextColor(ContextCompat.getColor(this, if (error) R.color.rec else R.color.muted))
    }

    override fun interim(text: String?) {
        interimView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        interimView.text = text ?: ""
        if (!text.isNullOrEmpty()) scrollToEndIfNear()
    }

    override fun message(text: String) = toast(text)

    override fun appended(p: Para, newPara: Boolean) {
        if (newPara) {
            // yeni konuşmacı adı çıktıysa ya da ilk paragrafsa baştan çiz
            if (parasView.childCount != Transcript.paras.size - 1) { rerender(); return }
            parasView.addView(paraView(p))
        } else {
            (parasView.getChildAt(parasView.childCount - 1)?.tag as? TextView)?.text = p.text
        }
        updateMeta()
        scrollToEndIfNear()
    }

    override fun rerender() {
        parasView.removeAllViews()
        Transcript.paras.forEach { parasView.addView(paraView(it)) }
        updateMeta()
    }

    // ---------- metin görünümü ----------
    private fun scrollToEndIfNear() {
        val child = scroll.getChildAt(0) ?: return
        if (child.bottom - (scroll.scrollY + scroll.height) < dp(400)) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun paraView(p: Para): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
        if (p.spk > 0 && c.diarOn) {
            val col = speakerColor(p.spk)
            val who = TextView(this)
            who.text = Transcript.name(p.spk) + "  ▾"
            who.setTextColor(col); who.textSize = 12.5f; who.setTypeface(who.typeface, Typeface.BOLD)
            who.setPadding(dp(10), dp(3), dp(10), dp(3))
            who.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor((col and 0x00FFFFFF) or 0x22000000)   // konuşmacı renginin açık tonu
            }
            who.setOnClickListener { speakerMenu(p) }
            box.addView(who, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        }
        val tx = TextView(this)
        tx.text = p.text; tx.textSize = 16f; tx.setLineSpacing(0f, 1.3f)
        tx.setTextColor(ContextCompat.getColor(this, R.color.ink))
        tx.setOnClickListener { editPara(p) }
        box.addView(tx)
        val trv = TextView(this)
        trv.textSize = 15f; trv.setLineSpacing(0f, 1.25f)
        trv.setTextColor(ContextCompat.getColor(this, R.color.accent))
        trv.setPadding(dp(10), dp(4), 0, dp(2))
        trv.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@MainActivity, R.color.accent_soft)); cornerRadius = dp(8).toFloat()
        }
        setTranslation(trv, p)
        box.addView(trv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
        tx.tag = trv
        box.tag = tx
        return box
    }

    private fun setTranslation(trv: TextView, p: Para) {
        val t = p.translation()
        trv.text = t
        trv.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun translated(p: Para) {
        val i = Transcript.paras.indexOf(p)
        val tx = (if (i >= 0) parasView.getChildAt(i)?.tag else null) as? TextView
        val trv = tx?.tag as? TextView
        if (trv != null) setTranslation(trv, p) else rerender()
        scrollToEndIfNear()
    }

    private fun updateMeta() {
        val has = Transcript.paras.isNotEmpty()
        empty.visibility = if (has) View.GONE else View.VISIBLE
        val n = Transcript.speakerCount()
        meta.text = if (has) "${Transcript.words()} kelime" + (if (n > 0 && c.diarOn) " · $n konuşmacı" else "") + " · düzeltmek için metne dokunun" else ""
        listOf(R.id.btnTxt, R.id.btnPdf, R.id.btnShare, R.id.btnMore).forEach { findViewById<View>(it).isEnabled = has }
    }

    private fun editPara(p: Para) {
        val et = EditText(this)
        et.setText(p.text)
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        et.minLines = 3
        val wrap = LinearLayout(this)
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(this).setTitle("Metni düzelt").setView(wrap)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isEmpty()) Transcript.paras.remove(p) else if (v != p.text) { p.text = v; p.edited = true; c.retranslate(p) }
                rerender(); c.persist()
            }
            .setNeutralButton("Sil") { _, _ -> Transcript.paras.remove(p); rerender(); c.persist() }
            .setNegativeButton("Vazgeç", null).show()
    }

    private fun speakerMenu(p: Para) {
        val ids = (Transcript.names.keys + Transcript.paras.map { it.spk }).filter { it > 0 }.toSortedSet().toList()
        val others = ids.filter { it != p.spk }
        val items = others.map { "Bu paragraf: ${Transcript.name(it)}" } +
            listOf("Bu paragraf: yeni konuşmacı", "Yeniden adlandır…") +
            others.map { "Tümünü “${Transcript.name(it)}” ile birleştir" }
        MaterialAlertDialogBuilder(this).setTitle(Transcript.name(p.spk))
            .setItems(items.toTypedArray()) { _, i ->
                Transcript.manual = true
                if (i == others.size + 1) { rename(p.spk); return@setItems }
                when {
                    i < others.size -> p.spk = others[i]
                    i == others.size -> {
                        p.spk = maxOf(c.speakers.nextId(), (ids.maxOrNull() ?: 0) + 1)
                        Transcript.names[p.spk] = Transcript.name(p.spk)
                    }
                    else -> {
                        val to = others[i - others.size - 2]
                        val from = p.spk
                        Transcript.paras.forEach { if (it.spk == from) it.spk = to }
                        Transcript.names.remove(from)
                    }
                }
                Transcript.joinNeighbours(); c.rebuildCentroids(); rerender(); c.persist()
            }.show()
    }

    private fun rename(id: Int) {
        val et = EditText(this)
        et.setText(Transcript.name(id)); et.selectAll()
        val wrap = LinearLayout(this)
        wrap.setPadding(dp(20), dp(8), dp(20), 0)
        wrap.addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        MaterialAlertDialogBuilder(this).setTitle("Konuşmacının adı").setView(wrap)
            .setPositiveButton("Kaydet") { _, _ ->
                val v = et.text.toString().trim()
                if (v.isNotEmpty()) { Transcript.names[id] = v; rerender(); c.persist() }
            }
            .setNegativeButton("Vazgeç", null).show()
    }

    // ---------- dışa aktarma ----------
    private fun fileBase(): String {
        val t = Transcript.title.trim().replace(Regex("[\\\\/:*?\"<>|]+"), "").replace(Regex("\\s+"), "-").take(60).ifBlank { "transkript" }
        return t + "_" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }

    private fun share() {
        val dir = File(cacheDir, "exports")
        dir.mkdirs()
        val f = File(dir, fileBase() + ".txt")
        f.writeText(Transcript.plainText())
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        val send = Intent(Intent.ACTION_SEND)
        send.type = "text/plain"
        send.putExtra(Intent.EXTRA_STREAM, uri)
        send.putExtra(Intent.EXTRA_TEXT, Transcript.plainText())
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, "Paylaş"))
    }

    // ---------- yardımcılar ----------
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun confirm(msg: String, ok: () -> Unit) {
        MaterialAlertDialogBuilder(this).setMessage(msg).setPositiveButton("Evet") { _, _ -> ok() }.setNegativeButton("Vazgeç", null).show()
    }
}

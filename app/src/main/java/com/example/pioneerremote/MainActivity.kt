// ============================================================================
// Pioneer VSX-S510 Remote — verzia 14
// - Automatické pripojenie na poslednú uloženú IP adresu pri štarte; ak neodpovedá,
//   automaticky prehľadá sieť, pripojí nájdený receiver a uloží novú IP adresu
// - Hlavný displej (horná karta):
//     * Hore výrazným písmom názov rádiovej stanice (alebo aktívny vstup/displej)
//     * Pod ním menším písmom názov relácie/skladby a bitrate
//     * Pod tým zvolený vstup a priestorový režim
//     * Stav pripojenia (Pripojené + IP adresa / Odpojené + chybová hláška)
//     * V dolnej časti displeja zelený posuvník hlasitosti + hodnota hlasitosti
// - Odstránené tlačidlá HDMI 1 až HDMI 6 zo zoznamu vstupov
// - Opravené príkazy pre priestorový zvuk (Surround) na VSX-S510 (0001SR, 0005SR,
//   0006SR, 0009SR, 0010SR, 0151SR, 0013SR, 0014SR + dotaz ?S / ?L)
// - Karta RÁDIO (Preferovaná 2. možnosť — výsuvný zoznam predvolieb):
//     * Tlačidlo "⭐ FAVORITES (Zoznam)" a vedľa neho "⏹ ZASTAVIŤ"
//     * Stlačenie FAVORITES zastaví prípadné prehrávanie (20NW + 14NW), načíta
//       čerstvý zoznam z receivera a vysunie tabuľku s klikateľnými predvoľbami
//     * Po kliknutí na predvoľbu sa stanica spustí (xxGHP) a zoznam sa zasunie (skryje)
//     * Opätovné stlačenie FAVORITES preruší hrajúci stream a znova vysunie zoznam
// ============================================================================

package com.example.pioneerremote

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class MainActivity : AppCompatActivity() {

    // ===== konfiguracia =====
    private var host: String = "192.168.1.80"
    private val port: Int = 8102
    private val volMax = 160

    // ===== farby (tmavy dizajn) =====
    private val C_BG = 0xFF0B1220.toInt()
    private val C_CARD = 0xFF1E293B.toInt()
    private val C_CARD2 = 0xFF16202F.toInt()
    private val C_BTN = 0xFF1E293B.toInt()
    private val C_STROKE = 0xFF334155.toInt()
    private val C_CYAN = 0xFF22D3EE.toInt()
    private val C_CYAN_D = 0xFF155E75.toInt()
    private val C_ORANGE = 0xFFF97316.toInt()
    private val C_RED = 0xFFDC2626.toInt()
    private val C_GREEN = 0xFF34D399.toInt()
    private val C_WHITE = 0xFFF8FAFC.toInt()
    private val C_SLATE = 0xFF94A3B8.toInt()
    private val C_DIM = 0xFF64748B.toInt()
    private val C_ON_ACCENT = 0xFF06121F.toInt()

    // ===== stav =====
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketRef = AtomicReference<Socket?>(null)
    private var outWriter: OutputStreamWriter? = null
    private var currentVol = 0
    private var isMuted = false
    private var isOn = false
    private var activeInput: String? = null
    private var activeMode: String? = null
    private var activeFavIndex: Int = -1
    private var activeStationName: String = ""
    private var seekJob: Job? = null
    private var favJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ===== Hlavný displej a ovládacie prvky =====
    private lateinit var ipEdit: EditText
    private lateinit var mainStationBig: TextView
    private lateinit var mainShowBitrate: TextView
    private lateinit var infoLine: TextView
    private lateinit var connLine: TextView
    private lateinit var statusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var volSlider: SeekBar
    private lateinit var powerBtn: Button
    private lateinit var muteBtn: Button

    private val inputButtons = mutableMapOf<String, Button>()
    private val modeButtons = mutableMapOf<String, Button>()
    private val favButtons = mutableMapOf<Int, Button>()

    // Vstupy bez HDMI 1..6 (SAT/CBL = 06FN, TV = 05FN)
    private val inputs = linkedMapOf(
        "BD" to "25FN",
        "CD" to "01FN",
        "DVR/BDR" to "15FN",
        "DVD" to "04FN",
        "IPOD/USB" to "17FN",
        "SAT/CBL" to "06FN",
        "TV" to "05FN",
        "TUNER" to "02FN",
        "VIDEO" to "10FN",
        "MEDIA SRV" to "44FN",
        "NETRADIO" to "38FN",
        "FAVORITES" to "45FN"
    )

    // Opravené príkazy pre priestorový zvuk na Pioneer VSX-S510:
    // 0001SR = STEREO, 0005SR = AUTO SURR/STREAM DIRECT, 0006SR = DIRECT,
    // 0009SR = STEREO (DIRECT), 0151SR = AUTO LEVEL CONTROL, 0010SR = STANDARD (PLII),
    // 0013SR = PRO LOGIC2 MOVIE, 0014SR = PRO LOGIC2 MUSIC, 0100SR = ADVANCED SURROUND (cycle)
    private val modes = linkedMapOf(
        "STEREO" to "0001SR",
        "AUTO SURR." to "0005SR",
        "DIRECT" to "0006SR",
        "ALC / PURE" to "0151SR",
        "PLII MOVIE" to "0013SR",
        "PLII MUSIC" to "0014SR",
        "STANDARD" to "0010SR",
        "ADV. SURR." to "0100SR"
    )

    // ===== KARTA RÁDIO (Výsuvný zoznam predvolieb + detail prehrávania) =====
    private lateinit var favToggleBtn: Button
    private lateinit var favStopBtn: Button
    private lateinit var favDrawerCard: LinearLayout
    private lateinit var favDrawerStatus: TextView
    private lateinit var favTableContainer: LinearLayout
    private var isFavListExpanded: Boolean = false

    private lateinit var nowPlayingTitle: TextView
    private lateinit var nowPlayingSub: TextView
    private lateinit var nowPlayingMeta: TextView
    private lateinit var nowPlayingFl: TextView

    // Načítané stanice vo Favorites (poradie 1..N -> názov)
    private val favStations = linkedMapOf<Int, String>()
    // Metadáta z obrazovky prehrávania (riadok 1..8 -> Pair(typeCode, text))
    private val playMetaLines = mutableMapOf<Int, Pair<String, String>>()
    @Volatile private var isSyncingFavorites: Boolean = false
    @Volatile private var isPlayingFavorite: Boolean = false
    @Volatile private var nwScreenType: String = ""
    @Volatile private var listScreenReady: Boolean = false
    private var playTimeText: String = ""
    private var lastFlText: String = ""

    // ===== DLNA =====
    private data class MediaEntry(
        val id: String, val title: String,
        val isContainer: Boolean, val url: String?)

    private var mediaServerDesc: String? = null
    private var mediaServerName: String = ""
    private var cdsControlUrl: String? = null
    private var avtControlUrl: String? = null
    private val browseStack = mutableListOf<Pair<String, String>>()
    private var currentListing = listOf<MediaEntry>()
    private var playQueue = listOf<MediaEntry>()
    private var queueIdx = -1
    private var queueJob: Job? = null

    private lateinit var mediaServerText: TextView
    private lateinit var mediaPathText: TextView
    private lateinit var mediaStatusText: TextView
    private lateinit var mediaList: LinearLayout

    // =====================================================================
    // ZIVOTNY CYKLUS
    // =====================================================================

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = C_BG
        val p = prefs()
        host = p.getString("host", host) ?: host
        mediaServerDesc = p.getString("dlnaServer", null)
        mediaServerName = p.getString("dlnaName", "") ?: ""
        loadCachedFavorites()
        buildUI()

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("pioneerSSDP").apply {
            setReferenceCounted(false); acquire()
        }

        // Po zapnutí aplikácie ihneď skúsime poslednú uloženú IP adresu;
        // ak neodpovedá, automaticky prehľadáme sieť, pripojíme nájdený receiver a IP uložíme.
        autoConnectOrDiscoverOnStartup()

        if (mediaServerDesc != null) initMediaServer(mediaServerDesc!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
        runCatching { socketRef.get()?.close() }
        multicastLock?.release()
    }

    private fun prefs() = getSharedPreferences("pioneer", MODE_PRIVATE)

    private fun saveHostIp(newIp: String) {
        host = newIp.trim()
        prefs().edit().putString("host", host).apply()
        ui {
            if (::ipEdit.isInitialized && ipEdit.text.toString() != host) {
                ipEdit.setText(host)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun loadCachedFavorites() {
        val raw = prefs().getString("favStationsJson", null) ?: return
        runCatching {
            val arr = JSONArray(raw)
            favStations.clear()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val idx = obj.getInt("idx")
                val name = obj.getString("name")
                if (!looksLikeMetadata(name)) {
                    favStations[idx] = name
                }
            }
        }
    }

    private fun saveCachedFavorites() {
        runCatching {
            val arr = JSONArray()
            favStations.forEach { (idx, name) ->
                val obj = JSONObject()
                obj.put("idx", idx)
                obj.put("name", name)
                arr.put(obj)
            }
            prefs().edit().putString("favStationsJson", arr.toString()).apply()
        }
    }

    private fun looksLikeMetadata(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (Regex("(?i).*\\b(kbps|khz|mp3|aac|wma|flac|wav|pcm|buffer)\\b.*").matches(t)) return true
        if (Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(t)) return true
        if (Regex("^\\d+\\s*%$").matches(t)) return true
        return false
    }

    // =====================================================================
    // STYLOVE POMOCNIKY
    // =====================================================================

    private fun roundedBg(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) d.setStroke(dp(1), strokeColor)
        return d
    }

    private fun flatBtn(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 12f
        b.maxLines = 2
        b.setTextColor(C_WHITE)
        b.transformationMethod = null
        b.background = roundedBg(C_BTN, 12, C_STROKE)
        b.setPadding(dp(4), dp(8), dp(4), dp(8))
        b.setOnClickListener { onClick() }
        return b
    }

    private fun pillBtn(label: String, bg: Int, fg: Int, onClick: (Button) -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.textSize = 13f
        b.maxLines = 1
        b.gravity = Gravity.CENTER
        b.setTextColor(fg)
        b.transformationMethod = null
        b.background = roundedBg(bg, 10)
        b.setPadding(dp(12), 0, dp(12), 0)
        b.minimumWidth = 0
        b.minimumHeight = 0
        b.setOnClickListener { onClick(b) }
        return b
    }

    private fun highlightBtn(b: Button, active: Boolean, accent: Int = C_CYAN) {
        b.background = roundedBg(if (active) accent else C_BTN, 12, if (active) accent else C_STROKE)
        b.setTextColor(if (active) C_ON_ACCENT else C_WHITE)
        b.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun rowOf(buttons: List<Button>): LinearLayout {
        val r = LinearLayout(this)
        r.orientation = LinearLayout.HORIZONTAL
        buttons.forEach {
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(2), dp(2), dp(2), dp(2))
            r.addView(it, lp)
        }
        return r
    }

    private fun makeGrid(buttons: List<Button>, cols: Int = 3): GridLayout {
        val g = GridLayout(this)
        g.columnCount = cols
        g.useDefaultMargins = true
        buttons.forEach { b ->
            val lp = GridLayout.LayoutParams()
            lp.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            lp.width = 0
            g.addView(b, lp)
        }
        return g
    }

    private fun sectionLabel(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 13f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(C_SLATE)
        tv.setPadding(0, dp(10), 0, dp(4))
        return tv
    }

    private fun hint(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 10f
        tv.setTextColor(C_DIM)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(12), 0, dp(8))
        return tv
    }

    // =====================================================================
    // VYTVORENIE UI
    // =====================================================================

    private fun buildUI() {
        val pad = dp(12)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.setPadding(pad, dp(8), pad, 0)

        // Hlavička: vycentrovaný názov + podnadpis a napravo okrúhla gulička ON/OFF
        val header = FrameLayout(this)
        header.setPadding(0, 0, 0, dp(6))

        val titleBox = LinearLayout(this)
        titleBox.orientation = LinearLayout.VERTICAL
        titleBox.gravity = Gravity.CENTER_HORIZONTAL

        val title = TextView(this)
        title.text = "Pioneer Remote"
        title.textSize = 19f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(C_WHITE)
        title.gravity = Gravity.CENTER
        titleBox.addView(title)

        val sub = TextView(this)
        sub.text = "VSX-S510"
        sub.textSize = 11f
        sub.setTextColor(C_CYAN)
        sub.gravity = Gravity.CENTER
        titleBox.addView(sub)

        header.addView(titleBox, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))

        val circleSize = dp(40)
        powerBtn = Button(this)
        powerBtn.textSize = 11f
        powerBtn.typeface = Typeface.DEFAULT_BOLD
        powerBtn.gravity = Gravity.CENTER
        powerBtn.transformationMethod = null
        powerBtn.setPadding(0, 0, 0, 0)
        powerBtn.minimumWidth = 0
        powerBtn.minimumHeight = 0
        powerBtn.setOnClickListener { togglePower() }
        updatePowerButton()

        header.addView(powerBtn, FrameLayout.LayoutParams(
            circleSize,
            circleSize,
            Gravity.END or Gravity.CENTER_VERTICAL
        ))

        root.addView(header)

        // Riadok: IP + vyhľadanie + pripojenie
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        ipEdit = EditText(this)
        ipEdit.setText(host)
        ipEdit.hint = "IP adresa receivera"
        ipEdit.inputType = InputType.TYPE_CLASS_PHONE
        ipEdit.setTextColor(C_WHITE)
        ipEdit.setHintTextColor(C_DIM)
        ipEdit.textSize = 12f
        ipEdit.background = roundedBg(C_CARD2, 10, C_STROKE)
        ipEdit.setPadding(dp(10), dp(6), dp(10), dp(6))
        top.addView(ipEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val scanBtn = pillBtn("🔍", C_BTN, C_WHITE) { discoverReceivers(autoConnectFirst = false) }
        top.addView(scanBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val connBtn = pillBtn("Pripoj", C_CYAN, C_ON_ACCENT) { connectPressed() }
        top.addView(connBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top)

        // =================================================================
        // HLAVNÝ DISPLEJ (KARTA HORE)
        // 1. Výrazným písmom názov rádiovej stanice
        // 2. Pod ním menším fontom názov relácie / skladby a bitrate
        // 3. Pod tým zvolený vstup a režim
        // 4. Stav pripojenia + IP adresa (alebo Odpojené + chybová hláška)
        // 5. V dolnej časti displeja zelený posuvník hlasitosti
        // =================================================================
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = roundedBg(C_CARD, 18, C_CYAN_D)

        mainStationBig = TextView(this)
        mainStationBig.text = "📻  —"
        mainStationBig.textSize = 20f
        mainStationBig.typeface = Typeface.DEFAULT_BOLD
        mainStationBig.setTextColor(C_WHITE)
        mainStationBig.gravity = Gravity.CENTER
        mainStationBig.maxLines = 2

        mainShowBitrate = TextView(this)
        mainShowBitrate.text = "Relácia / Skladba: —   ·   Bitrate: —"
        mainShowBitrate.textSize = 12f
        mainShowBitrate.setTextColor(C_CYAN)
        mainShowBitrate.gravity = Gravity.CENTER
        mainShowBitrate.setPadding(0, dp(2), 0, dp(2))

        infoLine = TextView(this)
        infoLine.text = "Vstup: —   ·   Režim: —"
        infoLine.textSize = 12f
        infoLine.setTextColor(C_SLATE)
        infoLine.gravity = Gravity.CENTER

        connLine = TextView(this)
        connLine.text = "○  Odpojené ($host)"
        connLine.textSize = 11f
        connLine.setTextColor(C_RED)
        connLine.gravity = Gravity.CENTER

        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 11f
        statusText.setTextColor(C_ORANGE)
        statusText.gravity = Gravity.CENTER

        // Zelený posuvník hlasitosti v dolnej časti hlavného displeja
        val volBox = LinearLayout(this)
        volBox.orientation = LinearLayout.HORIZONTAL
        volBox.gravity = Gravity.CENTER_VERTICAL
        volBox.setPadding(dp(6), dp(6), dp(6), dp(2))

        volumeLabel = TextView(this)
        volumeLabel.text = "🔊 —"
        volumeLabel.textSize = 13f
        volumeLabel.typeface = Typeface.DEFAULT_BOLD
        volumeLabel.setTextColor(C_GREEN)
        volumeLabel.setPadding(0, 0, dp(8), 0)

        volSlider = SeekBar(this)
        volSlider.max = volMax
        volSlider.progressTintList = ColorStateList.valueOf(C_GREEN)
        volSlider.thumbTintList = ColorStateList.valueOf(C_GREEN)
        volSlider.progressBackgroundTintList = ColorStateList.valueOf(C_STROKE)
        volSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) volumeLabel.text = volShortText(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { seekVolume(s?.progress ?: 0) }
        })

        volBox.addView(volumeLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        volBox.addView(volSlider, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        card.addView(mainStationBig)
        card.addView(mainShowBitrate)
        card.addView(infoLine)
        card.addView(connLine)
        card.addView(statusText)
        card.addView(volBox)

        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = dp(6)
        root.addView(card, cardLp)

        // Prepínače kariet (tabs)
        val tabDefs = listOf("🎚 OVLÁDANIE", "📻 RÁDIO", "💽 MÉDIÁ")
        val tabBtns = tabDefs.map { t -> pillBtn(t, C_BTN, C_SLATE) { } }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabBtns.forEach { b ->
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val tabsLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tabsLp.topMargin = dp(6)
        root.addView(tabs, tabsLp)

        // Swipe obrazovky
        val pager = ViewPager2(this)
        pager.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        val screens = listOf(buildScreenHome(), buildScreenRadio(), buildScreenMedia())
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = screens.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(FrameLayout(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val frame = holder.itemView as FrameLayout
                frame.removeAllViews()
                frame.addView(screens[position])
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                tabBtns.forEachIndexed { i, b -> styleTab(b, i == position) }
            }
        })
        tabBtns.forEachIndexed { i, b ->
            b.setOnClickListener { pager.currentItem = i }
        }
        styleTab(tabBtns[0], true)

        // MUTE / VSTUP pod prepínačmi kariet
        muteBtn = flatBtn("🔇 MUTE") { toggleMute() }
        val vstupBtn = flatBtn("VSTUP ▶") { send("FU"); sendSilent("?F") }
        muteBtn.textSize = 13f; vstupBtn.textSize = 13f
        val muteLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        muteLp.topMargin = dp(6)
        root.addView(rowOf(listOf(muteBtn, vstupBtn)), muteLp)

        // Tlačidlá hlasitosti VOL − / VOL + ostávajú ako boli
        val volMinus = flatBtn("VOL −") { send("VD"); sendSilent("?V") }
        val volPlus = flatBtn("VOL +") { send("VU"); sendSilent("?V") }
        volMinus.textSize = 13f; volPlus.textSize = 13f
        root.addView(rowOf(listOf(volMinus, volPlus)))

        root.addView(pager)
        setContentView(root)
    }

    private fun styleTab(btn: Button, active: Boolean) {
        btn.background = roundedBg(
            if (active) C_ORANGE else C_BTN, 10, if (active) C_ORANGE else C_STROKE)
        btn.setTextColor(if (active) C_ON_ACCENT else C_SLATE)
        btn.typeface = if (active) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

    private fun connectPressed() {
        val entered = ipEdit.text.toString().trim()
        if (entered.isNotEmpty()) {
            saveHostIp(entered)
        }
        avtControlUrl = null
        connect(silentFail = false)
    }

    // =====================================================================
    // SCREEN 1 — OVLADANIE (VSTUPY BEZ HDMI 1-6 + OPRAVENY PRIESTOROVY ZVUK)
    // =====================================================================

    private fun buildScreenHome(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(4), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page.addView(sectionLabel("VÝBER VSTUPU"))
        val ib = mutableListOf<Button>()
        inputs.forEach { (name, code) ->
            val b = flatBtn(name) {
                if (code != "45FN") isPlayingFavorite = false
                send(code)
                sendSilent("?F")
                activeInput = name
                updateInfoLine()
                styleInputButtons()
                updateMainDisplayHeader()
            }
            inputButtons[name] = b
            ib.add(b)
        }
        page.addView(makeGrid(ib, 3))

        page.addView(sectionLabel("PRIESTOROVÝ REŽIM (SURROUND)"))
        val mb = mutableListOf<Button>()
        modes.forEach { (name, code) ->
            val b = flatBtn(name) {
                setListeningMode(name, code)
            }
            modeButtons[name] = b
            mb.add(b)
        }
        page.addView(makeGrid(mb, 2))
        page.addView(hint("← swipe na rádio →"))
        return sv
    }

    // Opravené prepínanie priestorového zvuku pre Pioneer VSX-S510:
    // Pošle príkaz xxxxSR a následne si vyžiada potvrdenie ?S (Listening Mode Set) aj ?L (Playing Listening Mode)
    private fun setListeningMode(name: String, code: String) {
        activeMode = name
        updateInfoLine()
        styleModeButtons()
        scope.launch(Dispatchers.IO) {
            sendSilent(code)
            delay(250)
            sendSilent("?S")
            sendSilent("?L")
        }
    }

    // =====================================================================
    // SCREEN 2 — RADIO (DRUHA MOZNOST: VYSUVNY ZOZNAM FAVORITES + STOP)
    // =====================================================================

    private fun buildScreenRadio(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Horné dve tlačidlá vedľa seba:
        // 1. ⭐ FAVORITES (vysunie zoznam / ak hrá stream, preruší ho a znova vysunie zoznam)
        // 2. ⏹ ZASTAVIŤ (zastaví prehrávanie streamu)
        favToggleBtn = flatBtn("⭐ FAVORITES (Načítať zoznam)") {
            onFavoritesButtonClicked()
        }
        favToggleBtn.background = roundedBg(C_CYAN_D, 12, C_CYAN)
        favToggleBtn.setTextColor(C_WHITE)
        favToggleBtn.typeface = Typeface.DEFAULT_BOLD

        favStopBtn = flatBtn("⏹ ZASTAVIŤ") {
            stopFavoriteStream()
        }
        favStopBtn.background = roundedBg(C_RED, 12, C_RED)
        favStopBtn.setTextColor(C_WHITE)
        favStopBtn.typeface = Typeface.DEFAULT_BOLD

        page.addView(rowOf(listOf(favToggleBtn, favStopBtn)))

        // Výsuvný kontajner so zoznamom predvolieb (zobrazí sa po stlačení FAVORITES,
        // po kliknutí na predvoľbu sa automaticky zasunie/skryje)
        favDrawerCard = LinearLayout(this)
        favDrawerCard.orientation = LinearLayout.VERTICAL
        favDrawerCard.setPadding(dp(10), dp(10), dp(10), dp(10))
        favDrawerCard.background = roundedBg(C_CARD, 14, C_CYAN)
        val drawerLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        drawerLp.topMargin = dp(6)
        favDrawerCard.layoutParams = drawerLp
        favDrawerCard.visibility = View.GONE

        favDrawerStatus = TextView(this)
        favDrawerStatus.text = "Vyber predvoľbu zo zoznamu receivera:"
        favDrawerStatus.textSize = 12f
        favDrawerStatus.setTextColor(C_CYAN)
        favDrawerStatus.setPadding(dp(4), 0, dp(4), dp(6))
        favDrawerCard.addView(favDrawerStatus)

        favTableContainer = LinearLayout(this)
        favTableContainer.orientation = LinearLayout.VERTICAL
        favDrawerCard.addView(favTableContainer)

        page.addView(favDrawerCard)
        renderFavoriteTable()

        // Spodný displej s detailom prehrávanej skladby / stanice, bitrate a ostatnými údajmi
        page.addView(sectionLabel("DETAIL PREHRÁVANIA"))
        val dispCard = LinearLayout(this)
        dispCard.orientation = LinearLayout.VERTICAL
        dispCard.setPadding(dp(14), dp(12), dp(14), dp(12))
        dispCard.background = roundedBg(C_CARD2, 16, C_CYAN_D)

        nowPlayingTitle = TextView(this)
        nowPlayingTitle.text = "📻 Žiadna stanica nehrá"
        nowPlayingTitle.textSize = 16f
        nowPlayingTitle.typeface = Typeface.DEFAULT_BOLD
        nowPlayingTitle.setTextColor(C_WHITE)

        nowPlayingSub = TextView(this)
        nowPlayingSub.text = "Relácia / Skladba: —"
        nowPlayingSub.textSize = 13f
        nowPlayingSub.setTextColor(C_CYAN)
        nowPlayingSub.setPadding(0, dp(4), 0, 0)

        nowPlayingMeta = TextView(this)
        nowPlayingMeta.text = "Formát / Bitrate: —   ·   Čas: —"
        nowPlayingMeta.textSize = 12f
        nowPlayingMeta.setTextColor(C_SLATE)
        nowPlayingMeta.setPadding(0, dp(4), 0, 0)

        nowPlayingFl = TextView(this)
        nowPlayingFl.text = "Displej receivera: —"
        nowPlayingFl.textSize = 12f
        nowPlayingFl.typeface = Typeface.MONOSPACE
        nowPlayingFl.setTextColor(C_DIM)
        nowPlayingFl.setPadding(0, dp(6), 0, 0)

        dispCard.addView(nowPlayingTitle)
        dispCard.addView(nowPlayingSub)
        dispCard.addView(nowPlayingMeta)
        dispCard.addView(nowPlayingFl)
        page.addView(dispCard)

        page.addView(hint("← swipe na ovládanie / médiá →"))
        return sv
    }

    // Vykreslí tabuľku predvolieb zoradenú presne tak, ako sú uložené v receiveri
    private fun renderFavoriteTable() {
        if (!::favTableContainer.isInitialized) return
        favTableContainer.removeAllViews()
        favButtons.clear()

        val entriesToRender = if (favStations.isNotEmpty()) {
            favStations.entries.sortedBy { it.key }.map { it.key to it.value }
        } else {
            (1..8).map { it to "Predvoľba $it" }
        }

        entriesToRender.forEach { (idx, name) ->
            val b = flatBtn("$idx.  📻  $name") {
                onFavoritePresetSelected(idx, name)
            }
            b.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            b.textSize = 13f
            b.setPadding(dp(14), dp(10), dp(14), dp(10))
            highlightBtn(b, idx == activeFavIndex)
            favButtons[idx] = b

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(4)
            favTableContainer.addView(b, lp)
        }
    }

    private fun setFavDrawerExpanded(expanded: Boolean) {
        isFavListExpanded = expanded
        if (::favDrawerCard.isInitialized) {
            favDrawerCard.visibility = if (expanded) View.VISIBLE else View.GONE
        }
        if (::favToggleBtn.isInitialized) {
            favToggleBtn.text = if (expanded) "⭐ FAVORITES (Skryť ▲)" else "⭐ FAVORITES (Zoznam ▼)"
        }
    }

    // Preferovaná 2. možnosť:
    // - Stlačenie tlačidla FAVORITES preruší prípadné prehrávanie hrajúceho streamu,
    //   vráti receiver do koreňového zoznamu Favorites, načíta predvoľby a vysunie zoznam.
    // - Ak už je zoznam vysunutý a nič nehrá, stlačením sa zoznam zasunie.
    private fun onFavoritesButtonClicked() {
        if (isFavListExpanded && !isPlayingFavorite) {
            setFavDrawerExpanded(false)
            return
        }

        favJob?.cancel()
        setFavDrawerExpanded(true)
        favDrawerStatus.text = "⏳ Zastavujem prehrávanie a načítavam zoznam Favorites…"
        renderFavoriteTable()

        favJob = scope.launch(Dispatchers.IO) {
            try {
                isSyncingFavorites = true
                isPlayingFavorite = false
                if (!isOn) {
                    sendSilent("PO")
                    delay(2500)
                }

                // Prerušíme prípadný hrajúci stream a vrátime receiver do koreňového zoznamu Favorites
                returnReceiverToFavoritesList()

                // Vyžiadame čerstvý zoznam predvolieb z receivera
                sendSilent("?GAP")
                sendSilent("?GAH")
                sendSilent("?FL")
                delay(700)
                sendSilent("?GAP")
                delay(500)

                ui {
                    favDrawerStatus.text = "Klikni na predvoľbu pre spustenie (zoznam sa automaticky zasunie):"
                    renderFavoriteTable()
                }
            } finally {
                isSyncingFavorites = false
            }
        }
    }

    // Po kliknutí na predvoľbu v tabuľke:
    // 1. Zoznam predvolieb sa okamžite zasunie (skryje pod tlačidlá)
    // 2. Spustí sa zvolená predvoľba (keďže pri vysunutí zoznamu už bolo prehrávanie zastavené
    //    a receiver čaká priamo v zozname Favorites, príkaz xxGHP okamžite zafunguje!)
    private fun onFavoritePresetSelected(index: Int, name: String) {
        favJob?.cancel()
        activeFavIndex = index
        val stationTitle = favStations[index] ?: name
        activeStationName = stationTitle

        // Zasunieme zoznam predvolieb pod tlačidlá
        setFavDrawerExpanded(false)

        // Aktualizujeme hlavný aj spodný displej
        nowPlayingTitle.text = "📻 $index. $stationTitle"
        nowPlayingSub.text = "Spúšťam predvoľbu $index…"
        nowPlayingMeta.text = "Načítavam stream…"
        updateMainDisplayHeader()

        favJob = scope.launch(Dispatchers.IO) {
            isSyncingFavorites = false
            isPlayingFavorite = true
            playMetaLines.clear()

            if (!isOn) {
                sendSilent("PO")
                delay(2500)
            }

            if (activeInput != "FAVORITES" && activeInput != "45FN") {
                sendSilent("45FN")
                sendSilent("?F")
                delay(1400)
            }

            // Receiver je pripravený v zozname Favorites -> pošleme priamu voľbu riadku (xxGHP)
            val ghpCmd = String.format("%02dGHP", index)
            sendSilent(ghpCmd)
            delay(400)
            sendSilent("10NW") // potvrdenie Play

            // Počkáme na nabehnutie streamu a načítame metadáta relácie a bitrate
            delay(1400)
            sendSilent("?GAP")
            sendSilent("?GAH")
            sendSilent("?FL")
            delay(1600)
            sendSilent("?GAP")
            sendSilent("?FL")
        }
    }

    // Tlačidlo ⏹ ZASTAVIŤ vedľa FAVORITES:
    // Zastaví prehrávanie streamu a vráti receiver do zoznamu Favorites
    private fun stopFavoriteStream() {
        favJob?.cancel()
        isPlayingFavorite = false
        activeFavIndex = -1
        activeStationName = ""
        playMetaLines.clear()
        playTimeText = ""

        nowPlayingTitle.text = "⏹ Prehrávanie zastavené"
        nowPlayingSub.text = "Relácia / Skladba: —"
        nowPlayingMeta.text = "Formát / Bitrate: —"
        updateMainDisplayHeader()
        renderFavoriteTable()

        favJob = scope.launch(Dispatchers.IO) {
            sendSilent("20NW")
            delay(300)
            sendSilent("14NW")
            delay(400)
            sendSilent("?FL")
            sendSilent("?GAP")
        }
    }

    // Spoľahlivý návrat receivera z hrajúceho streamu do koreňového zoznamu Favorites
    private suspend fun returnReceiverToFavoritesList() {
        listScreenReady = false
        if (activeInput != "FAVORITES" && activeInput != "45FN") {
            sendSilent("45FN")
            sendSilent("?F")
            for (step in 0 until 10) {
                delay(200)
                if (listScreenReady) return
            }
            return
        }

        // 1. Zastavíme stream (20NW) a vrátime sa o úroveň späť do zoznamu (14NW)
        sendSilent("20NW")
        delay(300)
        sendSilent("14NW")
        delay(400)
        sendSilent("?GAP")
        delay(350)

        // 2. Ak receiver stále neohlásil zoznam (zostal visieť v Play obrazovke),
        // urobíme rýchly reset vstupu: 38FN (NetRadio) -> 45FN (Favorites)
        if (!listScreenReady) {
            sendSilent("38FN")
            delay(450)
            sendSilent("45FN")
            sendSilent("?F")
            for (step in 0 until 10) {
                delay(200)
                if (listScreenReady) break
            }
        }
    }

    // =====================================================================
    // SCREEN 3 — MEDIA (DLNA)
    // =====================================================================

    private fun buildScreenMedia(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val srvBtn = flatBtn("🔍 SERVERY") { scanMediaServers() }
        val backBtn = flatBtn("⟨ SPÄŤ") { browseBack() }
        val albumBtn = flatBtn("▶ ALBUM") { playCurrentFolder() }
        page.addView(rowOf(listOf(srvBtn, backBtn, albumBtn)))

        mediaServerText = TextView(this)
        mediaServerText.text = if (mediaServerName.isNotEmpty())
            "Server: $mediaServerName" else "Server: nevybraný — klikni 🔍 SERVERY"
        mediaServerText.textSize = 12f
        mediaServerText.setTextColor(C_SLATE)
        mediaServerText.setPadding(0, dp(8), 0, 0)
        page.addView(mediaServerText)

        mediaPathText = TextView(this)
        mediaPathText.text = "📚 /"
        mediaPathText.textSize = 12f
        mediaPathText.setTextColor(C_SLATE)
        page.addView(mediaPathText)

        mediaStatusText = TextView(this)
        mediaStatusText.text = ""
        mediaStatusText.textSize = 13f
        mediaStatusText.setTextColor(C_CYAN)
        mediaStatusText.setPadding(0, dp(8), 0, dp(8))
        page.addView(mediaStatusText)

        mediaList = LinearLayout(this)
        mediaList.orientation = LinearLayout.VERTICAL
        page.addView(mediaList)
        page.addView(hint("← swipe na rádio →"))
        return sv
    }

    // =====================================================================
    // AKTUALIZACIE HLAVNEHO A SPODNEHO DISPLEJA
    // =====================================================================

    private fun volShortText(v: Int): String {
        val dB = v - 80
        return "🔊 $v (" + (if (dB >= 0) "+" else "") + dB + " dB)"
    }

    private fun updateVolumeCard() {
        if (!::volumeLabel.isInitialized) return
        if (isMuted) {
            volumeLabel.text = "🔇 MUTE"
            volumeLabel.setTextColor(C_RED)
        } else {
            volumeLabel.text = volShortText(currentVol)
            volumeLabel.setTextColor(C_GREEN)
        }
    }

    private fun updateInfoLine() {
        val i = activeInput ?: "—"
        val m = activeMode ?: "—"
        infoLine.text = "Vstup: $i   ·   Režim: $m"
    }

    private fun updateMainDisplayHeader() {
        if (!::mainStationBig.isInitialized) return
        val presetName = if (activeFavIndex > 0) favStations[activeFavIndex] else null
        val l1 = playMetaLines[1]?.second.orEmpty()
        val l2 = playMetaLines[2]?.second.orEmpty()
        val l3 = playMetaLines[3]?.second.orEmpty()
        val l4 = playMetaLines[4]?.second.orEmpty()
        val l5 = playMetaLines[5]?.second.orEmpty()
        val l6 = playMetaLines[6]?.second.orEmpty()

        // 1. Horný výrazný nápis: Názov rádiovej stanice (alebo aktívny vstup / FL displej)
        val topTitle = when {
            !presetName.isNullOrBlank() -> "📻  $presetName"
            activeStationName.isNotBlank() -> "📻  $activeStationName"
            l1.isNotBlank() -> "📻  $l1"
            lastFlText.isNotBlank() -> "📟  $lastFlText"
            !activeInput.isNullOrBlank() -> "🎚  ${activeInput!!}"
            else -> "📻  —"
        }
        mainStationBig.text = topTitle

        // 2. Pod ním menším písmom: Názov relácie / skladby + bitrate
        val showParts = listOf(l1, l2, l3).filter {
            it.isNotBlank() &&
                !it.equals(presetName, ignoreCase = true) &&
                !it.equals(activeStationName, ignoreCase = true)
        }.distinct()
        val bitrateParts = listOf(l4, l5, l6).filter { it.isNotBlank() }.distinct()

        val combinedSub = mutableListOf<String>()
        if (showParts.isNotEmpty()) combinedSub.add(showParts.joinToString(" · "))
        if (bitrateParts.isNotEmpty()) combinedSub.add(bitrateParts.joinToString(" "))
        if (playTimeText.isNotBlank()) combinedSub.add(playTimeText)

        mainShowBitrate.text = if (combinedSub.isNotEmpty()) {
            combinedSub.joinToString("   |   ")
        } else if (lastFlText.isNotBlank()) {
            "Displej: $lastFlText"
        } else {
            "Relácia / Bitrate: —"
        }
    }

    private fun updateNowPlayingCardFromMeta() {
        updateMainDisplayHeader()
        if (!::nowPlayingTitle.isInitialized) return

        val presetName = if (activeFavIndex > 0) favStations[activeFavIndex] else null
        val l1 = playMetaLines[1]?.second.orEmpty()
        val l2 = playMetaLines[2]?.second.orEmpty()
        val l3 = playMetaLines[3]?.second.orEmpty()
        val l4 = playMetaLines[4]?.second.orEmpty()
        val l5 = playMetaLines[5]?.second.orEmpty()
        val l6 = playMetaLines[6]?.second.orEmpty()

        val stationTitle = when {
            !presetName.isNullOrBlank() -> "$activeFavIndex. $presetName"
            activeStationName.isNotBlank() -> activeStationName
            l1.isNotBlank() -> l1
            else -> "Prehrávanie z Favorites"
        }
        nowPlayingTitle.text = "📻 $stationTitle"

        val trackParts = listOf(l1, l2, l3).filter {
            it.isNotBlank() && !it.equals(presetName, ignoreCase = true)
        }.distinct()
        nowPlayingSub.text = if (trackParts.isNotEmpty())
            trackParts.joinToString("  ·  ")
        else
            "Relácia / Skladba: —"

        val techParts = listOf(l4, l5, l6).filter { it.isNotBlank() }.distinct().toMutableList()
        if (playTimeText.isNotBlank()) techParts.add("Čas: $playTimeText")
        nowPlayingMeta.text = if (techParts.isNotEmpty())
            techParts.joinToString("   ·   ")
        else
            "Formát / Bitrate: —"
    }

    private fun updateMuteButton() {
        muteBtn.text = if (isMuted) "🔊 ODMUTOVAŤ" else "🔇 MUTE"
        highlightBtn(muteBtn, isMuted, C_RED)
    }

    private fun updatePowerButton() {
        val bg = if (isOn) C_GREEN else C_RED
        val fg = if (isOn) C_ON_ACCENT else C_WHITE
        powerBtn.text = if (isOn) "ON" else "OFF"
        powerBtn.setTextColor(fg)
        powerBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(bg)
            setStroke(dp(1), C_WHITE)
        }
    }

    private fun togglePower() {
        send(if (isOn) "PF" else "PO")
        scope.launch(Dispatchers.IO) {
            delay(800)
            sendSilent("?P")
        }
    }

    private fun styleInputButtons() {
        inputButtons.forEach { (name, b) -> highlightBtn(b, name == activeInput) }
    }

    private fun styleModeButtons() {
        modeButtons.forEach { (name, b) -> highlightBtn(b, name == activeMode) }
    }

    private fun decodeFlHex(hexStr: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < hexStr.length) {
            val code = hexStr.substring(i, i + 2).toIntOrNull(16) ?: 32
            if (code in 32..126) sb.append(code.toChar())
            else if (code > 127) sb.append('·')
            i += 2
        }
        return sb.toString().trim()
    }

    // =====================================================================
    // HLASITOST — posuvnik posiela az po pusteni, s pevnym poctom krokov
    // =====================================================================

    private fun seekVolume(target: Int) {
        seekJob?.cancel()
        volSlider.isEnabled = false
        seekJob = scope.launch(Dispatchers.IO) {
            try {
                val diff = target - currentVol
                if (diff != 0) {
                    val cmd = if (diff > 0) "VU" else "VD"
                    repeat(minOf(abs(diff), 100)) {
                        outWriter?.let {
                            it.write(cmd + "\r\n")
                            it.flush()
                        }
                        delay(50)
                    }
                }
                sendSilent("?V")
            } finally {
                ui { volSlider.isEnabled = true }
            }
        }
    }

    private fun toggleMute() {
        send(if (isMuted) "MF" else "MO")
        sendSilent("?M")
    }

    // =====================================================================
    // AUTOMATICKÉ VYHĽADANIE A PRIPOJENIE RECEIVERA PRI ŠTARTE
    // =====================================================================

    private fun autoConnectOrDiscoverOnStartup() {
        ui {
            connLine.text = "⏳ Pripájam na uloženú IP $host…"
            connLine.setTextColor(C_ORANGE)
            statusText.text = ""
        }
        scope.launch(Dispatchers.IO) {
            // 1. Najprv skúsime poslednú uloženú IP adresu
            if (probe(host)) {
                saveHostIp(host)
                connect(silentFail = false)
                return@launch
            }
            // 2. Ak uložená IP neodpovedá, automaticky prehľadáme sieť a pripojíme nájdený receiver
            ui {
                connLine.text = "○ Uložená IP $host neodpovedá"
                connLine.setTextColor(C_RED)
                statusText.text = "🔍 Automaticky hľadám receiver v sieti…"
            }
            discoverReceivers(autoConnectFirst = true)
        }
    }

    private fun phoneIp(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces() ?: return null
            for (n in nets) {
                if (n.isLoopback || !n.isUp) continue
                for (a in n.inetAddresses) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun probe(ip: String): Boolean {
        return try {
            Socket().use { s ->
                s.soTimeout = 1000
                s.connect(InetSocketAddress(ip, port), 450)
                val w = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                w.write("?P\r\n?V\r\n")
                w.flush()
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val line = r.readLine() ?: return false
                line.startsWith("VOL") || line.startsWith("PWR") || line.startsWith("FN") ||
                    line.startsWith("MUT") || line.startsWith("LM") || line.startsWith("SR") ||
                    line.startsWith("FL")
            }
        } catch (_: Exception) { false }
    }

    private fun discoverReceivers(autoConnectFirst: Boolean) {
        ui { statusText.text = "🔍 Hľadám Pioneer receiver v Wi-Fi sieti (port $port)…" }
        scope.launch(Dispatchers.IO) {
            val myIp = phoneIp()
            if (myIp == null) {
                ui {
                    connLine.text = "○ Odpojené (Wi-Fi nedostupná)"
                    connLine.setTextColor(C_RED)
                    statusText.text = "Nepodarilo sa zistiť IP telefónu — skontroluj pripojenie na Wi-Fi."
                }
                return@launch
            }
            val prefix = myIp.substringBeforeLast(".")
            val found = Collections.synchronizedList(mutableListOf<String>())
            withTimeoutOrNull(18000) {
                coroutineScope {
                    (1..254).forEach { i ->
                        launch {
                            val ip = "$prefix.$i"
                            if (probe(ip)) found.add(ip)
                        }
                    }
                }
            }
            val sorted = found.toList().sorted()
            ui {
                if (sorted.isEmpty()) {
                    connLine.text = "○ Odpojené ($host)"
                    connLine.setTextColor(C_RED)
                    statusText.text = "V sieti $prefix.0/24 sa nenašiel receiver. Skontroluj napájanie / Network Standby."
                } else if (autoConnectFirst || sorted.size == 1) {
                    val chosenIp = sorted.first()
                    saveHostIp(chosenIp)
                    statusText.text = "✓ Nájdený receiver na $chosenIp — pripájam…"
                    connect(silentFail = false)
                } else {
                    statusText.text = ""
                    pickReceiver(sorted)
                }
            }
        }
    }

    private fun pickReceiver(found: List<String>) {
        if (found.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Nájdené prijímače")
            .setItems(found.toTypedArray()) { _, i ->
                saveHostIp(found[i])
                avtControlUrl = null
                connect(silentFail = false)
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // =====================================================================
    // DLNA SERVERY (KARTA 3 — MÉDIÁ)
    // =====================================================================

    private fun scanMediaServers() {
        ui { mediaStatusText.text = "Hľadám DLNA servery…" }
        scope.launch(Dispatchers.IO) {
            val found = ssdpFindAll("urn:schemas-upnp-org:device:MediaServer:1")
            if (found.isEmpty()) {
                ui { mediaStatusText.text = "Nenašiel som žiadny DLNA server v sieti." }
                return@launch
            }
            val names = found.map { loc ->
                val xml = httpGet(loc)
                Triple(
                    loc,
                    Regex("(?is)<friendlyName>(.*?)</friendlyName>")
                        .find(xml ?: "")?.groupValues?.get(1) ?: loc,
                    xml)
            }
            ui {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Vyber DLNA server")
                    .setItems(names.map { it.second }.toTypedArray()) { _, i ->
                        mediaServerDesc = names[i].first
                        mediaServerName = names[i].second
                        prefs().edit()
                            .putString("dlnaServer", mediaServerDesc)
                            .putString("dlnaName", mediaServerName)
                            .apply()
                        initMediaServer(mediaServerDesc!!)
                    }
                    .setNegativeButton("Zrušiť", null)
                    .show()
            }
        }
    }

    private fun initMediaServer(descUrl: String) {
        scope.launch(Dispatchers.IO) {
            val control = readServiceControlUrl(descUrl,
                "urn:schemas-upnp-org:service:ContentDirectory")
            if (control == null) {
                ui { mediaStatusText.text = "Server neponúka ContentDirectory." }
                return@launch
            }
            cdsControlUrl = control
            ui { mediaServerText.text = "Server: $mediaServerName" }
            browseStack.clear()
            browseFolder("0", "Knižnica")
        }
    }

    private fun browseBack() {
        if (browseStack.size > 1) {
            browseStack.removeAt(browseStack.size - 1)
            val parent = browseStack[browseStack.size - 1]
            browseFolder(parent.first, parent.second, pushStack = false)
        }
    }

    private fun browseFolder(objectId: String, title: String, pushStack: Boolean = true) {
        val control = cdsControlUrl ?: return
        if (pushStack) browseStack.add(objectId to title)
        scope.launch(Dispatchers.IO) {
            val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
                "<s:Body><u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\">" +
                "<ObjectID>" + objectId + "</ObjectID>" +
                "<BrowseFlag>BrowseDirectChildren</BrowseFlag>" +
                "<Filter>*</Filter><StartingIndex>0</StartingIndex>" +
                "<RequestedCount>0</RequestedCount><SortCriteria></SortCriteria>" +
                "</u:Browse></s:Body></s:Envelope>"
            val response = soapRequest(control, "Browse",
                "urn:schemas-upnp-org:service:ContentDirectory:1#Browse", body) ?: ""
            val result = Regex("(?is)<Result>(.*?)</Result>")
                .find(response)?.groupValues?.get(1) ?: ""
            val entries = parseDidl(result)
            currentListing = entries
            ui {
                mediaPathText.text = "📚 " + browseStack.joinToString(" / ") { it.second }
                renderMediaList(entries)
            }
        }
    }

    private fun parseDidl(xml: String): List<MediaEntry> {
        val decoded = xml
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&")
        val containers = Regex("(?is)<container[^>]*?id=\"([^\"]+)\"[^>]*>(.*?)</container>")
            .findAll(decoded).map { c ->
                val title = Regex("(?i)<dc:title>(.*?)</dc:title>")
                    .find(c.groupValues[2])?.groupValues?.get(1) ?: "(priečinok)"
                MediaEntry(c.groupValues[1], title.trim(), true, null)
            }.toList()
        val items = Regex("(?is)<item[^>]*?id=\"([^\"]+)\"[^>]*>(.*?)</item>")
            .findAll(decoded).map { i ->
                val title = Regex("(?i)<dc:title>(.*?)</dc:title>")
                    .find(i.groupValues[2])?.groupValues?.get(1) ?: "(skladba)"
                val res = Regex("(?is)<res[^>]*>(.*?)</res>")
                    .find(i.groupValues[2])?.groupValues?.get(1)?.trim()
                MediaEntry(i.groupValues[1], title.trim(), false, res)
            }.toList()
        return containers + items
    }

    private fun renderMediaList(entries: List<MediaEntry>) {
        mediaList.removeAllViews()
        entries.forEach { e ->
            val btn = flatBtn((if (e.isContainer) "📁 " else "🎵 ") + e.title) {
                if (e.isContainer) {
                    browseFolder(e.id, e.title)
                } else {
                    playQueue(listOf(e), 0)
                }
            }
            btn.gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
            if (!e.isContainer) btn.background = roundedBg(C_CARD2, 12, C_STROKE)
            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(3)
            mediaList.addView(btn, lp)
        }
        if (entries.isEmpty()) {
            val t = TextView(this)
            t.text = "(prázdne)"
            t.setTextColor(C_DIM)
            mediaList.addView(t)
        }
    }

    private fun playCurrentFolder() {
        val tracks = currentListing.filter { !it.isContainer && it.url != null }
        if (tracks.isEmpty()) {
            ui { mediaStatusText.text = "V tomto priečinku nie sú skladby." }
            return
        }
        playQueue(tracks, 0)
    }

    private fun playQueue(tracks: List<MediaEntry>, startIndex: Int) {
        queueJob?.cancel()
        playQueue = tracks
        queueIdx = startIndex
        queueJob = scope.launch(Dispatchers.IO) {
            try {
                if (activeInput != "MEDIA SRV" && activeInput != "44FN" && activeInput != "47FN") {
                    sendSilent("44FN")
                    delay(1000)
                }
                while (isActive && queueIdx < playQueue.size) {
                    val track = playQueue[queueIdx]
                    val control = ensureAvtControl() ?: run {
                        ui { mediaStatusText.text = "UPnP: receiver nedostupný." }
                        return@launch
                    }
                    ui {
                        mediaStatusText.text = "▶ " + track.title +
                            "  (" + (queueIdx + 1) + "/" + playQueue.size + ")"
                    }
                    runCatching { soapStop(control) }
                    delay(250)
                    soapSetUri(control, track.url!!, track.title, isBroadcast = false)
                    delay(250)
                    soapPlay(control)

                    var started = false
                    var stoppedCount = 0
                    var noStart = 0
                    var trackFinished = false
                    while (isActive && !trackFinished) {
                        delay(2500)
                        val st = transportState(control)
                        when {
                            st == "PLAYING" -> { started = true; stoppedCount = 0 }
                            st == "TRANSITIONING" -> { started = true }
                            started && (st == "STOPPED" || st == "NO_MEDIA_PRESENT") -> {
                                stoppedCount++
                                if (stoppedCount >= 2) trackFinished = true
                            }
                            !started -> {
                                noStart++
                                if (noStart > 8) trackFinished = true
                            }
                        }
                    }
                    queueIdx++
                }
                ui { mediaStatusText.text = "✓ Prehrávanie dokončené" }
            } catch (_: Exception) { }
        }
    }

    // =====================================================================
    // UPnP NIZKOUROVNOVE VOLANIA (PRE KARTU 3 — MÉDIÁ)
    // =====================================================================

    private fun ssdpFindAll(st: String, timeoutMs: Int = 3500): List<String> {
        val result = linkedSetOf<String>()
        val msearch = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: " + st + "\r\n\r\n"
        runCatching {
            MulticastSocket().use { sock ->
                sock.soTimeout = timeoutMs
                val addr = InetAddress.getByName("239.255.255.250")
                val pktOut = DatagramPacket(msearch.toByteArray(), msearch.length, addr, 1900)
                sock.send(pktOut)
                sock.send(pktOut)
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val pkt = DatagramPacket(buf, buf.size)
                    try { sock.receive(pkt) } catch (_: Exception) { break }
                    val resp = String(pkt.data, 0, pkt.length, Charsets.US_ASCII)
                    Regex("(?i)LOCATION:\\s*(\\S+)").find(resp)?.groupValues?.get(1)
                        ?.let { result.add(it) }
                }
            }
        }
        return result.toList()
    }

    private fun ensureAvtControl(): String? {
        avtControlUrl?.let { return it }

        val allRenderers = ssdpFindAll("urn:schemas-upnp-org:device:MediaRenderer:1", 2500)
        val matched = allRenderers.firstOrNull { it.contains(host) }
        if (matched != null) {
            readServiceControlUrl(matched, "urn:schemas-upnp-org:service:AVTransport")
                ?.let { avtControlUrl = it; return it }
        }

        val fallbackLocations = listOf(
            "http://$host:49152/description.xml",
            "http://$host:49153/description.xml",
            "http://$host:49154/description.xml",
            "http://$host:8080/description.xml",
            "http://$host:60006/upnp/desc/aios_device/aios_device.xml"
        )
        for (loc in fallbackLocations) {
            val ctrl = readServiceControlUrl(loc, "urn:schemas-upnp-org:service:AVTransport")
            if (ctrl != null) {
                avtControlUrl = ctrl
                return ctrl
            }
        }
        return null
    }

    private fun readServiceControlUrl(descUrl: String, serviceUrn: String): String? {
        val xml = httpGet(descUrl) ?: return null
        val shortName = serviceUrn.substringAfterLast(':')
        val serviceBlock = Regex("(?is)<service>.*?" + shortName + ".*?</service>")
            .find(xml)?.value ?: return null
        val controlPath = Regex("(?is)<controlURL>(.*?)</controlURL>")
            .find(serviceBlock)?.groupValues?.get(1)?.trim() ?: return null
        if (controlPath.startsWith("http://", ignoreCase = true) ||
            controlPath.startsWith("https://", ignoreCase = true)) {
            return controlPath
        }
        val base = Regex("(?is)<URLBase>(.*?)</URLBase>")
            .find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?: URL(descUrl).let {
                val p = if (it.port != -1) ":${it.port}" else ""
                "${it.protocol}://${it.host}$p"
            }
        return base.trimEnd('/') + "/" + controlPath.trimStart('/')
    }

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 3500; conn.readTimeout = 3500
        BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }.getOrNull()

    private fun soapRequest(controlUrl: String, action: String,
                            soapAction: String, body: String): String? {
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"$soapAction\"")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
            it.write(body); it.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: return null)).use { it.readText() }
    }

    private fun soap(controlUrl: String, action: String, body: String): String? {
        return soapRequest(controlUrl, action,
            "urn:schemas-upnp-org:service:AVTransport:1#" + action, body)
    }

    private fun transportState(controlUrl: String): String {
        val resp = soapRequest(controlUrl, "GetTransportInfo",
            "urn:schemas-upnp-org:service:AVTransport:1#GetTransportInfo",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:GetTransportInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:GetTransportInfo></s:Body></s:Envelope>") ?: return ""
        return Regex("(?i)<CurrentTransportState>(.*?)</CurrentTransportState>")
            .find(resp)?.groupValues?.get(1) ?: ""
    }

    private fun soapSetUri(controlUrl: String, streamUrl: String,
                           title: String = "Media",
                           isBroadcast: Boolean = false): String? {
        val escUrl = streamUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val escTitle = title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        val lower = streamUrl.lowercase()
        val mime = when {
            lower.endsWith(".aac") || lower.contains("aac") -> "audio/aac"
            lower.endsWith(".flac") -> "audio/flac"
            lower.endsWith(".wav") -> "audio/wav"
            else -> "audio/mpeg"
        }
        val dlnaPn = if (mime == "audio/aac") "AAC_ISO" else "MP3"
        val upnpClass = if (isBroadcast)
            "object.item.audioItem.audioBroadcast"
        else
            "object.item.audioItem.musicTrack"
        val dlnaOp = if (isBroadcast) "00" else "01"
        val dlnaFlags = if (isBroadcast)
            "01700000000000000000000000000000"
        else
            "01500000000000000000000000000000"

        val didl = "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"0\" parentID=\"-1\" restricted=\"1\">" +
            "<dc:title>$escTitle</dc:title>" +
            "<upnp:class>$upnpClass</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:DLNA.ORG_PN=$dlnaPn;DLNA.ORG_OP=$dlnaOp;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=$dlnaFlags\">$escUrl</res>" +
            "</item></DIDL-Lite>"

        val escDidl = didl.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")

        return soap(controlUrl, "SetAVTransportURI",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><CurrentURI>" + escUrl + "</CurrentURI>" +
            "<CurrentURIMetaData>" + escDidl + "</CurrentURIMetaData>" +
            "</u:SetAVTransportURI></s:Body></s:Envelope>")
    }

    private fun soapPlay(controlUrl: String): String? {
        return soap(controlUrl, "Play",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><Speed>1</Speed>" +
            "</u:Play></s:Body></s:Envelope>")
    }

    private fun soapStop(controlUrl: String): String? {
        return soap(controlUrl, "Stop",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Stop></s:Body></s:Envelope>")
    }

    // =====================================================================
    // PRIPOJENIE A SPRAVA PRIKAZOV
    // =====================================================================

    private fun connect(silentFail: Boolean = false) {
        runCatching { socketRef.get()?.close() }
        queueJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 3000)
                sock.keepAlive = true
                outWriter = OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII)
                socketRef.set(sock)
                saveHostIp(host)
                ui {
                    connLine.text = "●  Pripojené: $host:$port"
                    connLine.setTextColor(C_GREEN)
                    statusText.text = ""
                }
                sendSilent("?P")
                sendSilent("?V")
                sendSilent("?F")
                sendSilent("?M")
                sendSilent("?S")
                sendSilent("?L")
                sendSilent("?FL")
                sendSilent("?GAP")
                listen(sock)
            } catch (e: Exception) {
                if (!silentFail) {
                    ui {
                        connLine.text = "○  Odpojené ($host)"
                        connLine.setTextColor(C_RED)
                        statusText.text = "Chyba pripojenia: ${e.message ?: "neznáma"} — klikni 🔍 pre vyhľadanie."
                    }
                }
            }
        }
    }

    private fun listen(sock: Socket) {
        scope.launch(Dispatchers.IO) {
            try {
                val reader =
                    BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.US_ASCII))
                while (isActive && sock.isConnected) {
                    val line = reader.readLine() ?: break
                    handleResponse(line.trim())
                }
            } catch (e: Exception) {
                ui {
                    connLine.text = "○  Odpojené ($host)"
                    connLine.setTextColor(C_RED)
                    statusText.text = "Spojenie prerušené (${e.message ?: "odpojené"})"
                }
            }
        }
    }

    private fun handleResponse(line: String) {
        ui {
            when {
                line.startsWith("VOL") -> {
                    val v = line.substring(3).trim().toIntOrNull() ?: return@ui
                    currentVol = v
                    updateVolumeCard()
                    volSlider.progress = v.coerceIn(0, volMax)
                }
                line.startsWith("MUT") -> {
                    isMuted = line.startsWith("MUT0")
                    updateVolumeCard()
                    updateMuteButton()
                }
                line.startsWith("FN") && line.length == 4 -> {
                    val code = line.substring(2) + "FN"
                    activeInput = inputs.entries.firstOrNull { it.value == code }?.key
                        ?: when (code) {
                            "19FN" -> "HDMI 1"
                            "20FN" -> "HDMI 2"
                            "21FN" -> "HDMI 3"
                            "22FN" -> "HDMI 4"
                            "23FN" -> "HDMI 5"
                            "24FN" -> "HDMI 6"
                            "47FN" -> "DMR"
                            "26FN" -> "NETWORK"
                            else -> code
                        }
                    updateInfoLine()
                    styleInputButtons()
                    updateMainDisplayHeader()
                }
                // Spätná väzba pre priestorový režim: SRxxxx (nastavený režim) alebo LMxxxx (aktuálny dekodér)
                line.startsWith("SR") && line.length >= 6 -> {
                    val code = line.substring(2, 6) + "SR"
                    val m = modes.entries.firstOrNull { it.value == code }
                    if (m != null) {
                        activeMode = m.key
                        updateInfoLine()
                        styleModeButtons()
                    }
                }
                line.startsWith("LM") && line.length >= 6 -> {
                    val lmHex = line.substring(2).trim()
                    val matched = when {
                        lmHex.endsWith("01") -> "STEREO"
                        lmHex.endsWith("05") -> "AUTO SURR."
                        lmHex.endsWith("06") || lmHex.endsWith("07") -> "DIRECT"
                        lmHex.endsWith("51") -> "ALC / PURE"
                        lmHex.contains("13") || lmHex.contains("02") -> "PLII MOVIE"
                        lmHex.contains("14") || lmHex.contains("03") -> "PLII MUSIC"
                        else -> activeMode
                    }
                    if (matched != null) {
                        activeMode = matched
                        updateInfoLine()
                        styleModeButtons()
                    }
                }
                line == "PWR0" -> { isOn = true; updatePowerButton() }
                line == "PWR2" || line == "PWR1" -> { isOn = false; updatePowerButton() }
                line.startsWith("E0") -> {
                    // Chybové kódy Pioneer (E02 = NOT AVAILABLE NOW, E04 = COMMAND ERROR, E06 = PARAMETER ERROR)
                    val errDesc = when (line) {
                        "E02" -> "Príkaz teraz nie je dostupný (E02)"
                        "E03" -> "Neplatný príkaz (E03)"
                        "E04" -> "Chyba príkazu (E04)"
                        "E06" -> "Neplatný parameter (E06)"
                        else -> "Odpoveď receivera: $line"
                    }
                    statusText.text = errDesc
                }
                line.startsWith("FL") && line.length >= 6 -> {
                    val txt = decodeFlHex(line.substring(4))
                    if (txt.isNotEmpty()) {
                        lastFlText = txt
                        if (::nowPlayingFl.isInitialized) {
                            nowPlayingFl.text = "Displej receivera: $txt"
                        }
                        updateMainDisplayHeader()
                    }
                }
                line.startsWith("GCH") || line.startsWith("GCP") -> {
                    if (line.length >= 5) {
                        nwScreenType = line.substring(3, 5)
                    }
                    if (nwScreenType == "01" || nwScreenType == "02") {
                        listScreenReady = true
                        isPlayingFavorite = false
                    } else if (nwScreenType == "04" || nwScreenType == "05") {
                        isPlayingFavorite = true
                    }
                }
                line.startsWith("GDH") || line.startsWith("GDP") -> {
                    listScreenReady = true
                }
                line.startsWith("GHH") || line.startsWith("GHP") || line.startsWith("GIH") || line.startsWith("GIP") -> {
                    val raw = line.substring(3).trim()
                    if (raw.length >= 4) {
                        playTimeText = raw
                        updateNowPlayingCardFromMeta()
                    }
                }
                line.startsWith("GEH") || line.startsWith("GEP") -> {
                    // GEP + riadok(01..08) + fokus(0/1) + dataType(2 znaky) + "Text"
                    val idx = line.substring(3, 5).toIntOrNull()
                    val title = line.substringAfter("\"", "").substringBeforeLast("\"", "")
                    val typeCode = if (line.length >= 8) line.substring(6, 8) else "01"
                    val typeNum = typeCode.toIntOrNull() ?: 1

                    if (idx != null) {
                        val isMetadataLine = typeNum >= 10 || isPlayingFavorite ||
                            nwScreenType == "04" || nwScreenType == "05" || looksLikeMetadata(title)

                        if (isMetadataLine) {
                            if (title.isNotEmpty()) {
                                playMetaLines[idx] = typeCode to title
                            } else {
                                playMetaLines.remove(idx)
                            }
                            updateNowPlayingCardFromMeta()
                        } else if (isSyncingFavorites || favStations.size < 8) {
                            listScreenReady = true
                            if (title.isNotEmpty() && !title.startsWith("[")) {
                                favStations[idx] = title
                                saveCachedFavorites()
                                renderFavoriteTable()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun send(cmd: String) = sendSilent(cmd)

    private fun sendSilent(cmd: String) {
        scope.launch(Dispatchers.IO) {
            try {
                outWriter?.let { it.write(cmd + "\r\n"); it.flush() }
            } catch (e: Exception) {
                ui { statusText.text = "Chyba odosielania: " + e.message }
            }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread { block() }
}

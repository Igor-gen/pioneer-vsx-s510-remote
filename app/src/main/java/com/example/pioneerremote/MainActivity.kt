// ============================================================================
// Pioneer VSX-S510 Remote — verzia 19
// - Opravené ovládanie USB kľúča (vstup 17FN):
//     1. Odstránené prepínanie 38FN -> 17FN pri USB operáciách, pretože opätovné
//        zvolenie 17FN na VSX-S510 spúšťa Auto-Play prvého adresára!
//     2. Spoľahlivý prechod z Play obrazovky (04) do zoznamu adresárov (01)
//        pomocou 20NW/02IP (Stop) + 28NW (Top Menu) + 19IP (View) + 31NW/18IP (Return).
//     3. Presný výber adresára/súboru č. X: posun okna zoznamu cez %05dGGP na riadok X
//        + 01GHP + 30NW/17IP (Enter), takže sa nikdy nespustí 1. adresár namiesto zvoleného.
//     4. Trvalé filtrovanie metadát (typeNum >= 10 / Play screen), aby sa údaje
//        o hrajúcej skladbe z 1. adresára nikdy neuložili ako obsah iných adresárov.
// ============================================================================

package com.example.pioneerremote

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
    private var favJob: Job? = null
    private var usbJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ===== Hlavný displej a ovládacie prvky =====
    private lateinit var ipEdit: EditText
    private lateinit var mainStationBig: TextView
    private lateinit var mainShowBitrate: TextView
    private lateinit var infoLine: TextView
    private lateinit var connLine: TextView
    private lateinit var statusText: TextView
    private lateinit var volumeLabel: TextView
    private lateinit var volBar: ProgressBar
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

    // Príkazy pre priestorový zvuk na Pioneer VSX-S510
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

    // ===== KARTA RÁDIO (Iba tlačidlo FAVORITES + výsuvný zoznam predvolieb) =====
    private lateinit var favToggleBtn: Button
    private lateinit var favDrawerCard: LinearLayout
    private lateinit var favDrawerStatus: TextView
    private lateinit var favTableContainer: LinearLayout
    private var isFavListExpanded: Boolean = false

    private val favStations = linkedMapOf<Int, String>()
    private val playMetaLines = mutableMapOf<Int, Pair<String, String>>()
    @Volatile private var isSyncingFavorites: Boolean = false
    @Volatile private var isPlayingFavorite: Boolean = false
    @Volatile private var nwScreenType: String = ""
    @Volatile private var nwScreenName: String = ""
    @Volatile private var listScreenReady: Boolean = false
    private var playTimeText: String = ""
    private var lastFlText: String = ""

    // ===== KARTA MÉDIÁ: PREPÍNAČ USB KĽÚČ / DLNA SERVER =====
    private var isUsbMediaMode: Boolean = true
    private lateinit var modeUsbBtn: Button
    private lateinit var modeDlnaBtn: Button
    private lateinit var usbContainer: LinearLayout
    private lateinit var dlnaContainer: LinearLayout

    // ===== STROMOVÁ PAMÄŤ (CACHE) USB KĽÚČA + DVOJKLIK A OZNAČENIE =====
    private data class UsbNode(
        val indexInParent: Int, // 1-based index v nadradenom priečinku
        val title: String,
        val isFolder: Boolean,
        val pathKey: String     // napr. "/1", "/1/3"
    )

    private val usbTreeCache = linkedMapOf<String, MutableList<UsbNode>>()
    private val usbCurrentPath = mutableListOf<Pair<Int, String>>()
    private var usbSelectedPathKey: String? = null
    private val usbNodeButtons = mutableMapOf<String, Button>()

    // Detekcia dvojkliku cez SystemClock.elapsedRealtime()
    private var lastTapKey: String? = null
    private var lastTapTime: Long = 0L

    // Nízkoúrovňové premenné pre čítanie zoznamu z receivera (GEP01..GEP08)
    private val rawPageLines = Collections.synchronizedMap(linkedMapOf<Int, Pair<String, String>>())
    @Volatile private var rawRangeStart: Int = 1
    @Volatile private var rawRangeEnd: Int = 0
    @Volatile private var rawTotalItems: Int = 0
    @Volatile private var rawPageUpdatedAt: Long = 0L
    @Volatile private var rawGdhUpdatedAt: Long = 0L
    @Volatile private var isUsbPlayingNow: Boolean = false

    private lateinit var usbPathText: TextView
    private lateinit var usbSelectedText: TextView
    private lateinit var usbStatusText: TextView
    private lateinit var usbListLayout: LinearLayout
    private lateinit var usbPlayBtn: Button
    private lateinit var usbStopBtn: Button

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
        isUsbMediaMode = p.getBoolean("isUsbMediaMode", true)
        loadCachedFavorites()
        loadCachedUsbTree()
        buildUI()

        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("pioneerSSDP").apply {
            setReferenceCounted(false); acquire()
        }

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

    private fun loadCachedUsbTree() {
        val raw = prefs().getString("usbTreeJsonV19", null) ?: return
        runCatching {
            val rootObj = JSONObject(raw)
            usbTreeCache.clear()
            val keys = rootObj.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val arr = rootObj.getJSONArray(k)
                val list = mutableListOf<UsbNode>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    list.add(
                        UsbNode(
                            indexInParent = o.getInt("idx"),
                            title = o.getString("title"),
                            isFolder = o.getBoolean("isFolder"),
                            pathKey = o.getString("pathKey")
                        )
                    )
                }
                usbTreeCache[k] = list
            }
        }
    }

    private fun saveCachedUsbTree() {
        runCatching {
            val rootObj = JSONObject()
            usbTreeCache.forEach { (k, list) ->
                val arr = JSONArray()
                list.forEach { node ->
                    val o = JSONObject()
                    o.put("idx", node.indexInParent)
                    o.put("title", node.title)
                    o.put("isFolder", node.isFolder)
                    o.put("pathKey", node.pathKey)
                    arr.put(o)
                }
                rootObj.put(k, arr)
            }
            prefs().edit().putString("usbTreeJsonV19", rootObj.toString()).apply()
        }
    }

    private fun looksLikeMetadata(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return true
        if (Regex("(?i).*\\b(kbps|khz|wma|pcm|buffer)\\b.*").matches(t)) return true
        if (Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$").matches(t)) return true
        if (Regex("^\\d+\\s*%$").matches(t)) return true
        return false
    }

    private fun isLikelyUsbFolder(typeCode: String, title: String): Boolean {
        val lower = title.trim().lowercase()
        val audioExts = listOf(".mp3", ".flac", ".wav", ".m4a", ".aac", ".wma", ".ogg", ".alac", ".aif", ".aiff", ".pls", ".m3u")
        if (audioExts.any { lower.endsWith(it) }) return false
        return typeCode == "00" || typeCode == "01"
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

        // Vizuálny zelený pásik úrovne hlasitosti (neinteraktívny ProgressBar)
        val volBox = LinearLayout(this)
        volBox.orientation = LinearLayout.HORIZONTAL
        volBox.gravity = Gravity.CENTER_VERTICAL
        volBox.setPadding(dp(6), dp(8), dp(6), dp(2))

        volumeLabel = TextView(this)
        volumeLabel.text = "🔊 —"
        volumeLabel.textSize = 13f
        volumeLabel.typeface = Typeface.DEFAULT_BOLD
        volumeLabel.setTextColor(C_GREEN)
        volumeLabel.setPadding(0, 0, dp(10), 0)

        volBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        volBar.max = volMax
        volBar.progress = 0
        volBar.progressTintList = ColorStateList.valueOf(C_GREEN)
        volBar.progressBackgroundTintList = ColorStateList.valueOf(C_STROKE)
        volBar.isClickable = false
        volBar.isFocusable = false

        val barLp = LinearLayout.LayoutParams(0, dp(10), 1f)
        volBox.addView(volumeLabel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        volBox.addView(volBar, barLp)

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
                if (position == 2 && isUsbMediaMode) {
                    renderUsbCachedFolder()
                    if (usbTreeCache.isEmpty()) {
                        scanEntireUsbTree()
                    }
                }
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

        // Tlačidlá hlasitosti VOL − / VOL +
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
    // SCREEN 1 — OVLADANIE (VSTUPY BEZ HDMI 1-6 + PRIESTOROVY ZVUK)
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
    // SCREEN 2 — RADIO
    // =====================================================================

    private fun buildScreenRadio(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        favToggleBtn = flatBtn("⭐ FAVORITES (Načítať predvoľby ▼)") {
            onFavoritesButtonClicked()
        }
        favToggleBtn.background = roundedBg(C_CYAN_D, 12, C_CYAN)
        favToggleBtn.setTextColor(C_WHITE)
        favToggleBtn.typeface = Typeface.DEFAULT_BOLD
        favToggleBtn.textSize = 14f
        favToggleBtn.setPadding(dp(12), dp(12), dp(12), dp(12))

        val btnLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        btnLp.setMargins(dp(2), dp(4), dp(2), dp(4))
        page.addView(favToggleBtn, btnLp)

        // Výsuvný kontajner so zoznamom predvolieb
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

        page.addView(hint("← swipe na ovládanie / médiá →"))
        return sv
    }

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
            favToggleBtn.text = if (expanded)
                "⭐ FAVORITES (Zoznam otvorený ▲)"
            else
                "⭐ FAVORITES (Načítať predvoľby ▼)"
        }
    }

    private fun onFavoritesButtonClicked() {
        favJob?.cancel()
        setFavDrawerExpanded(true)
        favDrawerStatus.text = "⏳ Prepínam NETRADIO → FAVORITES a načítavam predvoľby z receivera…"
        renderFavoriteTable()

        favJob = scope.launch(Dispatchers.IO) {
            try {
                isSyncingFavorites = true
                isPlayingFavorite = false
                listScreenReady = false

                if (!isOn) {
                    sendSilent("PO")
                    delay(2500)
                }

                // 1. Krátke prepnutie na NETRADIO (38FN) na garantované prerušenie prehrávania
                sendSilent("38FN")
                ui {
                    activeInput = "NETRADIO"
                    updateInfoLine()
                    styleInputButtons()
                }
                delay(700)

                // 2. Okamžité prepnutie späť na FAVORITES (45FN)
                sendSilent("45FN")
                sendSilent("?F")
                ui {
                    activeInput = "FAVORITES"
                    updateInfoLine()
                    styleInputButtons()
                }

                // 3. Počkáme, kým receiver otvorí koreňový zoznam Favorites, a vyžiadame položky
                for (step in 0 until 12) {
                    delay(200)
                    if (step == 3 || step == 7) {
                        sendSilent("?GAP")
                        sendSilent("?GAH")
                    }
                    if (listScreenReady) break
                }

                sendSilent("?GAP")
                sendSilent("?GAH")
                sendSilent("?FL")
                delay(700)
                sendSilent("?GAP")
                delay(400)

                ui {
                    favDrawerStatus.text = "Vyber predvoľbu (po kliknutí sa zoznam zasunie a stanica začne hrať):"
                    renderFavoriteTable()
                }
            } finally {
                isSyncingFavorites = false
            }
        }
    }

    private fun onFavoritePresetSelected(index: Int, name: String) {
        favJob?.cancel()
        activeFavIndex = index
        val stationTitle = favStations[index] ?: name
        activeStationName = stationTitle

        setFavDrawerExpanded(false)

        playMetaLines.clear()
        playTimeText = ""
        updateMainDisplayHeader()

        favJob = scope.launch(Dispatchers.IO) {
            isSyncingFavorites = false
            isPlayingFavorite = true

            val ghpCmd = String.format("%02dGHP", index)
            sendSilent(ghpCmd)
            delay(350)
            sendSilent("30NW")
            sendSilent("10NW")

            delay(1500)
            sendSilent("?GAP")
            sendSilent("?GAH")
            sendSilent("?FL")
            delay(1500)
            sendSilent("?GAP")
            sendSilent("?FL")
        }
    }

    // =====================================================================
    // SCREEN 3 — MÉDIÁ (PREPÍNAČ: 🔌 USB KĽÚČ / 🌐 DLNA SERVER)
    // =====================================================================

    private fun buildScreenMedia(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Horný prepínač režimu: 🔌 USB KĽÚČ vs. 🌐 DLNA SERVER
        modeUsbBtn = flatBtn("🔌 USB KĽÚČ") {
            switchMediaMode(usbMode = true)
        }
        modeDlnaBtn = flatBtn("🌐 DLNA SERVER") {
            switchMediaMode(usbMode = false)
        }
        modeUsbBtn.textSize = 13f
        modeDlnaBtn.textSize = 13f
        val switchRow = rowOf(listOf(modeUsbBtn, modeDlnaBtn))
        val switchLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        switchLp.bottomMargin = dp(6)
        page.addView(switchRow, switchLp)

        // =================================================================
        // KONTAJNER 1: 🔌 USB KĽÚČ
        // =================================================================
        usbContainer = LinearLayout(this)
        usbContainer.orientation = LinearLayout.VERTICAL

        val usbScanAllBtn = flatBtn("🔄 NAČÍTAŤ CELÉ USB DO PAMÄTE") {
            scanEntireUsbTree()
        }
        usbScanAllBtn.background = roundedBg(C_CYAN_D, 12, C_CYAN)
        usbScanAllBtn.setTextColor(C_WHITE)
        usbScanAllBtn.typeface = Typeface.DEFAULT_BOLD
        usbContainer.addView(rowOf(listOf(usbScanAllBtn)))

        // Tlačidlá ▶ PLAY, 📂 OTVORIŤ a ⏹ STOP
        usbPlayBtn = flatBtn("▶ PLAY") {
            playSelectedUsbNode()
        }
        usbPlayBtn.background = roundedBg(C_GREEN, 12, C_GREEN)
        usbPlayBtn.setTextColor(C_ON_ACCENT)
        usbPlayBtn.typeface = Typeface.DEFAULT_BOLD

        val usbOpenFolderBtn = flatBtn("📂 OTVORIŤ") {
            openSelectedUsbFolder()
        }
        usbOpenFolderBtn.background = roundedBg(C_CYAN_D, 12, C_CYAN)
        usbOpenFolderBtn.setTextColor(C_WHITE)
        usbOpenFolderBtn.typeface = Typeface.DEFAULT_BOLD

        usbStopBtn = flatBtn("⏹ STOP") {
            stopUsbPlayback()
        }
        usbStopBtn.background = roundedBg(C_RED, 12, C_RED)
        usbStopBtn.setTextColor(C_WHITE)
        usbStopBtn.typeface = Typeface.DEFAULT_BOLD

        usbContainer.addView(rowOf(listOf(usbPlayBtn, usbOpenFolderBtn, usbStopBtn)))

        val uPrev = flatBtn("⏮ Predch.") { send("12NW"); send("03IP") }
        val uPause = flatBtn("⏸ Pauza") { send("11NW"); send("01IP") }
        val uNext = flatBtn("⏭ Ďalšia") { send("13NW"); send("04IP") }
        val uRep = flatBtn("🔁 Repeat") { send("34NW"); send("07IP") }
        usbContainer.addView(rowOf(listOf(uPrev, uPause, uNext, uRep)))

        usbPathText = TextView(this)
        usbPathText.text = "📂 Adresár: / (Koreň USB)"
        usbPathText.textSize = 12f
        usbPathText.typeface = Typeface.DEFAULT_BOLD
        usbPathText.setTextColor(C_CYAN)
        usbPathText.setPadding(dp(4), dp(8), dp(4), dp(2))
        usbContainer.addView(usbPathText)

        usbSelectedText = TextView(this)
        usbSelectedText.text = "✓ Označené: (nič) — 1× klik = označiť, 2× klik = otvoriť adresár / .."
        usbSelectedText.textSize = 12f
        usbSelectedText.setTextColor(C_ORANGE)
        usbSelectedText.setPadding(dp(4), 0, dp(4), dp(2))
        usbContainer.addView(usbSelectedText)

        usbStatusText = TextView(this)
        usbStatusText.text = if (usbTreeCache.isNotEmpty())
            "Obsah USB je načítaný v pamäti (${usbTreeCache.values.sumOf { it.size }} položiek)."
        else
            "Klikni na 🔄 NAČÍTAŤ CELÉ USB DO PAMÄTE pre zoskenovanie kľúča."
        usbStatusText.textSize = 11f
        usbStatusText.setTextColor(C_SLATE)
        usbStatusText.setPadding(dp(4), 0, dp(4), dp(6))
        usbContainer.addView(usbStatusText)

        usbListLayout = LinearLayout(this)
        usbListLayout.orientation = LinearLayout.VERTICAL
        usbContainer.addView(usbListLayout)
        renderUsbCachedFolder()

        page.addView(usbContainer)

        // =================================================================
        // KONTAJNER 2: 🌐 DLNA SERVER
        // =================================================================
        dlnaContainer = LinearLayout(this)
        dlnaContainer.orientation = LinearLayout.VERTICAL

        val srv

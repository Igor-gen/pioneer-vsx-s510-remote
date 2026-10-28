// ============================================================================
// Pioneer VSX-S510 Remote — verzia 6 (FAV tlačidlo + Rozšírené rádiá s fallbackmi)
// - Tmavý dizajn podľa náhľadu (karty, zaoblené tlačidlá, cyan/orange akcent)
// - Posuvník hlasitosti: posiela VU/VD až po pustení palca, zrýchlená odozva (50 ms)
// - 🔍 vyhľadanie receivera: sken podsiete telefónu (port 8102 + overenie ?V)
// - MUTE ako prepínač (sleduje MUT0/MUT1 spätnú väzbu)
// - Zvýraznenie aktívneho vstupu / režimu / rádia
// - Screen 2:
//     * Rozšírený zoznam slovenských a českých rádií s HTTP streammi a fallbackmi na nižší bitrate
//     * Tlačidlo ⭐ +FAV (pridanie/odstránenie aktuálnej skladby/stanice do interného priečinka Favorites - 30NW)
//     * Doplnené DIDL-Lite metadáta a automatické prepínanie na MEDIA SERVER / DMR
//     * Priame TCP ovládanie pre vTuner NETRADIO (38FN) a FAVORITES (45FN)
// - Screen 3: DLNA prehliadač + album fronta s DIDL-Lite metadátami
// ----------------------------------------------------------------------------
// BUILD: GitHub Actions — Android Studio / Gradle.
// Poznámka: AndroidManifest.xml musí mať theme @style/Theme.AppCompat.NoActionBar
// a povolené usesCleartextTraffic="true" v <application> pre HTTP komunikáciu!
// ============================================================================

package com.example.pioneerremote

import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
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

    // ===== farby (tmavy dizajn ako v nahlade) =====
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
    private var activeRadio: String? = null
    private var seekJob: Job? = null
    private var multicastLock: WifiManager.MulticastLock? = null

    // ===== UI =====
    private lateinit var ipEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var volumeBig: TextView
    private lateinit var infoLine: TextView
    private lateinit var radioLine: TextView
    private lateinit var connLine: TextView
    private lateinit var volSlider: SeekBar
    private lateinit var powerBtn: Button
    private lateinit var muteBtn: Button
    private val inputButtons = mutableMapOf<String, Button>()
    private val modeButtons = mutableMapOf<String, Button>()
    private val radioButtons = mutableMapOf<String, Button>()

    private val inputs = linkedMapOf(
        "BD" to "25FN", "CD" to "01FN", "DVR/BDR" to "15FN",
        "DVD" to "04FN", "IPOD/USB" to "17FN", "SAT/CBL" to "05FN",
        "TUNER" to "02FN", "VIDEO 1" to "10FN", "VIDEO 2" to "14FN",
        "MEDIA SRV" to "44FN", "NETRADIO" to "38FN", "FAVORITES" to "45FN",
        "HDMI 1" to "19FN", "HDMI 2" to "20FN", "HDMI 3" to "21FN",
        "HDMI 4" to "22FN", "HDMI 5" to "23FN", "HDMI 6" to "24FN"
    )

    private val modes = linkedMapOf(
        "STEREO" to "0001SR",
        "DIRECT" to "0004SR",
        "PURE DIRECT" to "0006SR",
        "AUTO SURR." to "0007SR",
        "PLII MOVIE" to "0016SR",
        "PLII MUSIC" to "0017SR"
    )

    // Rozšírený zoznam SK a CZ rádií s podporou viacerých alternatívnych URL (priorita vysoký bitrate -> fallback na nižší)
    private val radios = linkedMapOf(
        "Rádio Slovensko" to listOf("http://icecast.stv.livebox.sk/slovensko_256.mp3", "http://icecast.stv.livebox.sk/slovensko_128.mp3"),
        "Rádio_FM" to listOf("http://icecast.stv.livebox.sk/fm_256.mp3", "http://icecast.stv.livebox.sk/fm_128.mp3"),
        "Rádio Devín" to listOf("http://icecast.stv.livebox.sk/devin_256.mp3", "http://icecast.stv.livebox.sk/devin_128.mp3"),
        "Rádio Regina BA" to listOf("http://icecast.stv.livebox.sk/regina_ba_256.mp3", "http://icecast.stv.livebox.sk/regina_ba_128.mp3"),
        "Rádio Expres" to listOf("http://stream.bauermedia.sk/expres-hi.mp3", "http://stream.bauermedia.sk/expres.mp3"),
        "Európa 2" to listOf("http://stream.bauermedia.sk/europa2-hi.mp3", "http://stream.bauermedia.sk/europa2.mp3"),
        "Rádio Melody" to listOf("http://stream.bauermedia.sk/melody-hi.mp3", "http://stream.bauermedia.sk/melody.mp3"),
        "Rádio Rock" to listOf("http://stream.bauermedia.sk/rock-hi.mp3", "http://stream.bauermedia.sk/rock.mp3"),
        "Fun Rádio" to listOf("http://stream.funradio.sk:8000/fun192.mp3", "http://stream.funradio.sk:8000/fun128.mp3"),
        "Rádio Vlna" to listOf("http://stream.radiovlna.sk/vlna128.mp3"),
        "Rádio Jemné" to listOf("http://stream.jemne.sk/jemne128.mp3"),
        "Rádio Lumen" to listOf("http://stream.lumen.sk:8000/lumen_128.mp3"),
        "Best FM" to listOf("http://stream.bestfm.sk:8000/bestfm128.mp3"),
        "ČRo Radiožurnál" to listOf("http://amp.cesnet.cz:8000/cro1.mp3", "http://rozhlas.stream/radiozurnal_high.aac"),
        "ČRo Dvojka" to listOf("http://amp.cesnet.cz:8000/cro2.mp3"),
        "ČRo Vltava" to listOf("http://amp.cesnet.cz:8000/cro3.mp3"),
        "ČRo Jazz" to listOf("http://amp.cesnet.cz:8000/cro-jazz.mp3"),
        "SomaFM Groove Salad" to listOf("http://ice1.somafm.com/groovesalad-128-mp3"),
        "SomaFM Lush" to listOf("http://ice1.somafm.com/lush-128-mp3"),
        "Radio Paradise" to listOf("http://stream.radioparadise.com/mp3-192", "http://stream.radioparadise.com/mp3-128")
    )

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
        buildUI()
        connect()
        testStreams()
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("pioneerSSDP").apply {
            setReferenceCounted(false); acquire()
        }
        if (mediaServerDesc != null) initMediaServer(mediaServerDesc!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
        runCatching { socketRef.get()?.close() }
        multicastLock?.release()
    }

    private fun prefs() = getSharedPreferences("pioneer", MODE_PRIVATE)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

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
        b.setPadding(dp(2), dp(8), dp(2), dp(8))
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

    private fun makeGrid(buttons: List<Button>): GridLayout {
        val g = GridLayout(this)
        g.columnCount = 3
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
        tv.setPadding(0, dp(14), 0, dp(6))
        return tv
    }

    private fun hint(t: String): TextView {
        val tv = TextView(this)
        tv.text = t
        tv.textSize = 10f
        tv.setTextColor(C_DIM)
        tv.gravity = Gravity.CENTER
        tv.setPadding(0, dp(16), 0, dp(8))
        return tv
    }

    // =====================================================================
    // VYTVORENIE UI
    // =====================================================================

    private fun buildUI() {
        val pad = dp(14)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(C_BG)
        root.setPadding(pad, dp(10), pad, 0)

        // ZAP / VYP uplne hore, kazde na polovicu sirky
        powerBtn = flatBtn("⏻ ZAP") { send("PO"); sendSilent("?P") }
        val vypBtn = flatBtn("⏻ VYP") { send("PF"); sendSilent("?P") }
        powerBtn.textSize = 14f; vypBtn.textSize = 14f
        root.addView(rowOf(listOf(powerBtn, vypBtn)))

        val title = TextView(this)
        title.text = "Pioneer Remote"
        title.textSize = 20f
        title.typeface = Typeface.DEFAULT_BOLD
        title.setTextColor(C_WHITE)
        title.gravity = Gravity.CENTER
        root.addView(title)
        val sub = TextView(this)
        sub.text = "VSX-S510"
        sub.textSize = 11f
        sub.setTextColor(C_CYAN)
        sub.gravity = Gravity.CENTER
        sub.setPadding(0, 0, 0, dp(8))
        root.addView(sub)

        // riadok: IP + hladanie + pripojenie
        val top = LinearLayout(this)
        top.orientation = LinearLayout.HORIZONTAL
        ipEdit = EditText(this)
        ipEdit.setText(host)
        ipEdit.hint = "IP adresa receivera"
        ipEdit.inputType = InputType.TYPE_CLASS_PHONE
        ipEdit.setTextColor(C_WHITE)
        ipEdit.setHintTextColor(C_DIM)
        ipEdit.textSize = 13f
        ipEdit.background = roundedBg(C_CARD2, 10, C_STROKE)
        ipEdit.setPadding(dp(10), dp(8), dp(10), dp(8))
        top.addView(ipEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val scanBtn = pillBtn("🔍", C_BTN, C_WHITE) { discoverReceivers() }
        top.addView(scanBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val connBtn = pillBtn("Pripoj", C_CYAN, C_ON_ACCENT) { connectPressed() }
        top.addView(connBtn, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top)

        // stavova karta
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.setPadding(dp(12), dp(10), dp(12), dp(10))
        card.background = roundedBg(C_CARD, 18, C_CYAN_D)
        volumeBig = TextView(this)
        volumeBig.text = "🔊  —"
        volumeBig.textSize = 22f
        volumeBig.typeface = Typeface.DEFAULT_BOLD
        volumeBig.setTextColor(C_WHITE)
        volumeBig.gravity = Gravity.CENTER
        infoLine = TextView(this)
        infoLine.text = "Vstup: —   ·   Režim: —"
        infoLine.textSize = 13f
        infoLine.setTextColor(C_SLATE)
        infoLine.gravity = Gravity.CENTER
        radioLine = TextView(this)
        radioLine.text = "Rádio: —"
        radioLine.textSize = 13f
        radioLine.setTextColor(C_DIM)
        radioLine.gravity = Gravity.CENTER
        connLine = TextView(this)
        connLine.text = "○  Odpojené"
        connLine.textSize = 11f
        connLine.setTextColor(C_RED)
        connLine.gravity = Gravity.CENTER
        statusText = TextView(this)
        statusText.text = ""
        statusText.textSize = 11f
        statusText.setTextColor(C_DIM)
        statusText.gravity = Gravity.CENTER
        card.addView(volumeBig)
        card.addView(infoLine)
        card.addView(radioLine)
        card.addView(connLine)
        card.addView(statusText)
        val cardLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        cardLp.topMargin = dp(8)
        root.addView(card, cardLp)

        // tabs
        val tabDefs = listOf("🎚 OVLÁDANIE", "📻 RÁDIO", "💽 MÉDIÁ")
        val tabBtns = tabDefs.map { t -> pillBtn(t, C_BTN, C_SLATE) { } }
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabBtns.forEach { b ->
            tabs.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val tabsLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        tabsLp.topMargin = dp(8)
        root.addView(tabs, tabsLp)

        // swipe screeny
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

        // MUTE / VSTUP pod prepinacmi screenov, kazde na polovicu sirky
        muteBtn = flatBtn("🔇 MUTE") { toggleMute() }
        val vstupBtn = flatBtn("VSTUP ▶") { send("FU"); sendSilent("?F") }
        muteBtn.textSize = 14f; vstupBtn.textSize = 14f
        val muteLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        muteLp.topMargin = dp(8)
        root.addView(rowOf(listOf(muteBtn, vstupBtn)), muteLp)

        // VOL − / VOL + pod nimi
        val volMinus = flatBtn("VOL −") { send("VD"); sendSilent("?V") }
        val volPlus = flatBtn("VOL +") { send("VU"); sendSilent("?V") }
        volMinus.textSize = 14f; volPlus.textSize = 14f
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
        host = ipEdit.text.toString().trim()
        avtControlUrl = null
        prefs().edit().putString("host", host).apply()
        connect()
    }

    // =====================================================================
    // SCREEN 1 — OVLADANIE
    // =====================================================================

    private fun buildScreenHome(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val sc = LinearLayout(this)
        sc.orientation = LinearLayout.HORIZONTAL
        sc.gravity = Gravity.CENTER_VERTICAL
        sc.setPadding(dp(12), dp(4), dp(12), dp(4))
        sc.background = roundedBg(C_CARD2, 14)
        val lblMin = TextView(this)
        lblMin.text = "−80"; lblMin.textSize = 12f; lblMin.setTextColor(C_DIM)
        val lblMax = TextView(this)
        lblMax.text = "+80"; lblMax.textSize = 12f; lblMax.setTextColor(C_DIM)
        volSlider = SeekBar(this)
        volSlider.max = volMax
        volSlider.progressTintList = ColorStateList.valueOf(C_CYAN)
        volSlider.thumbTintList = ColorStateList.valueOf(C_CYAN)
        volSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) volumeBig.text = volText(p)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { seekVolume(s?.progress ?: 0) }
        })
        sc.addView(lblMin, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sc.addView(volSlider, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        sc.addView(lblMax, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val scLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        scLp.topMargin = dp(10)
        page.addView(sc, scLp)

        page.addView(sectionLabel("VÝBER VSTUPU"))
        val ib = mutableListOf<Button>()
        inputs.forEach { (name, code) ->
            val b = flatBtn(name) {
                send(code); sendSilent("?F")
                activeInput = name
                updateInfoLine(); styleInputButtons()
            }
            inputButtons[name] = b
            ib.add(b)
        }
        page.addView(makeGrid(ib))

        page.addView(sectionLabel("PRIESTOROVÝ REŽIM"))
        val mb = mutableListOf<Button>()
        modes.forEach { (name, code) ->
            val b = flatBtn(name) {
                send(code)
                activeMode = name
                updateInfoLine(); styleModeButtons()
            }
            modeButtons[name] = b
            mb.add(b)
        }
        page.addView(makeGrid(mb))
        page.addView(hint("← swipe na rádio →"))
        return sv
    }

    // =====================================================================
    // SCREEN 2 — RADIO (UPnP PUSH + FAV + VSTAVANE NETRADIO / FAVORITES)
    // =====================================================================

    private fun buildScreenRadio(): ScrollView {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        page.setPadding(0, dp(6), 0, dp(8))
        val sv = ScrollView(this)
        sv.addView(page, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        page.addView(sectionLabel("INTERNETOVÉ RÁDIO (UPnP / DLNA Push)"))
        
        val favAddBtn = flatBtn("⭐ +FAV (Uložiť do Favorites)") {
            send("30NW")
            statusText.text = "Uložené do Favorites (30NW)"
        }
        favAddBtn.background = roundedBg(C_CYAN_D, 12, C_CYAN)
        favAddBtn.setTextColor(C_WHITE)
        val favLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        favLp.bottomMargin = dp(6)
        page.addView(favAddBtn, favLp)

        val rb = mutableListOf<Button>()
        radios.forEach { (name, urls) ->
            val b = flatBtn(name) {
                activeRadio = name
                styleRadioButtons()
                playRadioWithFallback(name, urls)
            }
            radioButtons[name] = b
            rb.add(b)
        }
        page.addView(makeGrid(rb))

        val stopB = flatBtn("⏹ ZASTAVIŤ STREAM") {
            activeRadio = null
            styleRadioButtons()
            stopRadio()
        }
        stopB.background = roundedBg(C_RED, 12, C_RED)
        stopB.setTextColor(C_WHITE)
        val stopLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT)
        stopLp.topMargin = dp(10)
        page.addView(stopB, stopLp)

        page.addView(sectionLabel("INTERNÉ RÁDIO RECEIVERA (vTuner / Favorites)"))
        val netB = flatBtn("NETRADIO (38FN)") {
            send("38FN"); sendSilent("?F")
            activeRadio = "NETRADIO"
            styleRadioButtons()
        }
        val favB = flatBtn("FAVORITES (45FN)") {
            send("45FN"); sendSilent("?F")
            activeRadio = "FAVORITES"
            styleRadioButtons()
        }
        val mSrvB = flatBtn("MEDIA SRV (44FN)") {
            send("44FN"); sendSilent("?F")
            activeRadio = "MEDIA SRV"
            styleRadioButtons()
        }
        page.addView(rowOf(listOf(netB, favB, mSrvB)))

        val nwUp = flatBtn("▲ HORE") { send("00NW") }
        val nwDown = flatBtn("▼ DOLE") { send("01NW") }
        val nwEnt = flatBtn("⏎ VYBRAŤ") { send("13NW") }
        val nwRet = flatBtn("↩ SPÄŤ") { send("14NW") }
        val nwPlay = flatBtn("▶ PLAY") { send("10NW") }
        val nwStop = flatBtn("⏹ STOP") { send("20NW") }
        page.addView(rowOf(listOf(nwUp, nwDown, nwEnt)))
        page.addView(rowOf(listOf(nwRet, nwPlay, nwStop)))

        page.addView(hint("← swipe na ovládanie / médiá →"))
        return sv
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
    // AKTUALIZACIE ZOBRAZENIA
    // =====================================================================

    private fun volText(v: Int): String {
        val dB = v - 80
        return "🔊  $v   (" + (if (dB >= 0) "+" else "") + dB + " dB)"
    }

    private fun updateVolumeCard() {
        volumeBig.text = if (isMuted) "🔇  MUTE" else volText(currentVol)
    }

    private fun updateInfoLine() {
        val i = activeInput ?: "—"
        val m = activeMode ?: "—"
        infoLine.text = "Vstup: $i   ·   Režim: $m"
    }

    private fun updateMuteButton() {
        muteBtn.text = if (isMuted) "🔊 ODMUTOVAŤ" else "🔇 MUTE"
        highlightBtn(muteBtn, isMuted, C_RED)
    }

    private fun updatePowerButton() {
        highlightBtn(powerBtn, isOn, C_GREEN)
    }

    private fun styleInputButtons() {
        inputButtons.forEach { (name, b) -> highlightBtn(b, name == activeInput) }
    }

    private fun styleModeButtons() {
        modeButtons.forEach { (name, b) -> highlightBtn(b, name == activeMode) }
    }

    private fun styleRadioButtons() {
        radioButtons.forEach { (name, b) -> highlightBtn(b, name == activeRadio) }
        radioLine.text = "Rádio: " + (activeRadio ?: "—")
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
    // VYHLADANIE RECEIVERA V SIETI TELEFONU
    // =====================================================================

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
                s.soTimeout = 1200
                s.connect(InetSocketAddress(ip, port), 400)
                val w = OutputStreamWriter(s.getOutputStream(), Charsets.US_ASCII)
                w.write("?V\r\n")
                w.flush()
                val r = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.US_ASCII))
                val line = r.readLine() ?: return false
                line.startsWith("VOL") || line.startsWith("PWR") || line.startsWith("FN") ||
                    line.startsWith("MUT") || line.startsWith("LM")
            }
        } catch (_: Exception) { false }
    }

    private fun discoverReceivers() {
        statusText.text = "🔍 Skenujem sieť (port $port)…"
        scope.launch(Dispatchers.IO) {
            val myIp = phoneIp()
            if (myIp == null) {
                ui { statusText.text = "Nepodarilo sa zistiť IP telefónu — si pripojený na Wi-Fi?" }
                return@launch
            }
            val prefix = myIp.substringBeforeLast(".")
            val found = Collections.synchronizedList(mutableListOf<String>())
            withTimeoutOrNull(25000) {
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
                statusText.text = if (sorted.isEmpty())
                    "V sieti $prefix.0/24 som nenašiel receiver. Je zapnutý Network Standby?"
                else ""
                pickReceiver(sorted)
            }
        }
    }

    private fun pickReceiver(found: List<String>) {
        if (found.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(if (found.size > 1) "Nájdené prijímače" else "Nájdený prijímač")
            .setItems(found.toTypedArray()) { _, i ->
                host = found[i]
                avtControlUrl = null
                ipEdit.setText(host)
                prefs().edit().putString("host", host).apply()
                connect()
            }
            .setNegativeButton("Zrušiť", null)
            .show()
    }

    // =====================================================================
    // TEST DOSTUPNOSTI STREAMOV A PREHRAVANIE S FALLBACKOM NA NIŽŠÍ BITRATE
    // =====================================================================

    private fun testStreams() {
        scope.launch(Dispatchers.IO) {
            radios.forEach { (name, urls) ->
                launch {
                    var bestStatus = 0
                    for (url in urls) {
                        val s = checkStream(url)
                        if (s > bestStatus) {
                            bestStatus = s
                            if (s == 2) break
                        }
                    }
                    ui { markStream(name, bestStatus) }
                }
            }
        }
    }

    private fun checkStream(url: String): Int {
        for (method in listOf("HEAD", "GET")) {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                conn.instanceFollowRedirects = true
                val code = conn.responseCode
                if (code in 200..299) {
                    val br = conn.getHeaderField("icy-br")?.trim()?.toIntOrNull()
                    runCatching { conn.inputStream?.close() }
                    return if (br != null && br in 1 until 128) 1 else 2
                }
                conn.disconnect()
            } catch (_: Exception) {
            }
        }
        return 0
    }

    private fun markStream(name: String, status: Int) {
        val dot = when (status) {
            2 -> "🟢 "
            1 -> "🟠 "
            else -> "🔴 "
        }
        radioButtons[name]?.text = dot + name
    }

    private fun resolveFinalUrl(urlStr: String): String {
        var current = urlStr
        repeat(3) {
            try {
                val conn = URL(current).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = false
                conn.requestMethod = "GET"
                conn.connectTimeout = 2500
                conn.readTimeout = 2500
                val code = conn.responseCode
                if (code in 300..399) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (!loc.isNullOrBlank()) {
                        current = loc.replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "http://")
                        return@repeat
                    }
                }
                runCatching { conn.inputStream?.close() }
                conn.disconnect()
                return current
            } catch (_: Exception) {
                return current
            }
        }
        return current
    }

    private fun playRadioWithFallback(name: String, urls: List<String>) {
        queueJob?.cancel()
        ui {
            radioLine.text = "Rádio: spúšťam $name…"
            statusText.text = "Hľadám fungujúci stream (od najvyššieho bitrate)…"
        }
        scope.launch(Dispatchers.IO) {
            try {
                if (!isOn) {
                    sendSilent("PO")
                    delay(2500)
                }

                if (activeInput == null || activeInput == "NETRADIO" ||
                    (activeInput != "MEDIA SRV" && activeInput != "44FN" && activeInput != "47FN" && activeInput != "DMR")) {
                    sendSilent("44FN")
                    delay(1200)
                }

                val control = ensureAvtControl() ?: run {
                    ui { statusText.text = "UPnP: nenašiel som AVTransport na $host." }
                    return@launch
                }

                var success = false
                var workingUrl = ""

                for (url in urls) {
                    val resolved = resolveFinalUrl(url)
                        .replaceFirst(Regex("^https://", RegexOption.IGNORE_CASE), "http://")

                    runCatching { soapStop(control) }
                    delay(250)

                    val setResp = soapSetUri(control, resolved, name, isBroadcast = true)
                    if (setResp == null || setResp.contains("UPnPError")) continue

                    delay(250)
                    val playResp = soapPlay(control)
                    if (playResp != null && !playResp.contains("UPnPError")) {
                        success = true
                        workingUrl = resolved
                        break
                    }
                }

                if (!success) {
                    ui { statusText.text = "Všetky streamy pre $name zlyhali." }
                    return@launch
                }

                sendSilent("?F")
                ui {
                    radioLine.text = "Rádio: $name ▶"
                    statusText.text = "Hrá: $name ($workingUrl)"
                }
            } catch (e: Exception) {
                ui { statusText.text = "Rádio chyba: " + (e.message ?: "neznáma") }
            }
        }
    }

    private fun stopRadio() {
        queueJob?.cancel()
        scope.launch(Dispatchers.IO) {
            try {
                sendSilent("20NW")
                val control = ensureAvtControl() ?: return@launch
                soapStop(control)
                ui {
                    radioLine.text = "Rádio: zastavené"
                    statusText.text = ""
                }
            } catch (_: Exception) { }
        }
    }

    // =====================================================================
    // DLNA SERVERY
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
    // UPnP NIZKOUROVNOVE VOLANIA
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
                           title: String = "Internet Radio",
                           isBroadcast: Boolean = true): String? {
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

    private fun connect() {
        scope.coroutineContext.cancelChildren()
        queueJob = null
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val sock = Socket()
                sock.connect(InetSocketAddress(host, port), 3000)
                sock.keepAlive = true
                outWriter = OutputStreamWriter(sock.getOutputStream(), Charsets.US_ASCII)
                socketRef.set(sock)
                ui {
                    connLine.text = "●  Pripojené: " + host + ":" + port
                    connLine.setTextColor(C_GREEN)
                    statusText.text = ""
                }
                sendSilent("?P"); sendSilent("?V"); sendSilent("?F"); sendSilent("?M")
                listen(sock)
            } catch (e: Exception) {
                ui {
                    connLine.text = "○  Odpojené"
                    connLine.setTextColor(C_RED)
                    statusText.text = "Nepodarilo sa pripojiť (" + (e.message ?: "?") +
                        "). Skús tlačidlo 🔍 vyhľadať receiver."
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
            } catch (_: Exception) {
                ui {
                    connLine.text = "○  Odpojené"
                    connLine.setTextColor(C_RED)
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
                            "47FN" -> "DMR"
                            "26FN" -> "NETWORK"
                            else -> code
                        }
                    updateInfoLine()
                    styleInputButtons()
                }
                line.startsWith("LM") -> {
                    val m = modes.entries.firstOrNull { line.contains(it.value) }
                    if (m != null) {
                        activeMode = m.key
                        updateInfoLine()
                        styleModeButtons()
                    }
                }
                line == "PWR0" -> { isOn = true; updatePowerButton() }
                line == "PWR2" || line == "PWR1" -> { isOn = false; updatePowerButton() }
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

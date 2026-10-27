package com.example.pioneerremote

import android.app.AlertDialog
import android.net.wifi.WifiManager
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

class MainActivity : AppCompatActivity() {

    private var host: String = "192.168.1.80"
    private val port: Int = 8102
    private val volMax = 160

    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val socketRef = AtomicReference<Socket?>(null)
    private var outWriter: OutputStreamWriter? = null
    private var currentVol = 0
    private var multicastLock: WifiManager.MulticastLock? = null

    private lateinit var statusText: TextView
    private lateinit var volumeText: TextView
    private lateinit var inputText: TextView
    private lateinit var modeText: TextView
    private lateinit var radioText: TextView
    private lateinit var volSlider: SeekBar
    private var sliderFromUser = true

    private val inputs = linkedMapOf(
        "BD" to "25FN", "DVD" to "04FN", "SAT/CBL" to "05FN",
        "DVR/BDR" to "15FN", "VIDEO 1" to "10FN", "VIDEO 2" to "14FN",
        "CD" to "01FN", "TUNER" to "02FN", "IPOD/USB" to "17FN",
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

    private val radios = linkedMapOf(
        "Rádio Slovensko" to "https://icecast.stv.livebox.sk/slovensko_128.mp3",
        "Rádio_FM" to "https://icecast.stv.livebox.sk/fm_128.mp3",
        "Rádio Devín" to "https://icecast.stv.livebox.sk/devin_256.mp3",
        "Rádio Regina BA" to "https://icecast.stv.livebox.sk/regina_ba_128.mp3",
        "Rádio Expres" to "https://stream.bauermedia.sk/expres-hi.mp3",
        "Európa 2" to "https://stream.bauermedia.sk/europa2-hi.mp3",
        "Rádio Melody" to "https://stream.bauermedia.sk/melody-hi.mp3",
        "Rádio Rock" to "https://stream.bauermedia.sk/rock-hi.mp3",
        "Fun Rádio" to "https://live.funradio.sk:8000/fun192.mp3",
        "Rádio Jemné" to "https://stream.jemne.sk/jemne128.mp3",
        "Rádio Vlna" to "https://stream.radiovlna.sk/vlna128.mp3",
        "ČRo Radiožurnál" to "https://rozhlas.stream/radiozurnal_high.aac",
        "ČRo Dvojka" to "https://rozhlas.stream/dvojka_high.aac",
        "ČRo Vltava" to "https://rozhlas.stream/vltava_high.aac",
        "ČRo Jazz" to "https://rozhlas.stream/jazz_high.aac",
        "SomaFM Groove Salad" to "https://ice.somafm.com/groovesalad-128-mp3",
        "SomaFM Lush" to "https://ice.somafm.com/lush-128-mp3",
        "Radio Paradise" to "https://stream.radioparadise.com/mp3-192"
    )

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("pioneer", MODE_PRIVATE)
        host = prefs.getString("host", host) ?: host
        mediaServerDesc = prefs.getString("dlnaServer", null)
        mediaServerName = prefs.getString("dlnaName", "") ?: ""
        buildUI()
        connect()
        val wifi = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("pioneerSSDP").apply {
            setReferenceCounted(false); acquire()
        }
        if (mediaServerDesc != null) initMediaServer(mediaServerDesc!!)
    }

    private fun buildUI() {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, 0, pad, 0)
        }

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val ipEdit = EditText(this).apply {
            setText(host); hint = "IP adresa receivera"
            inputType = InputType.TYPE_CLASS_PHONE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        top.addView(ipEdit)
        top.addView(Button(this).apply {
            text = "Pripoj"
            setOnClickListener {
                host = ipEdit.text.toString().trim()
                getSharedPreferences("pioneer", MODE_PRIVATE)
                    .edit().putString("host", host).apply()
                connect()
            }
        })
        root.addView(top)

        statusText = TextView(this).apply { text = "Odpojene"; textSize = 12f }
        root.addView(statusText)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = ContextCompat.getDrawable(
                this@MainActivity, android.R.drawable.dialog_holo_light_frame)
        }
        volumeText = TextView(this).apply {
            text = "Hlasitost: -"; textSize = 22f; gravity = Gravity.CENTER }
        inputText = TextView(this).apply {
            text = "Vstup: -"; textSize = 16f; gravity = Gravity.CENTER }
        modeText = TextView(this).apply {
            text = "Rezim: -"; textSize = 16f; gravity = Gravity.CENTER }
        radioText = TextView(this).apply {
            text = "Rádio: -"; textSize = 16f; gravity = Gravity.CENTER }
        card.addView(volumeText); card.addView(inputText)
        card.addView(modeText); card.addView(radioText)
        root.addView(card)

        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad, 0, pad / 2)
        }
        val tab1 = tabButton("🎚 Ovládanie", true)
        val tab2 = tabButton("📻 Rádio", false)
        val tab3 = tabButton("💽 Médiá", false)
        listOf(tab1, tab2, tab3).forEach {
            tabs.addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(tabs)

        val pager = ViewPager2(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val screens = listOf(
            buildScreenHome(pad), buildScreenRadio(pad), buildScreenMedia(pad))
        pager.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun getItemCount() = screens.size
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
                object : RecyclerView.ViewHolder(FrameLayout(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }) {}
            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val frame = holder.itemView as FrameLayout
                frame.removeAllViews()
                frame.addView(screens[position])
            }
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                listOf(tab1, tab2, tab3).forEachIndexed { i, btn ->
                    styleTab(btn, i == position)
                }
            }
        })
        tab1.setOnClickListener { pager.currentItem = 0 }
        tab2.setOnClickListener { pager.currentItem = 1 }
        tab3.setOnClickListener { pager.currentItem = 2 }
        root.addView(pager)
        setContentView(root)
    }

    private fun tabButton(label: String, active: Boolean): Button =
        Button(this).apply { text = label; styleTab(this, active) }

    private fun styleTab(btn: Button, active: Boolean) {
        btn.setBackgroundColor(
            ContextCompat.getColor(this,
                if (active) android.R.color.holo_orange_dark else android.R.color.transparent))
    }

    private fun buildScreenHome(pad: Int): ScrollView = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
        }.also { page ->
            page.addView(row(listOf(
                b("⏻ ZAP", "PO"), b("⏻ VYP", "PF"),
                b("🔇 MUTE", "MO"), b("🔊 UNMUTE", "MF")
            )))
            page.addView(row(listOf(
                b("VOL −", "VD"), b("VOL +", "VU"), b("VSTUP ▶", "FU")
            )))

            page.addView(section("Hlasitost (rychly posun)"))
            volSlider = SeekBar(this@MainActivity).apply {
                max = volMax; progress = 0
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        if (fromUser) targetVolume(p)
                    }
                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            page.addView(volSlider)

            page.addView(section("Výber vstupu"))
            page.addView(grid(inputs.map { (name, code) ->
                name to { send(code); send("?F") }
            }))

            page.addView(section("Priestorový režim (Listening Mode)"))
            page.addView(grid(modes.map { (name, code) ->
                name to { send(code) }
            }))
        })
    }

    private fun buildScreenRadio(pad: Int): ScrollView = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
        }.also { page ->
            page.addView(section("Internetové rádio (streamy z igor-stream)"))
            page.addView(grid(radios.map { (name, url) ->
                name to { playRadio(name, url) }
            }))
            page.addView(Button(this@MainActivity).apply {
                text = "⏹ Zastaviť rádio"
                setOnClickListener { stopRadio() }
            })
            page.addView(section("Pôvodné NETRADIO receivera"))
            page.addView(Button(this@MainActivity).apply {
                text = "NETRADIO (38FN)"
                setOnClickListener { send("38FN") }
            })
        })
    }

    private fun buildScreenMedia(pad: Int): ScrollView = ScrollView(this).apply {
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, pad)
        }.also { page ->
            val srvRow = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            srvRow.addView(Button(this@MainActivity).apply {
                text = "🔍 Servery"
                setOnClickListener { scanMediaServers() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            srvRow.addView(Button(this@MainActivity).apply {
                text = "⟨ Späť"
                setOnClickListener { browseBack() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            srvRow.addView(Button(this@MainActivity).apply {
                text = "▶ Album"
                setOnClickListener { playCurrentFolder() }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            page.addView(srvRow)

            mediaServerText = TextView(this@MainActivity).apply {
                text = if (mediaServerName.isNotEmpty())
                    "Server: $mediaServerName" else "Server: nevybraný — klikni 🔍 Servery"
                textSize = 13f; setPadding(0, pad / 2, 0, 0)
            }
            page.addView(mediaServerText)

            mediaPathText = TextView(this@MainActivity).apply {
                text = "📚 /"; textSize = 13f
            }
            page.addView(mediaPathText)

            mediaStatusText = TextView(this@MainActivity).apply {
                text = ""; textSize = 14f; setPadding(0, pad / 2, 0, pad / 2)
            }
            page.addView(mediaStatusText)

            mediaList = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            page.addView(mediaList)
        })
    }

    private fun section(title: String): TextView =
        TextView(this).apply {
            text = title; textSize = 16f
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
        }

    private fun row(buttons: List<Button>): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            buttons.forEach {
                addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
        }

    private fun b(label: String, cmd: String): Button =
        Button(this).apply {
            text = label
            setOnClickListener {
                send(cmd)
                if (cmd.startsWith("V")) send("?V")
            }
        }

    private fun grid(items: List<Pair<String, () -> Unit>>): GridLayout =
        GridLayout(this).apply {
            columnCount = 3; useDefaultMargins = true
            items.forEach { (name, action) ->
                addView(Button(this@MainActivity).apply {
                    text = name
                    setOnClickListener { action() }
                    layoutParams = GridLayout.LayoutParams().apply {
                        columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                    }
                })
            }
        }

    private fun playRadio(name: String, url: String) {
        ui {
            radioText.text = "Rádio: spúšťam $name ..."
            statusText.text = "Rádio: hľadám receiver cez UPnP..."
        }
        scope.launch(Dispatchers.IO) {
            try {
                val control = ensureAvtControl() ?: run {
                    ui { statusText.text = "UPnP: nenašiel som AVTransport na receiveri." }
                    return@launch
                }
                soapSetUri(control, url)
                soapPlay(control)
                ui {
                    radioText.text = "Rádio: $name ▶"
                    statusText.text = "Rádio prehráva na receiveri: $name"
                }
            } catch (e: Exception) {
                ui { statusText.text = "Rádio chyba: " + e.message }
            }
        }
    }

    private fun stopRadio() {
        scope.launch(Dispatchers.IO) {
            try {
                val control = ensureAvtControl() ?: return@launch
                soapStop(control)
                ui { radioText.text = "Rádio: stop" }
            } catch (_: Exception) { }
        }
    }

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
                        getSharedPreferences("pioneer", MODE_PRIVATE).edit()
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
            val btn = Button(this).apply {
                text = (if (e.isContainer) "📁 " else "🎵 ") + e.title
                gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    if (e.isContainer) {
                        browseFolder(e.id, e.title)
                    } else {
                        playQueue(listOf(e), 0)
                    }
                }
            }
            mediaList.addView(btn, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (entries.isEmpty()) {
            mediaList.addView(TextView(this).apply { text = "(prázdne)" })
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
                while (isActive && queueIdx < playQueue.size) {
                    val track = playQueue[queueIdx]
                    val control = ensureAvtControl() ?: run {
                        ui { mediaStatusText.text = "UPnP: receiver nedostupny." }
                        return@launch
                    }
                    ui {
                        mediaStatusText.text = "▶ " + track.title +
                            "  (" + (queueIdx + 1) + "/" + playQueue.size + ")"
                    }
                    soapSetUri(control, track.url!!)
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
                ui { mediaStatusText.text = "✓ Album dokončený" }
            } catch (_: Exception) { }
        }
    }

    private fun ssdpFindAll(st: String, timeoutMs: Int = 4000): List<String> {
        val result = linkedSetOf<String>()
        val msearch = "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: " + st + "\r\n\r\n"
        MulticastSocket().use { sock ->
            sock.soTimeout = timeoutMs
            val addr = InetAddress.getByName("239.255.255.250")
            sock.send(DatagramPacket(msearch.toByteArray(), msearch.length, addr, 1900))
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
        return result.toList()
    }

    private fun ensureAvtControl(): String? {
        avtControlUrl?.let { return it }
        val descUrl = ssdpFindAll("urn:schemas-upnp-org:device:MediaRenderer:1")
            .firstOrNull() ?: ("http://" + host + ":49152/description.xml")
        return readServiceControlUrl(descUrl,
            "urn:schemas-upnp-org:service:AVTransport")?.also { avtControlUrl = it }
    }

    private fun readServiceControlUrl(descUrl: String, serviceUrn: String): String? {
        val xml = httpGet(descUrl) ?: return null
        val shortName = serviceUrn.substringAfterLast(':')
        val serviceBlock = Regex("(?is)<service>.*?" + shortName + ".*?</service>")
            .find(xml)?.value ?: return null
        val controlPath = Regex("(?is)<controlURL>(.*?)</controlURL>")
            .find(serviceBlock)?.groupValues?.get(1)?.trim() ?: return null
        val base = Regex("(?is)<URLBase>(.*?)</URLBase>")
            .find(xml)?.groupValues?.get(1)?.trim()
            ?: URL(descUrl).let { it.protocol + "://" + it.host + ":" + it.port }
        return base.trimEnd('/') + "/" + controlPath.trimStart('/')
    }

    private fun httpGet(urlStr: String): String? = runCatching {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 5000
        BufferedReader(InputStreamReader(conn.inputStream)).use { it.readText() }
    }.getOrNull()

    private fun soapRequest(controlUrl: String, action: String,
                            soapAction: String, body: String): String? {
        val conn = URL(controlUrl).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        conn.setRequestProperty("SOAPACTION", "\"" + soapAction + "\"")
        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
            it.write(body); it.flush()
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        return BufferedReader(InputStreamReader(stream ?: return null)).use { it.readText() }
    }

    private fun soap(controlUrl: String, action: String, body: String) {
        soapRequest(controlUrl, action,
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

    private fun soapSetUri(controlUrl: String, streamUrl: String) {
        val esc = streamUrl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        soap(controlUrl, "SetAVTransportURI",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><CurrentURI>" + esc + "</CurrentURI>" +
            "<CurrentURIMetaData></CurrentURIMetaData>" +
            "</u:SetAVTransportURI></s:Body></s:Envelope>")
    }

    private fun soapPlay(controlUrl: String) {
        soap(controlUrl, "Play",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID><Speed>1</Speed>" +
            "</u:Play></s:Body></s:Envelope>")
    }

    private fun soapStop(controlUrl: String) {
        soap(controlUrl, "Stop",
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body><u:Stop xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">" +
            "<InstanceID>0</InstanceID>" +
            "</u:Stop></s:Body></s:Envelope>")
    }

    private fun targetVolume(target: Int) {
        sliderFromUser = false
        volSlider.isEnabled = false
        scope.launch(Dispatchers.IO) {
            try {
                var guard = 0
                while (isActive && guard < 200) {
                    val diff = target - currentVol
                    if (diff == 0) break
                    outWriter?.let {
                        it.write(if (diff > 0) "VU\r\n" else "VD\r\n"); it.flush()
                    }
                    delay(120)
                    sendSilent("?V")
                    guard++
                }
            } finally {
                ui {
                    volSlider.isEnabled = true
                    sliderFromUser = true
                }
            }
        }
    }

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
                ui { statusText.text = "Pripojene: " + host + ":" + port }
                sendSilent("?P"); sendSilent("?V"); sendSilent("?F")
                listen(sock)
            } catch (e: Exception) {
                ui {
                    statusText.text = "Nepodarilo sa pripojit (" + e.message +
                        "). Skontroluj IP, port a Wi-Fi."
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
                ui { statusText.text = "Spojenie prerusene" }
            }
        }
    }

    private fun handleResponse(line: String) {
        ui {
            when {
                line.startsWith("VOL") -> {
                    val v = line.substring(3).toIntOrNull() ?: return@ui
                    currentVol = v
                    val dB = v - 80
                    volumeText.text = "Hlasitost: " + v +
                        "  (cca " + (if (dB >= 0) "+" else "") + dB + " dB)"
                    if (sliderFromUser) volSlider.progress = v.coerceIn(0, volMax)
                }
                line.startsWith("FN") && line.length == 4 -> {
                    val code = line.substring(2) + "FN"
                    inputText.text = "Vstup: " +
                        (inputs.entries.firstOrNull { it.value == code }?.key ?: line)
                }
                line.startsWith("LM") && line.length == 5 ->
                    modeText.text = "Rezim: " + line.substring(2) + " (kod LM)"
                line == "PWR0" -> statusText.text = "Receiver: ZAPNUTY"
                line == "PWR2" -> statusText.text = "Receiver: STANDBY"
                line == "MUT0" -> volumeText.text = "MUTE"
                line == "MUT1" -> sendSilent("?V")
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

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext.cancelChildren()
        runCatching { socketRef.get()?.close() }
        multicastLock?.release()
    }
}

package com.audi.rscockpit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.*
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.*

class MainActivity : Activity() {

    private var activeTab = 0 // 0 = Home, 1 = CarPlay, 2 = Gauges (Car), 3 = MMI, 4 = Settings
    private val railButtons = mutableListOf<Mib3RailButton>()

    private lateinit var homePanel: LinearLayout
    private lateinit var gaugesPanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var clockText: TextView
    private lateinit var btStatusText: TextView

    // Left Column Full-Card Sweeping Bar Gauges (Top to Bottom)
    private lateinit var boostGauge: Mib3BarGaugeView
    private lateinit var loadGauge: Mib3BarGaugeView
    private lateinit var hpGauge: Mib3BarGaugeView
    private lateinit var tqGauge: Mib3BarGaugeView

    // Right Column Thermal Cards (Top to Bottom)
    private lateinit var iatCard: Mib3TempCardView
    private lateinit var coolantCard: Mib3TempCardView
    private lateinit var oilCard: Mib3TempCardView
    private lateinit var egtCard: Mib3TempCardView

    // Telemetry state
    @Volatile private var boostPsi = 0.0f
    @Volatile private var engineLoadPct = 0.0f
    @Volatile private var crankHp = 0.0f
    @Volatile private var crankTq = 0.0f
    @Volatile private var iatF = 84.0f
    @Volatile private var coolantF = 194.0f
    @Volatile private var oilF = 206.0f
    @Volatile private var egtBank1F = 890.0f
    @Volatile private var obdConnected = false
    @Volatile private var running = true

    private val uiHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                    101
                )
            }
        }

        setContentView(buildRootLayout())
        selectTab(0)
        startClockAndUiLoop()
        startObd2Worker()
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveFullscreen()
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    private fun enableImmersiveFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
    }

    private fun buildRootLayout(): View {
        val root = Mib3RootBackground(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // 1. LEFT 5-BUTTON MIB3 GLASS NAVIGATION RAIL
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#08090B"))
        }

        val icons = listOf(
            Mib3IconType.HOME,
            Mib3IconType.CARPLAY,
            Mib3IconType.CAR_GAUGES,
            Mib3IconType.MMI,
            Mib3IconType.SETTINGS
        )

        icons.forEachIndexed { index, iconType ->
            val btn = Mib3RailButton(this, iconType).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
                setOnClickListener { handleRailClick(index) }
            }
            railButtons.add(btn)
            rail.addView(btn)
        }

        root.addView(rail)

        // Subtle vertical glass divider
        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#2A2D32"))
        })

        // 2. RIGHT MAIN VIEWPORT (TOP MIB3 STATUS BAR + CONTENT AREA)
        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setPadding(dp(16), dp(8), dp(18), dp(12))
        }

        // Top MIB3 Status Strip
        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(28))
        }

        val headerTitle = TextView(this).apply {
            text = "AUDI RS  |  3.0T SUPERCHARGED"
            setTextColor(Color.parseColor("#D4D7DC"))
            textSize = 12f
            letterSpacing = 0.14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btStatusText = TextView(this).apply {
            text = "vLINKER: STANDBY"
            setTextColor(Color.parseColor("#9DA3AE"))
            textSize = 11f
            letterSpacing = 0.08f
            setPadding(0, 0, dp(16), 0)
        }

        clockText = TextView(this).apply {
            text = "12:00 PM"
            setTextColor(Color.WHITE)
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        statusBar.addView(headerTitle)
        statusBar.addView(btStatusText)
        statusBar.addView(clockText)
        mainArea.addView(statusBar)

        // Thin MIB3 horizontal separator line
        mainArea.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(10)
            }
            setBackgroundColor(Color.parseColor("#2E3136"))
        })

        // Content Frame
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        homePanel = buildHomePanel()
        gaugesPanel = buildGaugesPanel()
        settingsPanel = buildSettingsPanel()

        contentFrame.addView(homePanel)
        contentFrame.addView(gaugesPanel)
        contentFrame.addView(settingsPanel)

        mainArea.addView(contentFrame)
        root.addView(mainArea)
        return root
    }

    // =========================================================================
    // TAB 0: MIB3 GLASS HOME TILES
    // =========================================================================
    private fun buildHomePanel(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val tiles = listOf(
            Triple("APPLE CARPLAY", Color.parseColor("#34C759")) { launchPackage("com.syu.carlink") },
            Triple("RS TELEMETRY", Color.parseColor("#E52528")) { selectTab(2) },
            Triple("AUDI MMI 3G+", Color.parseColor("#E5A93C")) { launchPackage("com.syu.av") },
            Triple("SYSTEM & SETUP", Color.parseColor("#64A8FF")) { selectTab(4) }
        )

        tiles.forEachIndexed { idx, (label, accentColor, action) ->
            val card = Mib3GlassTile(this, label, accentColor).apply {
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                    if (idx > 0) leftMargin = dp(12)
                    topMargin = dp(16)
                    bottomMargin = dp(16)
                }
                setOnClickListener { action() }
            }
            container.addView(card)
        }
        return container
    }

    // =========================================================================
    // TAB 2: 2-COLUMN x 4-ROW TELEMETRY GAUGES GRID
    // =========================================================================
    private fun buildGaugesPanel(): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        // LEFT COLUMN: 4 Full-Background Left-to-Right MIB3 Gradient Bar Gauges
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f).apply {
                rightMargin = dp(12)
            }
        }

        boostGauge = Mib3BarGaugeView(this, "SUPERCHARGER BOOST", "PSI", 0f, 24f, "%.1f")
        loadGauge = Mib3BarGaugeView(this, "ENGINE LOAD", "%", 0f, 100f, "%.0f")
        hpGauge = Mib3BarGaugeView(this, "CALC. CRANK POWER", "HP", 0f, 510f, "%.0f")
        tqGauge = Mib3BarGaugeView(this, "CALC. CRANK TORQUE", "LB-FT", 0f, 490f, "%.0f")

        listOf(boostGauge, loadGauge, hpGauge, tqGauge).forEachIndexed { idx, view ->
            view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (idx > 0) topMargin = dp(8)
            }
            leftCol.addView(view)
        }

        // RIGHT COLUMN: 4 Thermal Digital Cards (Cool Blue -> White -> Amber -> RS Red)
        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f)
        }

        iatCard = Mib3TempCardView(this, "CHARGE AIR TEMP", "°F", coolThresh = 95f, warnThresh = 130f, hotThresh = 155f)
        coolantCard = Mib3TempCardView(this, "ENGINE COOLANT", "°F", coolThresh = 165f, warnThresh = 212f, hotThresh = 228f)
        oilCard = Mib3TempCardView(this, "ENGINE OIL TEMP", "°F", coolThresh = 175f, warnThresh = 235f, hotThresh = 255f)
        egtCard = Mib3TempCardView(this, "EXHAUST BANK 1", "°F", coolThresh = 750f, warnThresh = 1350f, hotThresh = 1550f)

        listOf(iatCard, coolantCard, oilCard, egtCard).forEachIndexed { idx, view ->
            view.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                if (idx > 0) topMargin = dp(8)
            }
            rightCol.addView(view)
        }

        grid.addView(leftCol)
        grid.addView(rightCol)
        return grid
    }

    // =========================================================================
    // TAB 4: SETTINGS & FYT HOME OVERRIDE ACTIVATION
    // =========================================================================
    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }

        val row1 = Mib3ActionRow(this, "Enable Physical Home Button Override (Accessibility Service)", "Locks physical Home key to Audi RS Cockpit instead of UI08") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val row2 = Mib3ActionRow(this, "Bluetooth Settings (Pair Vgate vLinker MC+)", "Ensure vLinker MC+ is paired once in Android Bluetooth") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        val row3 = Mib3ActionRow(this, "Open Android System Settings", "Access FYT factory menu, display, and sound settings") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        listOf(row1, row2, row3).forEachIndexed { idx, row ->
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(68)).apply {
                if (idx > 0) topMargin = dp(10)
            }
            panel.addView(row)
        }
        return panel
    }

    private fun handleRailClick(index: Int) {
        when (index) {
            0 -> selectTab(0)
            1 -> launchPackage("com.syu.carlink")
            2 -> selectTab(2)
            3 -> launchPackage("com.syu.av")
            4 -> selectTab(4)
        }
    }

    private fun selectTab(index: Int) {
        activeTab = index
        railButtons.forEachIndexed { i, btn -> btn.setSelectedState(i == index) }
        homePanel.visibility = if (index == 0) View.VISIBLE else View.GONE
        gaugesPanel.visibility = if (index == 2) View.VISIBLE else View.GONE
        settingsPanel.visibility = if (index == 4) View.VISIBLE else View.GONE
    }

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            Toast.makeText(this, "Package not found: $pkg", Toast.LENGTH_SHORT).show()
        }
    }

    // 60 FPS UI Loop for silky-smooth full-card bar transitions
    private fun startClockAndUiLoop() {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        uiHandler.post(object : Runnable {
            override fun run() {
                if (!running) return
                clockText.text = fmt.format(Date())
                btStatusText.text = if (obdConnected) "vLINKER MC+: LIVE" else "vLINKER: STANDBY"
                btStatusText.setTextColor(
                    if (obdConnected) Color.parseColor("#4CD964") else Color.parseColor("#9DA3AE")
                )

                boostGauge.updateValue(boostPsi)
                loadGauge.updateValue(engineLoadPct)
                hpGauge.updateValue(crankHp)
                tqGauge.updateValue(crankTq)

                iatCard.updateTemp(iatF)
                coolantCard.updateTemp(coolantF)
                oilCard.updateTemp(oilF)
                egtCard.updateTemp(egtBank1F)

                uiHandler.postDelayed(this, 16)
            }
        })
    }

    // =========================================================================
    // VGATE vLINKER MC+ BLUETOOTH OBD2 WORKER + SIMOS 8.5 POWER MATH
    // =========================================================================
    @SuppressLint("MissingPermission")
    private fun startObd2Worker() {
        thread(isDaemon = true) {
            val sppUuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            var simPhase = 0.0

            while (running) {
                var socket: BluetoothSocket? = null
                try {
                    val adapter = BluetoothAdapter.getDefaultAdapter()
                    val device: BluetoothDevice? = adapter?.bondedDevices?.firstOrNull {
                        it.name?.contains("vLinker", ignoreCase = true) == true ||
                            it.name?.contains("OBD", ignoreCase = true) == true
                    }

                    if (device != null) {
                        socket = device.createRfcommSocketToServiceRecord(sppUuid)
                        socket.connect()
                        val outStream = socket.outputStream
                        val inStream = socket.inputStream

                        listOf("ATZ\r", "ATE0\r", "ATL0\r", "ATS0\r", "ATH0\r", "ATSP6\r").forEach {
                            sendObdCmd(outStream, inStream, it)
                        }
                        obdConnected = true

                        val baroKpa = parseSingleByte(sendObdCmd(outStream, inStream, "01331\r"), "4133", 101)

                        while (running && socket.isConnected) {
                            val mapKpa = parseSingleByte(sendObdCmd(outStream, inStream, "010B1\r"), "410B", baroKpa)
                            val loadRaw = parseSingleByte(sendObdCmd(outStream, inStream, "01041\r"), "4104", 0)
                            val rpmRaw = parseTwoBytes(sendObdCmd(outStream, inStream, "010C1\r"), "410C", 800 * 4)
                            val mafRaw = parseTwoBytes(sendObdCmd(outStream, inStream, "01101\r"), "4110", 400)
                            val iatC = parseSingleByte(sendObdCmd(outStream, inStream, "010F1\r"), "410F", 68) - 40
                            val ectC = parseSingleByte(sendObdCmd(outStream, inStream, "01051\r"), "4105", 130) - 40
                            val oilC = parseSingleByte(sendObdCmd(outStream, inStream, "015C1\r"), "415C", 136) - 40
                            val egtRaw = parseTwoBytes(sendObdCmd(outStream, inStream, "013C1\r"), "413C", 5000)

                            val rpm = rpmRaw / 4.0f
                            val mafGs = mafRaw / 100.0f
                            val currentBoost = max(0f, (mapKpa - baroKpa) * 0.145038f)
                            val currentLoad = (loadRaw * 100f) / 255f

                            val mafHp = mafGs * 1.38f
                            val boostHp = (rpm / 6800f) * (275f + currentBoost * 10.6f) * (currentLoad / 100f)
                            val calcHp = max(mafHp, boostHp).coerceIn(0f, 510f)
                            val calcTq = if (rpm > 650f) ((calcHp * 5252f) / rpm).coerceIn(0f, 490f) else 0f

                            boostPsi = currentBoost
                            engineLoadPct = currentLoad
                            crankHp = calcHp
                            crankTq = calcTq
                            iatF = iatC * 1.8f + 32f
                            coolantF = ectC * 1.8f + 32f
                            oilF = oilC * 1.8f + 32f
                            egtBank1F = ((egtRaw / 10f) - 40f) * 1.8f + 32f
                        }
                    }
                } catch (_: Exception) {
                    obdConnected = false
                    try { socket?.close() } catch (_: Exception) {}
                }

                if (!obdConnected && running) {
                    simPhase += 0.045
                    val wave = ((sin(simPhase) + 1.0) * 0.5).toFloat()
                    boostPsi = wave * 20.8f
                    engineLoadPct = 14f + wave * 84f
                    crankHp = 38f + wave * 447f
                    crankTq = 65f + sin(simPhase * 0.92).coerceAtLeast(0.0).toFloat() * 405f
                    iatF = 82f + wave * 54f
                    coolantF = 192f + wave * 14f
                    oilF = 204f + wave * 24f
                    egtBank1F = 840f + wave * 680f
                    Thread.sleep(60)
                }
            }
        }
    }

    private fun sendObdCmd(out: OutputStream, input: InputStream, cmd: String): String {
        out.write(cmd.toByteArray())
        out.flush()
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + 180
        while (System.currentTimeMillis() < deadline) {
            if (input.available() > 0) {
                val c = input.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else {
                Thread.sleep(4)
            }
        }
        return sb.toString().replace("\\s".toRegex(), "")
    }

    private fun parseSingleByte(resp: String, header: String, fallback: Int): Int {
        val idx = resp.indexOf(header)
        return if (idx != -1 && resp.length >= idx + header.length + 2) {
            resp.substring(idx + header.length, idx + header.length + 2).toIntOrNull(16) ?: fallback
        } else fallback
    }

    private fun parseTwoBytes(resp: String, header: String, fallback: Int): Int {
        val idx = resp.indexOf(header)
        return if (idx != -1 && resp.length >= idx + header.length + 4) {
            resp.substring(idx + header.length, idx + header.length + 4).toIntOrNull(16) ?: fallback
        } else fallback
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}

// =============================================================================
// CUSTOM AUDI MIB3 GLASS VIEWS & FULL-BACKGROUND SWEEPING BAR GAUGE
// =============================================================================

enum class Mib3IconType { HOME, CARPLAY, CAR_GAUGES, MMI, SETTINGS }

class Mib3RootBackground(context: Context) : LinearLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        paint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.parseColor("#040406"), Color.parseColor("#0B0D11"), Color.parseColor("#050507")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, paint)

        val glassPath = Path().apply {
            moveTo(w * 0.25f, h)
            lineTo(w, h * 0.62f)
            lineTo(w, h)
            close()
        }
        paint.shader = LinearGradient(
            w * 0.25f, h, w, h * 0.62f,
            Color.parseColor("#0AFFFFFF"),
            Color.parseColor("#18FFFFFF"),
            Shader.TileMode.CLAMP
        )
        canvas.drawPath(glassPath, paint)
    }

    init { setWillNotDraw(false) }
}

class Mib3RailButton(context: Context, private val iconType: Mib3IconType) : View(context) {
    private var isActive = false
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    fun setSelectedState(selected: Boolean) {
        isActive = selected
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        if (isActive) {
            bgPaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#180608"), Color.parseColor("#5C1618"), Color.parseColor("#9C2A2B")),
                floatArrayOf(0f, 0.65f, 1f),
                Shader.TileMode.CLAMP
            )
        } else {
            bgPaint.shader = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#0A0B0D"), Color.parseColor("#1D1F23"), Color.parseColor("#2E3136")),
                floatArrayOf(0f, 0.7f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        borderPaint.color = if (isActive) Color.parseColor("#FF4A4D") else Color.parseColor("#45FFFFFF")
        canvas.drawLine(w - 1f, 0f, w - 1f, h, borderPaint)
        borderPaint.color = Color.parseColor("#1FFFFFFF")
        canvas.drawLine(0f, h - 1f, w, h - 1f, borderPaint)

        val cx = w * 0.5f
        val cy = h * 0.5f
        val s = min(w, h) * 0.25f

        strokePaint.color = if (isActive) Color.parseColor("#FF3B3E") else Color.parseColor("#ECEEF2")
        strokePaint.strokeWidth = s * 0.13f

        when (iconType) {
            Mib3IconType.HOME -> {
                val path = Path().apply {
                    moveTo(cx, cy - s * 0.85f)
                    lineTo(cx + s * 0.95f, cy - s * 0.12f)
                    moveTo(cx + s * 0.72f, cy - s * 0.28f)
                    lineTo(cx + s * 0.72f, cy + s * 0.78f)
                    lineTo(cx - s * 0.72f, cy + s * 0.78f)
                    lineTo(cx - s * 0.72f, cy - s * 0.28f)
                    moveTo(cx - s * 0.95f, cy - s * 0.12f)
                    lineTo(cx, cy - s * 0.85f)
                }
                canvas.drawPath(path, strokePaint)
            }
            Mib3IconType.CARPLAY -> {
                val path = Path().apply {
                    moveTo(cx, cy - s * 0.9f)
                    lineTo(cx + s * 0.65f, cy + s * 0.82f)
                    lineTo(cx, cy + s * 0.45f)
                    lineTo(cx - s * 0.65f, cy + s * 0.82f)
                    close()
                }
                canvas.drawPath(path, strokePaint)
            }
            Mib3IconType.CAR_GAUGES -> {
                val car = Path().apply {
                    moveTo(cx - s * 0.68f, cy - s * 0.15f)
                    lineTo(cx - s * 0.45f, cy - s * 0.68f)
                    lineTo(cx + s * 0.45f, cy - s * 0.68f)
                    lineTo(cx + s * 0.68f, cy - s * 0.15f)
                    lineTo(cx + s * 0.92f, cy - s * 0.22f)
                    moveTo(cx + s * 0.68f, cy - s * 0.15f)
                    lineTo(cx + s * 0.95f, cy + s * 0.08f)
                    lineTo(cx + s * 0.95f, cy + s * 0.58f)
                    lineTo(cx + s * 0.64f, cy + s * 0.58f)
                    lineTo(cx + s * 0.64f, cy + s * 0.42f)
                    lineTo(cx - s * 0.64f, cy + s * 0.42f)
                    lineTo(cx - s * 0.64f, cy + s * 0.58f)
                    lineTo(cx - s * 0.95f, cy + s * 0.58f)
                    lineTo(cx - s * 0.95f, cy + s * 0.08f)
                    lineTo(cx - s * 0.68f, cy - s * 0.15f)
                    moveTo(cx - s * 0.92f, cy - s * 0.22f)
                    lineTo(cx - s * 0.68f, cy - s * 0.15f)
                }
                canvas.drawPath(car, strokePaint)
                canvas.drawLine(cx - s * 0.78f, cy + s * 0.06f, cx - s * 0.45f, cy + s * 0.12f, strokePaint)
                canvas.drawLine(cx + s * 0.78f, cy + s * 0.06f, cx + s * 0.45f, cy + s * 0.12f, strokePaint)
                canvas.drawRect(cx - s * 0.34f, cy + s * 0.04f, cx + s * 0.34f, cy + s * 0.34f, strokePaint)
            }
            Mib3IconType.MMI -> {
                canvas.drawCircle(cx, cy - s * 0.2f, s * 0.16f, strokePaint)
                canvas.drawLine(cx, cy - s * 0.36f, cx, cy - s * 0.85f, strokePaint)
                canvas.drawLine(cx, cy - s * 0.04f, cx, cy + s * 0.82f, strokePaint)
                val arcRect1 = RectF(cx - s * 0.48f, cy - s * 0.68f, cx + s * 0.48f, cy + s * 0.28f)
                canvas.drawArc(arcRect1, -35f, 70f, false, strokePaint)
                canvas.drawArc(arcRect1, 145f, 70f, false, strokePaint)
                val arcRect2 = RectF(cx - s * 0.82f, cy - s * 1.02f, cx + s * 0.82f, cy + s * 0.62f)
                canvas.drawArc(arcRect2, -35f, 70f, false, strokePaint)
                canvas.drawArc(arcRect2, 145f, 70f, false, strokePaint)
            }
            Mib3IconType.SETTINGS -> {
                val outerR = s * 0.78f
                val innerR = s * 0.60f
                val teeth = 10
                val gearPath = Path()
                for (i in 0 until teeth) {
                    val a0 = (i * 360.0 / teeth) * PI / 180.0
                    val a1 = ((i + 0.35) * 360.0 / teeth) * PI / 180.0
                    val a2 = ((i + 0.55) * 360.0 / teeth) * PI / 180.0
                    val a3 = ((i + 0.90) * 360.0 / teeth) * PI / 180.0
                    val p0x = cx + (cos(a0) * innerR).toFloat()
                    val p0y = cy + (sin(a0) * innerR).toFloat()
                    if (i == 0) gearPath.moveTo(p0x, p0y) else gearPath.lineTo(p0x, p0y)
                    gearPath.lineTo(cx + (cos(a0) * outerR).toFloat(), cy + (sin(a0) * outerR).toFloat())
                    gearPath.lineTo(cx + (cos(a1) * outerR).toFloat(), cy + (sin(a1) * outerR).toFloat())
                    gearPath.lineTo(cx + (cos(a2) * innerR).toFloat(), cy + (sin(a2) * innerR).toFloat())
                    gearPath.lineTo(cx + (cos(a3) * innerR).toFloat(), cy + (sin(a3) * innerR).toFloat())
                }
                gearPath.close()
                canvas.drawPath(gearPath, strokePaint)
                canvas.drawCircle(cx, cy, s * 0.22f, strokePaint)
            }
        }
    }
}

class Mib3BarGaugeView(
    context: Context,
    private val title: String,
    private val unit: String,
    private val minVal: Float,
    private val maxVal: Float,
    private val formatStr: String
) : View(context) {
    private var targetVal = minVal
    private var displayVal = minVal

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(4f, 0f, 1f, Color.parseColor("#CC000000"))
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(6f, 0f, 2f, Color.parseColor("#E6000000"))
    }
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(4f, 0f, 1f, Color.parseColor("#CC000000"))
    }

    fun updateValue(v: Float) {
        targetVal = v.coerceIn(minVal, maxVal)
        val diff = targetVal - displayVal
        displayVal = if (abs(diff) < 0.01f) targetVal else displayVal + diff * 0.24f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.18f

        // 1. Base MIB3 Deep Obsidian-Gray Glass Rectangle
        bgPaint.shader = LinearGradient(
            0f, 0f, w, 0f,
            intArrayOf(Color.parseColor("#0A0B0E"), Color.parseColor("#16181D"), Color.parseColor("#0E1014")),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        // 2. Full-Height Left-to-Right Audi MIB3 Crimson & Metallic Red Gradient Fill
        val pct = ((displayVal - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
        val fillRight = w * pct

        if (fillRight > 1f) {
            fillPaint.shader = LinearGradient(
                0f, 0f, fillRight, 0f,
                intArrayOf(
                    Color.parseColor("#180507"),
                    Color.parseColor("#4F1215"),
                    Color.parseColor("#8A2124"),
                    Color.parseColor("#B52D30")
                ),
                floatArrayOf(0f, 0.45f, 0.82f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, fillRight, h, fillPaint)

            // Crisp luminous leading edge line at the right tip of the fill
            fillPaint.shader = null
            fillPaint.color = Color.parseColor("#D9FF5A5E")
            canvas.drawRect(max(0f, fillRight - 2.5f), 0f, fillRight, h, fillPaint)
        }

        // 3. Top MIB3 Glass Sheen Overlay across the entire rectangle
        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.48f,
            Color.parseColor("#1AFFFFFF"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.48f, sheenPaint)

        // 4. Outer Glass Border & Bottom Accent Line
        borderPaint.color = Color.parseColor("#38FFFFFF")
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
        borderPaint.color = if (pct > 0.02f) Color.parseColor("#E5393C") else Color.parseColor("#45FFFFFF")
        canvas.drawLine(0f, h - 1.5f, w, h - 1.5f, borderPaint)

        // 5. High-Contrast Vertically Centered Typography
        val textCenterY = h * 0.5f

        titlePaint.color = Color.parseColor("#E6EAF0")
        titlePaint.textSize = h * 0.21f
        titlePaint.letterSpacing = 0.05f
        titlePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        titlePaint.textAlign = Paint.Align.LEFT
        val titleBaseline = textCenterY - (titlePaint.descent() + titlePaint.ascent()) * 0.5f
        canvas.drawText(title, pad * 1.1f, titleBaseline, titlePaint)

        // Unit Label (Far Right)
        unitPaint.textAlign = Paint.Align.RIGHT
        unitPaint.color = Color.parseColor("#C8CDD6")
        unitPaint.textSize = h * 0.20f
        unitPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val unitBaseline = textCenterY - (unitPaint.descent() + unitPaint.ascent()) * 0.5f + h * 0.03f
        canvas.drawText(unit, w - pad * 1.1f, unitBaseline, unitPaint)

        // Large Primary Readout (To the left of Unit)
        val unitWidth = unitPaint.measureText(unit) + pad * 0.45f
        val valStr = String.format(Locale.US, formatStr, displayVal)
        valuePaint.textAlign = Paint.Align.RIGHT
        valuePaint.color = Color.WHITE
        valuePaint.textSize = h * 0.44f
        valuePaint.typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        val valBaseline = textCenterY - (valuePaint.descent() + valuePaint.ascent()) * 0.5f
        canvas.drawText(valStr, w - pad * 1.1f - unitWidth, valBaseline, valuePaint)
    }
}

class Mib3TempCardView(
    context: Context,
    private val title: String,
    private val unit: String,
    private val coolThresh: Float,
    private val warnThresh: Float,
    private val hotThresh: Float
) : View(context) {
    private var tempVal = coolThresh
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        setShadowLayer(4f, 0f, 1f, Color.parseColor("#CC000000"))
    }

    fun updateTemp(v: Float) {
        tempVal = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.16f

        val accentColor = when {
            tempVal < coolThresh -> Color.parseColor("#4DA6FF")
            tempVal < warnThresh -> Color.parseColor("#F2F4F8")
            tempVal < hotThresh -> Color.parseColor("#FFA726")
            else -> Color.parseColor("#FF3B3E")
        }

        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.parseColor("#0A0B0E"), Color.parseColor("#1A1D22"), Color.parseColor("#101216")),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        sheenPaint.shader = LinearGradient(
            0f, 0f, 0f, h * 0.45f,
            Color.parseColor("#14FFFFFF"),
            Color.TRANSPARENT,
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h * 0.45f, sheenPaint)

        borderPaint.color = Color.parseColor("#35FFFFFF")
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
        borderPaint.color = accentColor
        canvas.drawLine(0f, h - 2f, w, h - 2f, borderPaint)

        val textCenterY = h * 0.5f

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#C8CDD6")
        textPaint.textSize = h * 0.21f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val labelBase = textCenterY - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(title, pad * 1.2f, labelBase, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.parseColor("#9DA3AE")
        textPaint.textSize = h * 0.21f
        val unitBase = textCenterY - (textPaint.descent() + textPaint.ascent()) * 0.5f + h * 0.03f
        canvas.drawText(unit, w - pad * 1.2f, unitBase, textPaint)

        val unitW = textPaint.measureText(unit) + pad * 0.4f
        textPaint.color = accentColor
        textPaint.textSize = h * 0.40f
        textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        val valBase = textCenterY - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(String.format(Locale.US, "%.0f", tempVal), w - pad * 1.2f - unitW, valBase, textPaint)
    }
}

class Mib3GlassTile(
    context: Context,
    private val label: String,
    private val accentColor: Int
) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.shader = LinearGradient(
            0f, 0f, w, h,
            intArrayOf(Color.parseColor("#121418"), Color.parseColor("#25282E"), Color.parseColor("#0F1114")),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val sheen = Path().apply {
            moveTo(0f, 0f)
            lineTo(w, 0f)
            lineTo(w, h * 0.42f)
            lineTo(0f, h * 0.65f)
            close()
        }
        bgPaint.shader = LinearGradient(0f, 0f, 0f, h * 0.65f, Color.parseColor("#18FFFFFF"), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawPath(sheen, bgPaint)

        borderPaint.color = Color.parseColor("#35FFFFFF")
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)

        borderPaint.color = accentColor
        borderPaint.strokeWidth = 4f
        canvas.drawLine(0f, h - 2f, w, h - 2f, borderPaint)

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = h * 0.085f
        textPaint.letterSpacing = 0.08f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        canvas.drawText(label, w * 0.5f, h * 0.84f, textPaint)
    }
}

class Mib3ActionRow(
    context: Context,
    private val title: String,
    private val subtitle: String,
    onClick: () -> Unit
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(32, 16, 32, 16)
        setBackgroundColor(Color.parseColor("#17191E"))
        setOnClickListener { onClick() }

        addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9DA3AE"))
            textSize = 12f
        })
    }
}

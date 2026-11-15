package com.audi.rscockpit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread
import kotlin.math.*

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    private var activeTab = 0 // 0 = Home, 1 = CarPlay, 2 = Gauges (Car), 3 = MMI, 4 = Settings
    private val railButtons = mutableListOf<Mib3RailButton>()

    private lateinit var homePanel: FrameLayout
    private lateinit var appGridContainer: LinearLayout
    private lateinit var horizontalScrollView: HorizontalScrollView
    private lateinit var gaugesPanel: LinearLayout
    private lateinit var settingsPanel: LinearLayout
    private lateinit var clockText: TextView
    private lateinit var btStatusText: TextView
    private lateinit var uninstallDropZone: TextView

    private val installedApps = mutableListOf<AppEntry>()
    private var draggedPackageName: String? = null
    private val uiHandler = Handler(Looper.getMainLooper())
    private var scrollLoopRunning = false

    private lateinit var boostGauge: Mib3BarGaugeView
    private lateinit var loadGauge: Mib3BarGaugeView
    private lateinit var hpGauge: Mib3BarGaugeView
    private lateinit var tqGauge: Mib3BarGaugeView

    private lateinit var iatCard: Mib3TempCardView
    private lateinit var coolantCard: Mib3TempCardView
    private lateinit var oilCard: Mib3TempCardView
    private lateinit var egtCard: Mib3TempCardView

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

        loadInstalledApps()
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

        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(72), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
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

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#1C1E22"))
        })

        val mainArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            setPadding(dp(14), dp(8), dp(16), dp(10))
            setBackgroundColor(Color.BLACK)
        }

        val statusBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(26))
        }

        val headerTitle = TextView(this).apply {
            text = "AUDI SPORT  |  AP MEDIA INTERFACE"
            setTextColor(Color.parseColor("#D4D7DC"))
            textSize = 11.5f
            letterSpacing = 0.12f
            isSingleLine = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        btStatusText = TextView(this).apply {
            text = "vLINKER: STANDBY"
            setTextColor(Color.parseColor("#9DA3AE"))
            textSize = 10.5f
            letterSpacing = 0.06f
            isSingleLine = true
            setPadding(0, 0, dp(12), 0)
        }

        clockText = TextView(this).apply {
            text = "12:00 PM"
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isSingleLine = true
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        statusBar.addView(headerTitle)
        statusBar.addView(btStatusText)
        statusBar.addView(clockText)
        mainArea.addView(statusBar)

        mainArea.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(4)
                bottomMargin = dp(8)
            }
            setBackgroundColor(Color.parseColor("#1C1E22"))
        })

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.BLACK)
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

    private fun loadInstalledApps() {
        val pm = packageManager
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
        val rawApps = resolveInfos
            .filter { it.activityInfo.packageName != packageName }
            .distinctBy { it.activityInfo.packageName }
            .map {
                AppEntry(
                    packageName = it.activityInfo.packageName,
                    label = it.loadLabel(pm).toString(),
                    icon = it.loadIcon(pm)
                )
            }

        val prefs = getSharedPreferences("mib3_launcher_prefs", Context.MODE_PRIVATE)
        val savedOrder = prefs.getString("app_order", "")
            ?.split(",")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        installedApps.clear()
        val byPkg = rawApps.associateBy { it.packageName }.toMutableMap()

        for (pkg in savedOrder) {
            byPkg.remove(pkg)?.let { installedApps.add(it) }
        }
        installedApps.addAll(byPkg.values.sortedBy { it.label.lowercase(Locale.US) })
    }

    private fun saveAppOrder() {
        val serialized = installedApps.joinToString(",") { it.packageName }
        getSharedPreferences("mib3_launcher_prefs", Context.MODE_PRIVATE)
            .edit()
            .putString("app_order", serialized)
            .apply()
    }

    private fun buildHomePanel(): FrameLayout {
        val wrapper = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val hintText = TextView(this).apply {
            text = "HOLD ICON FOR 2s TO REORDER  •  DRAG TO BOTTOM TO UNINSTALL"
            setTextColor(Color.parseColor("#787E8A"))
            textSize = 9.5f
            letterSpacing = 0.08f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ).apply { bottomMargin = dp(2) }
        }

        horizontalScrollView = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply { bottomMargin = dp(32) }
        }

        appGridContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        renderAppColumns()
        horizontalScrollView.addView(appGridContainer)
        wrapper.addView(horizontalScrollView)

        uninstallDropZone = TextView(this).apply {
            text = "DROP HERE TO UNINSTALL APP"
            setTextColor(Color.WHITE)
            textSize = 12f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#D9B52D30"))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38),
                Gravity.BOTTOM
            )
        }
        wrapper.addView(uninstallDropZone)
        wrapper.addView(hintText)
        return wrapper
    }

    private fun renderAppColumns() {
        appGridContainer.removeAllViews()
        val chunked = installedApps.chunked(2)
        chunked.forEachIndexed { colIdx, pair ->
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(dp(172), ViewGroup.LayoutParams.MATCH_PARENT).apply {
                    if (colIdx > 0) leftMargin = dp(10)
                }
            }

            pair.forEachIndexed { rowIdx, app ->
                val tile = createDraggableAppTile(app).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply {
                        if (rowIdx > 0) topMargin = dp(10)
                    }
                }
                col.addView(tile)
            }

            if (pair.size == 1) {
                col.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        0,
                        1f
                    ).apply { topMargin = dp(10) }
                })
            }

            appGridContainer.addView(col)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createDraggableAppTile(app: AppEntry): FrameLayout {
        val card = Mib3AppTileFrame(this).apply {
            tag = app.packageName
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val iconView = ImageView(this).apply {
            setImageDrawable(app.icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

        val labelView = TextView(this).apply {
            text = app.label.uppercase(Locale.US)
            setTextColor(Color.parseColor("#ECEEF2"))
            textSize = 11f
            letterSpacing = 0.05f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        inner.addView(iconView)
        inner.addView(labelView)
        card.addView(inner)

        var downX = 0f
        var downY = 0f
        var dragStarted = false
        val holdRunnable = Runnable {
            dragStarted = true
            draggedPackageName = app.packageName
            card.setDraggingHighlight(true)
            uninstallDropZone.visibility = View.VISIBLE
            card.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            
            val clip = ClipData.newPlainText("app_pkg", app.packageName)
            val shadow = View.DragShadowBuilder(card)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                card.startDragAndDrop(clip, shadow, app.packageName, 0)
            } else {
                @Suppress("DEPRECATION")
                card.startDrag(clip, shadow, app.packageName, 0)
            }
        }

        card.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    dragStarted = false
                    uiHandler.postDelayed(holdRunnable, 2000L)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragStarted && (abs(event.x - downX) > dp(16) || abs(event.y - downY) > dp(16))) {
                        uiHandler.removeCallbacks(holdRunnable)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    uiHandler.removeCallbacks(holdRunnable)
                    if (!dragStarted) {
                        launchPackage(app.packageName)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    uiHandler.removeCallbacks(holdRunnable)
                    true
                }
                else -> false
            }
        }

        card.setOnDragListener { _, dragEvent ->
            when (dragEvent.action) {
                DragEvent.ACTION_DRAG_STARTED -> true
                DragEvent.ACTION_DRAG_LOCATION -> {
                    val screenX = dragEvent.x
                    val edgeThreshold = dp(70)
                    val widthPx = horizontalScrollView.width

                    if (screenX < edgeThreshold) {
                        startAutoScrollIfNeeded(-15)
                    } else if (screenX > widthPx - edgeThreshold) {
                        startAutoScrollIfNeeded(15)
                    } else {
                        stopAutoScroll()
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENTERED -> {
                    if (draggedPackageName != app.packageName) {
                        card.setDropTargetHighlight(true)
                    }
                    true
                }
                DragEvent.ACTION_DRAG_EXITED -> {
                    card.setDropTargetHighlight(false)
                    stopAutoScroll()
                    true
                }
                DragEvent.ACTION_DROP -> {
                    stopAutoScroll()
                    uninstallDropZone.visibility = View.GONE
                    card.setDropTargetHighlight(false)

                    val dropY = dragEvent.y
                    if (dropY > homePanel.height - dp(60)) {
                        val pkg = draggedPackageName
                        if (pkg != null) {
                            val uri = Uri.parse("package:$pkg")
                            val uninstallIntent = Intent(Intent.ACTION_DELETE, uri)
                            startActivity(uninstallIntent)
                        }
                    } else {
                        val fromPkg = draggedPackageName ?: return@setOnDragListener false
                        val fromIdx = installedApps.indexOfFirst { it.packageName == fromPkg }
                        val toIdx = installedApps.indexOfFirst { it.packageName == app.packageName }
                        if (fromIdx != -1 && toIdx != -1 && fromIdx != toIdx) {
                            val moved = installedApps.removeAt(fromIdx)
                            installedApps.add(toIdx, moved)
                            saveAppOrder()
                            renderAppColumns()
                        }
                    }
                    true
                }
                DragEvent.ACTION_DRAG_ENDED -> {
                    stopAutoScroll()
                    uninstallDropZone.visibility = View.GONE
                    card.setDraggingHighlight(false)
                    card.setDropTargetHighlight(false)
                    draggedPackageName = null
                    true
                }
                else -> false
            }
        }

        return card
    }

    private fun startAutoScrollIfNeeded(dx: Int) {
        if (scrollLoopRunning) return
        scrollLoopRunning = true
        thread(isDaemon = true) {
            while (scrollLoopRunning) {
                uiHandler.post { horizontalScrollView.smoothScrollBy(dx, 0) }
                Thread.sleep(25)
            }
        }
    }

    private fun stopAutoScroll() {
        scrollLoopRunning = false
    }

    private fun buildGaugesPanel(): LinearLayout {
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1.06f).apply {
                rightMargin = dp(10)
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

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.94f)
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

    private fun buildSettingsPanel(): LinearLayout {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.BLACK)
        }

        val row1 = Mib3ActionRow(this, "Enable Physical Home Button Override (Accessibility Service)", "Locks physical Home key to Audi RS Cockpit instead of UI08") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val row2 = Mib3ActionRow(this, "Set Default Home Launcher", "Select Audi RS Cockpit as default Android Home app") {
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }
        val row3 = Mib3ActionRow(this, "Bluetooth Settings (Pair Vgate vLinker MC+)", "Ensure vLinker MC+ is paired once in Android Bluetooth") {
            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        }
        val row4 = Mib3ActionRow(this, "Open Android System Settings", "Access FYT factory menu, display, and sound settings") {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        listOf(row1, row2, row3, row4).forEachIndexed { idx, row ->
            row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(58)).apply {
                if (idx > 0) topMargin = dp(8)
            }
            panel.addView(row)
        }
        return panel
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                activeTab = (activeTab - 1 + 5) % 5
                handleRailClick(activeTab)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                activeTab = (activeTab + 1) % 5
                handleRailClick(activeTab)
                return true
            }
            KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_HEADSETHOOK -> {
                try {
                    val siriIntent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(siriIntent)
                } catch (_: Exception) {
                    Toast.makeText(this, "Voice Assistant triggered", Toast.LENGTH_SHORT).show()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun handleRailClick(index: Int) {
        activeTab = index
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
// CUSTOM PITCH-BLACK MIB3 VIEWS
// =============================================================================

enum class Mib3IconType { HOME, CARPLAY, CAR_GAUGES, MMI, SETTINGS }

class Mib3RootBackground(context: Context) : LinearLayout(context) {
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
    }
    init { setWillNotDraw(false) }
}

class Mib3AppTileFrame(context: Context) : FrameLayout(context) {
    private var isDragging = false
    private var isDropTarget = false
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    init { setWillNotDraw(false) }

    fun setDraggingHighlight(active: Boolean) {
        isDragging = active
        invalidate()
    }

    fun setDropTargetHighlight(active: Boolean) {
        isDropTarget = active
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        bgPaint.color = if (isDragging || isDropTarget) Color.parseColor("#3B0E10") else Color.parseColor("#0C0D10")
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        borderPaint.color = when {
            isDropTarget -> Color.parseColor("#FF4A4D")
            isDragging -> Color.parseColor("#E5393C")
            else -> Color.parseColor("#262930")
        }
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
    }
}

class Mib3RailButton(context: Context, private val iconType: Mib3IconType) : View(context) {
    private var isActive = false
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
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

        bgPaint.color = if (isActive) Color.parseColor("#5C1618") else Color.BLACK
        canvas.drawRect(0f, 0f, w, h, bgPaint)

        borderPaint.color = if (isActive) Color.parseColor("#FF4A4D") else Color.parseColor("#22252B")
        canvas.drawLine(w - 1f, 0f, w - 1f, h, borderPaint)

        val cx = w * 0.5f
        val cy = h * 0.5f
        val s = min(w, h) * 0.25f
        val iconColor = if (isActive) Color.parseColor("#FF3B3E") else Color.parseColor("#ECEEF2")

        strokePaint.color = iconColor
        strokePaint.strokeWidth = s * 0.13f
        fillIconPaint.color = iconColor

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
                val r = s * 0.75f
                canvas.drawCircle(cx, cy, r, strokePaint)
                val phoneRect = RectF(cx - s * 0.3f, cy - s * 0.5f, cx + s * 0.3f, cy + s * 0.5f)
                canvas.drawRoundRect(phoneRect, s * 0.1f, s * 0.1f, fillIconPaint)
                val screenRect = RectF(cx - s * 0.2f, cy - s * 0.35f, cx + s * 0.2f, cy + s * 0.25f)
                val oldColor = fillIconPaint.color
                fillIconPaint.color = Color.BLACK
                canvas.drawRect(screenRect, fillIconPaint)
                fillIconPaint.color = oldColor
                canvas.drawCircle(cx, cy + s * 0.38f, s * 0.05f, fillIconPaint)
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
                canvas.drawRect(cx - s * 0.34f, cy + s * 0.04f, cx + s * 0.34f, cy + s * 0.34f, strokePaint)
            }
            Mib3IconType.MMI -> {
                // Clean MMI text in bold white/crimson Audi font matching headers
                textPaint.color = iconColor
                textPaint.textSize = s * 0.65f
                textPaint.letterSpacing = 0.08f
                val baseline = cy - (textPaint.descent() + textPaint.ascent()) * 0.5f
                canvas.drawText("MMI", cx, baseline, textPaint)
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

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0C0D10") }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val unitPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun updateValue(v: Float) {
        targetVal = v.coerceIn(minVal, maxVal)
        val diff = targetVal - displayVal
        displayVal = if (abs(diff) < 0.01f) targetVal else displayVal + diff * 0.24f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.16f

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        val pct = ((displayVal - minVal) / (maxVal - minVal)).coerceIn(0f, 1f)
        val fillRight = w * pct

        if (fillRight > 1f) {
            fillPaint.shader = LinearGradient(
                0f, 0f, fillRight, 0f,
                intArrayOf(Color.parseColor("#4F1215"), Color.parseColor("#B52D30")),
                floatArrayOf(0f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, fillRight, h, fillPaint)
        }

        borderPaint.color = Color.parseColor("#262930")
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
        borderPaint.color = if (pct > 0.02f) Color.parseColor("#E5393C") else Color.parseColor("#262930")
        canvas.drawLine(0f, h - 1.5f, w, h - 1.5f, borderPaint)

        val textCenterY = h * 0.5f

        titlePaint.color = Color.parseColor("#E6EAF0")
        titlePaint.textSize = h * 0.15f
        titlePaint.letterSpacing = 0.04f
        titlePaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        titlePaint.textAlign = Paint.Align.LEFT
        val titleBaseline = textCenterY - (titlePaint.descent() + titlePaint.ascent()) * 0.5f
        canvas.drawText(title, pad * 1.05f, titleBaseline, titlePaint)

        unitPaint.textAlign = Paint.Align.RIGHT
        unitPaint.color = Color.parseColor("#C8CDD6")
        unitPaint.textSize = h * 0.155f
        unitPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val unitBaseline = textCenterY - (unitPaint.descent() + unitPaint.ascent()) * 0.5f + h * 0.02f
        canvas.drawText(unit, w - pad * 1.05f, unitBaseline, unitPaint)

        val unitWidth = unitPaint.measureText(unit) + pad * 0.35f
        val valStr = String.format(Locale.US, formatStr, displayVal)
        val valX = w - pad * 1.05f - unitWidth

        valuePaint.textAlign = Paint.Align.RIGHT
        valuePaint.color = Color.WHITE
        valuePaint.textSize = h * 0.33f
        valuePaint.typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        val valBaseline = textCenterY - (valuePaint.descent() + valuePaint.ascent()) * 0.5f

        dropPaint.set(valuePaint)
        dropPaint.color = Color.BLACK
        canvas.drawText(valStr, valX + 2f, valBaseline + 2f, dropPaint)
        canvas.drawText(valStr, valX, valBaseline, valuePaint)
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
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0C0D10") }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun updateTemp(v: Float) {
        tempVal = v
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = h * 0.15f

        val accentColor = when {
            tempVal < coolThresh -> Color.parseColor("#4DA6FF")
            tempVal < warnThresh -> Color.parseColor("#F2F4F8")
            tempVal < hotThresh -> Color.parseColor("#FFA726")
            else -> Color.parseColor("#FF3B3E")
        }

        canvas.drawRect(0f, 0f, w, h, bgPaint)

        borderPaint.color = Color.parseColor("#262930")
        canvas.drawRect(1f, 1f, w - 1f, h - 1f, borderPaint)
        borderPaint.color = accentColor
        canvas.drawLine(0f, h - 2f, w, h - 2f, borderPaint)

        val textCenterY = h * 0.5f

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.color = Color.parseColor("#C8CDD6")
        textPaint.textSize = h * 0.138f
        textPaint.letterSpacing = 0.02f
        textPaint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val labelBase = textCenterY - (textPaint.descent() + textPaint.ascent()) * 0.5f
        canvas.drawText(title, pad * 1.0f, labelBase, textPaint)

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.color = Color.parseColor("#9DA3AE")
        textPaint.textSize = h * 0.15f
        val unitBase = textCenterY - (textPaint.descent() + textPaint.ascent()) * 0.5f + h * 0.02f
        canvas.drawText(unit, w - pad * 1.0f, unitBase, textPaint)

        val unitW = textPaint.measureText(unit) + pad * 0.25f
        val valStr = String.format(Locale.US, "%.0f", tempVal)
        val valX = w - pad * 1.0f - unitW

        textPaint.color = accentColor
        textPaint.textSize = h * 0.29f
        textPaint.typeface = Typeface.create("sans-serif-bold", Typeface.NORMAL)
        val valBase = textCenterY - (textPaint.descent() + textPaint.ascent()) * 0.5f

        dropPaint.set(textPaint)
        dropPaint.color = Color.BLACK
        canvas.drawText(valStr, valX + 2f, valBase + 2f, dropPaint)
        canvas.drawText(valStr, valX, valBase, textPaint)
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
        setPadding(32, 12, 32, 12)
        setBackgroundColor(Color.parseColor("#121418"))
        setOnClickListener { onClick() }

        addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        addView(TextView(context).apply {
            text = subtitle
            setTextColor(Color.parseColor("#9DA3AE"))
            textSize = 11.5f
        })
    }
}

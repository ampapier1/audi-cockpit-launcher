package com.audi.rscockpit

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.*
import android.widget.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.*

data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

class MainActivity : Activity() {

    private var activeTab = 0
    private var focusedTab = 0
    private var focusInContent = false
    private var focusedAppIndex = 0
    private var focusedSettingIndex = 0

    private val railButtons = mutableListOf<Mib3RailButton>()
    private val appTileViews = mutableListOf<Mib3AppTileFrame>()
    private val settingRowViews = mutableListOf<Mib3ActionRow>()

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
    @Volatile private var bleStageLabel = "BLE: SCANNING"
    @Volatile private var running = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableImmersiveFullscreen()
        requestBlePermissions()

        loadInstalledApps()
        setContentView(buildRootLayout())
        selectTab(0)
        updateRotaryHighlights()
        startClockAndUiLoop()
        startBleObd2Worker()
    }

    private fun requestBlePermissions() {
        val perms = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 101)
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveFullscreen()
        updateRotaryHighlights()
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
            layoutParams = LinearLayout.LayoutParams(dp(74), ViewGroup.LayoutParams.MATCH_PARENT)
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
                ).apply {
                    if (index > 0) topMargin = dp(3)
                }
                setOnClickListener {
                    focusedTab = index
                    handleRailActivate(index)
                }
            }
            railButtons.add(btn)
            rail.addView(btn)
        }

        root.addView(rail)

        root.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#242830"))
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
            setTextColor(Color.

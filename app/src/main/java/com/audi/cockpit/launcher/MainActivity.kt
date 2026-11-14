package com.audi.cockpit.launcher

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

class MainActivity : AppCompatActivity() {

    companion object {
        const val PKG_CARPLAY = "com.syu.carlink"
        const val PKG_MMI_AV = "com.syu.av"
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private lateinit var navButtons: List<FrameLayout>
    private lateinit var telemetryPage: View
    private lateinit var homeAppsPage: GridView
    private lateinit var xrayView: QuattroXRayView

    private lateinit var tvStatusBanner: TextView
    private lateinit var tvBoost: TextView
    private lateinit var tvIat: TextView
    private lateinit var tvCoolant: TextView
    private lateinit var tvOil: TextView
    private lateinit var tvEgt1: TextView
    private lateinit var tvEgt2: TextView
    private lateinit var tvLoad: TextView
    private lateinit var tvHpTq: TextView

    private var activeTab = 2
    private var isSimulating = true
    private var obdJob: Job? = null

    data class TelemetryData(
        val boostPsi: Float = 0.0f,
        val chargeAirF: Int = 92,
        val coolantF: Int = 194,
        val oilF: Int = 205,
        val egtBank1F: Int = 980,
        val egtBank2F: Int = 985,
        val loadPct: Int = 18,
        val crankHp: Int = 42,
        val crankTq: Int = 78,
        val connected: Boolean = false
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#0B0C0E"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        val leftRail = buildLeftNavRail()
        root.addView(leftRail, LinearLayout.LayoutParams(96, LinearLayout.LayoutParams.MATCH_PARENT))

        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        telemetryPage = buildTelemetryPage()
        homeAppsPage = buildHomeAppsGrid()

        contentFrame.addView(telemetryPage)
        contentFrame.addView(homeAppsPage)
        root.addView(contentFrame)

        setContentView(root)
        selectTab(2)
        requestBtAndStartObd()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun buildLeftNavRail(): LinearLayout {
        val rail = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#111317"))
        }

        val items = listOf(
            NavIconView.IconType.HOME,
            NavIconView.IconType.CARPLAY,
            NavIconView.IconType.TELEMETRY,
            NavIconView.IconType.MMI,
            NavIconView.IconType.SETTINGS
        )

        val buttons = mutableListOf<FrameLayout>()
        items.forEachIndexed { index, iconType ->
            val btnContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
                )
                setOnClickListener { handleNavClick(index) }
            }
            val iconView = NavIconView(this, iconType)
            btnContainer.addView(iconView)
            rail.addView(btnContainer)
            buttons.add(btnContainer)
        }
        navButtons = buttons
        return rail
    }

    private fun handleNavClick(index: Int) {
        when (index) {
            0 -> selectTab(0)
            1 -> launchExternalPackage(PKG_CARPLAY, "CarLink 2.0")
            2 -> selectTab(2)
            3 -> launchExternalPackage(PKG_MMI_AV, "Audi MMI")
            4 -> startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun selectTab(index: Int) {
        activeTab = index
        navButtons.forEachIndexed { i, frame ->
            (frame.getChildAt(0) as NavIconView).setActive(i == index)
        }
        homeAppsPage.visibility = if (index == 0) View.VISIBLE else View.GONE
        telemetryPage.visibility = if (index == 2) View.VISIBLE else View.GONE
    }

    private fun launchExternalPackage(pkg: String, label: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
        } else {
            Toast.makeText(this, "$label ($pkg) not found", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildHomeAppsGrid(): GridView {
        val grid = GridView(this).apply {
            numColumns = 5
            verticalSpacing = 24
            horizontalSpacing = 24
            setPadding(40, 32, 40, 32)
            setBackgroundColor(Color.parseColor("#0D0F12"))
            visibility = View.GONE
        }

        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val apps = packageManager.queryIntentActivities(mainIntent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(packageManager).toString().lowercase() }

        grid.adapter = object : BaseAdapter() {
            override fun getCount() = apps.size
            override fun getItem(pos: Int) = apps[pos]
            override fun getItemId(pos: Int) = pos.toLong()
            override fun getView(pos: Int, convertView: View?, parent: android.view.ViewGroup?): View {
                val info = apps[pos]
                val cell = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(12, 16, 12, 16)
                    setBackgroundColor(Color.parseColor("#16191F"))
                }
                val icon = ImageView(this@MainActivity).apply {
                    setImageDrawable(info.loadIcon(packageManager))
                    layoutParams = LinearLayout.LayoutParams(68, 68)
                }
                val label = TextView(this@MainActivity).apply {
                    text = info.loadLabel(packageManager)
                    setTextColor(Color.WHITE)
                    textSize = 13f
                    maxLines = 1
                    gravity = Gravity.CENTER
                    setPadding(0, 8, 0, 0)
                }
                cell.addView(icon)
                cell.addView(label)
                cell.setOnClickListener {
                    launchExternalPackage(info.activityInfo.packageName, label.text.toString())
                }
                return cell
            }
        }
        return grid
    }

    private fun buildTelemetryPage(): View {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 14, 24, 14)
            setBackgroundColor(Color.parseColor("#0B0C0E"))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 8)
        }
        val title = TextView(this).apply {
            text = "Car  •  RS Telemetry (Jackal Stage 2 / 192mm)"
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        tvStatusBanner = TextView(this).apply {
            text = "DEMO MODE (Tap to connect vLinker MC+)"
            setTextColor(Color.parseColor("#E5192B"))
            textSize = 13f
            setOnClickListener {
                isSimulating = !isSimulating
                if (!isSimulating) requestBtAndStartObd()
            }
        }
        header.addView(title)
        header.addView(tvStatusBanner)
        page.addView(header)

        val divider = View(this).apply {
            setBackgroundColor(Color.parseColor("#3A3F47"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        }
        page.addView(divider)

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setPadding(0, 10, 0, 6)
        }

        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(290, LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        tvOil = createGaugeRow(leftCol, "Engine oil", "205 °F")
        tvCoolant = createGaugeRow(leftCol, "Coolant", "194 °F")
        tvIat = createGaugeRow(leftCol, "Charge air (JHM)", "92 °F")
        tvBoost = createGaugeRow(leftCol, "Supercharger boost", "0.0 PSI")

        val centerCol = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        xrayView = QuattroXRayView(this)
        centerCol.addView(xrayView)

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(310, LinearLayout.LayoutParams.MATCH_PARENT)
            gravity = Gravity.CENTER_VERTICAL
        }
        tvEgt1 = createGaugeRow(rightCol, "Exhaust bank 1", "980 °F")
        tvEgt2 = createGaugeRow(rightCol, "Exhaust bank 2", "985 °F")
        tvLoad = createGaugeRow(rightCol, "Engine load", "18 %")
        tvHpTq = createGaugeRow(rightCol, "Output (Stage 2 93)", "42 HP / 78 TQ")

        body.addView(leftCol)
        body.addView(centerCol)
        body.addView(rightCol)
        page.addView(body)

        return page
    }

    private fun createGaugeRow(parent: LinearLayout, labelText: String, initialValue: String): TextView {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 6, 10, 8)
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            lp.setMargins(0, 4, 0, 4)
            layoutParams = lp
            setBackgroundColor(Color.parseColor("#13161B"))
        }
        val lbl = TextView(this).apply {
            text = labelText
            setTextColor(Color.parseColor("#B0B7C3"))
            textSize = 12f
        }
        val valView = TextView(this).apply {
            text = initialValue
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(lbl)
        box.addView(valView)
        parent.addView(box)
        return valView
    }

    private fun updateUi(data: TelemetryData) {
        tvBoost.text = String.format("%.1f PSI", data.boostPsi)
        tvIat.text = "${data.chargeAirF} °F"
        tvCoolant.text = "${data.coolantF} °F"
        tvOil.text = "${data.oilF} °F"
        tvEgt1.text = "${data.egtBank1F} °F"
        tvEgt2.text = "${data.egtBank2F} °F"
        tvLoad.text = "${data.loadPct} %"
        tvHpTq.text = "${data.crankHp} HP / ${data.crankTq} TQ"

        tvStatusBanner.text = if (data.connected) "vLinker MC+ LIVE (Simos 8.5)" else "DEMO SIMULATION (Tap for OBD2)"
        tvStatusBanner.setTextColor(if (data.connected) Color.parseColor("#2EE56B") else Color.parseColor("#E5192B"))

        xrayView.updateThermalState(
            oilF = data.oilF,
            coolantF = data.coolantF,
            iatF = data.chargeAirF,
            egtF = max(data.egtBank1F, data.egtBank2F),
            boostPsi = data.boostPsi
        )
    }

    private fun requestBtAndStartObd() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN), 101)
            }
        }
        startTelemetryLoop()
    }

    @SuppressLint("MissingPermission")
    private fun startTelemetryLoop() {
        obdJob?.cancel()
        obdJob = lifecycleScope.launch(Dispatchers.IO) {
            var tick = 0.0
            while (isActive) {
                val btManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
                val adapter: BluetoothAdapter? = btManager?.adapter
                val vLinker = try {
                    adapter?.bondedDevices?.firstOrNull {
                        it.name.contains("vLinker", ignoreCase = true) || it.name.contains("OBD", ignoreCase = true)
                    }
                } catch (_: Exception) { null }

                if (vLinker != null && !isSimulating) {
                    try {
                        val socket: BluetoothSocket = vLinker.createRfcommSocketToServiceRecord(SPP_UUID)
                        socket.connect()
                        pollObdStream(socket)
                    } catch (_: Exception) {
                        delay(2000)
                    }
                } else {
                    tick += 0.15
                    val wave = ((sin(tick) + 1.0) / 2.0).toFloat()
                    val boost = (wave * 19.8f)
                    val load = (18 + wave * 82).roundToInt()
                    val rpm = (1800 + wave * 5200).roundToInt()
                    val tq = (95 + wave * 340).roundToInt()
                    val hp = ((tq * rpm) / 5252.0).roundToInt().coerceAtMost(472)

                    val sim = TelemetryData(
                        boostPsi = boost,
                        chargeAirF = (94 + wave * 34).roundToInt(),
                        coolantF = (194 + wave * 12).roundToInt(),
                        oilF = (205 + wave * 24).roundToInt(),
                        egtBank1F = (960 + wave * 540).roundToInt(),
                        egtBank2F = (965 + wave * 545).roundToInt(),
                        loadPct = load,
                        crankHp = hp,
                        crankTq = tq,
                        connected = false
                    )
                    withContext(Dispatchers.Main) { updateUi(sim) }
                    delay(120)
                }
            }
        }
    }

    private suspend fun pollObdStream(socket: BluetoothSocket) {
        val outStream: OutputStream = socket.outputStream
        val inStream: InputStream = socket.inputStream

        fun sendCmd(cmd: String): String {
            outStream.write((cmd + "\r").toByteArray())
            outStream.flush()
            val sb = StringBuilder()
            val start = System.currentTimeMillis()
            while (System.currentTimeMillis() - start < 350) {
                if (inStream.available() > 0) {
                    val c = inStream.read().toChar()
                    if (c == '>') break
                    sb.append(c)
                }
            }
            return sb.toString().replace("\r", " ").replace("\n", " ").trim()
        }

        sendCmd("ATZ")
        sendCmd("ATE0")
        sendCmd("ATL0")
        sendCmd("ATSP6")
        sendCmd("ATSH7E0")

        var baroKpa = 99
        while (socket.isConnected && !isSimulating) {
            val baroHex = sendCmd("0133")
            parseBytes(baroHex, "41 33")?.firstOrNull()?.let { baroKpa = it }

            val mapKpa = parseBytes(sendCmd("010B"), "41 0B")?.firstOrNull() ?: baroKpa
            val boostPsi = max(0f, (mapKpa - baroKpa) * 0.145038f)

            val coolantC = (parseBytes(sendCmd("0105"), "41 05")?.firstOrNull() ?: 90) - 40
            val iatC = (parseBytes(sendCmd("010F"), "41 0F")?.firstOrNull() ?: 35) - 40
            val loadPct = ((parseBytes(sendCmd("0104"), "41 04")?.firstOrNull() ?: 45) * 100) / 255

            val rpmBytes = parseBytes(sendCmd("010C"), "41 0C")
            val rpm = if (rpmBytes != null && rpmBytes.size >= 2) ((rpmBytes[0] * 256) + rpmBytes[1]) / 4 else 850

            val oilBytes = parseBytes(sendCmd("015C"), "41 5C")
            val oilC = if (oilBytes != null && oilBytes.isNotEmpty()) oilBytes[0] - 40 else coolantC + 6

            val egt1Bytes = parseBytes(sendCmd("0178"), "41 78")
            val egt1C = if (egt1Bytes != null && egt1Bytes.size >= 3) (((egt1Bytes[1] * 256) + egt1Bytes[2]) / 10) - 40 else (480 + loadPct * 4)

            val boostFactor = (boostPsi / 19.5f).coerceIn(0f, 1.15f)
            val estTorqueLbFt = (75 + (365 * (0.45f * (loadPct / 100f) + 0.55f * boostFactor))).roundToInt()
            val estHp = ((estTorqueLbFt * rpm) / 5252.0).roundToInt()

            val liveData = TelemetryData(
                boostPsi = boostPsi,
                chargeAirF = (iatC * 9 / 5) + 32,
                coolantF = (coolantC * 9 / 5) + 32,
                oilF = (oilC * 9 / 5) + 32,
                egtBank1F = (egt1C * 9 / 5) + 32,
                egtBank2F = ((egt1C + 4) * 9 / 5) + 32,
                loadPct = loadPct,
                crankHp = estHp,
                crankTq = estTorqueLbFt,
                connected = true
            )
            withContext(Dispatchers.Main) { updateUi(liveData) }
            delay(60)
        }
    }

    private fun parseBytes(raw: String, header: String): List<Int>? {
        val clean = raw.replace(" ", "")
        val hdr = header.replace(" ", "")
        val idx = clean.indexOf(hdr)
        if (idx == -1) return null
        val payload = clean.substring(idx + hdr.length)
        val out = mutableListOf<Int>()
        var i = 0
        while (i + 1 < payload.length) {
            payload.substring(i, i + 2).toIntOrNull(16)?.let { out.add(it) }
            i += 2
        }
        return out
    }
}

class NavIconView(context: Context, private val type: IconType) : View(context) {
    enum class IconType { HOME, CARPLAY, TELEMETRY, MMI, SETTINGS }

    private var active = false
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3.2f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 21f
        typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val borderPaint = Paint().apply {
        color = Color.parseColor("#2A2E35")
        strokeWidth = 2f
    }

    fun setActive(isActive: Boolean) {
        active = isActive
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        if (active) {
            val grad = LinearGradient(
                0f, 0f, w, 0f,
                intArrayOf(Color.parseColor("#D91627"), Color.parseColor("#7A0B14")),
                null, Shader.TileMode.CLAMP
            )
            val bg = Paint().apply { shader = grad }
            canvas.drawRect(0f, 0f, w, h, bg)
            val glowBar = Paint().apply { color = Color.parseColor("#FF2A3C") }
            canvas.drawRect(0f, 0f, 6f, h, glowBar)
        }

        canvas.drawLine(0f, h - 1f, w, h - 1f, borderPaint)
        canvas.drawLine(w - 1f, 0f, w - 1f, h, borderPaint)

        val cx = w / 2f
        val cy = h / 2f

        when (type) {
            IconType.HOME -> {
                val p = Path().apply {
                    moveTo(cx - 18f, cy - 2f)
                    lineTo(cx, cy - 18f)
                    lineTo(cx + 18f, cy - 2f)
                    moveTo(cx - 13f, cy - 5f)
                    lineTo(cx - 13f, cy + 15f)
                    lineTo(cx + 13f, cy + 15f)
                    lineTo(cx + 13f, cy - 5f)
                }
                canvas.drawPath(p, strokePaint)
            }
            IconType.CARPLAY -> {
                canvas.drawCircle(cx, cy, 18f, strokePaint)
                val play = Path().apply {
                    moveTo(cx - 6f, cy - 9f)
                    lineTo(cx + 9f, cy)
                    lineTo(cx - 6f, cy + 9f)
                    close()
                }
                canvas.drawPath(play, strokePaint)
            }
            IconType.TELEMETRY -> {
                val car = Path().apply {
                    moveTo(cx - 18f, cy + 8f)
                    lineTo(cx - 18f, cy + 1f)
                    lineTo(cx - 13f, cy - 9f)
                    lineTo(cx + 13f, cy - 9f)
                    lineTo(cx + 18f, cy + 1f)
                    lineTo(cx + 18f, cy + 8f)
                    close()
                }
                canvas.drawPath(car, strokePaint)
                canvas.drawLine(cx - 14f, cy + 11f, cx - 10f, cy + 11f, strokePaint)
                canvas.drawLine(cx + 10f, cy + 11f, cx + 14f, cy + 11f, strokePaint)
            }
            IconType.MMI -> {
                canvas.drawText("MMI", cx, cy + 8f, textPaint)
            }
            IconType.SETTINGS -> {
                canvas.drawCircle(cx, cy, 8f, strokePaint)
                for (i in 0 until 8) {
                    val rad = Math.toRadians(i * 45.0)
                    val x1 = cx + (11f * kotlin.math.cos(rad)).toFloat()
                    val y1 = cy + (11f * kotlin.math.sin(rad)).toFloat()
                    val x2 = cx + (16f * kotlin.math.cos(rad)).toFloat()
                    val y2 = cy + (16f * kotlin.math.sin(rad)).toFloat()
                    canvas.drawLine(x1, y1, x2, y2, strokePaint)
                }
            }
        }
    }
}

class QuattroXRayView(context: Context) : View(context) {
    private var engineColor = Color.parseColor("#18A858")
    private var intercoolerColor = Color.parseColor("#1D8FE1")
    private var exhaustColor = Color.parseColor("#D4B11E")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#7E8A9A")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val legendText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11f
    }

    fun updateThermalState(oilF: Int, coolantF: Int, iatF: Int, egtF: Int, boostPsi: Float) {
        engineColor = tempToColor(max(oilF, coolantF), cold = 150, warm = 215, hot = 242)
        intercoolerColor = tempToColor(iatF, cold = 85, warm = 125, hot = 155)
        exhaustColor = if (boostPsi > 12f || egtF > 1350) Color.parseColor("#E5192B")
        else tempToColor(egtF, cold = 700, warm = 1150, hot = 1450)
        invalidate()
    }

    private fun tempToColor(temp: Int, cold: Int, warm: Int, hot: Int): Int {
        return when {
            temp < cold -> Color.parseColor("#1D8FE1")
            temp < warm -> Color.parseColor("#18A858")
            temp < hot -> Color.parseColor("#D4B11E")
            else -> Color.parseColor("#E5192B")
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.54f
        val cy = h * 0.50f

        glowPaint.color = intercoolerColor
        glowPaint.alpha = 185
        val hxRect = RectF(cx - 175f, cy - 34f, cx - 150f, cy + 36f)
        canvas.drawRoundRect(hxRect, 6f, 6f, glowPaint)
        canvas.drawRoundRect(hxRect, 6f, 6f, linePaint)

        glowPaint.color = engineColor
        glowPaint.alpha = 195
        val engineRect = RectF(cx - 142f, cy - 42f, cx - 48f, cy + 32f)
        canvas.drawRoundRect(engineRect, 10f, 10f, glowPaint)
        canvas.drawRoundRect(engineRect, 10f, 10f, linePaint)

        val scRect = RectF(cx - 130f, cy - 52f, cx - 64f, cy - 34f)
        canvas.drawRoundRect(scRect, 5f, 5f, glowPaint)
        canvas.drawRoundRect(scRect, 5f, 5f, linePaint)

        val exPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = exhaustColor
            style = Paint.Style.STROKE
            strokeWidth = 6f
        }
        canvas.drawLine(cx - 85f, cy + 28f, cx + 115f, cy + 44f, exPaint)
        canvas.drawLine(cx - 85f, cy - 28f, cx + 115f, cy - 44f, exPaint)

        canvas.drawLine(cx - 48f, cy - 5f, cx + 125f, cy - 5f, linePaint)
        val rearDiff = RectF(cx + 125f, cy - 22f, cx + 158f, cy + 14f)
        glowPaint.color = Color.parseColor("#2B6CB0")
        glowPaint.alpha = 170
        canvas.drawRoundRect(rearDiff, 6f, 6f, glowPaint)
        canvas.drawRoundRect(rearDiff, 6f, 6f, linePaint)

        drawWheel(canvas, cx - 125f, cy - 78f)
        drawWheel(canvas, cx - 125f, cy + 78f)
        drawWheel(canvas, cx + 142f, cy - 74f)
        drawWheel(canvas, cx + 142f, cy + 74f)

        val labels = listOf(
            "Overheated" to "#E5192B",
            "Hot" to "#D4B11E",
            "Warm" to "#18A858",
            "Cold" to "#1D8FE1"
        )
        labels.forEachIndexed { idx, (name, hex) ->
            val top = h - 98f + (idx * 22f)
            val box = RectF(10f, top, 108f, top + 18f)
            val p = Paint().apply { color = Color.parseColor(hex); alpha = 160 }
            canvas.drawRect(box, p)
            canvas.drawRect(box, linePaint)
            canvas.drawText(name, 16f, top + 13f, legendText)
        }
    }

    private fun drawWheel(canvas: Canvas, x: Float, y: Float) {
        val wheelRect = RectF(x - 24f, y - 14f, x + 24f, y + 14f)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#232830") }
        canvas.drawOval(wheelRect, fill)
        canvas.drawOval(wheelRect, linePaint)
    }
}

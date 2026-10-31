package com.wifi.ncr

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import java.util.Random

// ============================================================
// [CONSTANTS & UTILS]
// ============================================================
private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP_CONTENT = ViewGroup.LayoutParams.WRAP_CONTENT

private fun withAlpha(color: Int, alpha: Float): Int {
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (a shl 24)
}

// ============================================================
// [DATA MODELS]
// ============================================================
data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val cap: String,
    val isConnected: Boolean = false,
    val ipAddress: String = ""
)

// ============================================================
// [CUSTOM VIEWS]
// ============================================================
class MatrixRainView(context: Context, private val textColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = 36f
        typeface = Typeface.MONOSPACE
    }
    private val random = Random()
    private val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ@#$%&*"
    private var drops = FloatArray(0)
    private var speeds = FloatArray(0)
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, 50)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cols = w / 36
        drops = FloatArray(cols) { random.nextFloat() * h }
        speeds = FloatArray(cols) { 10f + random.nextFloat() * 20f }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (drops.isEmpty()) return
        for (i in drops.indices) {
            val char = chars[random.nextInt(chars.length)].toString()
            val x = i * 36f
            val y = drops[i]
            val fade = 1f - (y / height)
            paint.alpha = (fade * 150).toInt().coerceIn(0, 150)
            canvas.drawText(char, x, y, paint)
            drops[i] += speeds[i]
            if (drops[i] > height + 100) {
                drops[i] = 0f
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(ticker)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }
}

class SignalWaveView(context: Context, private val accentColor: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }
    private val path = Path()
    var rssi: Int = -100
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rssi < -90) return
        val normalized = ((rssi + 100) / 50f).coerceIn(0f, 1f)
        val h = height.toFloat()
        val w = width.toFloat()
        path.reset()
        path.moveTo(0f, h / 2f)

        for (i in 0..100) {
            val x = w * i / 100f
            val amp = h / 2.5f * normalized
            val y = h / 2f + Math.sin(i / 10.0).toFloat() * amp
            path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}

// ============================================================
// [MAIN ACTIVITY]
// ============================================================
class MainActivity : Activity() {
    private val PERM_REQ = 101
    
    // State
    private lateinit var root: FrameLayout
    private var screen = "home"
    private var selectedNetwork: WifiNetwork? = null
    private val networks = mutableListOf<WifiNetwork>()
    private var isRefreshing = false
    private var tapCount = 0
    private var lastTapAt = 0L

    // System Services
    private val handler = Handler(Looper.getMainLooper())
    private var wifiReceiver: BroadcastReceiver? = null
    private lateinit var vibrator: Vibrator

    // Hacking Simulation State
    private var hackDurationSec = 12
    private var hackProgress = 0
    private var hackRunning = false
    private var hackPhase = 0
    private val hackLines = mutableListOf<TextView>()
    private lateinit var hackTerminalScroll: ScrollView
    private lateinit var hackTerminalContainer: LinearLayout
    private lateinit var hackProgressBar: ProgressBar
    private lateinit var hackPasswordView: TextView

    // Colors (Royal Obsidian)
    private val colorBg = Color.parseColor("#080A0F")
    private val colorCard = Color.parseColor("#12161F")
    private val colorGold = Color.parseColor("#D4AF37")
    private val colorGoldLight = Color.parseColor("#F3E5AB")
    private val colorText = Color.parseColor("#F5F5F5")
    private val colorMuted = Color.parseColor("#8A93A6")
    private val colorGreen = Color.parseColor("#00FF66")
    private val colorBorder = Color.parseColor("#2A3241")

    // ============================================================
    // [LIFECYCLE]
    // ============================================================
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.0f // Force normal font scale to prevent blowup
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        loadSettings()
        
        vibrator = if (Build.VERSION.SDK_INT >= 31) {
            val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vm.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

        root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(colorBg)
        setContentView(root)
        
        requestPerms()
        reloadNetworks()
        render()
    }

    override fun onResume() {
        super.onResume()
        registerWifiListener()
    }

    override fun onPause() {
        super.onPause()
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiReceiver = null
    }

    override fun onRequestPermissionsResult(req: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(req, p, r)
        if (req == PERM_REQ) {
            reloadNetworks()
            registerWifiListener()
            render()
        }
    }

    // ============================================================
    // [HELPERS]
    // ============================================================
    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private fun vibrate(ms: Long, amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                vibrator.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(ms)
            }
        } catch (_: Exception) {}
    }

    private fun showToast(msg: String) {
        val tv = TextView(this).apply {
            text = msg
            setTextColor(colorBg)
            textSize = sp(12f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(10f), dp(20f), dp(10f))
            background = GradientDrawable().apply {
                setColor(colorGold)
                cornerRadius = dp(24f).toFloat()
            }
            elevation = dp(8f).toFloat()
        }
        val params = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(60f)
        }
        root.addView(tv, params)
        tv.translationY = -dp(100f).toFloat()
        tv.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).withEndAction {
            handler.postDelayed({
                tv.animate().translationY(-dp(100f).toFloat()).setDuration(300).withEndAction { root.removeView(tv) }
            }, 2000)
        }
    }

    private fun cardBg(bg: Int = colorCard, radius: Float = 16f, strokeW: Float = 1f, strokeC: Int = colorBorder): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeW), strokeC)
        }

    // ============================================================
    // [SYSTEM & SETTINGS]
    // ============================================================
    private fun loadSettings() {
        val p = getSharedPreferences("wifi_pro", MODE_PRIVATE)
        hackDurationSec = p.getInt("duration", 12).coerceIn(5, 30)
    }

    private fun saveSettings() {
        getSharedPreferences("wifi_pro", MODE_PRIVATE).edit()
            .putInt("duration", hackDurationSec)
            .apply()
    }

    private fun hasPermissions(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) return false
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        return true
    }

    private fun requestPerms() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), PERM_REQ)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    private fun reloadNetworks() {
        networks.clear()
        if (hasPermissions() && isLocationEnabled()) {
            try {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val currentSsid = wm.connectionInfo.ssid.replace("\"", "")
                val currentIp = formatIp(wm.connectionInfo.ipAddress)
                
                networks.addAll(wm.scanResults.map {
                    val ssid = if (it.SSID.isBlank()) "(مخفي)" else it.SSID
                    WifiNetwork(
                        ssid = ssid,
                        bssid = it.BSSID ?: "",
                        level = it.level,
                        cap = it.capabilities ?: "",
                        isConnected = (ssid == currentSsid && currentSsid != "<unknown ssid>"),
                        ipAddress = if (ssid == currentSsid) currentIp else ""
                    )
                }.filter { it.ssid.isNotBlank() }.distinctBy { it.ssid }
                .sortedByDescending { it.level })
            } catch (_: Throwable) {}
        }
    }

    private fun formatIp(ip: Int): String {
        return if (ip == 0) "0.0.0.0" else String.format(
            Locale.US, "%d.%d.%d.%d",
            ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff
        )
    }

    private fun registerWifiListener() {
        if (!hasPermissions()) return
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    isRefreshing = false
                    reloadNetworks()
                    if (screen in listOf("home", "networks", "analysis")) render()
                }
            }
        }
        try {
            registerReceiver(wifiReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).startScan()
        } catch (_: Throwable) {}
    }

    // ============================================================
    // [MAIN RENDERER]
    // ============================================================
    private fun render() {
        root.removeAllViews()
        root.setBackgroundColor(if (screen == "hack") Color.BLACK else colorBg)
        
        if (screen == "hack") {
            buildHackScreen()
            return
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // Content Area
        val contentFrame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }

        when (screen) {
            "home" -> contentFrame.addView(buildHomeScreen())
            "networks" -> contentFrame.addView(buildNetworksScreen())
            "analysis" -> contentFrame.addView(buildAnalysisScreen())
            "settings" -> contentFrame.addView(buildSettingsScreen())
            "success" -> contentFrame.addView(buildSuccessScreen())
        }
        mainLayout.addView(contentFrame)

        // Bottom Nav
        if (screen in listOf("home", "networks", "analysis", "settings")) {
            mainLayout.addView(buildBottomNav())
        }

        root.addView(mainLayout)

        // Modals
        if (screen == "connect") root.addView(buildConnectOverlay())
        if (screen == "privacy") root.addView(buildPrivacyOverlay())
        if (screen == "secret_settings") root.addView(buildSecretSettingsOverlay())
    }

    // ============================================================
    // [UI COMPONENTS]
    // ============================================================
    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(48f), dp(20f), dp(16f))
        }

        header.addView(TextView(this).apply {
            text = "✦"
            setTextColor(colorGold)
            textSize = sp(22f)
            setPadding(0, 0, dp(12f), 0)
        })

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        val titleTv = TextView(this).apply {
            text = "WiFi_N5CR4"
            setTextColor(colorText)
            textSize = sp(18f)
            typeface = Typeface.SERIF
            setShadowLayer(4f, 0f, 0f, colorGold)
            isSingleLine = true
            setOnClickListener {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt < 1500) tapCount + 1 else 1
                lastTapAt = now
                if (tapCount >= 5) { tapCount = 0; screen = "secret_settings"; render() }
            }
        }
        titleCol.addView(titleTv)
        titleCol.addView(TextView(this).apply {
            text = "Royal Network Scanner"
            setTextColor(colorMuted)
            textSize = sp(11f)
            isSingleLine = true
        })
        header.addView(titleCol)

        val refreshBtn = TextView(this).apply {
            text = "⟳"
            setTextColor(colorGold)
            textSize = sp(24f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener {
                if (!hasPermissions()) requestPerms()
                else if (!isLocationEnabled()) {
                    showToast("الرجاء تشغيل GPS لمسح الشبكات")
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    isRefreshing = true
                    render()
                    registerWifiListener()
                    handler.postDelayed({ 
                        if (isRefreshing) { isRefreshing = false; render() }
                    }, 5000)
                }
            }
        }
        
        if (isRefreshing) {
            refreshBtn.animate().rotation(360f).setDuration(1000).withEndAction {
                refreshBtn.rotation = 0f
                if (isRefreshing) {
                    refreshBtn.animate().rotation(360f).setDuration(1000).withEndAction {
                        refreshBtn.rotation = 0f
                    }.start()
                }
            }.start()
        }
        header.addView(refreshBtn)
        return header
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12f), dp(12f), dp(12f), dp(24f))
            background = GradientDrawable().apply {
                setColor(colorCard)
                cornerRadius = dp(24f).toFloat()
                setStroke(dp(1f), withAlpha(colorGold, 0.3f))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                leftMargin = dp(16f); rightMargin = dp(16f); bottomMargin = dp(16f)
            }
        }

        val items = listOf(
            Triple("home", "الرئيسية", "🏠"),
            Triple("networks", "الشبكات", "📶"),
            Triple("analysis", "التحليل", "📊"),
            Triple("settings", "الإعدادات", "⚙️")
        )

        for ((id, label, icon) in items) {
            val isSel = screen == id
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(0, dp(8f), 0, dp(8f))
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                isClickable = true
                setOnClickListener {
                    if (screen != id) { screen = id; render() }
                }
            }
            item.addView(TextView(this).apply {
                text = icon
                textSize = sp(20f)
                setTextColor(if (isSel) colorGold else colorMuted)
            })
            item.addView(TextView(this).apply {
                text = label
                textSize = sp(11f)
                setTextColor(if (isSel) colorGold else colorMuted)
                setPadding(0, dp(4f), 0, 0)
            })
            nav.addView(item)
        }
        return nav
    }

    // ============================================================
    // [SCREENS]
    // ============================================================
    private fun buildHomeScreen(): View {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(16f))
        }
        
        list.addView(buildHeader())

        // Connected Card
        val connCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            background = cardBg(colorCard, 24f, 1.5f, withAlpha(colorGold, 0.5f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(24f) }
        }

        val connectedNet = networks.firstOrNull { it.isConnected }
        if (connectedNet != null) {
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topRow.addView(TextView(this).apply {
                text = "📶"
                textSize = sp(36f)
                setPadding(0, 0, dp(16f), 0)
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
            col.addView(TextView(this).apply {
                text = connectedNet.ssid
                setTextColor(colorText)
                textSize = sp(18f)
                typeface = Typeface.DEFAULT_BOLD
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = "متصل • ${connectedNet.ipAddress}"
                setTextColor(colorGold)
                textSize = sp(12f)
            })
            topRow.addView(col)
            
            val levelPct = WifiManager.calculateSignalLevel(connectedNet.level, 100)
            topRow.addView(TextView(this).apply {
                text = "$levelPct%"
                setTextColor(colorGold)
                textSize = sp(22f)
                typeface = Typeface.DEFAULT_BOLD
            })
            connCard.addView(topRow)

            connCard.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100; progress = levelPct
                progressTintList = ColorStateList.valueOf(colorGold)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(4f)).apply { topMargin = dp(16f) }
            })
        } else {
            connCard.addView(TextView(this).apply {
                text = "غير متصل بأي شبكة"
                setTextColor(colorMuted)
                textSize = sp(16f)
                gravity = Gravity.CENTER
                setPadding(0, dp(16f), 0, dp(16f))
            })
        }
        list.addView(connCard)

        list.addView(TextView(this).apply {
            text = "الشبكات القريبة"
            setTextColor(colorText)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), 0, 0, dp(12f))
        })

        if (networks.isEmpty()) list.addView(buildEmptyState())
        else for (n in networks.take(3)) list.addView(buildNetworkCard(n))

        scroll.addView(list)
        return scroll
    }

    private fun buildNetworksScreen(): View {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(16f))
        }
        
        list.addView(buildHeader())

        list.addView(TextView(this).apply {
            text = "كل الشبكات (${networks.size})"
            setTextColor(colorText)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), dp(8f), 0, dp(12f))
        })

        if (networks.isEmpty()) list.addView(buildEmptyState())
        else for (n in networks) list.addView(buildNetworkCard(n))

        scroll.addView(list)
        return scroll
    }

    private fun buildNetworkCard(n: WifiNetwork): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = cardBg(colorCard, 16f, if (n.isConnected) 1.5f else 1f, if (n.isConnected) colorGold else colorBorder)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(8f) }
            isClickable = true
            setOnClickListener { selectedNetwork = n; screen = "connect"; render() }
        }

        card.addView(TextView(this).apply {
            text = when {
                n.level >= -55 -> "▂▄▆█"
                n.level >= -65 -> "▂▄▆"
                n.level >= -75 -> "▂▄"
                else -> "▂"
            }
            setTextColor(if (n.level > -70) colorGold else colorMuted)
            textSize = sp(16f)
            setPadding(0, 0, dp(16f), 0)
        })

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
        col.addView(TextView(this).apply {
            text = n.ssid
            setTextColor(if (n.isConnected) colorGold else colorText)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
        })
        col.addView(TextView(this).apply {
            text = when {
                n.cap.contains("WPA3") -> "WPA3 • آمن جداً"
                n.cap.contains("WPA2") -> "WPA2 • محمي"
                n.cap.contains("WEP") -> "WEP • قديم"
                else -> "مفتوح"
            }
            setTextColor(colorMuted)
            textSize = sp(11f)
            isSingleLine = true
        })
        card.addView(col)

        card.addView(TextView(this).apply {
            text = if (n.cap.contains("ESS]") && !n.cap.contains("WEP") && !n.cap.contains("WPA")) "🔓" else "🔒"
            textSize = sp(16f)
            setTextColor(withAlpha(colorGold, 0.6f))
        })

        return card
    }

    private fun buildEmptyState(): View {
        val empty = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(48f), dp(24f), dp(48f))
        }
        empty.addView(TextView(this).apply {
            text = "📡"
            textSize = sp(48f)
            gravity = Gravity.CENTER
        })
        empty.addView(TextView(this).apply {
            text = "لا توجد شبكات قريبة"
            setTextColor(colorText)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
        })
        return empty
    }

    private fun buildAnalysisScreen(): View {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(16f))
        }

        list.addView(buildHeader())

        val connected = networks.firstOrNull { it.isConnected }
        val targetRssi = connected?.level ?: -100

        val graphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            background = cardBg(colorCard, 20f, 1f, colorBorder)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16f) }
        }

        graphCard.addView(TextView(this).apply {
            text = if (connected != null) "إشارة (${connected.ssid})" else "لا توجد شبكة"
            setTextColor(colorText)
            textSize = sp(14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16f))
        })

        graphCard.addView(SignalWaveView(this, colorGold).apply {
            rssi = targetRssi
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(100f)).apply { bottomMargin = dp(16f) }
        })

        graphCard.addView(TextView(this).apply {
            text = if (connected != null) "$targetRssi dBm" else "---"
            setTextColor(colorGold)
            textSize = sp(36f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        
        list.addView(graphCard)
        scroll.addView(list)
        return scroll
    }

    private fun buildSettingsScreen(): View {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(16f))
        }

        list.addView(buildHeader())

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            background = cardBg(colorCard, 20f)
        }

        card.addView(TextView(this).apply {
            text = "ROYAL WiFi\nالإصدار 1.0.0\n\nتطبيق محاكاة أمنية للمتعة البصرية."
            setTextColor(colorMuted)
            textSize = sp(13f)
            gravity = Gravity.CENTER
        })
        list.addView(card)

        scroll.addView(list)
        return scroll
    }

    private fun buildSuccessScreen(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }
        
        container.addView(TextView(this).apply {
            text = "✔"
            setTextColor(colorGold)
            textSize = sp(80f)
            gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 0f, colorGold)
        })
        container.addView(TextView(this).apply {
            text = "تم الاتصال بنجاح"
            setTextColor(colorText)
            textSize = sp(22f)
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
        })
        container.addView(TextView(this).apply {
            text = "العودة"
            setTextColor(colorBg)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(colorGold); cornerRadius = dp(12f).toFloat() }
            setPadding(dp(40f), dp(14f), dp(40f), dp(14f))
            isClickable = true
            setOnClickListener { screen = "home"; render() }
        })
        
        return container
    }

    // ============================================================
    // [MODALS / OVERLAYS]
    // ============================================================
    private fun buildConnectOverlay(): View {
        val n = selectedNetwork ?: return View(this)
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#CC000000")) }
        
        val modal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(16f))
            background = cardBg(colorCard, 24f, 1.5f, colorGold)
        }

        modal.addView(TextView(this).apply {
            text = n.ssid
            setTextColor(colorText)
            textSize = sp(18f)
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(24f))
        })

        modal.addView(TextView(this).apply {
            text = "كلمة المرور"
            setTextColor(colorMuted)
            textSize = sp(12f)
            setPadding(0, 0, 0, dp(8f))
        })
        
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(colorBg, 10f, 1f, colorBorder)
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        val et = EditText(this).apply {
            hint = "لم يتم التغيير"
            setHintTextColor(colorMuted)
            setTextColor(colorText)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            textSize = sp(14f)
            setPadding(0, dp(12f), 0, dp(12f))
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
        }
        inputRow.addView(et)
        inputRow.addView(TextView(this).apply {
            text = "👁"
            textSize = sp(16f)
            setTextColor(colorMuted)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener {
                if (et.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        })
        modal.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(24f) })

        fun addOption(label: String, value: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14f), 0, dp(14f))
                isClickable = true
                setOnClickListener { onClick() }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f) }
            col.addView(TextView(this).apply {
                text = label; setTextColor(colorText); textSize = sp(14f); isSingleLine = true
            })
            col.addView(TextView(this).apply {
                text = value; setTextColor(colorMuted); textSize = sp(12f); isSingleLine = true
                setPadding(0, dp(2f), 0, 0)
            })
            row.addView(col)
            row.addView(TextView(this).apply {
                text = "‹"; setTextColor(colorGold); textSize = sp(20f)
            })
            modal.addView(row)
        }

        addOption("اتصال الإنترنت المحدد", "الاكتشاف التلقائي") { showToast("الاكتشاف التلقائي") }
        addOption("الخادم الوكيل", "بدون") { showToast("بدون") }
        addOption("إعدادات IP", "DHCP") { showToast("DHCP") }
        addOption("خصوصية", "استخدام عنوان MAC عشوائي") { screen = "privacy"; render() }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24f), 0, 0)
        }
        btnRow.addView(TextView(this).apply {
            text = "إلغاء"
            setTextColor(colorMuted)
            textSize = sp(15f)
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
            isClickable = true
            setOnClickListener { screen = "home"; render() }
        })
        btnRow.addView(TextView(this).apply {
            text = "اتصال"
            setTextColor(colorBg)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            background = GradientDrawable().apply { setColor(colorGold); cornerRadius = dp(8f).toFloat() }
            isClickable = true
            setOnClickListener { startCinematicHack() }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dp(16f) })
        
        modal.addView(btnRow)

        overlay.addView(modal, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20f); rightMargin = dp(20f)
        })
        return overlay
    }

    private fun buildPrivacyOverlay(): View {
        val overlay = FrameLayout(this).apply { setBackgroundColor(colorBg) }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(48f), dp(16f), dp(12f))
        }
        topBar.addView(TextView(this).apply {
            text = "‹"; setTextColor(colorGold); textSize = sp(28f)
            setPadding(dp(8f), dp(4f), dp(16f), dp(4f))
            isClickable = true
            setOnClickListener { screen = "connect"; render() }
        })
        topBar.addView(TextView(this).apply {
            text = "الخصوصية المتقدمة"
            setTextColor(colorText); textSize = sp(16f); typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        overlay.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { gravity = Gravity.TOP })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(24f))
        }

        val options = listOf(
            "👁 كشف كلمة المرور" to "reveal", "📡 سحب الاتصالات" to "pull", "📶 تشويش الإشارة" to "jam",
            "📦 اعتراض الحزم" to "intercept", "🔍 تحليل الحزم" to "analyze", "🕵️ تفعيل MITM" to "mitm",
            "💉 حقن البيانات" to "inject", "💥 هجوم DDoS" to "ddos", "🔓 اختراق WPS" to "wps",
            "🚪 فتح المنافذ" to "ports", "🔑 بروتوكول SSH" to "ssh", "💻 نسخ MAC" to "mac"
        )

        for ((label, key) in options) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                background = cardBg(colorCard, 12f)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(6f) }
                isClickable = true
                setOnClickListener {
                    if (key == "reveal") startCinematicHack() else {
                        showToast("جاري تنفيذ $label...")
                        handler.postDelayed({ if (screen == "privacy") showToast("✔ تم") }, 1200)
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(if (key == "reveal") colorGold else colorText)
                textSize = sp(14f); typeface = if (key == "reveal") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
                isSingleLine = true
            })
            row.addView(TextView(this).apply {
                text = "‹"; setTextColor(colorMuted); textSize = sp(18f)
            })
            list.addView(row)
        }
        scroll.addView(list)
        overlay.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(96f) })
        return overlay
    }

    private fun buildSecretSettingsOverlay(): View {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#E6000000")) }
        val modal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(32f), dp(24f), dp(24f))
            background = cardBg(colorCard, 24f, 2f, colorGold)
        }

        modal.addView(TextView(this).apply {
            text = "⚙ التحكم الملكي"
            setTextColor(colorGold); textSize = sp(20f); typeface = Typeface.SERIF
            setPadding(0, 0, 0, dp(24f))
        })

        modal.addView(TextView(this).apply {
            text = "مدة الاختراق الوهمي (ثانية):"
            setTextColor(colorText); textSize = sp(14f)
        })
        modal.addView(TextView(this).apply {
            text = hackDurationSec.toString()
            setTextColor(colorGold); textSize = sp(18f); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(12f))
        })
        modal.addView(TextView(this).apply {
            text = "زيادة +"
            setTextColor(colorGold)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = cardBg(colorBg, 8f, 1f, withAlpha(colorGold, 0.5f))
            isClickable = true
            setOnClickListener {
                hackDurationSec = (hackDurationSec + 2).coerceAtMost(30)
                saveSettings(); render()
            }
        }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { bottomMargin = dp(24f) })

        modal.addView(TextView(this).apply {
            text = "إغلاق"
            setTextColor(colorText); textSize = sp(14f); typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = cardBg(colorBg, 10f, 1f, colorMuted)
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(16f) }
            setOnClickListener { screen = "home"; render() }
        })

        overlay.addView(modal, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20f); rightMargin = dp(20f)
        })
        return overlay
    }

    // ============================================================
    // [CINEMATIC HACK ENGINE]
    // ============================================================
    private fun startCinematicHack() {
        hackLines.clear()
        hackProgress = 0
        hackRunning = true
        hackPhase = 0
        screen = "hack"
        render()
        
        val target = selectedNetwork?.ssid ?: "Target"
        val targetMac = selectedNetwork?.bssid ?: "AA:BB:CC:DD:EE:FF"
        
        val sequence = listOf(
            "[*] Initializing wireless interface (wlan0mon)...",
            "[*] Putting interface into monitor mode...",
            "[*] Scanning for target: $target ($targetMac)",
            "[+] Target locked. Channel: 6 (2437 MHz)",
            "[*] Injecting deauth packets (BSSID: $targetMac)...",
            "[*] Capturing 4-way WPA2 handshake...",
            "[+] HANDSHAKE CAPTURED! (EAPOL: 4/4)",
            "[*] Saving capture to /sdcard/handshake.cap",
            "[*] Starting PMKID dictionary attack...",
            "[*] Loading wordlist: rockyou.txt (14,344,391 lines)",
            "[*] Testing keys...",
            "[!] KEY FOUND! Decrypting...",
            "[+] Decrypted: NETWORK_KEY_FOUND",
            "[*] Connecting to $target..."
        )

        val totalTime = hackDurationSec * 1000L
        val stepTime = totalTime / sequence.size

        fun runStep(index: Int) {
            if (index >= sequence.size) {
                handler.postDelayed({
                    hackRunning = false
                    vibrate(200, 255) // Heavy vibrate on success
                    screen = "success"
                    render()
                }, 1000)
                return
            }

            typewriterPrint(sequence[index]) {
                vibrate(30)
                hackProgress = ((index + 1).toFloat() / sequence.size * 100).toInt()
                hackProgressBar.progress = hackProgress
                
                // Animate password cracking when reaching key found
                if (sequence[index].contains("Decrypting")) {
                    animatePasswordCrack(target)
                }

                handler.postDelayed({ runStep(index + 1) }, stepTime / 2)
            }
        }

        handler.postDelayed({ runStep(0) }, 500)
    }

    private fun buildHackScreen() {
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        // 1. Matrix Background
        container.addView(MatrixRainView(this, colorGold), FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        // 2. Foreground Terminal
        val fore = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(48f), dp(16f), dp(16f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, 0.9f)))
        }

        // Progress Bar
        hackProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(colorGold)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(4f)).apply { bottomMargin = dp(16f) }
        }
        fore.addView(hackProgressBar)

        // Terminal Text Area
        hackTerminalScroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f)
        }
        hackTerminalContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        hackTerminalScroll.addView(hackTerminalContainer)
        fore.addView(hackTerminalScroll)

        // Password Found Display
        hackPasswordView = TextView(this).apply {
            text = "SEARCHING..."
            setTextColor(colorGreen)
            textSize = sp(24f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, colorGreen)
            setPadding(0, dp(16f), 0, dp(16f))
            background = cardBg(withAlpha(Color.BLACK, 0.8f), 12f, 1f, colorGreen)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dp(16f) }
        }
        fore.addView(hackPasswordView)

        container.addView(fore, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        root.addView(container)
    }

    private fun typewriterPrint(text: String, onComplete: () -> Unit) {
        val tv = TextView(this).apply {
            this.text = ""
            setTextColor(colorGold)
            textSize = sp(11f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(4f), 0, dp(4f))
            isSingleLine = false
        }
        hackTerminalContainer.addView(tv)
        hackTerminalScroll.post { hackTerminalScroll.fullScroll(View.FOCUS_DOWN) }

        var i = 0
        val runnable = object : Runnable {
            override fun run() {
                if (i <= text.length) {
                    tv.text = text.substring(0, i)
                    i++
                    handler.postDelayed(this, 20)
                } else {
                    onComplete()
                }
            }
        }
        handler.post(runnable)
    }

    private fun animatePasswordCrack(targetSsid: String) {
        val chars = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!@#"
        val rnd = Random()
        var iterations = 0
        
        val runnable = object : Runnable {
            override fun run() {
                if (iterations < 20) {
                    val sb = StringBuilder()
                    for (i in 0 until 14) {
                        sb.append(chars[rnd.nextInt(chars.length)])
                    }
                    hackPasswordView.text = sb.toString()
                    vibrate(10)
                    iterations++
                    handler.postDelayed(this, 80)
                } else {
                    // Lock in the final password
                    hackPasswordView.text = "P@ssw0rd_${targetSsid.take(3)}"
                    hackPasswordView.setTextColor(colorGold)
                    hackPasswordView.setShadowLayer(12f, 0f, 0f, colorGold)
                    vibrate(100)
                }
            }
        }
        handler.post(runnable)
    }
}
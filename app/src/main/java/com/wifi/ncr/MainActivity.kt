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
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.util.Random

private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private fun withAlpha(c: Int, a: Float): Int = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val cap: String,
    val isConnected: Boolean = false,
    val ipAddress: String = ""
)

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

class MainActivity : Activity() {
    private val PERM_REQ = 101
    private lateinit var root: FrameLayout
    private var screen = "home"
    private var selectedNetwork: WifiNetwork? = null
    private var tapCount = 0
    private var lastTapAt = 0L
    private val networks = mutableListOf<WifiNetwork>()
    private val handler = Handler(Looper.getMainLooper())
    private var wifiReceiver: BroadcastReceiver? = null
    private var isRefreshing = false

    private var hackDurationSec = 8
    private var hackLines = mutableListOf<String>()
    private var hackProgress = 0
    private var hackDone = false
    private var hackSessionStart = 0L
    private lateinit var hackTerminalScroll: ScrollView
    private lateinit var hackTextView: TextView
    private lateinit var hackProgressBar: ProgressBar
    private lateinit var hackProgressText: TextView

    private var uiScale = 1.0f

    private val colorBg = Color.parseColor("#090D14")
    private val colorCard = Color.parseColor("#171F2B")
    private val colorGold = Color.parseColor("#D9B33E")
    private val colorText = Color.parseColor("#F5F5F5")
    private val colorMuted = Color.parseColor("#929BAA")

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        config.fontScale = 1.0f
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        loadSettings()
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

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * uiScale, resources.displayMetrics).toInt()
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v * uiScale, resources.displayMetrics)

    private fun showToast(s: String) {
        val tv = TextView(this).apply {
            text = s
            setTextColor(colorBg)
            textSize = sp(13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
            background = GradientDrawable().apply {
                setColor(colorGold)
                cornerRadius = dp(24f).toFloat()
            }
            elevation = dp(8f).toFloat()
        }
        val params = FrameLayout.LayoutParams(WRAP, WRAP).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(60f)
        }
        root.addView(tv, params)
        tv.translationY = -dp(150f).toFloat()
        tv.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).withEndAction {
            handler.postDelayed({
                tv.animate().translationY(-dp(150f).toFloat()).setDuration(300).withEndAction { root.removeView(tv) }
            }, 2500)
        }
    }

    private fun cardBg(bg: Int = colorCard, radius: Float = 20f, strokeW: Float = 1f, strokeC: Int = Color.TRANSPARENT): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
            if (strokeW > 0) setStroke(dp(strokeW), strokeC)
        }

    private fun loadSettings() {
        val p = getSharedPreferences("wifi_pro", MODE_PRIVATE)
        hackDurationSec = p.getInt("duration", 8).coerceIn(2, 60)
        uiScale = p.getFloat("ui_scale", 1.0f).coerceIn(0.8f, 1.3f)
    }

    private fun saveSettings() {
        getSharedPreferences("wifi_pro", MODE_PRIVATE).edit()
            .putInt("duration", hackDurationSec)
            .putFloat("ui_scale", uiScale)
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
            networks.addAll(loadRealNetworks())
            networks.sortByDescending { it.level }
        }
    }

    private fun loadRealNetworks(): List<WifiNetwork> {
        return try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val currentSsid = wm.connectionInfo.ssid.replace("\"", "")
            val currentIp = formatIp(wm.connectionInfo.ipAddress)
            
            wm.scanResults.map {
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
        } catch (_: Throwable) { emptyList() }
    }

    private fun formatIp(ip: Int): String {
        return if (ip == 0) "0.0.0.0" else String.format(
            java.util.Locale.US, "%d.%d.%d.%d",
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

    private fun render() {
        root.removeAllViews()
        root.setBackgroundColor(colorBg)
        
        if (screen == "hack") {
            renderHack()
            return
        }

        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

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

        if (screen in listOf("home", "networks", "analysis", "settings")) {
            mainLayout.addView(buildBottomNav())
        }

        root.addView(mainLayout)

        if (screen == "connect") root.addView(buildConnectOverlay())
        if (screen == "privacy") root.addView(buildPrivacyOverlay())
        if (screen == "secret_settings") root.addView(buildSecretSettingsOverlay())
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(48f), dp(20f), dp(16f))
        }

        header.addView(TextView(this).apply {
            text = "✦"
            setTextColor(colorGold)
            textSize = sp(24f)
            setPadding(0, 0, dp(12f), 0)
        })

        val titleCol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleTv = TextView(this).apply {
            text = "ROYAL WiFi"
            setTextColor(colorText)
            textSize = sp(18f)
            typeface = Typeface.SERIF
            setShadowLayer(4f, 0f, 0f, colorGold)
            setOnClickListener {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt < 1500) tapCount + 1 else 1
                lastTapAt = now
                if (tapCount >= 5) { tapCount = 0; screen = "secret_settings"; render() }
            }
        }
        titleCol.addView(titleTv)
        titleCol.addView(TextView(this).apply {
            text = "Network Scanner"
            setTextColor(colorMuted)
            textSize = sp(11f)
        })
        header.addView(titleCol, LinearLayout.LayoutParams(0, WRAP, 1f))

        val refreshBtn = TextView(this).apply {
            text = "⟳"
            setTextColor(colorGold)
            textSize = sp(22f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener {
                if (!hasPermissions()) requestPerms()
                else if (!isLocationEnabled()) {
                    showToast("الرجاء تشغيل خدمات الموقع لمسح الشبكات")
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
                setStroke(dp(1f), withAlpha(colorGold, 0.2f))
            }
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply {
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
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
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

    private fun buildHomeScreen(): View {
        val scroll = ScrollView(this).apply { layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT) }
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), 0, dp(16f), dp(16f))
        }
        
        list.addView(buildHeader())

        val connCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            background = cardBg(colorCard, 24f, 1.5f, withAlpha(colorGold, 0.4f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { bottomMargin = dp(24f) }
        }

        val connectedNet = networks.firstOrNull { it.isConnected }
        if (connectedNet != null) {
            val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            topRow.addView(TextView(this).apply {
                text = "📶"
                textSize = sp(36f)
                setPadding(0, 0, dp(16f), 0)
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = connectedNet.ssid
                setTextColor(colorText)
                textSize = sp(18f)
                typeface = Typeface.DEFAULT_BOLD
            })
            col.addView(TextView(this).apply {
                text = "متصل • ${connectedNet.ipAddress}"
                setTextColor(colorGold)
                textSize = sp(12f)
            })
            topRow.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            
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

            val secType = if (connectedNet.cap.contains("WPA3")) "WPA3" else if (connectedNet.cap.contains("WPA2")) "WPA2" else "مفتوح"
            connCard.addView(TextView(this).apply {
                text = "الحماية: $secType"
                setTextColor(colorMuted)
                textSize = sp(12f)
                setPadding(0, dp(12f), 0, 0)
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

        if (networks.isEmpty()) {
            list.addView(buildEmptyState())
        } else {
            for (n in networks.take(3)) {
                list.addView(buildNetworkCard(n))
            }
            if (networks.size > 3) {
                list.addView(TextView(this).apply {
                    text = "عرض كل الشبكات (${networks.size})"
                    setTextColor(colorGold)
                    textSize = sp(13f)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(16f), 0, dp(16f))
                    isClickable = true
                    setOnClickListener { screen = "networks"; render() }
                })
            }
        }

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

        if (networks.isEmpty()) {
            list.addView(buildEmptyState())
        } else {
            for (n in networks) {
                list.addView(buildNetworkCard(n))
            }
        }

        scroll.addView(list)
        return scroll
    }

    private fun buildNetworkCard(n: WifiNetwork): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
            background = cardBg(colorCard, 16f, if (n.isConnected) 1.5f else 1f, if (n.isConnected) colorGold else Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { bottomMargin = dp(8f) }
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

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f) }
        col.addView(TextView(this).apply {
            text = n.ssid
            setTextColor(if (n.isConnected) colorGold else colorText)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
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
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(2f), 0, 0)
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
        empty.addView(TextView(this).apply {
            text = "تأكد من تشغيل GPS ومنح الصلاحيات"
            setTextColor(colorMuted)
            textSize = sp(13f)
            gravity = Gravity.CENTER
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

        list.addView(TextView(this).apply {
            text = "تحليل الإشارة"
            setTextColor(colorText)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), dp(8f), 0, dp(12f))
        })

        val connected = networks.firstOrNull { it.isConnected }
        val targetRssi = connected?.level ?: -100

        val graphCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(20f))
            background = cardBg(colorCard, 20f, 1f, withAlpha(colorGold, 0.2f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { bottomMargin = dp(16f) }
        }

        graphCard.addView(TextView(this).apply {
            text = if (connected != null) "إشارة الشبكة الحالية (${connected.ssid})" else "لا توجد شبكة متصلة لتحليلها"
            setTextColor(colorText)
            textSize = sp(14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16f))
        })

        val wave = SignalWaveView(this, colorGold).apply {
            rssi = targetRssi
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(100f)).apply { bottomMargin = dp(16f) }
        }
        graphCard.addView(wave)

        graphCard.addView(TextView(this).apply {
            text = if (connected != null) "$targetRssi dBm" else "---"
            setTextColor(colorGold)
            textSize = sp(36f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        
        graphCard.addView(TextView(this).apply {
            text = when {
                connected == null -> ""
                targetRssi > -55 -> "ممتاز"
                targetRssi > -65 -> "جيد جداً"
                targetRssi > -75 -> "جيد"
                else -> "ضعيف"
            }
            setTextColor(colorMuted)
            textSize = sp(14f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4f), 0, 0)
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
            text = "حجم الواجهة"
            setTextColor(colorText)
            textSize = sp(14f)
            setPadding(0, 0, 0, dp(8f))
        })
        
        val scaleRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(16f)) }
        val scales = listOf(0.85f to "صغير", 1.0f to "عادي", 1.15f to "كبير")
        for ((s, name) in scales) {
            scaleRow.addView(TextView(this).apply {
                text = name
                setTextColor(if (uiScale == s) colorGold else colorMuted)
                textSize = sp(13f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                background = cardBg(if (uiScale == s) withAlpha(colorGold, 0.1f) else Color.TRANSPARENT, 8f, 1f, if (uiScale == s) colorGold else colorMuted)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginEnd = dp(8f) }
                setOnClickListener {
                    uiScale = s; saveSettings(); render()
                }
            })
        }
        card.addView(scaleRow)

        card.addView(TextView(this).apply {
            text = "معلومات التطبيق"
            setTextColor(colorText)
            textSize = sp(14f)
            setPadding(0, dp(16f), 0, dp(8f))
        })
        card.addView(TextView(this).apply {
            text = "ROYAL WiFi\nالإصدار 1.0.0"
            setTextColor(colorMuted)
            textSize = sp(13f)
        })
        list.addView(card)

        scroll.addView(list)
        return scroll
    }

    private fun buildConnectOverlay(): View {
        val n = selectedNetwork ?: return View(this)
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#CC000000")) }
        
        val modal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(16f))
            background = cardBg(colorCard, 24f, 1.5f, withAlpha(colorGold, 0.4f))
        }

        modal.addView(TextView(this).apply {
            text = n.ssid
            setTextColor(colorText)
            textSize = sp(18f)
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            maxLines = 1
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
            background = cardBg(colorBg, 10f, 1f, Color.parseColor("#333A45"))
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
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
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
        modal.addView(inputRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { bottomMargin = dp(24f) })

        fun addOption(label: String, value: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(14f), 0, dp(14f))
                isClickable = true
                setOnClickListener { onClick() }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = label
                setTextColor(colorText)
                textSize = sp(14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = value
                setTextColor(colorMuted)
                textSize = sp(12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(TextView(this).apply {
                text = "‹"
                setTextColor(colorGold)
                textSize = sp(20f)
            })
            modal.addView(row)
        }

        addOption("اتصال الإنترنت المحدد", "الاكتشاف التلقائي") { showToast("الاكتشاف التلقائي") }
        addOption("الخادم الوكيل", "بدون") { showToast("الخادم الوكيل: بدون") }
        addOption("إعدادات IP", "DHCP") { showToast("IP: DHCP") }
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
            background = GradientDrawable().apply {
                setColor(colorGold)
                cornerRadius = dp(8f).toFloat()
            }
            isClickable = true
            setOnClickListener { startFakeHack() }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(16f) })
        
        modal.addView(btnRow)

        overlay.addView(modal, FrameLayout.LayoutParams(MATCH_PARENT, WRAP).apply {
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
            text = "‹"
            setTextColor(colorGold)
            textSize = sp(28f)
            setPadding(dp(8f), dp(4f), dp(16f), dp(4f))
            isClickable = true
            setOnClickListener { screen = "connect"; render() }
        })
        topBar.addView(TextView(this).apply {
            text = "الخصوصية المتقدمة — 24 خيار"
            setTextColor(colorText)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))

        overlay.addView(topBar, FrameLayout.LayoutParams(MATCH_PARENT, WRAP).apply { gravity = Gravity.TOP })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(24f))
        }

        val options = listOf(
            "👁 كشف كلمة المرور" to "reveal",
            "📡 سحب الاتصالات" to "pull",
            "📶 تشويش الإشارة" to "jam",
            "📦 اعتراض الحزم" to "intercept",
            "🔍 تحليل الحزم" to "analyze",
            "🕵️ تفعيل MITM" to "mitm",
            "💉 حقن البيانات" to "inject",
            "💥 هجوم DDoS" to "ddos",
            "🔓 اختراق WPS" to "wps",
            "🚪 فتح المنافذ" to "ports",
            "🔑 بروتوكول SSH" to "ssh",
            "💻 نسخ MAC" to "mac",
            "🛡️ تجاوز الحماية" to "bypass",
            "🔀 إعادة التوجيه" to "redirect",
            "🌐 تغيير DNS" to "dns",
            "📢 حقن إعلانات" to "ads",
            "📜 سجل التصفح" to "log",
            "👤 اختطاف الجلسة" to "session",
            "🔒 تشفير الاتصال" to "encrypt",
            "🎭 إخفاء الهوية" to "anonymize",
            "🛡️ مضاد التتبع" to "antitrack",
            "🧱 تجاوز الجدار الناري" to "firewall",
            "🌍 تفعيل VPN" to "vpn",
            "🚫 حظر الأجهزة" to "block"
        )

        for ((label, key) in options) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                background = cardBg(colorCard, 12f)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { bottomMargin = dp(6f) }
                isClickable = true
                setOnClickListener {
                    if (key == "reveal") startFakeHack() else {
                        showToast("جاري تنفيذ $label...")
                        handler.postDelayed({ if (screen == "privacy") showToast("✔ تم تنفيذ $label") }, 1200)
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(if (key == "reveal") colorGold else colorText)
                textSize = sp(14f)
                typeface = if (key == "reveal") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            row.addView(TextView(this).apply {
                text = "‹"
                setTextColor(colorMuted)
                textSize = sp(18f)
            })
            list.addView(row)
        }
        scroll.addView(list)
        overlay.addView(scroll, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT).apply { topMargin = dp(96f) })

        return overlay
    }

    private fun startFakeHack() {
        hackLines.clear()
        hackProgress = 0
        hackDone = false
        hackSessionStart = System.currentTimeMillis()
        screen = "hack"
        render()
        tickHack()
    }

    private fun tickHack() {
        val totalMs = (hackDurationSec * 1000L).coerceAtLeast(2000L)
        val startAt = hackSessionStart

        val ticker = object : Runnable {
            override fun run() {
                if (screen != "hack") return
                val elapsed = System.currentTimeMillis() - startAt
                hackProgress = (elapsed.toFloat() / totalMs * 100f).toInt().coerceIn(0, 100)

                if (elapsed < totalMs) {
                    hackLines.add(generateHackLine())
                    if (hackLines.size > 40) hackLines.removeAt(0)
                    updateHackText()
                    updateHackProgress()
                    handler.postDelayed(this, 100L)
                } else {
                    hackProgress = 100
                    hackLines.add("[+] KEY RECOVERED: " + generateFakePassword())
                    hackLines.add("[+] HANDSHAKE ACCEPTED")
                    hackLines.add("[+] CONNECTING DEVICE...")
                    updateHackText()
                    updateHackProgress()
                    hackDone = true
                    handler.postDelayed({ if (screen == "hack") { screen = "success"; render() } }, 1400)
                }
            }
        }
        handler.post(ticker)
    }

    private fun updateHackText() {
        if (::hackTextView.isInitialized) {
            hackTextView.text = hackLines.joinToString("\n")
            if (::hackTerminalScroll.isInitialized) {
                hackTerminalScroll.post { hackTerminalScroll.scrollTo(0, hackTextView.height) }
            }
        }
    }

    private fun updateHackProgress() {
        if (::hackProgressBar.isInitialized) {
            hackProgressBar.progress = hackProgress
            hackProgressText.text = "$hackProgress%"
        }
    }

    private fun renderHack() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(48f), dp(16f), dp(16f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(colorBg, Color.BLACK))
            layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
        }

        container.addView(TextView(this).apply {
            text = ">> ATTACK VECTOR ACTIVE"
            setTextColor(colorGold)
            textSize = sp(14f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(12f))
            setShadowLayer(4f, 0f, 0f, colorGold)
        })

        hackProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(colorGold)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, dp(6f))
        }
        container.addView(hackProgressBar)

        hackProgressText = TextView(this).apply {
            text = "0%"
            setTextColor(colorMuted)
            textSize = sp(11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            setPadding(0, dp(4f), 0, dp(12f))
        }
        container.addView(hackProgressText)

        hackTerminalScroll = ScrollView(this).apply {
            background = cardBg(Color.parseColor("#0A0A0A"), 12f, 1.5f, withAlpha(colorGold, 0.5f))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(12f) }
        }
        hackTextView = TextView(this).apply {
            text = ""
            setTextColor(colorGold)
            textSize = sp(11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
        }
        hackTerminalScroll.addView(hackTextView)
        container.addView(hackTerminalScroll)

        container.addView(TextView(this).apply {
            text = "> " + if (hackDone) "COMPLETE" else "RUNNING..."
            setTextColor(if (hackDone) colorGold else colorMuted)
            textSize = sp(12f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
        })

        root.addView(container)
    }

    private fun buildSuccessScreen(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(colorBg, Color.BLACK))
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
            text = "يمكنك الآن استخدام الشبكة بحرية"
            setTextColor(colorMuted)
            textSize = sp(14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(40f))
        })
        container.addView(TextView(this).apply {
            text = "العودة للرئيسية"
            setTextColor(colorBg)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(colorGold)
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(40f), dp(14f), dp(40f), dp(14f))
            isClickable = true
            setOnClickListener { screen = "home"; render() }
        })
        
        return container
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
            setTextColor(colorGold)
            textSize = sp(20f)
            typeface = Typeface.SERIF
            setPadding(0, 0, 0, dp(24f))
        })

        modal.addView(TextView(this).apply {
            text = "مدة الاختراق الوهمي (ثانية):"
            setTextColor(colorText)
            textSize = sp(14f)
        })
        modal.addView(TextView(this).apply {
            text = hackDurationSec.toString()
            setTextColor(colorGold)
            textSize = sp(18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(12f))
        })
        modal.addView(TextView(this).apply {
            text = "زيادة +"
            setTextColor(colorGold)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = cardBg(colorBg, 8f, 1f, withAlpha(colorGold, 0.5f))
            isClickable = true
            setOnClickListener {
                hackDurationSec = (hackDurationSec + 2).coerceAtMost(60)
                saveSettings()
                render()
            }
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { bottomMargin = dp(24f) })

        modal.addView(TextView(this).apply {
            text = "إغلاق"
            setTextColor(colorText)
            textSize = sp(14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = cardBg(colorBg, 10f, 1f, colorMuted)
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP).apply { topMargin = dp(16f) }
            setOnClickListener { screen = "home"; render() }
        })

        overlay.addView(modal, FrameLayout.LayoutParams(MATCH_PARENT, WRAP).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20f); rightMargin = dp(20f)
        })
        return overlay
    }

    private fun generateHackLine(): String {
        val rnd = Random()
        val prefixes = listOf("[*]", "[+]", "[-]", "[!]")
        val actions = listOf(
            "Probing target %s:%d",
            "Sending deauth frames to %s",
            "Capturing 4-way handshake...",
            "Brute forcing PMKID: %s",
            "Injecting packet payload: %d bytes",
            "Bypassing WPA2-CCMP encryption",
            "Reading encrypted BSSID: %s",
            "Spoofing MAC address: %s",
            "Dumping EAPOL frames... %d captured",
            "Attacking WPS pin: %d"
        )
        val target = selectedNetwork?.bssid ?: "aa:bb:cc:dd:ee:ff"
        val action = actions[rnd.nextInt(actions.size)]
        val msg = when {
            action.contains("%s:%d") -> String.format(action, target, rnd.nextInt(65535))
            action.contains("%s") && action.contains("%d") -> String.format(action, target, rnd.nextInt(10000))
            action.contains("%s") -> String.format(action, target)
            action.contains("%d") -> String.format(action, rnd.nextInt(5000))
            else -> action
        }
        return "${prefixes[rnd.nextInt(prefixes.size)]} $msg"
    }

    private fun generateFakePassword(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%^&*"
        val rnd = Random()
        return (1..16).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
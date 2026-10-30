package com.wifi.ncr

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.roundToInt

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val cap: String,
    val isConnected: Boolean = false,
    val ipAddress: String = ""
)

class MainActivity : Activity() {

    private enum class Page { HOME, NETWORKS, ANALYSIS, SETTINGS }

    private var page = Page.HOME
    private var selectedNetwork: WifiNetwork? = null
    private val networks = mutableListOf<WifiNetwork>()
    private lateinit var root: FrameLayout
    private var receiverRegistered = false
    private var refreshing = false
    private var uiScale = 1f
    private var lastScanMessage = "لم يتم تحديث النتائج بعد"

    private val bg = Color.rgb(9, 13, 20)
    private val card = Color.rgb(23, 31, 43)
    private val cardAlt = Color.rgb(29, 39, 53)
    private val gold = Color.rgb(217, 179, 62)
    private val goldDark = Color.rgb(184, 134, 11)
    private val white = Color.rgb(245, 245, 245)
    private val muted = Color.rgb(146, 155, 170)
    private val green = Color.rgb(53, 201, 139)
    private val red = Color.rgb(255, 91, 100)
    private val line = Color.rgb(49, 60, 75)

    private val wifiReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) return
            refreshing = false
            val updated = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            loadNetworks()
            lastScanMessage = if (updated) "تم تحديث نتائج الفحص" else "تعذر تحديث النتائج؛ تعرض آخر نتائج متاحة"
            if (::root.isInitialized) render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        uiScale = getSharedPreferences("wifi_pro", MODE_PRIVATE)
            .getFloat("ui_scale", 1f).coerceIn(0.85f, 1.2f)
        root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
        }
        setContentView(root)
        render()
        requestWifiPermissions()
    }

    override fun onResume() {
        super.onResume()
        registerWifiReceiver()
        loadNetworks()
        if (::root.isInitialized) render()
    }

    override fun onPause() {
        unregisterWifiReceiver()
        super.onPause()
    }

    override fun onDestroy() {
        unregisterWifiReceiver()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 410) {
            loadNetworks()
            render()
            if (hasLocationPermission()) requestScan()
        }
    }

    private fun dp(value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value * uiScale,
            resources.displayMetrics
        ).roundToInt()

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            value * uiScale,
            resources.displayMetrics
        )

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestWifiPermissions() {
        val required = mutableListOf<String>()
        if (!hasLocationPermission()) required += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            required += Manifest.permission.NEARBY_WIFI_DEVICES
        }
        if (required.isNotEmpty()) requestPermissions(required.distinct().toTypedArray(), 410)
    }

    private fun registerWifiReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(wifiReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(wifiReceiver, filter)
            }
            receiverRegistered = true
        } catch (_: Exception) {
            receiverRegistered = false
        }
    }

    private fun unregisterWifiReceiver() {
        if (!receiverRegistered) return
        try {
            unregisterReceiver(wifiReceiver)
        } catch (_: Exception) {
        } finally {
            receiverRegistered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun loadNetworks() {
        networks.clear()
        if (!hasLocationPermission()) return
        try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wm.connectionInfo
            val currentSsid = info.ssid?.removeSurrounding("\"") ?: ""
            val currentIp = formatIp(info.ipAddress)
            val results = wm.scanResults.orEmpty()
            val mapped = results.map { result ->
                val ssid = result.SSID?.takeIf { it.isNotBlank() } ?: "(شبكة مخفية)"
                WifiNetwork(
                    ssid = ssid,
                    bssid = result.BSSID.orEmpty(),
                    level = result.level,
                    cap = result.capabilities.orEmpty(),
                    isConnected = currentSsid.isNotBlank() &&
                        currentSsid != "<unknown ssid>" &&
                        ssid == currentSsid,
                    ipAddress = if (ssid == currentSsid) currentIp else ""
                )
            }
            networks.addAll(mapped.distinctBy { it.bssid.ifBlank { it.ssid } }
                .sortedByDescending { it.level })
        } catch (_: SecurityException) {
            lastScanMessage = "يلزم منح صلاحية الموقع لعرض نتائج المسح"
        } catch (_: Exception) {
            lastScanMessage = "تعذر قراءة نتائج الشبكات"
        }
    }

    private fun formatIp(ip: Int): String {
        if (ip == 0) return "غير متاح"
        return String.format(
            Locale.US, "%d.%d.%d.%d",
            ip and 0xff, (ip shr 8) and 0xff,
            (ip shr 16) and 0xff, (ip shr 24) and 0xff
        )
    }

    private fun requestScan() {
        if (!hasLocationPermission()) {
            requestWifiPermissions()
            return
        }
        refreshing = true
        render()
        try {
            @Suppress("DEPRECATION")
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val started = wm.startScan()
            if (!started) {
                refreshing = false
                lastScanMessage = "تعذر بدء المسح؛ قد يقيّد Android عدد مرات المسح"
                loadNetworks()
                render()
            }
        } catch (_: SecurityException) {
            refreshing = false
            lastScanMessage = "لا توجد صلاحية كافية لمسح الشبكات"
            render()
        } catch (_: Exception) {
            refreshing = false
            lastScanMessage = "حدث خطأ أثناء بدء المسح"
            render()
        }
    }

    private fun render() {
        root.removeAllViews()
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(bg)
        }
        val content = FrameLayout(this)
        shell.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        when (page) {
            Page.HOME -> content.addView(buildHome())
            Page.NETWORKS -> content.addView(buildNetworksPage())
            Page.ANALYSIS -> content.addView(buildAnalysisPage())
            Page.SETTINGS -> content.addView(buildSettingsPage())
        }
        shell.addView(buildBottomNav())
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun scrollPage(): Pair<ScrollView, LinearLayout> {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18f), 0, dp(18f), dp(24f))
        }
        scroll.addView(body)
        return scroll to body
    }

    private fun buildHeader(): View {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2f), dp(20f), dp(2f), dp(18f))
        }
        val mark = TextView(this).apply {
            text = "⌁"
            textSize = sp(35f)
            gravity = Gravity.CENTER
            setTextColor(gold)
            background = rounded(Color.rgb(37, 34, 25), 16f, goldDark, 1f)
            layoutParams = LinearLayout.LayoutParams(dp(54f), dp(54f))
        }
        header.addView(mark)
        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        titleBox.addView(label("ROYAL WiFi", 19f, white, true, Typeface.SERIF))
        titleBox.addView(label("NETWORK SCANNER", 9f, muted, true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, WRAP, 1f))
        val refresh = TextView(this).apply {
            text = if (refreshing) "◌" else "⟳"
            textSize = sp(27f)
            gravity = Gravity.CENTER
            setTextColor(gold)
            background = rounded(cardAlt, 14f, line, 1f)
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
            contentDescription = "تحديث الشبكات"
            setOnClickListener { requestScan() }
        }
        header.addView(refresh)
        return header
    }

    private fun buildHome(): View {
        val (scroll, body) = scrollPage()
        body.addView(buildHeader())
        body.addView(sectionHeading("حالة الاتصال", "نظرة سريعة على شبكتك الحالية"))
        val connected = networks.firstOrNull { it.isConnected }
        body.addView(buildConnectedCard(connected))
        body.addView(sectionHeading("الشبكات القريبة", "الأقوى إشارةً أولاً"))
        val preview = networks.take(3)
        if (preview.isEmpty()) {
            body.addView(buildEmptyState())
        } else {
            preview.forEach { body.addView(buildNetworkCard(it)) }
            if (networks.size > 3) {
                body.addView(actionButton("عرض جميع الشبكات  •  ${networks.size}", true) {
                    page = Page.NETWORKS
                    render()
                })
            }
        }
        body.addView(label(lastScanMessage, 11f, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, 0)
        })
        return scroll
    }

    private fun buildConnectedCard(network: WifiNetwork?): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(19f), dp(20f), dp(18f))
            background = rounded(
                Color.rgb(25, 34, 46), 23f,
                if (network != null) withAlpha(gold, 115) else line, 1f
            )
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val icon = TextView(this).apply {
            text = "◉"
            textSize = sp(34f)
            gravity = Gravity.CENTER
            setTextColor(gold)
            layoutParams = LinearLayout.LayoutParams(dp(52f), dp(52f))
            background = rounded(Color.rgb(45, 40, 27), 16f, Color.TRANSPARENT, 0f)
        }
        top.addView(icon)
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, 0, 0)
        }
        names.addView(label(network?.ssid ?: "لا توجد شبكة متصلة", 18f, white, true))
        names.addView(label(
            if (network != null) "متصل حالياً" else "اتصل بشبكة Wi-Fi من إعدادات الجهاز",
            11f, if (network != null) green else muted, true
        ))
        top.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(label(
            network?.let { "${signalPercent(it.level)}%" } ?: "—",
            21f, gold, true
        ))
        box.addView(top)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = network?.let { signalPercent(it.level) } ?: 0
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(line)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(5f)).apply {
                topMargin = dp(17f)
            }
        }
        box.addView(progress)
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(14f), 0, 0)
        }
        details.addView(label(
            network?.let { protectionName(it.cap) } ?: "الحماية: —",
            11f, muted, false
        ), LinearLayout.LayoutParams(0, WRAP, 1f))
        details.addView(label(
            network?.ipAddress?.takeIf { it != "غير متاح" } ?: "IP: —",
            11f, muted, false
        ))
        box.addView(details)
        return box
    }

    private fun buildNetworksPage(): View {
        val (scroll, body) = scrollPage()
        body.addView(buildHeader())
        body.addView(sectionHeading("كل الشبكات", "${networks.size} شبكة مكتشفة"))
        if (networks.isEmpty()) body.addView(buildEmptyState())
        else networks.forEach { body.addView(buildNetworkCard(it)) }
        body.addView(label(lastScanMessage, 11f, muted, false).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12f), 0, 0)
        })
        return scroll
    }

    private fun buildNetworkCard(network: WifiNetwork): View {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(15f), dp(15f), dp(15f), dp(15f))
            background = rounded(
                if (network.isConnected) Color.rgb(34, 39, 42) else card,
                17f, if (network.isConnected) withAlpha(gold, 170) else line,
                if (network.isConnected) 1.3f else 0.7f
            )
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                bottomMargin = dp(9f)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { showNetworkDetails(network) }
        }
        val signal = TextView(this).apply {
            text = signalGlyph(network.level)
            textSize = sp(20f)
            gravity = Gravity.CENTER
            setTextColor(signalColor(network.level))
            layoutParams = LinearLayout.LayoutParams(dp(48f), dp(48f))
            background = rounded(Color.rgb(35, 42, 51), 14f, Color.TRANSPARENT, 0f)
        }
        outer.addView(signal)
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(8f), 0)
        }
        textColumn.addView(label(
            network.ssid, 14f,
            if (network.isConnected) gold else white, true
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        })
        textColumn.addView(label(
            (if (network.isConnected) "متصل • " else "") + protectionName(network.cap),
            10.5f, muted, false
        ).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3f), 0, 0)
        })
        outer.addView(textColumn, LinearLayout.LayoutParams(0, WRAP, 1f))
        val trailing = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        trailing.addView(label("${network.level} dBm", 11f, white, true))
        trailing.addView(label("${signalPercent(network.level)}%", 10f, muted, false))
        outer.addView(trailing)
        return outer
    }

    private fun buildAnalysisPage(): View {
        val (scroll, body) = scrollPage()
        body.addView(buildHeader())
        body.addView(sectionHeading("تحليل الإشارة", "قراءة مستوى الإشارة للشبكة المتصلة"))
        val connected = networks.firstOrNull { it.isConnected }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(24f), dp(20f), dp(24f))
            background = rounded(card, 22f, line, 1f)
        }
        panel.addView(label(
            connected?.ssid ?: "لا توجد شبكة متصلة",
            15f, white, true
        ).apply { gravity = Gravity.CENTER })
        panel.addView(label(
            connected?.let { "${it.level} dBm" } ?: "—",
            38f, gold, true
        ).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(18f), 0, dp(4f))
        })
        panel.addView(label(
            connected?.let { signalQuality(it.level) } ?: "اتصل بشبكة لعرض التحليل",
            13f, muted, true
        ).apply { gravity = Gravity.CENTER })
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = connected?.let { signalPercent(it.level) } ?: 0
            progressTintList = ColorStateList.valueOf(gold)
            progressBackgroundTintList = ColorStateList.valueOf(line)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(7f)).apply {
                topMargin = dp(22f)
            }
        }
        panel.addView(bar)
        body.addView(panel)
        body.addView(sectionHeading("مقياس الإشارة", "القيمة التقريبية من RSSI"))
        val scale = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(17f), dp(17f), dp(17f), dp(17f))
            background = rounded(card, 18f, line, 1f)
        }
        listOf(
            "ممتاز" to "−50 إلى −59 dBm",
            "جيد جداً" to "−60 إلى −69 dBm",
            "جيد" to "−70 إلى −79 dBm",
            "ضعيف" to "−80 dBm أو أقل"
        ).forEach { (name, range) ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7f), 0, dp(7f))
            }
            row.addView(label(name, 12f, white, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            row.addView(label(range, 11f, muted, false))
            scale.addView(row)
        }
        body.addView(scale)
        body.addView(label(
            "ملاحظة: النسبة المعروضة تقدير بصري لقيمة RSSI وليست قياساً لسرعة الإنترنت.",
            10.5f, muted, false
        ).apply { setPadding(dp(4f), dp(12f), dp(4f), 0) })
        return scroll
    }

    private fun buildSettingsPage(): View {
        val (scroll, body) = scrollPage()
        body.addView(buildHeader())
        body.addView(sectionHeading("الإعدادات", "خصص تجربة استخدام التطبيق"))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18f), dp(18f), dp(18f), dp(18f))
            background = rounded(card, 20f, line, 1f)
        }
        panel.addView(label("حجم الواجهة", 14f, white, true))
        panel.addView(label("اختر الحجم المناسب لشاشتك", 11f, muted, false).apply {
            setPadding(0, dp(4f), 0, dp(12f))
        })
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        listOf(0.85f to "صغير", 1f to "عادي", 1.15f to "كبير").forEach { (scale, title) ->
            val chosen = kotlin.math.abs(uiScale - scale) < 0.02f
            val button = TextView(this).apply {
                text = title
                gravity = Gravity.CENTER
                textSize = sp(12f)
                setTextColor(if (chosen) bg else white)
                background = rounded(if (chosen) gold else cardAlt, 11f, if (chosen) gold else line, 1f)
                setPadding(dp(8f), dp(11f), dp(8f), dp(11f))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    uiScale = scale
                    getSharedPreferences("wifi_pro", MODE_PRIVATE).edit()
                        .putFloat("ui_scale", uiScale).apply()
                    render()
                }
            }
            row.addView(button, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginEnd = dp(7f)
            })
        }
        panel.addView(row)
        panel.addView(dividerView().apply {
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(18f)
        })
        panel.addView(label("معلومات التطبيق", 14f, white, true).apply {
            setPadding(0, dp(17f), 0, dp(7f))
        })
        panel.addView(label("ROYAL WiFi PRO  •  الإصدار 1.0.0", 11f, muted, false))
        body.addView(panel)
        body.addView(actionButton("فتح إعدادات Wi-Fi في الجهاز", false) {
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                toast("تعذر فتح إعدادات Wi-Fi")
            }
        })
        body.addView(actionButton("إعادة طلب الصلاحيات", false) {
            requestWifiPermissions()
        })
        return scroll
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(19, 26, 37), 22f, line, 1f)
            setPadding(dp(7f), dp(7f), dp(7f), dp(7f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                leftMargin = dp(14f)
                rightMargin = dp(14f)
                topMargin = dp(5f)
                bottomMargin = dp(12f)
            }
        }
        val items = listOf(
            Triple(Page.HOME, "⌂", "الرئيسية"),
            Triple(Page.NETWORKS, "▤", "الشبكات"),
            Triple(Page.ANALYSIS, "◒", "التحليل"),
            Triple(Page.SETTINGS, "⚙", "الإعدادات")
        )
        items.forEach { (target, icon, title) ->
            val active = page == target
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(3f), dp(7f), dp(3f), dp(7f))
                isClickable = true
                isFocusable = true
                background = if (active) rounded(Color.rgb(47, 42, 29), 15f, Color.TRANSPARENT, 0f) else null
                setOnClickListener {
                    if (page != target) {
                        page = target
                        render()
                    }
                }
            }
            item.addView(label(icon, 21f, if (active) gold else muted, true).apply {
                gravity = Gravity.CENTER
            })
            item.addView(label(title, 9.5f, if (active) gold else muted, active).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(3f), 0, 0)
            })
            nav.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        return nav
    }

    private fun buildEmptyState(): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20f), dp(30f), dp(20f), dp(30f))
            background = rounded(card, 18f, line, 1f)
        }
        box.addView(label("⌁", 42f, gold, true).apply { gravity = Gravity.CENTER })
        box.addView(label("لا توجد شبكات لعرضها", 14f, white, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(10f), 0, dp(5f))
        })
        box.addView(label(
            if (!hasLocationPermission()) "امنح صلاحية الموقع لعرض نتائج مسح Wi-Fi"
            else "تأكد من تشغيل Wi-Fi وخدمات الموقع ثم حدّث النتائج",
            11f, muted, false
        ).apply { gravity = Gravity.CENTER })
        box.addView(actionButton("إعادة الفحص", true) { requestScan() })
        return box
    }

    private fun sectionHeading(title: String, subtitle: String): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2f), dp(8f), dp(2f), dp(12f))
        }
        col.addView(label(title, 16f, white, true))
        col.addView(label(subtitle, 10.5f, muted, false).apply {
            setPadding(0, dp(3f), 0, 0)
        })
        return col
    }

    private fun actionButton(text: String, primary: Boolean, action: () -> Unit): View =
        TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = sp(12f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (primary) bg else gold)
            background = rounded(
                if (primary) gold else cardAlt, 13f,
                if (primary) gold else line, 1f
            )
            setPadding(dp(14f), dp(13f), dp(14f), dp(13f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(10f)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }

    private fun showNetworkDetails(network: WifiNetwork) {
        selectedNetwork = network
        val dialog = android.app.Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(22f), dp(22f), dp(22f), dp(18f))
            background = rounded(card, 23f, withAlpha(gold, 130), 1f)
        }
        panel.addView(label(network.ssid, 19f, white, true).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(16f))
        })
        detailRow(panel, "الحالة", if (network.isConnected) "متصل" else "غير متصل")
        detailRow(panel, "الحماية", protectionName(network.cap))
        detailRow(panel, "قوة الإشارة", "${network.level} dBm  •  ${signalPercent(network.level)}%")
        detailRow(panel, "BSSID", network.bssid.ifBlank { "غير متاح" })
        detailRow(panel, "عنوان IP", network.ipAddress.ifBlank { "غير متاح" })
        panel.addView(actionButton("فتح إعدادات Wi-Fi", true) {
            dialog.dismiss()
            try {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            } catch (_: Exception) {
                toast("تعذر فتح إعدادات Wi-Fi")
            }
        })
        panel.addView(actionButton("إغلاق", false) { dialog.dismiss() })
        dialog.setContentView(panel)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            WRAP
        )
        dialog.show()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.window?.setLayout(
            (resources.displayMetrics.widthPixels * 0.90f).toInt(),
            WRAP
        )
    }

    private fun detailRow(parent: LinearLayout, title: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8f), 0, dp(8f))
        }
        row.addView(label(title, 11f, muted, false), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(label(value, 11f, white, true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START
        })
        parent.addView(row)
        parent.addView(dividerView())
    }

    private fun dividerView(): View = View(this).apply {
        setBackgroundColor(line)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1f))
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean,
        family: Typeface? = null
    ): TextView = TextView(this).apply {
        text = value
        setTextColor(color)
        textSize = sp(size)
        typeface = family ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        includeFontPadding = true
    }

    private fun rounded(
        fill: Int,
        radius: Float,
        stroke: Int,
        strokeWidth: Float
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(radius).toFloat()
        if (strokeWidth > 0f) setStroke(dp(strokeWidth), stroke)
    }

    private fun signalPercent(level: Int): Int =
        (((level + 100f) / 55f) * 100f).roundToInt().coerceIn(0, 100)

    private fun signalGlyph(level: Int): String = when {
        level >= -55 -> "▂▄▆█"
        level >= -65 -> "▂▄▆"
        level >= -75 -> "▂▄"
        else -> "▂"
    }

    private fun signalColor(level: Int): Int = when {
        level >= -65 -> green
        level >= -78 -> gold
        else -> red
    }

    private fun signalQuality(level: Int): String = when {
        level >= -55 -> "ممتاز"
        level >= -65 -> "جيد جداً"
        level >= -75 -> "جيد"
        level >= -85 -> "ضعيف"
        else -> "ضعيف جداً"
    }

    private fun protectionName(capabilities: String): String = when {
        capabilities.contains("WPA3", true) -> "WPA3 • محمي"
        capabilities.contains("WPA2", true) -> "WPA2 • محمي"
        capabilities.contains("WPA", true) -> "WPA • محمي"
        capabilities.contains("WEP", true) -> "WEP • حماية قديمة"
        capabilities.isBlank() -> "نوع الحماية غير معروف"
        else -> "مفتوحة أو غير مشفرة"
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha.coerceIn(0, 255), Color.red(color), Color.green(color), Color.blue(color))

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

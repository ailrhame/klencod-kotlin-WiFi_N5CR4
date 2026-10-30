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
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Random

data class WifiNetwork(val ssid: String, val bssid: String, val level: Int, val cap: String)
data class UiTheme(
    val id: String, val name: String, val bg: Int, val card: Int,
    val text: Int, val accent: Int, val muted: Int, val border: Int
)

val THEMES = listOf(
    UiTheme("green", "Matrix", Color.parseColor("#050A05"), Color.parseColor("#0F1A0F"), Color.parseColor("#00FF66"), Color.parseColor("#00FF66"), Color.parseColor("#009944"), Color.parseColor("#1A3322")),
    UiTheme("red", "Crimson", Color.parseColor("#0A0000"), Color.parseColor("#1F0F0F"), Color.parseColor("#FF3333"), Color.parseColor("#FF3333"), Color.parseColor("#993333"), Color.parseColor("#331818")),
    UiTheme("cyan", "Frost", Color.parseColor("#000A0F"), Color.parseColor("#0F1F2F"), Color.parseColor("#00CCFF"), Color.parseColor("#00CCFF"), Color.parseColor("#0077AA"), Color.parseColor("#183344")),
    UiTheme("purple", "Nebula", Color.parseColor("#0A0010"), Color.parseColor("#1F0F2F"), Color.parseColor("#CC44FF"), Color.parseColor("#CC44FF"), Color.parseColor("#772299"), Color.parseColor("#331844"))
)

class MainActivity : Activity() {
    private val PERM_REQ = 101
    private lateinit var root: FrameLayout
    private var theme = THEMES[0]
    private var screen = "list"
    private var selectedNetwork: WifiNetwork? = null
    private var tapCount = 0
    private var lastTapAt = 0L
    private val networks = mutableListOf<WifiNetwork>()
    private val handler = Handler(Looper.getMainLooper())
    private var wifiReceiver: BroadcastReceiver? = null

    private var hackDurationSec = 8
    private var hackLines = mutableListOf<String>()
    private var hackProgress = 0
    private var hackDone = false
    private var hackSessionStart = 0L
    private lateinit var hackTerminalScroll: ScrollView
    private lateinit var hackTextView: TextView
    private lateinit var hackProgressBar: ProgressBar
    private lateinit var hackProgressText: TextView

    override fun onCreate(saved: Bundle?) {
        super.onCreate(saved)
        loadSettings()
        root = FrameLayout(this)
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setBackgroundColor(theme.bg)
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

    private fun dp(v: Float): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt()
    private fun sp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun card(bg: Int = theme.card, radius: Float = 14f, strokeW: Float = 1f, strokeC: Int = theme.border): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeW), strokeC)
        }

    private fun loadSettings() {
        val p = getSharedPreferences("wifi_pro", MODE_PRIVATE)
        hackDurationSec = p.getInt("duration", 8).coerceIn(2, 60)
        val tid = p.getString("theme", "green") ?: "green"
        theme = THEMES.firstOrNull { it.id == tid } ?: THEMES[0]
    }

    private fun saveSettings() {
        getSharedPreferences("wifi_pro", MODE_PRIVATE).edit()
            .putInt("duration", hackDurationSec)
            .putString("theme", theme.id)
            .apply()
    }

    private fun requestPerms() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED)
                needed.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), PERM_REQ)
    }

    private fun reloadNetworks() {
        networks.clear()
        val real = loadRealNetworks()
        networks.addAll(if (real.isNotEmpty()) real else sampleNetworks())
        networks.sortByDescending { it.level }
    }

    private fun loadRealNetworks(): List<WifiNetwork> {
        return try {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return emptyList()
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.scanResults.map {
                WifiNetwork(if (it.SSID.isBlank()) "(hidden)" else it.SSID, it.BSSID ?: "", it.level, it.capabilities ?: "")
            }.filter { it.ssid.isNotBlank() }.distinctBy { it.ssid }
        } catch (_: Throwable) { emptyList() }
    }

    private fun sampleNetworks() = listOf(
        WifiNetwork("TP-LINK_11F8", "aa:bb:cc:11:22:01", -45, "[WPA2-PSK-CCMP][ESS]"),
        WifiNetwork("Home_Network_5G", "aa:bb:cc:11:22:02", -52, "[WPA2-PSK-CCMP][ESS]"),
        WifiNetwork("Office_Corp_WiFi", "aa:bb:cc:11:22:03", -58, "[WPA2-802.1X-CCMP][ESS]"),
        WifiNetwork("CoffeeShop_Guest", "aa:bb:cc:11:22:04", -64, "[ESS]"),
        WifiNetwork("Neighbor_2.4G", "aa:bb:cc:11:22:05", -71, "[WPA2-PSK-CCMP][ESS]")
    )

    private fun registerWifiListener() {
        wifiReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) {} }
        wifiReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                if (i?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION && screen == "list") {
                    reloadNetworks()
                    render()
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
        root.setBackgroundColor(if (screen == "hack") Color.BLACK else theme.bg)
        when (screen) {
            "list" -> renderList()
            "connect" -> { renderList(); renderConnectOverlay() }
            "privacy" -> renderPrivacyOverlay()
            "hack" -> renderHack()
            "success" -> renderSuccess()
            "settings" -> renderSettings()
        }
    }

    private fun renderList() {
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(20f), dp(16f), dp(12f))
        }

        val title = TextView(this).apply {
            text = "WiFi_N5CR4"
            setTextColor(theme.accent)
            textSize = sp(22f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt < 1500) tapCount + 1 else 1
                lastTapAt = now
                if (tapCount >= 5) { tapCount = 0; screen = "settings"; render() }
            }
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(TextView(this).apply {
            text = "↻"
            setTextColor(theme.accent)
            textSize = sp(24f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener {
                toast("جاري المسح...")
                reloadNetworks()
                registerWifiListener()
                handler.postDelayed({ render() }, 600)
            }
        })

        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        val subtitle = TextView(this).apply {
            text = "الشبكات المتاحة (${networks.size})"
            setTextColor(theme.muted)
            textSize = sp(13f)
            setPadding(dp(20f), dp(4f), dp(20f), dp(8f))
        }
        root.addView(subtitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP; topMargin = dp(76f) })

        val scroll = ScrollView(this)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(24f))
        }

        if (networks.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "لا توجد شبكات. اضغط ↻ للمسح."
                setTextColor(theme.muted)
                textSize = sp(15f)
                gravity = Gravity.CENTER
                setPadding(0, dp(48f), 0, 0)
            })
        } else {
            for (n in networks) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16f), dp(16f), dp(16f), dp(16f))
                    background = card()
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8f) }
                    isClickable = true
                    setOnClickListener { selectedNetwork = n; screen = "connect"; render() }
                }
                row.addView(TextView(this).apply {
                    text = when {
                        n.level >= -55 -> "▂▄▆█"
                        n.level >= -65 -> "▂▄▆"
                        n.level >= -75 -> "▂▄"
                        else -> "▂"
                    }
                    setTextColor(if (n.level > -70) theme.accent else theme.muted)
                    textSize = sp(16f)
                    setPadding(0, 0, dp(12f), 0)
                })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = n.ssid
                    setTextColor(theme.text)
                    textSize = sp(16f)
                    typeface = Typeface.DEFAULT_BOLD
                })
                col.addView(TextView(this).apply {
                    text = when {
                        n.cap.contains("WPA3") -> "WPA3 • آمن جداً"
                        n.cap.contains("WPA2") -> "WPA2 • محمي"
                        n.cap.contains("WEP") -> "WEP • قديم"
                        else -> "مفتوح"
                    }
                    setTextColor(theme.muted)
                    textSize = sp(12f)
                    setPadding(0, dp(2f), 0, 0)
                })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = if (n.cap.contains("ESS]") && !n.cap.contains("WEP") && !n.cap.contains("WPA")) "🔓" else "🔒"
                    textSize = sp(18f)
                })
                listContainer.addView(row)
            }
        }

        scroll.addView(listContainer)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(108f); bottomMargin = dp(16f) })
    }

    private fun renderConnectOverlay() {
        val n = selectedNetwork ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#B3000000")) }
        val modal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(24f), dp(20f), dp(16f))
            background = GradientDrawable().apply { setColor(Color.parseColor("#1C1C1E")); cornerRadius = dp(20f).toFloat() }
        }

        modal.addView(TextView(this).apply {
            text = n.ssid
            setTextColor(Color.WHITE)
            textSize = sp(22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24f))
        })

        modal.addView(TextView(this).apply {
            text = "كلمة المرور"
            setTextColor(Color.parseColor("#8E8E93"))
            textSize = sp(12f)
            setPadding(0, 0, 0, dp(6f))
        })
        modal.addView(EditText(this).apply {
            hint = "لم يتم التغيير"
            setHintTextColor(Color.parseColor("#8E8E93"))
            setTextColor(Color.WHITE)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = card(Color.parseColor("#2C2C2E"), 8f, 0f)
            setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
            textSize = sp(15f)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(20f) })

        fun addOption(label: String, value: String, onClick: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16f), 0, dp(16f))
                isClickable = true
                setOnClickListener { onClick() }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(TextView(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = sp(15f)
            })
            col.addView(TextView(this).apply {
                text = value
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = sp(13f)
                setPadding(0, dp(2f), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "‹"
                setTextColor(Color.parseColor("#8E8E93"))
                textSize = sp(20f)
            })
            modal.addView(row)
        }

        addOption("اتصال الإنترنت المحدد", "الاكتشاف التلقائي") { toast("الاكتشاف التلقائي") }
        addOption("الخادم الوكيل", "بدون") { toast("الخادم الوكيل: بدون") }
        addOption("إعدادات IP", "DHCP") { toast("IP: DHCP") }
        addOption("خصوصية", "استخدام عنوان MAC عشوائي\n(الإعداد الافتراضي)") { screen = "privacy"; render() }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(24f), 0, 0)
        }
        btnRow.addView(TextView(this).apply {
            text = "إلغاء"
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = sp(16f)
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
            isClickable = true
            setOnClickListener { screen = "list"; render() }
        })
        btnRow.addView(TextView(this).apply {
            text = "|"
            setTextColor(Color.parseColor("#3A3A3C"))
            textSize = sp(16f)
            setPadding(dp(8f), dp(12f), dp(8f), dp(12f))
        })
        btnRow.addView(TextView(this).apply {
            text = "اتصال"
            setTextColor(Color.parseColor("#0A84FF"))
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
            isClickable = true
            setOnClickListener { startFakeHack() }
        })
        modal.addView(btnRow)

        overlay.addView(modal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(16f); rightMargin = dp(16f)
        })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderPrivacyOverlay() {
        val overlay = FrameLayout(this).apply { setBackgroundColor(theme.bg) }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12f), dp(20f), dp(12f), dp(12f))
        }
        topBar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(theme.accent)
            textSize = sp(28f)
            setPadding(dp(8f), dp(4f), dp(16f), dp(4f))
            isClickable = true
            setOnClickListener { screen = "connect"; render() }
        })
        topBar.addView(TextView(this).apply {
            text = "الخصوصية المتقدمة — 24 خيار"
            setTextColor(theme.text)
            textSize = sp(18f)
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        overlay.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), dp(4f), dp(12f), dp(24f))
        }

        val options = listOf(
            "كشف كلمة المرور" to "reveal",
            "سحب الاتصالات" to "pull",
            "تشويش الإشارة" to "jam",
            "اعتراض الحزم" to "intercept",
            "تحليل الحزم" to "analyze",
            "تفعيل MITM" to "mitm",
            "حقن البيانات" to "inject",
            "هجوم DDoS" to "ddos",
            "اختراق WPS" to "wps",
            "فتح المنافذ" to "ports",
            "بروتوكول SSH" to "ssh",
            "نسخ MAC" to "mac",
            "تجاوز الحماية" to "bypass",
            "إعادة التوجيه" to "redirect",
            "تغيير DNS" to "dns",
            "حقن إعلانات" to "ads",
            "سجل التصفح" to "log",
            "اختطاف الجلسة" to "session",
            "تشفير الاتصال" to "encrypt",
            "إخفاء الهوية" to "anonymize",
            "مضاد التتبع" to "antitrack",
            "تجاوز الجدار الناري" to "firewall",
            "تفعيل VPN" to "vpn",
            "حظر الأجهزة" to "block"
        )

        for ((label, key) in options) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                background = card()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) }
                isClickable = true
                setOnClickListener {
                    if (key == "reveal") startFakeHack() else {
                        toast("جاري تنفيذ $label...")
                        handler.postDelayed({ if (screen == "privacy") toast("✔ تم تنفيذ $label") }, 1200)
                    }
                }
            }
            row.addView(TextView(this).apply {
                text = label
                setTextColor(if (key == "reveal") theme.accent else theme.text)
                textSize = sp(15f)
                typeface = if (key == "reveal") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "‹"
                setTextColor(theme.muted)
                textSize = sp(20f)
            })
            list.addView(row)
        }
        scroll.addView(list)
        overlay.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(72f) })

        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
            setPadding(dp(16f), dp(20f), dp(16f), dp(16f))
        }

        container.addView(TextView(this).apply {
            text = ">> ATTACK VECTOR ACTIVE"
            setTextColor(theme.accent)
            textSize = sp(14f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8f))
        })

        hackProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(theme.accent)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f))
        }
        container.addView(hackProgressBar)

        hackProgressText = TextView(this).apply {
            text = "0%"
            setTextColor(theme.muted)
            textSize = sp(11f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            setPadding(0, dp(4f), 0, dp(8f))
        }
        container.addView(hackProgressText)

        hackTerminalScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A0A0A"))
                cornerRadius = dp(8f).toFloat()
                setStroke(dp(1f), theme.accent)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(8f) }
        }
        hackTextView = TextView(this).apply {
            text = ""
            setTextColor(theme.accent)
            textSize = sp(11f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }
        hackTerminalScroll.addView(hackTextView)
        container.addView(hackTerminalScroll)

        container.addView(TextView(this).apply {
            text = "> " + if (hackDone) "COMPLETE" else "RUNNING..."
            setTextColor(if (hackDone) theme.accent else theme.muted)
            textSize = sp(12f)
            typeface = Typeface.MONOSPACE
        })

        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderSuccess() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(24f), dp(24f), dp(24f))
        }
        container.addView(TextView(this).apply {
            text = "✔"
            setTextColor(theme.accent)
            textSize = sp(80f)
            gravity = Gravity.CENTER
        })
        container.addView(TextView(this).apply {
            text = "تم الاتصال بنجاح"
            setTextColor(theme.text)
            textSize = sp(22f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
        })
        container.addView(TextView(this).apply {
            text = "يمكنك الآن استخدام الشبكة بحرية"
            setTextColor(theme.muted)
            textSize = sp(14f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32f))
        })
        container.addView(TextView(this).apply {
            text = "العودة"
            setTextColor(theme.bg)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(theme.accent)
                cornerRadius = dp(12f).toFloat()
            }
            setPadding(dp(32f), dp(14f), dp(32f), dp(14f))
            isClickable = true
            setOnClickListener { screen = "list"; render() }
        })
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderSettings() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(32f), dp(24f), dp(24f))
            background = GradientDrawable().apply { setColor(theme.card); cornerRadius = dp(16f).toFloat() }
        }

        container.addView(TextView(this).apply {
            text = "⚙ الإعدادات المتقدمة"
            setTextColor(theme.accent)
            textSize = sp(20f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, dp(24f))
        })

        container.addView(TextView(this).apply {
            text = "مدة الاختراق الوهمي (ثانية):"
            setTextColor(theme.text)
            textSize = sp(14f)
        })
        container.addView(TextView(this).apply {
            text = hackDurationSec.toString()
            setTextColor(theme.accent)
            textSize = sp(18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(12f))
        })
        container.addView(TextView(this).apply {
            text = "زيادة +"
            setTextColor(theme.accent)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            background = card(theme.bg, 8f)
            isClickable = true
            setOnClickListener {
                hackDurationSec = (hackDurationSec + 2).coerceAtMost(60)
                saveSettings()
                render()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16f) })

        container.addView(TextView(this).apply {
            text = "الثيم:"
            setTextColor(theme.text)
            textSize = sp(14f)
            setPadding(0, dp(16f), 0, dp(8f))
        })

        for (t in THEMES) {
            val row = TextView(this).apply {
                text = t.name
                setTextColor(if (t.id == theme.id) theme.accent else theme.text)
                textSize = sp(15f)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = card(theme.bg, 8f, if (t.id == theme.id) 2f else 0f, t.accent)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) }
                isClickable = true
                setOnClickListener {
                    theme = t
                    saveSettings()
                    render()
                }
            }
            container.addView(row)
        }

        container.addView(TextView(this).apply {
            text = "إغلاق"
            setTextColor(theme.text)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = card(theme.bg, 10f, 1f, theme.muted)
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24f) }
            setOnClickListener { screen = "list"; render() }
        })

        val scroll = ScrollView(this)
        scroll.addView(container)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            leftMargin = dp(20f); rightMargin = dp(20f); topMargin = dp(80f); bottomMargin = dp(40f)
        })
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
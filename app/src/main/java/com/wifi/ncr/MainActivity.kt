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
import android.graphics.Color
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

data class WifiNetwork(val ssid: String, val bssid: String, val level: Int, val cap: String)
data class UiTheme(
    val id: String, val name: String,
    val bg1: Int, val bg2: Int, val card: Int, val input: Int,
    val gold: Int, val goldLight: Int, val goldDark: Int,
    val text: Int, val muted: Int, val border: Int
)

val THEMES = listOf(
    UiTheme("royal", "ملكي ذهبي", Color.parseColor("#0B0C10"), Color.parseColor("#151A20"), Color.parseColor("#1F2833"), Color.parseColor("#151A20"), Color.parseColor("#D4AF37"), Color.parseColor("#F3E5AB"), Color.parseColor("#B8860B"), Color.parseColor("#FFFFFF"), Color.parseColor("#8A8F9C"), Color.parseColor("#333A45")),
    UiTheme("crimson", "ياقوت أحمر", Color.parseColor("#100608"), Color.parseColor("#1A0B0F"), Color.parseColor("#2A1217"), Color.parseColor("#1A0B0F"), Color.parseColor("#E0314B"), Color.parseColor("#FF8FA0"), Color.parseColor("#9B1B30"), Color.parseColor("#FFFFFF"), Color.parseColor("#A38A8F"), Color.parseColor("#4A2229")),
    UiTheme("emerald", "زمرد", Color.parseColor("#06100C"), Color.parseColor("#0B1A14"), Color.parseColor("#12281F"), Color.parseColor("#0B1A14"), Color.parseColor("#3DDC97"), Color.parseColor("#A8F0CF"), Color.parseColor("#1E9E6A"), Color.parseColor("#FFFFFF"), Color.parseColor("#86A396"), Color.parseColor("#1F4033")),
    UiTheme("sapphire", "سافير", Color.parseColor("#070B16"), Color.parseColor("#0C1426"), Color.parseColor("#121D36"), Color.parseColor("#0C1426"), Color.parseColor("#6C8CFF"), Color.parseColor("#C3D0FF"), Color.parseColor("#3F5BD6"), Color.parseColor("#FFFFFF"), Color.parseColor("#8A93AD"), Color.parseColor("#26335A"))
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
    private var uiScale = 1.0f
    private var hackLines = mutableListOf<String>()
    private var hackProgress = 0
    private var hackDone = false
    private var hackSessionStart = 0L
    private lateinit var hackTerminalScroll: ScrollView
    private lateinit var hackTextView: TextView
    private lateinit var hackProgressBar: ProgressBar
    private lateinit var hackProgressText: TextView

    // Force normal font scale to prevent layout blowup
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
        root.setBackgroundColor(theme.bg1)
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
            setTextColor(theme.bg1)
            textSize = sp(13f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            background = GradientDrawable().apply {
                setColors(intArrayOf(theme.goldLight, theme.gold))
                cornerRadius = dp(24f).toFloat()
            }
        }
        val params = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(50f)
        }
        root.addView(tv, params)
        tv.translationY = -dp(150f).toFloat()
        tv.animate().translationY(0f).setDuration(300).setInterpolator(DecelerateInterpolator()).withEndAction {
            handler.postDelayed({
                tv.animate().translationY(-dp(150f).toFloat()).setDuration(300).withEndAction { root.removeView(tv) }
            }, 2500)
        }
    }

    private fun cardBg(bg: Int = theme.card, radius: Float = 14f, strokeW: Float = 1f, strokeC: Int = theme.border): GradientDrawable =
        GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(strokeW), strokeC)
        }

    private fun loadSettings() {
        val p = getSharedPreferences("wifi_pro", MODE_PRIVATE)
        hackDurationSec = p.getInt("duration", 8).coerceIn(2, 60)
        uiScale = p.getFloat("ui_scale", 1.0f).coerceIn(0.8f, 1.3f)
        val tid = p.getString("theme", "royal") ?: "royal"
        theme = THEMES.firstOrNull { it.id == tid } ?: THEMES[0]
    }

    private fun saveSettings() {
        getSharedPreferences("wifi_pro", MODE_PRIVATE).edit()
            .putInt("duration", hackDurationSec)
            .putFloat("ui_scale", uiScale)
            .putString("theme", theme.id)
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
            wm.scanResults.map {
                WifiNetwork(if (it.SSID.isBlank()) "(مخفي)" else it.SSID, it.BSSID ?: "", it.level, it.capabilities ?: "")
            }.filter { it.ssid.isNotBlank() }.distinctBy { it.ssid }
        } catch (_: Throwable) { emptyList() }
    }

    private fun registerWifiListener() {
        if (!hasPermissions()) return
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
        root.setBackgroundColor(if (screen == "hack") Color.BLACK else theme.bg1)
        if (screen != "hack") {
            root.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bg1, theme.bg2))
        }
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
            setPadding(dp(16f), dp(24f), dp(16f), dp(12f))
        }

        val title = TextView(this).apply {
            text = "WiFi_N5CR4"
            setTextColor(theme.gold)
            textSize = sp(22f)
            typeface = Typeface.SERIF
            setShadowLayer(4f, 0f, 0f, theme.goldDark)
            isSingleLine = true
            setOnClickListener {
                val now = System.currentTimeMillis()
                tapCount = if (now - lastTapAt < 1500) tapCount + 1 else 1
                lastTapAt = now
                if (tapCount >= 5) { tapCount = 0; screen = "settings"; render() }
            }
        }
        topBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        topBar.addView(TextView(this).apply {
            text = "⟳"
            setTextColor(theme.gold)
            textSize = sp(24f)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            setOnClickListener {
                if (!hasPermissions()) requestPerms()
                else if (!isLocationEnabled()) {
                    showToast("الرجاء تشغيل خدمات الموقع لمسح الشبكات")
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } else {
                    showToast("جاري المسح...")
                    registerWifiListener()
                    handler.postDelayed({ render() }, 1000)
                }
            }
        })

        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

        val subtitle = TextView(this).apply {
            text = "الشبكات المتاحة (${networks.size})"
            setTextColor(theme.muted)
            textSize = sp(12f)
            setPadding(dp(20f), dp(4f), dp(20f), dp(8f))
        }
        root.addView(subtitle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP; topMargin = dp(72f) })

        val scroll = ScrollView(this)
        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(24f))
        }

        if (!hasPermissions()) {
            listContainer.addView(TextView(this).apply {
                text = "الرجاء منح صلاحية الموقع والـ Wi-Fi للمتابعة"
                setTextColor(theme.muted)
                textSize = sp(14f)
                gravity = Gravity.CENTER
                setPadding(0, dp(48f), 0, dp(16f))
            })
            listContainer.addView(TextView(this).apply {
                text = "منح الصلاحيات"
                setTextColor(theme.bg1)
                textSize = sp(14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColors(intArrayOf(theme.goldLight, theme.gold)); cornerRadius = dp(12f).toFloat() }
                setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
                isClickable = true
                setOnClickListener { requestPerms() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(32f); rightMargin = dp(32f) })
        } else if (!isLocationEnabled()) {
            listContainer.addView(TextView(this).apply {
                text = "خدمات الموقع (GPS) مغلقة\nيجب تشغيلها لرؤية الشبكات"
                setTextColor(theme.muted)
                textSize = sp(14f)
                gravity = Gravity.CENTER
                setPadding(0, dp(48f), 0, dp(16f))
            })
            listContainer.addView(TextView(this).apply {
                text = "فتح إعدادات الموقع"
                setTextColor(theme.bg1)
                textSize = sp(14f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColors(intArrayOf(theme.goldLight, theme.gold)); cornerRadius = dp(12f).toFloat() }
                setPadding(dp(20f), dp(14f), dp(20f), dp(14f))
                isClickable = true
                setOnClickListener { startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(32f); rightMargin = dp(32f) })
        } else if (networks.isEmpty()) {
            listContainer.addView(TextView(this).apply {
                text = "لا توجد شبكات قريبة. اضغط ⟳ للمسح."
                setTextColor(theme.muted)
                textSize = sp(14f)
                gravity = Gravity.CENTER
                setPadding(0, dp(48f), 0, 0)
            })
        } else {
            for (n in networks) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
                    background = cardBg()
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
                    setTextColor(if (n.level > -70) theme.gold else theme.muted)
                    textSize = sp(14f)
                    setPadding(0, 0, dp(12f), 0)
                })
                val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                col.addView(TextView(this).apply {
                    text = n.ssid
                    setTextColor(theme.text)
                    textSize = sp(14f)
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
                    setTextColor(theme.muted)
                    textSize = sp(11f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(2f), 0, 0)
                })
                row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                row.addView(TextView(this).apply {
                    text = if (n.cap.contains("ESS]") && !n.cap.contains("WEP") && !n.cap.contains("WPA")) "🔓" else "🔒"
                    textSize = sp(16f)
                    setTextColor(theme.gold)
                })
                listContainer.addView(row)
            }
        }

        scroll.addView(listContainer)
        root.addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { topMargin = dp(96f); bottomMargin = dp(16f) })
    }

    private fun renderConnectOverlay() {
        val n = selectedNetwork ?: return
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#CC000000")) }
        val modal = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(24f), dp(24f), dp(16f))
            background = cardBg(theme.card, 20f, 2f, theme.gold)
        }

        modal.addView(TextView(this).apply {
            text = n.ssid
            setTextColor(theme.text)
            textSize = sp(18f)
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(24f))
        })

        modal.addView(TextView(this).apply {
            text = "كلمة المرور"
            setTextColor(theme.muted)
            textSize = sp(12f)
            setPadding(0, 0, 0, dp(8f))
        })
        
        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg(theme.input, 10f, 1f, theme.border)
            setPadding(dp(14f), 0, dp(14f), 0)
        }
        val et = EditText(this).apply {
            hint = "لم يتم التغيير"
            setHintTextColor(theme.muted)
            setTextColor(theme.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            background = null
            textSize = sp(14f)
            setPadding(0, dp(12f), 0, dp(12f))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val eye = TextView(this).apply {
            text = "👁"
            textSize = sp(16f)
            setTextColor(theme.muted)
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
            setOnClickListener {
                if (et.inputType == (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)) {
                    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
        }
        inputRow.addView(et)
        inputRow.addView(eye)
        modal.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(24f) })

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
                setTextColor(theme.text)
                textSize = sp(14f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            col.addView(TextView(this).apply {
                text = value
                setTextColor(theme.muted)
                textSize = sp(12f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2f), 0, 0)
            })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "‹"
                setTextColor(theme.gold)
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
            setTextColor(theme.muted)
            textSize = sp(15f)
            setPadding(dp(24f), dp(12f), dp(24f), dp(12f))
            isClickable = true
            setOnClickListener { screen = "list"; render() }
        })
        btnRow.addView(TextView(this).apply {
            text = "اتصال"
            setTextColor(theme.bg1)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(24f), dp(10f), dp(24f), dp(10f))
            background = GradientDrawable().apply {
                setColors(intArrayOf(theme.goldLight, theme.gold))
                cornerRadius = dp(8f).toFloat()
            }
            isClickable = true
            setOnClickListener { startFakeHack() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16f) })
        
        modal.addView(btnRow)

        overlay.addView(modal, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20f); rightMargin = dp(20f)
        })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderPrivacyOverlay() {
        val overlay = FrameLayout(this).apply { setBackgroundColor(theme.bg1) }
        overlay.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(theme.bg1, theme.bg2))
        
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(24f), dp(16f), dp(12f))
        }
        topBar.addView(TextView(this).apply {
            text = "‹"
            setTextColor(theme.gold)
            textSize = sp(28f)
            setPadding(dp(8f), dp(4f), dp(16f), dp(4f))
            isClickable = true
            setOnClickListener { screen = "connect"; render() }
        })
        topBar.addView(TextView(this).apply {
            text = "الخصوصية المتقدمة — 24 خيار"
            setTextColor(theme.text)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        overlay.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP })

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
                background = cardBg()
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6f) }
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
                setTextColor(if (key == "reveal") theme.gold else theme.text)
                textSize = sp(14f)
                typeface = if (key == "reveal") Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(TextView(this).apply {
                text = "‹"
                setTextColor(theme.muted)
                textSize = sp(18f)
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
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.parseColor("#050505"), Color.parseColor("#0A0A0A")))
        }

        container.addView(TextView(this).apply {
            text = ">> ATTACK VECTOR ACTIVE"
            setTextColor(theme.gold)
            textSize = sp(12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, 0, 0, dp(8f))
            setShadowLayer(4f, 0f, 0f, theme.gold)
        })

        hackProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(theme.gold)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6f))
        }
        container.addView(hackProgressBar)

        hackProgressText = TextView(this).apply {
            text = "0%"
            setTextColor(theme.muted)
            textSize = sp(10f)
            typeface = Typeface.MONOSPACE
            gravity = Gravity.END
            setPadding(0, dp(4f), 0, dp(8f))
        }
        container.addView(hackProgressText)

        hackTerminalScroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#0A0A0A"))
                cornerRadius = dp(8f).toFloat()
                setStroke(dp(1f), theme.goldDark)
            }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { bottomMargin = dp(8f) }
        }
        hackTextView = TextView(this).apply {
            text = ""
            setTextColor(theme.gold)
            textSize = sp(10f)
            typeface = Typeface.MONOSPACE
            setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
        }
        hackTerminalScroll.addView(hackTextView)
        container.addView(hackTerminalScroll)

        container.addView(TextView(this).apply {
            text = "> " + if (hackDone) "COMPLETE" else "RUNNING..."
            setTextColor(if (hackDone) theme.gold else theme.muted)
            textSize = sp(11f)
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
            setTextColor(theme.gold)
            textSize = sp(64f)
            gravity = Gravity.CENTER
            setShadowLayer(10f, 0f, 0f, theme.goldDark)
        })
        container.addView(TextView(this).apply {
            text = "تم الاتصال بنجاح"
            setTextColor(theme.text)
            textSize = sp(20f)
            typeface = Typeface.SERIF
            gravity = Gravity.CENTER
            setPadding(0, dp(16f), 0, dp(8f))
        })
        container.addView(TextView(this).apply {
            text = "يمكنك الآن استخدام الشبكة بحرية"
            setTextColor(theme.muted)
            textSize = sp(13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(32f))
        })
        container.addView(TextView(this).apply {
            text = "العودة"
            setTextColor(theme.bg1)
            textSize = sp(15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColors(intArrayOf(theme.goldLight, theme.gold))
                cornerRadius = dp(10f).toFloat()
            }
            setPadding(dp(32f), dp(12f), dp(32f), dp(12f))
            isClickable = true
            setOnClickListener { screen = "list"; render() }
        })
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private fun renderSettings() {
        val overlay = FrameLayout(this).apply { setBackgroundColor(Color.parseColor("#E6000000")) }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24f), dp(32f), dp(24f), dp(24f))
            background = cardBg(theme.card, 20f, 2f, theme.gold)
        }

        container.addView(TextView(this).apply {
            text = "⚙ التحكم الملكي"
            setTextColor(theme.gold)
            textSize = sp(20f)
            typeface = Typeface.SERIF
            setPadding(0, 0, 0, dp(24f))
        })

        container.addView(TextView(this).apply {
            text = "حجم الواجهة:"
            setTextColor(theme.text)
            textSize = sp(13f)
        })
        val scaleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4f), 0, dp(16f))
        }
        val scales = listOf(0.85f to "صغير", 1.0f to "عادي", 1.15f to "كبير")
        for ((s, name) in scales) {
            scaleRow.addView(TextView(this).apply {
                text = name
                setTextColor(if (uiScale == s) theme.gold else theme.text)
                textSize = sp(13f)
                setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
                background = cardBg(theme.bg1, 8f, if (uiScale == s) 2f else 0f, theme.gold)
                isClickable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(4f) }
                setOnClickListener {
                    uiScale = s
                    saveSettings()
                    render()
                }
            })
        }
        container.addView(scaleRow)

        container.addView(TextView(this).apply {
            text = "مدة الاختراق الوهمي (ثانية):"
            setTextColor(theme.text)
            textSize = sp(13f)
        })
        container.addView(TextView(this).apply {
            text = hackDurationSec.toString()
            setTextColor(theme.gold)
            textSize = sp(16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(4f), 0, dp(12f))
        })
        container.addView(TextView(this).apply {
            text = "زيادة +"
            setTextColor(theme.gold)
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            background = cardBg(theme.bg1, 8f, 1f, theme.goldDark)
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
            textSize = sp(13f)
            setPadding(0, dp(16f), 0, dp(8f))
        })

        for (t in THEMES) {
            val row = TextView(this).apply {
                text = t.name
                setTextColor(if (t.id == theme.id) theme.gold else theme.text)
                textSize = sp(14f)
                setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
                background = cardBg(theme.bg1, 8f, if (t.id == theme.id) 2f else 0f, t.gold)
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
            textSize = sp(14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = cardBg(theme.bg1, 10f, 1f, theme.muted)
            setPadding(0, dp(12f), 0, dp(12f))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(24f) }
            setOnClickListener { screen = "list"; render() }
        })

        overlay.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER
            leftMargin = dp(20f); rightMargin = dp(20f)
        })
        root.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
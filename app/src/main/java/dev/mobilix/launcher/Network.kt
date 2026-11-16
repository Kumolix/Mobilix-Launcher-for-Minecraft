package dev.mobilix.launcher

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address

// MobiLabs: «Игра по сети» поверх приложения ZeroTier One (виртуальная локальная сеть, как Radmin).

/** Помощники для ZeroTier One: установлен ли он, адрес устройства в его сети, порт открытого для сети мира. */
object ZeroTierNet {
    const val PKG = "com.zerotier.one"

    fun installed(ctx: Context): Boolean = try { ctx.packageManager.getPackageInfo(PKG, 0); true } catch (_: Throwable) { false }

    fun openPlay(ctx: Context) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Throwable) { try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://play.google.com/store/apps/details?id=$PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {} }
    }

    fun openApp(ctx: Context) {
        val i = ctx.packageManager.getLaunchIntentForPackage(PKG)
        if (i != null) ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else openPlay(ctx)
    }

    private fun privateV4(h: String): Boolean {
        val p = h.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168) || (p[0] == 100 && p[1] in 64..127)
    }

    /** Адреса IPv4 активных VPN-сетей (интерфейс, адрес): у ZeroTier это адрес устройства в виртуальной сети. */
    fun myIps(ctx: Context): List<Pair<String, String>> {
        val res = ArrayList<Pair<String, String>>()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) {
                    val a = la.address
                    if (a is Inet4Address && !a.isLinkLocalAddress && !a.isLoopbackAddress && a.hostAddress != null && privateV4(a.hostAddress!!)) res.add((lp.interfaceName ?: "vpn") to a.hostAddress!!)
                }
            }
        } catch (_: Throwable) {}
        return res
    }

    /** Порт последнего открытого для сети мира: Minecraft пишет «Started serving on N». */
    fun lanPort(log: File?): Int? = try {
        if (log == null || !log.exists()) null else java.io.RandomAccessFile(log, "r").use { raf ->
            val start = maxOf(0L, raf.length() - 200_000); raf.seek(start)
            val buf = ByteArray((raf.length() - start).toInt()); raf.readFully(buf)
            Regex("Started serving on (\\d+)").findAll(String(buf)).lastOrNull()?.groupValues?.get(1)?.toInt()
        }
    } catch (_: Throwable) { null }

    fun shareText(ip: String, port: Int?) = "[ZeroTier] IP: " + ip + (if (port != null) ":$port" else "")
}

private fun MainActivity.ztNets(): JSONArray = try { JSONArray(prefs.getString("ztNets", "[]")) } catch (_: Exception) { JSONArray() }

/** Страница «Сеть»: установка ZeroTier One, мой адрес, сохранённые сети. */
internal fun MainActivity.netPage(): View {
    val sv = ScrollView(this)
    val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(28)) }
    sv.addView(col)
    fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(16), dp(18), dp(16)); background = rr(Th.chip2, 20)
    }.also { col.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) }) }
    fun title(c: LinearLayout, t: String) = c.addView(TextView(this).apply { text = t; textSize = 17f; setTextColor(Th.text); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
    fun body(c: LinearLayout, t: String) = c.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(Th.dim); setLineSpacing(0f, 1.15f); setPadding(0, dp(6), 0, 0) })
    fun btn(c: LinearLayout, label: String, primary: Boolean = true, onClick: () -> Unit) = c.addView(Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) android.graphics.Color.WHITE else Th.chipText); background = sel(if (primary) Th.act else Th.chip)
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
    fun refresh() = openPage(tr("Сеть", "Network"), netPage(), animate = false)

    if (!ZeroTierNet.installed(this)) {
        val c = card()
        title(c, tr("Установите ZeroTier One", "Install ZeroTier One"))
        body(c, tr("Виртуальная локальная сеть для игры с друзьями без белого IP: все участники видят друг друга как в одной сети. Бесплатно до 25 устройств. Сначала нужно установить приложение ZeroTier One, потом вернитесь сюда.",
            "A virtual local network for playing with friends without a public IP: everyone sees each other as on one network. Free for up to 25 devices. Install the ZeroTier One app first, then come back here."))
        btn(c, tr("Открыть в Google Play", "Open in Google Play")) { ZeroTierNet.openPlay(this) }
        btn(c, tr("Я установил(а), обновить", "Installed, refresh"), false) { refresh() }
        return sv
    }

    // мой адрес
    val ips = ZeroTierNet.myIps(this)
    val st = card()
    st.addView(TextView(this).apply {
        text = if (ips.isNotEmpty()) "● " + tr("Подключено", "Connected") else "○ " + tr("Не подключено", "Not connected")
        textSize = 13f; setTextColor(if (ips.isNotEmpty()) Th.accent else Th.dim); typeface = Typeface.DEFAULT_BOLD
    })
    if (ips.isNotEmpty()) {
        st.addView(TextView(this).apply { text = ips[0].second; textSize = 30f; setTextColor(Th.text); typeface = Typeface.create("monospace", Typeface.BOLD); setPadding(0, dp(6), 0, 0) })
        body(st, tr("Ваш адрес в виртуальной сети (интерфейс ${ips[0].first}). Друзья вводят его в «Сетевая игра» вместе с портом из чата игры.", "Your address in the virtual network (interface ${ips[0].first}). Friends enter it in Multiplayer with the port from the in-game chat."))
        btn(st, tr("Копировать адрес", "Copy address")) {
            (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier", ZeroTierNet.shareText(ips[0].second, null)))
            Toast.makeText(this, tr("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
        }
    } else {
        body(st, tr("Откройте ZeroTier One и включите переключатель нужной сети. Затем вернитесь и нажмите «Обновить».", "Open ZeroTier One and switch your network on. Then come back and tap Refresh."))
    }
    btn(st, tr("Открыть ZeroTier One", "Open ZeroTier One"), ips.isEmpty()) { ZeroTierNet.openApp(this) }
    btn(st, tr("Обновить", "Refresh"), false) { refresh() }

    // сети
    val nets = ztNets()
    val nc = card()
    title(nc, tr("Мои сети", "My networks"))
    body(nc, tr("Сохраните здесь ID сетей, чтобы быстро копировать и делиться ими. Добавить сеть в ZeroTier One нужно в самом приложении (там же включается переключатель).", "Save network IDs here to copy and share them quickly. Adding a network happens in ZeroTier One itself (that is also where you switch it on)."))
    for (i in 0 until nets.length()) {
        val o = nets.getJSONObject(i)
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = rr(Th.chip, 14) }
        row.addView(TextView(this).apply { text = o.optString("name").ifEmpty { tr("Сеть", "Network") }; textSize = 15f; setTextColor(Th.text) })
        row.addView(TextView(this).apply { text = o.optString("id"); textSize = 12f; setTextColor(Th.dim); typeface = Typeface.MONOSPACE })
        val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        r2.addView(TextView(this).apply {
            text = tr("Копировать ID", "Copy ID"); setTextColor(Th.accent); textSize = 13f; setPadding(0, dp(4), dp(18), dp(4))
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier network", o.optString("id")))
                Toast.makeText(this@netPage, tr("ID скопирован", "ID copied"), Toast.LENGTH_SHORT).show()
            }
        })
        r2.addView(TextView(this).apply {
            text = tr("Удалить", "Delete"); setTextColor(0xFFE05555.toInt()); textSize = 13f; setPadding(0, dp(4), 0, dp(4))
            setOnClickListener {
                val n2 = JSONArray(); for (k in 0 until nets.length()) if (k != i) n2.put(nets.get(k))
                prefs.edit().putString("ztNets", n2.toString()).apply(); refresh()
            }
        })
        row.addView(r2)
        nc.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
    }
    btn(nc, tr("+ Добавить сеть", "+ Add network")) {
        val idE = EditText(this).apply { hint = tr("Network ID (16 символов)", "Network ID (16 hex chars)"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true); typeface = Typeface.MONOSPACE }
        val nameE = EditText(this).apply { hint = tr("Название (необязательно)", "Name (optional)"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(idE); addView(nameE) }
        MobDialog(this).setTitle(tr("Новая сеть", "New network")).setView(box)
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .setPositiveButton(tr("Сохранить", "Save")) { _, _ ->
                val id = idE.text.toString().trim().lowercase()
                if (!Regex("[0-9a-f]{16}").matches(id)) { Toast.makeText(this, tr("ID — ровно 16 символов 0-9, a-f", "ID must be exactly 16 hex characters"), Toast.LENGTH_LONG).show(); return@setPositiveButton }
                nets.put(JSONObject().put("id", id).put("name", nameE.text.toString().trim()))
                prefs.edit().putString("ztNets", nets.toString()).apply(); refresh()
            }.show()
    }
    btn(nc, tr("Кабинет my.zerotier.com", "my.zerotier.com dashboard"), false) {
        try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://my.zerotier.com/"))) } catch (_: Exception) {}
    }

    val hc = card()
    title(hc, tr("Как играть", "How to play"))
    body(hc, tr("1. В кабинете создайте сеть и скопируйте её ID.\n2. В ZeroTier One добавьте сеть по ID и включите её; в кабинете отметьте устройство галочкой Auth.\n3. Друзья делают то же самое.\n4. В Minecraft откройте мир для сети. В игре кнопка ↘ вверху по центру делится вашим адресом и портом.",
        "1. Create a network in the dashboard and copy its ID.\n2. Add the network by ID in ZeroTier One and switch it on; tick Auth for the device in the dashboard.\n3. Friends do the same.\n4. In Minecraft open the world to LAN. The ↘ button at the top centre of the game shares your address and port."))
    return sv
}

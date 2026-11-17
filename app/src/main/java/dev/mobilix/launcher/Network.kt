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

/** Клиент ZeroTier Central (https://api.zerotier.com): сети, участники, авторизация. Нужен API-токен из my.zerotier.com → Account. */
internal object ZtCentral {
    private const val BASE = "https://api.zerotier.com/api/v1"
    fun call(token: String, method: String, path: String, body: String? = null): String {
        val c = java.net.URL(BASE + path).openConnection() as java.net.HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", "token $token"); c.setRequestProperty("Accept", "application/json")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw java.io.IOException(if (code == 401 || code == 403) "токен не подходит ($code)" else "ошибка $code")
        return txt
    }
}

@Volatile private var ztLastErr: String? = null
private fun MainActivity.ztBg(work: () -> Unit, done: () -> Unit) = Thread {
    try { work(); ztLastErr = null; runOnUiThread { done() } } catch (e: Exception) {
        // одну и ту же ошибку (нет интернета, неверный токен) показываем один раз, пока не будет успешного запроса: иначе автообновление спамит
        val msg = if (e is java.net.UnknownHostException || e is java.net.ConnectException) tr("нет связи с my.zerotier.com", "cannot reach my.zerotier.com") else (e.message ?: e.toString())
        if (msg != ztLastErr) { ztLastErr = msg; runOnUiThread { Toast.makeText(this, "ZeroTier: $msg", Toast.LENGTH_LONG).show() } }
    }
}.start()

/** Участник в сети: Central то отдаёт флаг online, то только время последнего появления. */
private fun ztOnline(m: JSONObject): Boolean {
    if (m.optBoolean("online")) return true
    val last = maxOf(m.optLong("lastSeen"), m.optLong("lastOnline"))
    return last > 0 && System.currentTimeMillis() - last < 150_000
}

/** Участники сети: имя, адрес, онлайн, авторизация; можно разрешить или запретить вход. */
private fun MainActivity.ztMembers(token: String, nid: String, netName: String) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(4), 0, dp(4)) }
    val scroll = ScrollView(this).apply { addView(box) }
    val dlg = MobDialog(this).setTitle(netName.ifEmpty { nid }).setView(scroll).setPositiveButton(tr("Закрыть", "Close"), null)
    fun load() {
        var arr = JSONArray()
        ztBg({ arr = JSONArray(ZtCentral.call(token, "GET", "/network/$nid/member")) }) {
            box.removeAllViews()
            if (arr.length() == 0) box.addView(TextView(this).apply { text = tr("Пока никого. Пусть друзья добавят сеть по ID в ZeroTier One и включат её: они появятся здесь.", "Nobody yet. Ask friends to add the network ID in ZeroTier One and switch it on: they will show up here."); setTextColor(Th.dim); textSize = 13f; setPadding(0, dp(8), 0, dp(8)) })
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i); val cfg = m.optJSONObject("config") ?: JSONObject()
                val node = m.optString("nodeId"); val ok = cfg.optBoolean("authorized"); val online = ztOnline(m)
                val ip = cfg.optJSONArray("ipAssignments")?.optString(0).orEmpty()
                val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(8), dp(12), dp(8)); background = rr(Th.chip, 12) }
                row.addView(TextView(this).apply { text = (if (online) "● " else "○ ") + m.optString("name").ifEmpty { node }; setTextColor(if (online) Th.accent else Th.text); textSize = 14f })
                row.addView(TextView(this).apply { text = node + (if (ip.isNotEmpty()) "  ·  $ip" else ""); setTextColor(Th.dim); textSize = 12f; typeface = Typeface.MONOSPACE })
                if (ip.isNotEmpty()) row.addView(TextView(this).apply {
                    text = tr("Копировать IP", "Copy IP"); setTextColor(Th.accent); textSize = 13f; setPadding(0, dp(6), 0, dp(2))
                    setOnClickListener {
                        (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier IP", ip))
                        Toast.makeText(this@ztMembers, tr("IP скопирован", "IP copied"), Toast.LENGTH_SHORT).show()
                    }
                })
                row.addView(TextView(this).apply {
                    text = if (ok) tr("Запретить вход", "Deauthorize") else tr("Разрешить вход", "Authorize")
                    setTextColor(if (ok) 0xFFE05555.toInt() else Th.accent); textSize = 13f; setPadding(0, dp(6), 0, dp(2))
                    setOnClickListener { ztBg({ ZtCentral.call(token, "POST", "/network/$nid/member/$node", """{"config":{"authorized":${!ok}}}""") }) { load() } }
                })
                box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            }
        }
    }
    dlg.show(); load()
}

/** Карточка ZeroTier Central: токен, создание сетей, список и участники. */
/** Сохраняет сеть в список, включает узел, входит в сеть и сам авторизуется (для своих сетей). */
private fun MainActivity.ztAdopt(token: String, id: String, name: String) {
    val nets = ztNets()
    var have = false; for (i in 0 until nets.length()) if (nets.getJSONObject(i).optString("id") == id) have = true
    if (!have) { nets.put(JSONObject().put("id", id).put("name", name)); prefs.edit().putString("ztNets", nets.toString()).apply() }
    ZtManager.start(this); ZtManager.join(this, id)
    Thread {
        val t0 = System.currentTimeMillis()
        while (ZtManager.nodeId() == null && System.currentTimeMillis() - t0 < 30000) Thread.sleep(500)
        ZtManager.authorizeSelf(token, id)
    }.start()
}

private fun MainActivity.ztCentralCard(c: LinearLayout, refresh: () -> Unit) {
    fun cbtn(label: String, primary: Boolean = true, f: () -> Unit) = c.addView(Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) android.graphics.Color.WHITE else Th.chipText); background = sel(if (primary) Th.act else Th.chip); setOnClickListener { f() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
    val token = prefs.getString("ztToken", "").orEmpty()
    fun askToken() {
        val e = EditText(this).apply { hint = "API token"; setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true); typeface = Typeface.MONOSPACE }
        MobDialog(this).setTitle(tr("API-токен ZeroTier", "ZeroTier API token")).setView(e).setNegativeButton(tr("Отмена", "Cancel"), null)
            .setPositiveButton(tr("Сохранить", "Save")) { _, _ -> prefs.edit().putString("ztToken", e.text.toString().trim()).apply(); refresh() }.show()
    }
    if (token.isEmpty()) {
        c.addView(TextView(this).apply { text = tr("Войдите по API-токену, чтобы создавать сети, видеть, кто подключился, и разрешать вход друзьям, не выходя из лаунчера. Токен: my.zerotier.com → Account → API Access Tokens → New Token (бесплатно). Для токена нужен Legacy-аккаунт ZeroTier (создаётся на my.zerotier.com).", "Sign in with an API token to create networks, see who joined and let friends in without leaving the launcher. Token: my.zerotier.com → Account → API Access Tokens → New Token (free). A Legacy ZeroTier account is required for tokens."); textSize = 13f; setTextColor(Th.dim); setLineSpacing(0f, 1.15f); setPadding(0, dp(6), 0, 0) })
        cbtn(tr("Вставить токен", "Enter token")) { askToken() }
        cbtn(tr("Открыть страницу токенов", "Open token page"), false) { try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://my.zerotier.com/account"))) } catch (_: Exception) {} }
        return
    }
    val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    c.addView(list)
    list.addView(TextView(this).apply { text = tr("Загрузка…", "Loading…"); setTextColor(Th.dim); textSize = 13f; setPadding(0, dp(8), 0, 0) })
    var nets = JSONArray()
    ztBg({ nets = JSONArray(ZtCentral.call(token, "GET", "/network")) }) {
        list.removeAllViews()
        if (nets.length() == 0) list.addView(TextView(this).apply { text = tr("Сетей пока нет, создайте первую.", "No networks yet, create the first one."); setTextColor(Th.dim); textSize = 13f; setPadding(0, dp(8), 0, 0) })
        for (i in 0 until nets.length()) {
            val n = nets.getJSONObject(i); val id = n.optString("id"); val name = n.optJSONObject("config")?.optString("name").orEmpty()
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = rr(Th.chip, 14) }
            row.addView(TextView(this).apply { text = name.ifEmpty { tr("Сеть", "Network") }; textSize = 15f; setTextColor(Th.text) })
            val cnt = TextView(this).apply { text = id; textSize = 12f; setTextColor(Th.dim); typeface = Typeface.MONOSPACE }
            row.addView(cnt)
            Thread {   // счётчик считаем по списку участников: поле сети отстаёт
                try {
                    val ms = JSONArray(ZtCentral.call(token, "GET", "/network/$id/member"))
                    var on = 0; for (q in 0 until ms.length()) if (ztOnline(ms.getJSONObject(q))) on++
                    runOnUiThread { cnt.text = id + "  ·  " + tr("онлайн ", "online ") + on + tr(" из ", " of ") + ms.length() }
                } catch (_: Exception) {}
            }.start()
            val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            fun act(t: String, col: Int, f: () -> Unit) = r2.addView(TextView(this).apply { text = t; setTextColor(col); textSize = 13f; setPadding(0, dp(4), dp(18), dp(4)); setOnClickListener { f() } })
            act(tr("Участники", "Members"), Th.accent) { ztMembers(token, id, name) }
            act(tr("Подключить меня", "Join me"), Th.accent) { ztAdopt(token, id, name); refresh() }
            act(tr("Копировать ID", "Copy ID"), Th.accent) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier network", id))
                Toast.makeText(this, tr("ID скопирован", "ID copied"), Toast.LENGTH_SHORT).show()
            }
            act(tr("Удалить", "Delete"), 0xFFE05555.toInt()) {
                MobDialog(this).setTitle(tr("Удалить сеть?", "Delete network?")).setMessage(name.ifEmpty { id }).setNegativeButton(tr("Отмена", "Cancel"), null)
                    .setPositiveButton(tr("Удалить", "Delete")) { _, _ -> ztBg({ ZtCentral.call(token, "DELETE", "/network/$id") }) { refresh() } }.show()
            }
            row.addView(r2)
            list.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }
    cbtn(tr("+ Создать сеть", "+ Create network")) {
        val e = EditText(this).apply { hint = tr("Название сети", "Network name"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true) }
        MobDialog(this).setTitle(tr("Новая сеть", "New network")).setView(e).setNegativeButton(tr("Отмена", "Cancel"), null)
            .setPositiveButton(tr("Создать", "Create")) { _, _ ->
                val nm = e.text.toString().trim().ifEmpty { "Mobilix" }
                var newId = ""
                ztBg({ newId = JSONObject(ZtCentral.call(token, "POST", "/network", JSONObject().put("config", JSONObject().put("name", nm).put("private", true)).toString())).optString("id") }) {
                    if (newId.length == 16) ztAdopt(token, newId, nm)
                    refresh()
                }
            }.show()
    }
    cbtn(tr("Сменить токен", "Change token"), false) { askToken() }
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
    /** Сворачиваемая карточка как в настройках: состояние запоминается. Возвращает тело карточки. */
    fun fold(id: String, name: String): LinearLayout {
        val open = (prefs.getString("netOpen", "0") ?: "0").split(",").toMutableSet()
        val outer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), 0, dp(18), 0); background = rr(Th.chip2, 20) }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(16)) }
        val arrow = TextView(this).apply { textSize = 18f; setTextColor(Th.accent) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; isClickable = true }
        head.addView(TextView(this).apply { text = name; textSize = 17f; setTextColor(Th.text); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(arrow)
        fun apply() { val o = id in open; body.visibility = if (o) View.VISIBLE else View.GONE; arrow.text = if (o) "▾" else "▸" }
        head.setOnClickListener { if (id in open) open.remove(id) else open.add(id); prefs.edit().putString("netOpen", open.joinToString(",")).apply(); apply() }
        apply()
        outer.addView(head, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))
        outer.addView(body)
        col.addView(outer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
        return body
    }
    fun title(c: LinearLayout, t: String) = c.addView(TextView(this).apply { text = t; textSize = 17f; setTextColor(Th.text); typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) })
    fun body(c: LinearLayout, t: String) = c.addView(TextView(this).apply { text = t; textSize = 13f; setTextColor(Th.dim); setLineSpacing(0f, 1.15f); setPadding(0, dp(6), 0, 0) })
    fun btn(c: LinearLayout, label: String, primary: Boolean = true, onClick: () -> Unit) = c.addView(Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) android.graphics.Color.WHITE else Th.chipText); background = sel(if (primary) Th.act else Th.chip)
        setOnClickListener { onClick() }
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46)).apply { topMargin = dp(12) })
    fun refresh() = openPage(tr("Онлайн по LAN", "Online over LAN"), netPage(), animate = false)

    if (ZtManager.libOk(this)) {
        fun later() { sv.postDelayed({ refresh() }, 1500) }
        val running = ZtManager.enabled
        val st = fold("0", if (running) "ZeroTier" else tr("Включить ZeroTier", "Turn ZeroTier on"))
        val err = ZtManager.error
        st.addView(TextView(this).apply {
            text = when { err != null -> "✕ " + err; ZtManager.online() -> "● " + tr("Узел ZeroTier в сети", "ZeroTier node online"); running -> "… " + tr("Запуск узла", "Starting node"); else -> "○ " + tr("Узел выключен", "Node is off") }
            textSize = 13f; setTextColor(if (ZtManager.online()) Th.accent else Th.dim); typeface = Typeface.DEFAULT_BOLD
        })
        ZtManager.nodeId()?.let { id ->
            st.addView(TextView(this).apply { text = id; textSize = 22f; setTextColor(Th.text); typeface = Typeface.create("monospace", Typeface.BOLD); setPadding(0, dp(6), 0, 0) })
            body(st, tr("ID вашего узла (его видно в кабинете ZeroTier в списке участников).", "Your node ID (shown in the ZeroTier dashboard member list)."))
        }
        if (running) st.addView(TextView(this).apply {
            text = (if (ZtManager.events.isEmpty()) tr("событий пока нет", "no events yet") else ZtManager.events.joinToString("\n"))
            textSize = 10.5f; setTextColor(Th.dim); typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, 0)
        })
        if (!running) btn(st, tr("Включить ZeroTier", "Turn ZeroTier on")) { ZtManager.start(this); later() }
        else btn(st, tr("Выключить", "Turn off"), false) { ZtManager.stop(this); refresh() }
        btn(st, tr("Обновить", "Refresh"), false) { refresh() }

        val nets = ztNets()
        val nc = fold("1", tr("Подключенные сети", "Connected networks"))
        body(nc, tr("Добавьте сеть по ID (его даёт хозяин сети) и подключитесь. Хозяин должен разрешить вам вход (Authorize) во вкладке участников.", "Add a network by its ID (given by the network owner) and join it. The owner has to authorize you in the members list."))
        var myIp: String? = null
        for (i in 0 until nets.length()) {
            val o = nets.getJSONObject(i); val id = o.optString("id")
            val joined = ZtManager.joined(this, id); val ip = ZtManager.ip(id)
            if (ip != null && myIp == null) myIp = ip
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); background = rr(Th.chip, 14) }
            row.addView(TextView(this).apply { text = o.optString("name").ifEmpty { tr("Сеть", "Network") }; textSize = 15f; setTextColor(Th.text) })
            row.addView(TextView(this).apply { text = id; textSize = 12f; setTextColor(Th.dim); typeface = Typeface.MONOSPACE })
            row.addView(TextView(this).apply {
                text = when { ip != null -> "● " + tr("подключено, ваш адрес ", "connected, your address ") + ip; joined -> "… " + tr("ждём разрешения хозяина", "waiting for owner approval"); else -> "○ " + tr("не подключено", "not connected") }
                textSize = 13f; setTextColor(if (ip != null) Th.accent else Th.dim); setPadding(0, dp(4), 0, 0)
            })
            val r2 = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            fun act(t: String, col: Int, f: () -> Unit) = r2.addView(TextView(this).apply { text = t; setTextColor(col); textSize = 13f; setPadding(0, dp(4), dp(18), dp(4)); setOnClickListener { f() } })
            if (joined) act(tr("Отключить", "Leave"), Th.accent) { ZtManager.leave(this, id); refresh() }
            else act(tr("Подключить", "Join"), Th.accent) { if (!running) ZtManager.start(this); ZtManager.join(this, id); later() }
            act(tr("Копировать ID", "Copy ID"), Th.accent) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier network", id))
                Toast.makeText(this, tr("ID скопирован", "ID copied"), Toast.LENGTH_SHORT).show()
            }
            act(tr("Удалить", "Delete"), 0xFFE05555.toInt()) {
                ZtManager.leave(this, id)
                val n2 = JSONArray(); for (k in 0 until nets.length()) if (k != i) n2.put(nets.get(k))
                prefs.edit().putString("ztNets", n2.toString()).apply(); refresh()
            }
            row.addView(r2)
            nc.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) })
        }
        btn(nc, tr("+ Войти в сеть по ID", "+ Join a network by ID")) {
            val idE = EditText(this).apply { hint = tr("Network ID (16 символов)", "Network ID (16 hex chars)"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true); typeface = Typeface.MONOSPACE }
            val nameE = EditText(this).apply { hint = tr("Название (необязательно)", "Name (optional)"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true) }
            MobDialog(this).setTitle(tr("Войти в сеть", "Join a network")).setView(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(idE); addView(nameE) })
                .setNegativeButton(tr("Отмена", "Cancel"), null)
                .setPositiveButton(tr("Войти", "Join")) { _, _ ->
                    val id = idE.text.toString().trim().lowercase()
                    if (!Regex("[0-9a-f]{16}").matches(id)) { Toast.makeText(this, tr("ID — ровно 16 символов 0-9, a-f", "ID must be exactly 16 hex characters"), Toast.LENGTH_LONG).show(); return@setPositiveButton }
                    nets.put(JSONObject().put("id", id).put("name", nameE.text.toString().trim()))
                    prefs.edit().putString("ztNets", nets.toString()).apply()
                    if (!ZtManager.online() && !ZtManager.starting) ZtManager.start(this)
                    ZtManager.join(this, id); later()
                }.show()
        }

        ztCentralCard(fold("2", tr("Мои сети", "My networks")), ::refresh)
        val gc = fold("3", tr("Подключение", "Connection"))
        val ip0 = myIp
        if (ip0 == null) body(gc, tr("Подключитесь к сети и дождитесь адреса, тогда здесь появятся кнопки.", "Join a network and wait for your address, then the buttons appear here."))
        else {
            body(gc, tr("Хозяин: включите раздачу и откройте мир для сети в игре (Esc → Открыть для сети). Порт назначает игра, лаунчер подхватит его сам. Друзьям уйдёт адрес вида $ip0:порт (кнопка ↘ в игре).", "Host: turn sharing on and open the world to LAN in game (Esc → Open to LAN). The game picks the port, the launcher detects it. Friends get an address like $ip0:port (↘ button in game)."))
            if (!ZtManager.hostWanted) btn(gc, tr("Раздавать мой мир друзьям", "Share my world")) { ZtManager.startHost(this, ip0) { ZeroTierNet.lanPort(java.io.File(dataRoot(), "game.log")) }; later() }
            else btn(gc, "● " + (if (ZtManager.hostPort > 0) tr("Раздаём: $ip0:${ZtManager.hostPort}", "Sharing: $ip0:${ZtManager.hostPort}") else tr("Ждём открытия мира для сети…", "Waiting for the world to open to LAN…")) + "  (" + tr("остановить", "stop") + ")", false) { ZtManager.stopHost(this); refresh() }
            if (ZtManager.hostPort > 0) btn(gc, tr("Копировать мой адрес", "Copy my address"), false) {
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier", "$ip0:${ZtManager.hostPort}"))
                Toast.makeText(this, tr("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
            }
            body(gc, tr("Гость: вставьте адрес хозяина вместе с портом (ip:порт) и подключитесь, затем в игре «Сетевая игра» добавьте сервер localhost:порт.", "Guest: paste the host address with port (ip:port) and connect, then in Multiplayer add the server localhost:port."))
            val hostE = EditText(this).apply { hint = tr("Адрес друга, например 10.147.17.5:43521", "Friend address, e.g. 10.147.17.5:43521"); setHintTextColor(Th.hint); setTextColor(Th.text); setSingleLine(true); setText(ZtManager.clientTarget.orEmpty()) }
            gc.addView(hostE)
            if (ZtManager.clientTarget == null) btn(gc, tr("Подключиться к другу", "Connect to friend")) {
                val raw = hostE.text.toString().trim().removePrefix("[ZeroTier] IP:").trim()
                val ip = raw.substringBefore(':'); val port = raw.substringAfter(':', "").toIntOrNull()
                if (ip.isEmpty() || port == null) Toast.makeText(this, tr("Нужен адрес вместе с портом: ip:порт", "Address with port needed: ip:port"), Toast.LENGTH_LONG).show() else { ZtManager.startClient(ip, port); refresh() }
            } else btn(gc, "● " + tr("В игре: localhost:${ZtManager.clientTarget!!.substringAfter(':')} (отключить)", "In game: localhost:${ZtManager.clientTarget!!.substringAfter(':')} (disconnect)"), false) { ZtManager.stopClient(); refresh() }
        }
        // автоматика: раз в 10 секунд обновляем статус; свой узел сам авторизуем в своих сетях (если задан токен)
        val tok = prefs.getString("ztToken", "").orEmpty()
        sv.postDelayed(object : Runnable {
            override fun run() {
                if (!sv.isAttachedToWindow) return
                if (ZtManager.enabled && tok.isNotEmpty()) Thread { for (k in 0 until nets.length()) { val id = nets.getJSONObject(k).optString("id"); if (ZtManager.joined(this@netPage, id) && ZtManager.ip(id) == null) ZtManager.authorizeSelf(tok, id) } }.start()
                if (sv.findFocus() is EditText) sv.postDelayed(this, 10000) else refresh()
            }
        }, 10000)
        return sv
    }

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
            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier", ZeroTierNet.shareText(ips[0].second, null)))
            Toast.makeText(this, tr("Скопировано", "Copied"), Toast.LENGTH_SHORT).show()
        }
    } else {
        body(st, tr("Откройте ZeroTier One и включите переключатель нужной сети. Затем вернитесь и нажмите «Обновить».", "Open ZeroTier One and switch your network on. Then come back and tap Refresh."))
    }
    btn(st, tr("Открыть ZeroTier One", "Open ZeroTier One"), ips.isEmpty()) { ZeroTierNet.openApp(this) }
    btn(st, tr("Обновить", "Refresh"), false) { refresh() }

    ztCentralCard(card().also { title(it, "ZeroTier Central") }, ::refresh)

    // сети
    val nets = ztNets()
    val nc = card()
    title(nc, tr("Сохранённые ID", "Saved IDs"))
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
                (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("ZeroTier network", o.optString("id")))
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

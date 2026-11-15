package dev.mobilix.launcher

import android.content.Context
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import dev.mobilix.launcher.MainActivity.Companion.AGREEMENT_URL
import dev.mobilix.launcher.MainActivity.Companion.ASSETS_URL
import dev.mobilix.launcher.MainActivity.Companion.DEFAULT_MIRROR
import dev.mobilix.launcher.MainActivity.Companion.LWJGL_REPOS
import dev.mobilix.launcher.MainActivity.Companion.MANIFEST_URL
import dev.mobilix.launcher.MainActivity.Companion.NICK
import dev.mobilix.launcher.MainActivity.Task
import dev.mobilix.launcher.MainActivity.Preset
import dev.mobilix.launcher.MainActivity.CTheme
import dev.mobilix.launcher.MainActivity.Companion.RUNTIME_REPOS
import dev.mobilix.launcher.MainActivity.Companion.VERSION
import java.io.BufferedInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Paths
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

// Аккаунты: вход Microsoft и Ely.by, authlib-agent.
// Функции вынесены из MainActivity как расширения без изменения логики.

internal fun MainActivity.postForm(url: String, p: Map<String, String>): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.connectTimeout = 15000; c.readTimeout = 30000
    c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded"); c.setRequestProperty("User-Agent", "Mobilix/1.8")
    val body = p.entries.joinToString("&") { java.net.URLEncoder.encode(it.key, "UTF-8") + "=" + java.net.URLEncoder.encode(it.value, "UTF-8") }
    c.outputStream.use { it.write(body.toByteArray()) }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

internal fun MainActivity.getBearer(url: String, token: String): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000; c.readTimeout = 30000
    c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("User-Agent", "Mobilix/1.8")
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

/** Токен Microsoft -> Xbox Live -> XSTS -> токен Minecraft и профиль. Возвращает (токен, uuid, ник). */
internal fun MainActivity.msMinecraftLogin(msAccess: String): Triple<String, String, String> {
    val (c1, t1) = postJson("https://user.auth.xboxlive.com/user/authenticate", JSONObject()
        .put("Properties", JSONObject().put("AuthMethod", "RPS").put("SiteName", "user.auth.xboxlive.com").put("RpsTicket", "d=$msAccess"))
        .put("RelyingParty", "http://auth.xboxlive.com").put("TokenType", "JWT"))
    if (c1 !in 200..299) throw RuntimeException("Xbox Live: HTTP $c1")
    val xbl = JSONObject(t1)
    val xblToken = xbl.getString("Token")
    val uhs = xbl.getJSONObject("DisplayClaims").getJSONArray("xui").getJSONObject(0).getString("uhs")
    val (c2, t2) = postJson("https://xsts.auth.xboxlive.com/xsts/authorize", JSONObject()
        .put("Properties", JSONObject().put("SandboxId", "RETAIL").put("UserTokens", JSONArray().put(xblToken)))
        .put("RelyingParty", "rp://api.minecraftservices.com/").put("TokenType", "JWT"))
    if (c2 !in 200..299) {
        val xe = try { JSONObject(t2).optLong("XErr") } catch (_: Exception) { 0L }
        throw RuntimeException(when (xe) {
            2148916233L -> tr("у аккаунта нет профиля Xbox", "the account has no Xbox profile")
            2148916238L -> tr("детский аккаунт: его нужно добавить в семью Microsoft", "child account: add it to a Microsoft family")
            else -> "XSTS: HTTP $c2"
        })
    }
    val xsts = JSONObject(t2).getString("Token")
    val (c3, t3) = postJson("https://api.minecraftservices.com/authentication/login_with_xbox", JSONObject().put("identityToken", "XBL3.0 x=$uhs;$xsts"))
    if (c3 == 403) throw RuntimeException(tr("Mojang ещё не одобрил приложение (Invalid app registration)", "Mojang has not approved the app yet (Invalid app registration)"))
    if (c3 !in 200..299) throw RuntimeException("Minecraft: HTTP $c3")
    val mc = JSONObject(t3).getString("access_token")
    val (c4, t4) = getBearer("https://api.minecraftservices.com/minecraft/profile", mc)
    if (c4 == 404) throw RuntimeException(tr("на аккаунте нет Minecraft Java Edition", "this account does not own Minecraft Java Edition"))
    if (c4 !in 200..299) throw RuntimeException("Профиль: HTTP $c4")
    val pj = JSONObject(t4)
    return Triple(mc, pj.getString("id"), pj.getString("name"))
}

internal fun MainActivity.msSave(mc: String, uuid: String, name: String, refresh: String) {
    prefs.edit().putString("ms_token", mc).putString("ms_uuid", uuid).putString("ms_name", name)
        .putString("ms_refresh", refresh).putLong("ms_exp", System.currentTimeMillis() + 23L * 3600 * 1000).apply()
}

internal fun MainActivity.msLogout(tell: Boolean) {
    prefs.edit().remove("ms_token").remove("ms_uuid").remove("ms_name").remove("ms_refresh").remove("ms_exp").apply()
    if (tell) Toast.makeText(this, tr("Вы вышли из Microsoft", "Signed out of Microsoft"), Toast.LENGTH_SHORT).show()
}

/** Токен Minecraft живёт сутки: при необходимости обновляем через refresh-токен. false: нужен новый вход. */
internal fun MainActivity.msEnsureSession(): Boolean {
    if (System.currentTimeMillis() < prefs.getLong("ms_exp", 0L) - 120000L) return true
    val rt = prefs.getString("ms_refresh", "") ?: ""
    return try {
        val (c, t) = postForm(MS_TOKEN_URL, mapOf("client_id" to MS_CLIENT, "grant_type" to "refresh_token", "refresh_token" to rt, "scope" to MS_SCOPE))
        if (c !in 200..299) return false
        val j = JSONObject(t)
        val (mc, id, name) = msMinecraftLogin(j.getString("access_token"))
        msSave(mc, id, name, j.optString("refresh_token", rt))
        true
    } catch (_: java.io.IOException) { true }   // нет сети: пробуем запуститься с текущим токеном
    catch (_: Exception) { false }
}

internal fun MainActivity.b64u(b: ByteArray) = android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)

internal fun MainActivity.msBrowserLogin() {
    val rnd = java.security.SecureRandom()
    val vb = ByteArray(48).also { rnd.nextBytes(it) }; val verifier = b64u(vb)
    val challenge = b64u(java.security.MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    val state = b64u(ByteArray(16).also { rnd.nextBytes(it) })
    prefs.edit().putString("ms_pkce_v", verifier).putString("ms_pkce_s", state).apply()
    fun e(x: String) = java.net.URLEncoder.encode(x, "UTF-8")
    val url = "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize?client_id=${e(MS_CLIENT)}&response_type=code" +
        "&redirect_uri=${e(MS_REDIRECT)}&scope=${e(MS_SCOPE)}&code_challenge=$challenge&code_challenge_method=S256&state=$state&prompt=select_account"
    try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
    catch (_: Exception) { msDeviceLogin() }   // нет браузера: вход по коду
}

/** Возврат из браузера: mobilix-launcher://auth?code=...&state=... */
internal fun MainActivity.handleAuthIntent(i: Intent?) {
    val d = i?.data ?: return
    if (d.scheme != "mobilix-launcher" || d.host != "auth") return
    intent = Intent()
    val err = d.getQueryParameter("error")
    val code = d.getQueryParameter("code")
    val verifier = prefs.getString("ms_pkce_v", "") ?: ""
    val okState = !d.getQueryParameter("state").isNullOrEmpty() && d.getQueryParameter("state") == prefs.getString("ms_pkce_s", null)
    prefs.edit().remove("ms_pkce_v").remove("ms_pkce_s").apply()
    if (err != null || code == null || !okState || verifier.isEmpty()) {
        Toast.makeText(this, tr("Вход не удался: ", "Sign-in failed: ") + (d.getQueryParameter("error_description") ?: err ?: tr("неверный ответ", "bad response")), Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(this, tr("Проверяем вход…", "Checking sign-in…"), Toast.LENGTH_SHORT).show()
    Thread {
        try {
            val (c, t) = postForm(MS_TOKEN_URL, mapOf("client_id" to MS_CLIENT, "grant_type" to "authorization_code", "code" to code,
                "redirect_uri" to MS_REDIRECT, "code_verifier" to verifier, "scope" to MS_SCOPE))
            if (c !in 200..299) throw RuntimeException("Microsoft: HTTP $c " + (try { JSONObject(t).optString("error_description").take(150) } catch (_: Exception) { "" }))
            val j = JSONObject(t)
            val (mc, id, name) = msMinecraftLogin(j.getString("access_token"))
            elyLogout(false)
            msSave(mc, id, name, j.optString("refresh_token", ""))
            try { importSkinNow("mojang", name, quiet = true) } catch (_: Exception) {}
            runOnUiThread {
                nickText.text = nickName()
                Toast.makeText(this, tr("Вы вошли как $name", "Signed in as $name"), Toast.LENGTH_LONG).show()
                openPage(tr("Аккаунт", "Account"), accountPage())
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, tr("Вход не удался: ", "Sign-in failed: ") + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
        }
    }.start()
}

/** Вход через Microsoft по коду устройства: пользователь открывает страницу и вводит код. */
internal fun MainActivity.msDeviceLogin() {
    Toast.makeText(this, tr("Подключение к Microsoft…", "Connecting to Microsoft…"), Toast.LENGTH_SHORT).show()
    Thread {
        try {
            val (c, t) = postForm("https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode", mapOf("client_id" to MS_CLIENT, "scope" to MS_SCOPE))
            if (c !in 200..299) throw RuntimeException("Microsoft: HTTP $c " + (try { JSONObject(t).optString("error_description").take(150) } catch (_: Exception) { "" }))
            val j = JSONObject(t)
            val userCode = j.getString("user_code")
            val uri = j.getString("verification_uri")
            val dev = j.getString("device_code")
            var interval = j.optInt("interval", 5)
            val until = System.currentTimeMillis() + j.optInt("expires_in", 900) * 1000L
            val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
            runOnUiThread {
                MobDialog(this).setTitle(tr("Вход через Microsoft", "Sign in with Microsoft"))
                    .setMessage(tr("1. Откройте $uri\n2. Введите код: $userCode\n3. Войдите в аккаунт с Minecraft и вернитесь сюда.\n\nЛаунчер сам заметит вход.",
                        "1. Open $uri\n2. Enter the code: $userCode\n3. Sign in with the account that owns Minecraft and come back.\n\nThe launcher will notice the sign-in by itself."))
                    .setPositiveButton(tr("Открыть и скопировать код", "Open and copy code")) { _, _ ->
                        try {
                            (getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                                .setPrimaryClip(android.content.ClipData.newPlainText("code", userCode))
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri)))
                        } catch (_: Exception) {}
                        Toast.makeText(this, tr("Ждём подтверждения…", "Waiting for confirmation…"), Toast.LENGTH_LONG).show()
                    }
                    .setNegativeButton(tr("Отмена", "Cancel")) { _, _ -> cancelled.set(true) }
                    .setOnCancelListener { cancelled.set(true) }
                    .show()
            }
            var access: String? = null; var refresh = ""
            while (!cancelled.get() && System.currentTimeMillis() < until && access == null) {
                Thread.sleep(interval * 1000L)
                if (cancelled.get()) break
                val (c2, t2) = postForm(MS_TOKEN_URL, mapOf("client_id" to MS_CLIENT, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to dev))
                val j2 = JSONObject(t2)
                if (c2 in 200..299) { access = j2.getString("access_token"); refresh = j2.optString("refresh_token", "") }
                else when (j2.optString("error")) {
                    "authorization_pending" -> {}
                    "slow_down" -> interval += 5
                    else -> throw RuntimeException(j2.optString("error_description").take(150).ifEmpty { j2.optString("error") })
                }
            }
            if (access == null) return@Thread
            val (mc, id, name) = msMinecraftLogin(access)
            elyLogout(false)
            msSave(mc, id, name, refresh)
            try { importSkinNow("mojang", name, quiet = true) } catch (_: Exception) {}
            runOnUiThread {
                nickText.text = nickName()
                Toast.makeText(this, tr("Вы вошли как $name", "Signed in as $name"), Toast.LENGTH_LONG).show()
                openPage(tr("Аккаунт", "Account"), accountPage())
            }
        } catch (e: Exception) {
            runOnUiThread { Toast.makeText(this, tr("Вход не удался: ", "Sign-in failed: ") + (e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show() }
        }
    }.start()
}

internal fun MainActivity.postJson(url: String, body: JSONObject): Pair<Int, String> {
    val c = URL(url).openConnection() as HttpURLConnection
    c.requestMethod = "POST"; c.doOutput = true
    c.connectTimeout = 15000; c.readTimeout = 30000
    c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "Mobilix/1.8")
    c.outputStream.use { it.write(body.toString().toByteArray()) }
    val code = c.responseCode
    val text = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
    return code to text
}

internal fun MainActivity.clientToken(): String = prefs.getString("ely_client", null) ?: java.util.UUID.randomUUID().toString().replace("-", "").also { prefs.edit().putString("ely_client", it).apply() }

/** Проверяет токен Ely.by, при необходимости обновляет. false: нужен новый вход. */
internal fun MainActivity.elyEnsureSession(): Boolean {
    return try {
        val tok = prefs.getString("ely_token", "") ?: ""
        if (postJson("$ELY_AUTH/validate", JSONObject().put("accessToken", tok)).first in 200..299) return true
        val (c, t) = postJson("$ELY_AUTH/refresh", JSONObject().put("accessToken", tok).put("clientToken", clientToken()))
        if (c !in 200..299) return false
        prefs.edit().putString("ely_token", JSONObject(t).getString("accessToken")).apply()
        true
    } catch (_: Exception) { true } // нет сети: пробуем запуститься с текущим токеном
}

internal fun MainActivity.elyLogout(tell: Boolean) {
    prefs.edit().remove("ely_token").remove("ely_uuid").remove("ely_name").apply()
    if (tell) Toast.makeText(this, tr("Вы вышли из Ely.by", "Signed out of Ely.by"), Toast.LENGTH_SHORT).show()
}

internal fun MainActivity.elyLogin(user: String, pass: String, onDone: (String?) -> Unit) {
    Thread {
        var err: String? = null
        try {
            val (code, text) = postJson("$ELY_AUTH/authenticate", JSONObject().put("username", user).put("password", pass)
                .put("clientToken", clientToken()).put("requestUser", true))
            if (code in 200..299) {
                val j = JSONObject(text)
                val prof = j.getJSONObject("selectedProfile")
                msLogout(false)
                prefs.edit().putString("ely_token", j.getString("accessToken")).putString("ely_uuid", prof.getString("id"))
                    .putString("ely_name", prof.getString("name")).apply()
                // скин аккаунта становится скином в лаунчере
                try { importSkinNow("ely", prof.getString("name")) } catch (_: Exception) {}
            } else {
                val m = try { JSONObject(text).optString("errorMessage") } catch (_: Exception) { "" }
                err = if (m.contains("two factor", true)) "2FA" else if (code == 401 || code == 403) tr("Неверный логин или пароль", "Wrong login or password") else (m.ifEmpty { "HTTP $code" })
            }
        } catch (e: Exception) { err = tr("Нет связи с Ely.by", "Cannot reach Ely.by") }
        runOnUiThread { onDone(err) }
    }.start()
}

internal fun MainActivity.elyDialog(after: () -> Unit) {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(12), dp(24), 0) }
    val u = android.widget.EditText(this).apply { hint = tr("Ник или email Ely.by", "Ely.by nickname or email"); setSingleLine(true) }
    val p = android.widget.EditText(this).apply {
        hint = tr("Пароль", "Password"); setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val f = android.widget.EditText(this).apply { hint = tr("Код двухфакторной защиты (если включена)", "2FA code (if enabled)"); setSingleLine(true); inputType = android.text.InputType.TYPE_CLASS_NUMBER }
    box.addView(u); box.addView(p); box.addView(f)
    MobDialog(this).setTitle(tr("Вход через Ely.by", "Sign in with Ely.by")).setView(box)
        .setPositiveButton(tr("Войти", "Sign in")) { _, _ ->
            val pass = p.text.toString() + (if (f.text.isNotEmpty()) ":" + f.text else "")
            elyLogin(u.text.toString().trim(), pass) { err ->
                if (err == null) { Toast.makeText(this, tr("Вход выполнен", "Signed in"), Toast.LENGTH_SHORT).show(); nickText.text = nickName(); after() }
                else if (err == "2FA") Toast.makeText(this, tr("Включена двухфакторная защита: введите код в третье поле", "2FA is on: enter the code in the third field"), Toast.LENGTH_LONG).show()
                else Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
        }
        .setNegativeButton(tr("Отмена", "Cancel"), null).show()
}

/** Можно ли подключать -javaagent: в рантайме есть модуль java.instrument и libinstrument.so, и она загружается. */
internal fun MainActivity.agentUsable(rt: File): Boolean {
    val so = File(rt, "lib/libinstrument.so")
    if (!so.exists()) { log("  libinstrument.so не найдена в рантайме"); return false }
    val rel = try { File(rt, "release").readText() } catch (_: Exception) { null }
    if (rel != null && !rel.contains("java.instrument")) { log("  в рантайме нет модуля java.instrument"); return false }
    // Файл есть и модуль подключён; нужные библиотеки (libjli, libjava) подгружает сам процесс игры до запуска JVM.
    return true
}

/** authlib-injector (открытый, AGPL-3.0): подменяет серверы авторизации и скинов на Ely.by. */
internal fun MainActivity.ensureAuthlib(): File? {
    val f = File(filesDir, "authlib-injector.jar")
    if (f.exists() && f.length() > 10000) return f
    return try {
        val rel = JSONObject(fetchText("https://api.github.com/repos/yushijinhun/authlib-injector/releases/latest"))
        val assets = rel.getJSONArray("assets")
        var url: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".jar")) { url = a.getString("browser_download_url"); break }
        }
        if (url == null) null else { downloadBig(url, f); f }
    } catch (e: Exception) { log("authlib-injector: не скачался ($e)"); null }
}

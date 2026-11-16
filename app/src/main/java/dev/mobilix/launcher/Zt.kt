package dev.mobilix.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.zerotier.sockets.ZeroTierNode
import com.zerotier.sockets.ZeroTierServerSocket
import com.zerotier.sockets.ZeroTierSocket
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * MobiLabs: встроенный узел ZeroTier (libzt, userspace-стек lwIP, без системного VPN).
 * Мир хозяина пробрасывается на адрес в сети ZeroTier (порт ZT_PORT), а друг подключается к нему через локальный порт 127.0.0.1.
 */
object ZtManager {
    const val ZT_PORT = 25565
    @Volatile private var node: ZeroTierNode? = null
    @Volatile var starting = false
    /** «Включён» пользователем. Сам узел после выключения не останавливаем (остановка libzt роняла приложение), он просто простаивает. */
    @Volatile var enabled = false
    /** Последние события узла для диагностики (почему не выходит в сеть). */
    val events = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val names = mapOf(200 to "NODE_UP", 201 to "NODE_ONLINE", 202 to "NODE_OFFLINE", 203 to "NODE_DOWN", 204 to "NODE_FATAL_ERROR", 210 to "NETWORK_NOT_FOUND",
        211 to "NETWORK_CLIENT_TOO_OLD", 212 to "NETWORK_REQ_CONFIG", 213 to "NETWORK_OK", 214 to "NETWORK_ACCESS_DENIED", 215 to "NETWORK_READY_IP4", 216 to "NETWORK_READY_IP6",
        217 to "NETWORK_READY_IP4_IP6", 218 to "NETWORK_DOWN", 219 to "NETWORK_UPDATE", 220 to "STACK_UP", 221 to "STACK_DOWN", 230 to "NETIF_UP", 231 to "NETIF_DOWN",
        240 to "PEER_DIRECT", 241 to "PEER_RELAY", 242 to "PEER_UNREACHABLE", 243 to "PEER_PATH_DISCOVERED", 244 to "PEER_PATH_DEAD")
    fun note(t: String) { events.add(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + " " + t); while (events.size > 14) events.removeAt(0) }
    @Volatile var error: String? = null
    @Volatile private var host: ZeroTierServerSocket? = null
    @Volatile private var client: ServerSocket? = null
    @Volatile var hostActive = false
    @Volatile var clientTarget: String? = null

    fun libOk(ctx: Context) = File(ctx.applicationInfo.nativeLibraryDir, "libzt.so").exists()
    fun online(): Boolean = try { enabled && node?.isOnline == true } catch (_: Throwable) { false }
    fun nodeId(): String? = try { if (!enabled) null else node?.id?.let { java.lang.Long.toHexString(it).padStart(10, '0') } } catch (_: Throwable) { null }

    /** Запускает узел (один на процесс) и держит приложение живым сервисом на переднем плане. */
    fun start(ctx: Context) {
        if (starting) return
        val app = ctx.applicationContext
        enabled = true; error = null
        ZtService.start(ctx)
        val existing = node
        if (existing != null) {   // узел уже запущен в этом процессе: только заново входим в сети
            val saved = app.getSharedPreferences("mobilix", Context.MODE_PRIVATE).getString("ztJoined", "").orEmpty()
            for (id in saved.split(',').filter { it.length == 16 }) try { existing.join(java.lang.Long.parseUnsignedLong(id, 16)) } catch (_: Throwable) {}
            return
        }
        starting = true
        Thread {
            try {
                val n = ZeroTierNode()
                n.initFromStorage(File(app.filesDir, "zt").apply { mkdirs() }.path)
                n.initSetEventHandler(object : com.zerotier.sockets.ZeroTierEventListener {
                    override fun onZeroTierEvent(id: Long, eventCode: Int) { note((names[eventCode] ?: "event $eventCode") + (if (id != 0L) " " + java.lang.Long.toHexString(id) else "")) }
                })
                val rc = n.start()
                note("start() = $rc")
                node = n
                val t0 = System.currentTimeMillis()
                while (!online() && System.currentTimeMillis() - t0 < 60000) Thread.sleep(200)
                val saved = app.getSharedPreferences("mobilix", Context.MODE_PRIVATE).getString("ztJoined", "").orEmpty()
                for (id in saved.split(',').filter { it.length == 16 }) try { n.join(java.lang.Long.parseUnsignedLong(id, 16)) } catch (_: Throwable) {}
            } catch (e: Throwable) { error = e.toString(); node = null }
            starting = false
        }.start()
    }

    fun stop(ctx: Context) {
        stopHost(ctx); stopClient()
        enabled = false
        val saved = ctx.getSharedPreferences("mobilix", Context.MODE_PRIVATE).getString("ztJoined", "").orEmpty()
        for (id in saved.split(',').filter { it.length == 16 }) try { node?.leave(java.lang.Long.parseUnsignedLong(id, 16)) } catch (_: Throwable) {}
        ZtService.stop(ctx)
    }

    fun join(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences("mobilix", Context.MODE_PRIVATE)
        p.edit().putString("ztJoined", (p.getString("ztJoined", "").orEmpty().split(',').filter { it.isNotEmpty() && it != id } + id).joinToString(",")).apply()
        try { node?.join(java.lang.Long.parseUnsignedLong(id, 16)) } catch (e: Throwable) { error = e.toString() }
    }

    fun leave(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences("mobilix", Context.MODE_PRIVATE)
        p.edit().putString("ztJoined", p.getString("ztJoined", "").orEmpty().split(',').filter { it.isNotEmpty() && it != id }.joinToString(",")).apply()
        try { node?.leave(java.lang.Long.parseUnsignedLong(id, 16)) } catch (_: Throwable) {}
    }

    fun joined(ctx: Context, id: String) = ctx.getSharedPreferences("mobilix", Context.MODE_PRIVATE).getString("ztJoined", "").orEmpty().split(',').contains(id)

    /** Адрес устройства в сети, когда сеть готова (хозяин разрешил вход и выдан IP). */
    fun ip(id: String): String? {
        val n = node ?: return null
        return try {
            val nid = java.lang.Long.parseUnsignedLong(id, 16)
            if (n.isNetworkTransportReady(nid)) n.getIPv4Address(nid)?.hostAddress else null
        } catch (_: Throwable) { null }
    }

    private fun pipe(a: InputStream, b: OutputStream, close: () -> Unit) = Thread {
        val buf = ByteArray(16384)
        try { while (true) { val n = a.read(buf); if (n < 0) break; if (n > 0) { b.write(buf, 0, n); b.flush() } } } catch (_: Throwable) {}
        close()
    }.also { it.isDaemon = true; it.start() }

    /** Хозяин: всё, что приходит на ZT_PORT из сети ZeroTier, пересылается в открытый для сети мир (порт читается из game.log). */
    fun startHost(ctx: Context, shareIp: String, localPort: () -> Int?) {
        if (host != null) return
        val srv = try { ZeroTierServerSocket(ZT_PORT) } catch (e: Throwable) { error = "хост: $e"; return }
        host = srv; hostActive = true
        try { File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "zt-share.txt").writeText("$shareIp:$ZT_PORT") } catch (_: Throwable) {}
        Thread {
            while (host === srv) {
                try {
                    val zs = srv.accept()
                    Thread {
                        try {
                            val p = localPort() ?: throw java.io.IOException("мир не открыт для сети")
                            val ls = Socket(InetAddress.getByName("127.0.0.1"), p)
                            val zi = zs.inputStream; val zo = zs.outputStream
                            val done = { try { zs.close() } catch (_: Throwable) {}; try { ls.close() } catch (_: Throwable) {} }
                            pipe(zi, ls.getOutputStream(), done); pipe(ls.getInputStream(), zo, done)
                        } catch (_: Throwable) { try { zs.close() } catch (_: Throwable) {} }
                    }.start()
                } catch (_: Throwable) { if (host !== srv) break }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stopHost(ctx: Context) {
        val s = host; host = null; hostActive = false
        try { s?.close() } catch (_: Throwable) {}
        try { File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "zt-share.txt").delete() } catch (_: Throwable) {}
    }

    /** Гость: 127.0.0.1:localPort → адрес хозяина в сети ZeroTier. В игре добавьте сервер localhost:localPort. */
    fun startClient(hostIp: String, localPort: Int = ZT_PORT) {
        stopClient()
        val srv = try { ServerSocket(localPort, 8, InetAddress.getByName("127.0.0.1")) } catch (e: Throwable) { error = "гость: $e"; return }
        client = srv; clientTarget = hostIp
        Thread {
            while (client === srv) {
                try {
                    val ls = srv.accept()
                    Thread {
                        try {
                            val zs = ZeroTierSocket(hostIp, ZT_PORT)
                            val done = { try { zs.close() } catch (_: Throwable) {}; try { ls.close() } catch (_: Throwable) {} }
                            pipe(ls.getInputStream(), zs.outputStream, done); pipe(zs.inputStream, ls.getOutputStream(), done)
                        } catch (_: Throwable) { try { ls.close() } catch (_: Throwable) {} }
                    }.start()
                } catch (_: Throwable) { if (client !== srv) break }
            }
        }.also { it.isDaemon = true }.start()
    }

    fun stopClient() { val s = client; client = null; clientTarget = null; try { s?.close() } catch (_: Throwable) {} }
}

/** Держит приложение живым, пока работает узел ZeroTier. */
class ZtService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("zt", "ZeroTier", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "zt") else Notification.Builder(this)
        startForeground(2, b.setContentTitle("Mobilix").setContentText("ZeroTier: сеть для игры с друзьями активна").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build())
        return START_NOT_STICKY
    }
    companion object {
        fun start(c: Context) { val i = Intent(c, ZtService::class.java); if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) }
        fun stop(c: Context) { c.stopService(Intent(c, ZtService::class.java)) }
    }
}

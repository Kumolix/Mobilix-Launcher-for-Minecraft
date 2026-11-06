package dev.mobilix.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File

/**
 * Установщик Forge без окна: служба в процессе :game запускает его так же, как раньше окно игры, но с уведомлением.
 * Не нужен чёрный экран, и система реже останавливает такой процесс.
 */
class InstallerService : Service() {
    private var wl: PowerManager.WakeLock? = null
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("inst", "Установка", NotificationManager.IMPORTANCE_LOW))
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "inst") else Notification.Builder(this)
        startForeground(2, b.setContentTitle("Mobilix").setContentText("Установка Forge… это долго, не закрывайте лаунчер")
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build())
        if (running) return START_NOT_STICKY
        running = true
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "mobilix:installer").apply { acquire(60 * 60 * 1000L) }
        Thread(null, { runInstaller() }, "installer-main", 32L * 1024 * 1024).start()
        return START_NOT_STICKY
    }

    private fun runInstaller() {
        val dataDir = getExternalFilesDir(null) ?: filesDir
        try {
            File(dataDir, "installer.started").writeText(System.currentTimeMillis().toString())
            val j = JSONObject(File(dataDir, "installer.json").readText())
            val jvm = j.getJSONArray("jvm")
            val opts = ArrayList<String>()
            var i = 0
            while (i < jvm.length()) {
                val a = jvm.getString(i)
                if ((a == "-cp" || a == "-classpath") && i + 1 < jvm.length()) { opts.add("-Djava.class.path=" + jvm.getString(i + 1)); i += 2; continue }
                opts.add(a); i++
            }
            if (!opts.contains("-Xrs")) opts.add("-Xrs")
            val game = j.getJSONArray("game")
            val args = Array(game.length()) { game.getString(it) }
            Native.redirectOutput(File(dataDir, "installer.log").path)
            val r = Native.runMain(j.getString("javaHome"), opts.toTypedArray(), j.getString("main"), args)
            try { File(dataDir, "installer.done").writeText(r) } catch (_: Exception) {}
        } catch (e: Throwable) {
            try { File(dataDir, "installer.done").writeText("ОШИБКА службы установки: $e") } catch (_: Exception) {}
        }
        try { wl?.release() } catch (_: Throwable) {}
        // JVM нельзя создать второй раз в том же процессе: завершаем :game, игра потом стартует в свежем
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}

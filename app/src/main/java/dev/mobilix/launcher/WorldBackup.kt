package dev.mobilix.launcher

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Автобэкап миров после закрытия игры.
 * Перед запуском лаунчер пишет метку сессии (папка игры и время старта). Игра работает в отдельном процессе :game.
 * Когда игра закрылась (процесса нет или GameActivity оставила метку конца), в копию попадают только те миры,
 * в которых что-то менялось с начала сессии. Хранятся последние KEEP копий для каждой папки игры.
 */
object WorldBackup {
    const val KEEP = 3
    private val running = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun session(root: File) = File(root, "game-session.json")
    fun endedMarker(root: File) = File(root, "game-session.ended")
    private fun backupDir(root: File, key: String) = File(root, "backups/$key")

    /** Ключ папки игры: имя папки сборки или имя папки версии. */
    fun keyOf(gameDir: File) = gameDir.name

    fun begin(root: File, gameDir: File) {
        try {
            endedMarker(root).delete()
            session(root).writeText(JSONObject().put("gameDir", gameDir.path).put("start", System.currentTimeMillis()).toString())
        } catch (_: Exception) {}
    }

    private fun gameAlive(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.runningAppProcesses?.any { it.processName.endsWith(":game") } == true
    } catch (_: Throwable) { false }

    /**
     * Вызывать, когда лаунчер снова на экране. Если игра закрылась, делает копию в фоне.
     * onDone получает текст для уведомления (или null, если копировать было нечего) и вызывается в фоновом потоке.
     */
    fun checkAfterGame(ctx: Context, root: File, onDone: (String?) -> Unit) {
        val s = session(root)
        if (!s.exists()) return
        if (!endedMarker(root).exists()) {
            if (gameAlive(ctx)) return
            // процесс игры мог ещё не успеть появиться сразу после запуска
            val st = try { JSONObject(s.readText()).getLong("start") } catch (_: Throwable) { 0L }
            if (System.currentTimeMillis() - st < 20000) return
        }
        if (!running.compareAndSet(false, true)) return
        Thread {
            var msg: String? = null
            try {
                val j = JSONObject(s.readText())
                val gameDir = File(j.getString("gameDir"))
                val start = j.getLong("start")
                s.delete(); endedMarker(root).delete()
                msg = backup(root, gameDir, start)
            } catch (_: Throwable) {
                s.delete(); endedMarker(root).delete()
            } finally {
                running.set(false)
            }
            onDone(msg)
        }.start()
    }

    /** Копия миров, изменённых после since. Возвращает сообщение или null, если менять было нечего. */
    private fun backup(root: File, gameDir: File, since: Long): String? {
        val saves = File(gameDir, "saves")
        val worlds = saves.listFiles { f -> f.isDirectory }?.filter { changedSince(it, since - 2000) }.orEmpty()
        if (worlds.isEmpty()) return null
        val dir = backupDir(root, keyOf(gameDir)).apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val out = File(dir, "worlds-$stamp.zip")
        val tmp = File(dir, "worlds-$stamp.zip.part")
        try {
            ZipOutputStream(tmp.outputStream().buffered()).use { z ->
                z.setLevel(Deflater.BEST_SPEED)
                for (w in worlds) addDir(z, w, "saves/${w.name}")
            }
            if (!tmp.renameTo(out)) throw Exception("rename")
        } catch (e: Throwable) {
            tmp.delete()
            return null
        }
        list(root, gameDir).drop(KEEP).forEach { it.delete() }
        return "${worlds.size}"
    }

    private fun changedSince(dir: File, t: Long): Boolean {
        val st = ArrayDeque<File>(); st.add(dir)
        while (st.isNotEmpty()) {
            val d = st.removeLast()
            val ch = d.listFiles() ?: continue
            for (f in ch) { if (f.isDirectory) st.add(f) else if (f.lastModified() >= t) return true }
        }
        return false
    }

    private fun addDir(z: ZipOutputStream, dir: File, prefix: String) {
        val ch = dir.listFiles() ?: return
        for (f in ch.sortedBy { it.name }) {
            if (f.isDirectory) addDir(z, f, prefix + "/" + f.name)
            else if (f.name != "session.lock") {
                try {
                    z.putNextEntry(ZipEntry(prefix + "/" + f.name))
                    f.inputStream().use { it.copyTo(z) }
                    z.closeEntry()
                } catch (_: Exception) { try { z.closeEntry() } catch (_: Exception) {} }
            }
        }
    }

    /** Копии для папки игры, от новых к старым. */
    fun list(root: File, gameDir: File): List<File> =
        backupDir(root, keyOf(gameDir)).listFiles { f -> f.name.endsWith(".zip") }?.sortedByDescending { it.name }.orEmpty()

    /** Названия миров в копии. */
    fun worldNames(zip: File): List<String> {
        val r = LinkedHashSet<String>()
        try { ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) { val p = e.name.split('/'); if (p.size >= 3 && p[0] == "saves") r.add(p[1]); e = z.nextEntry }
        } } catch (_: Exception) {}
        return r.toList()
    }

    /** Восстанавливает миры рядом с существующими (под новым именем), ничего не перезаписывая. Возвращает число миров. */
    fun restore(zip: File, gameDir: File): Int {
        val saves = File(gameDir, "saves").apply { mkdirs() }
        val targets = HashMap<String, File>()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val p = e.name.split('/')
                if (!e.isDirectory && p.size >= 3 && p[0] == "saves" && p[1].isNotEmpty() && p[1] != "." && p[1] != "..") {
                    val target = targets.getOrPut(p[1]) {
                        var t = File(saves, p[1] + " (копия)"); var k = 2
                        while (t.exists()) { t = File(saves, p[1] + " (копия $k)"); k++ }
                        t
                    }
                    val out = File(target, p.drop(2).joinToString("/"))
                    if (out.canonicalPath.startsWith(target.canonicalPath + File.separator)) { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                }
                e = z.nextEntry
            }
        }
        return targets.size
    }

    fun label(zip: File): String {
        val m = Regex("worlds-(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})(\\d{2})").find(zip.name) ?: return zip.name
        val g = m.groupValues
        return "${g[3]}.${g[2]}.${g[1]} ${g[4]}:${g[5]}"
    }
}

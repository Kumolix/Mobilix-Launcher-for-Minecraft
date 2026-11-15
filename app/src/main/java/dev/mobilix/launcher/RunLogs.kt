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

// Логи запусков: последний лог остаётся доступным после вылета (панель «Лог» в лаунчере), предыдущий хранится отдельно.

/** Строки-шум: список модов, блок «Incompatible Java and native library», остальное в логе оставляем. */
private val RUN_LOG_NOISE = Regex("^\\s*(Possible (reasons|solutions):|\\[LWJGL\\])|Incompatible Java and native|^\\t(- \\S|   \\S|[ab]\\) )|^\\s+\\|--|^\\s+\\\\--")

private fun MainActivity.runLogTail(f: File, maxLines: Int): List<String> =
    if (!f.exists()) emptyList() else lastLinesOf(f, 300_000).filter { it.isNotBlank() && !RUN_LOG_NOISE.containsMatchIn(it) }.takeLast(maxLines)

/** Текст лога запуска: лог лаунчера + хвост лога игры. prev = предыдущий запуск (из архива). */
internal fun MainActivity.readRunLog(prev: Boolean = false): String {
    val r = dataRoot()
    val ln = File(r, if (prev) "prev-launcher.log" else "launcher.log")
    val gn = File(r, if (prev) "prev-game.log" else "game.log")
    val sb = StringBuilder()
    if (ln.exists()) { sb.append("=== Лог лаунчера ===\n"); runLogTail(ln, 150).forEach { sb.append(it).append('\n') } }
    if (gn.exists()) { sb.append("=== Лог игры ===\n"); runLogTail(gn, 300).forEach { sb.append(it).append('\n') } }
    return sb.toString()
}

/** Перед новым запуском сохраняет логи прошлого настоящего запуска (не просто скачивания), чтобы их не затёрло. */
internal fun MainActivity.archiveRunLogs(root: File) {
    try {
        val l = File(root, "launcher.log"); val g = File(root, "game.log")
        if (!l.exists() || !l.readText().contains("Открываю окно игры")) return
        l.copyTo(File(root, "prev-launcher.log"), true)
        if (g.exists() && g.length() > 0) File(root, "prev-game.log").writeText(lastLinesOf(g, 300_000).joinToString("\n"))
    } catch (_: Exception) {}
}

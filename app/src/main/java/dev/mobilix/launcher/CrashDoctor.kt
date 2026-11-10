package dev.mobilix.launcher

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Разбор вылетов из-за модов: находит подозрительные моды по crash-report, latest.log и hs_err.log. */
object CrashDoctor {
    class Mod(val id: String, val name: String, val file: File, val depends: Set<String>)
    class Suspect(val mod: Mod, val why: String)
    class Report(
        val gameDir: File,
        val mods: List<Mod>,
        val suspects: List<Suspect>,
        val notes: List<String>,
        val native: Boolean,          // нет Java-ошибки: процесс упал или был убит
        val hasRenderMod: Boolean,
    )
    class Detected(val gameDir: File, val start: Long, val kind: String)

    /** Известные моды, которые на мобильном рендере часто валят игру (и зависящие от них). */
    private val RENDER_MODS = setOf("sodium", "iris", "embeddium", "oculus", "rubidium", "distanthorizons", "nvidium", "indium", "sodium-extra", "reeses-sodium-options", "sodiumextra", "iris-flywheel-compat")

    private fun gameAlive(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).runningAppProcesses?.any { it.processName.endsWith(":game") } == true
    } catch (_: Throwable) { false }

    /** Был ли прошлый запуск аварийным. Читает game-session.json до того, как его удалит резервная копия миров. */
    fun detect(ctx: Context, root: File): Detected? {
        val s = File(root, "game-session.json")
        if (!s.exists() || gameAlive(ctx)) return null
        return try {
            val j = JSONObject(s.readText())
            val start = j.getLong("start"); val gd = File(j.getString("gameDir"))
            val ended = File(root, "game-session.ended").exists()
            val crashReport = latestCrashReport(gd, start)
            val hs = File(gd, "hs_err.log").takeIf { it.exists() && it.lastModified() >= start - 5000 }
            when {
                crashReport != null -> Detected(gd, start, "crash")
                hs != null -> Detected(gd, start, "native")
                !ended && System.currentTimeMillis() - start > 20000 -> Detected(gd, start, "killed")
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    private fun latestCrashReport(gd: File, since: Long): File? =
        File(gd, "crash-reports").listFiles()?.filter { it.isFile && it.lastModified() >= since - 5000 }?.maxByOrNull { it.lastModified() }

    fun readMod(f: File): Mod {
        var id = ""; var name = ""; val dep = HashSet<String>()
        try {
            ZipFile(f).use { z ->
                fun text(n: String) = z.getEntry(n)?.let { z.getInputStream(it).bufferedReader().use { r -> r.readText() } }
                val fj = text("fabric.mod.json")
                val qj = text("quilt.mod.json")
                val toml = text("META-INF/neoforge.mods.toml") ?: text("META-INF/mods.toml")
                if (fj != null) {
                    val o = JSONObject(fj); id = o.optString("id"); name = o.optString("name")
                    o.optJSONObject("depends")?.keys()?.forEach { dep.add(it) }
                } else if (qj != null) {
                    val o = JSONObject(qj).optJSONObject("quilt_loader")
                    id = o?.optString("id") ?: ""; name = o?.optJSONObject("metadata")?.optString("name") ?: ""
                } else if (toml != null) {
                    id = Regex("modId\\s*=\\s*\"([^\"]+)\"").findAll(toml).map { it.groupValues[1] }.firstOrNull { it != "minecraft" && it != "forge" && it != "neoforge" } ?: ""
                    name = Regex("displayName\\s*=\\s*\"([^\"]+)\"").find(toml)?.groupValues?.get(1) ?: ""
                } else { }
            }
        } catch (_: Throwable) {}
        val base = f.name.removeSuffix(".disabled").removeSuffix(".jar")
        return Mod(id.ifEmpty { base.lowercase() }, name.ifEmpty { base }, f, dep)
    }

    fun enabledMods(gd: File): List<Mod> =
        File(gd, "mods").listFiles()?.filter { it.isFile && it.name.endsWith(".jar") }?.sortedBy { it.name.lowercase() }?.map { readMod(it) } ?: emptyList()

    fun disabledFiles(gd: File): List<File> =
        File(gd, "mods").listFiles()?.filter { it.isFile && it.name.endsWith(".jar.disabled") }?.sortedBy { it.name.lowercase() } ?: emptyList()

    private fun tail(f: File, max: Int): String = try {
        java.io.RandomAccessFile(f, "r").use { r ->
            val n = minOf(r.length(), max.toLong()).toInt(); r.seek(r.length() - n)
            val b = ByteArray(n); r.readFully(b); String(b, Charsets.UTF_8)
        }
    } catch (_: Throwable) { "" }

    fun analyze(d: Detected): Report {
        val gd = d.gameDir
        val mods = enabledMods(gd)
        val byId = mods.associateBy { it.id }
        val reason = LinkedHashMap<String, String>()          // id -> причина (первая, самая сильная)
        val notes = ArrayList<String>()
        fun add(id: String, why: String) { if (byId.containsKey(id) && !reason.containsKey(id)) reason[id] = why }

        val cr = latestCrashReport(gd, d.start)
        val text = (cr?.let { tail(it, 400_000) } ?: "") + "\n" + tail(File(gd, "logs/latest.log"), 400_000) + "\n" + (File(gd, "hs_err.log").takeIf { it.exists() }?.let { tail(it, 60_000) } ?: "")
        val javaError = cr != null || Regex("(Exception|Error):|Caused by:|Mixin apply failed|Incompatible mods found").containsMatchIn(text)

        // 1. «Suspected Mods» в crash-report Fabric/Quilt: «Sodium (sodium)»
        Regex("Suspected Mods:\\s*\\n((?:[ \\t]+.*\\n)+)").find(text)?.groupValues?.get(1)?.lines()?.forEach { l ->
            Regex("\\(([A-Za-z0-9_\\-.]+)\\)").findAll(l).forEach { add(it.groupValues[1], tr("в списке «Suspected Mods» отчёта о вылете", "listed in the crash report's Suspected Mods")) }
        }
        // 2. Ошибки зависимостей Fabric: «Mod 'X' (id) requires/conflicts/breaks …»
        Regex("Mod '([^']+)' \\(([^)]+)\\)[^\\n]*?(requires|conflicts|breaks|depends)[^\\n]*").findAll(text).take(8).forEach { m ->
            add(m.groupValues[2], tr("проблема зависимостей", "dependency problem"))
            notes.add(m.value.trim().take(220))
        }
        Regex("Mod '([^']+)' \\(([^)]+)\\)[^\\n]*?(requires|conflicts|breaks)[^\\n]*").findAll(text).take(8).forEach { m ->
            val miss = Regex("of mod '([^']+)' \\(([^)]+)\\)").find(m.value)
            if (miss != null && !byId.containsKey(miss.groupValues[2])) notes.add(tr("Не хватает мода: ", "Missing mod: ") + miss.groupValues[1])
        }
        // 3. Mixin: «modid.mixins.json»
        Regex("([A-Za-z0-9_\\-]+)\\.mixins?\\.json").findAll(text).map { it.groupValues[1] }.distinct().take(10).forEach { t ->
            val hit = mods.firstOrNull { it.id == t || it.id.startsWith(t) || t.startsWith(it.id) }
            if (hit != null) add(hit.id, tr("ошибка Mixin", "Mixin failure"))
        }
        // 4. Имена jar в стеке ошибки: «~[sodium-fabric-0.6.0.jar:?]»
        val jarCount = HashMap<String, Int>()
        Regex("\\[([^\\[\\]/]+?\\.jar):\\?\\]").findAll(text).forEach { jarCount[it.groupValues[1]] = (jarCount[it.groupValues[1]] ?: 0) + 1 }
        jarCount.entries.sortedByDescending { it.value }.take(5).forEach { (jar, _) ->
            mods.firstOrNull { it.file.name == jar }?.let { add(it.id, tr("встречается в стеке ошибки", "appears in the error stack")) }
        }
        // 5. Известные проблемные моды: если Java-ошибки нет (процесс упал нативно) или причин не нашли
        val renderMods = mods.filter { it.id in RENDER_MODS }
        if (reason.isEmpty() && (!javaError || d.kind != "crash")) {
            renderMods.forEach { add(it.id, tr("рендер-мод: на мобильном рендере часто роняет игру без ошибки в логе", "render mod: often crashes the game on the mobile renderer with no log error")) }
        }
        val suspects = reason.entries.map { Suspect(byId[it.key]!!, it.value) }
        return Report(gd, mods, suspects, notes.distinct().take(5), !javaError, renderMods.isNotEmpty())
    }

    /** Отключает моды (переименование в .disabled) вместе с модами, которые от них зависят. Возвращает названия отключённых. */
    fun disable(rep: Report, chosen: List<Mod>): List<String> {
        val off = LinkedHashMap<String, Mod>()
        chosen.forEach { off[it.id] = it }
        var changed = true
        while (changed) {
            changed = false
            for (m in rep.mods) if (!off.containsKey(m.id) && m.depends.any { off.containsKey(it) }) { off[m.id] = m; changed = true }
        }
        val names = ArrayList<String>()
        for (m in off.values) {
            val to = File(m.file.parentFile, m.file.name + ".disabled")
            if (m.file.exists() && m.file.renameTo(to)) names.add(m.name)
        }
        return names
    }

    fun restoreAll(gd: File): Int {
        var n = 0
        for (f in disabledFiles(gd)) if (f.renameTo(File(f.parentFile, f.name.removeSuffix(".disabled")))) n++
        return n
    }

    /** Подставляется из MainActivity (язык приложения). */
    var trFn: (String, String) -> String = { ru, _ -> ru }
    private fun tr(ru: String, en: String) = trFn(ru, en)
}

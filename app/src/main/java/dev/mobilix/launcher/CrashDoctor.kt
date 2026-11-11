package dev.mobilix.launcher

import android.app.ActivityManager
import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/** Разбор вылетов из-за модов: находит подозрительные моды по crash-report, latest.log и hs_err.log. */
object CrashDoctor {
    class Mod(val id: String, val name: String, val file: File, val depends: Set<String>, val provides: Set<String> = emptySet())
    class Suspect(val mod: Mod, val why: String)
    class Report(
        val gameDir: File,
        val mods: List<Mod>,
        val suspects: List<Suspect>,
        val notes: List<String>,
        val native: Boolean,          // нет Java-ошибки: процесс упал или был убит
        val hasRenderMod: Boolean,
        val missing: List<String> = emptyList(),   // id модов-зависимостей, которых нет в сборке
        val updates: List<Triple<String, String, String>> = emptyList(),   // (id устаревшего мода, минимальная версия, id мода, который её требует)
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
                !ended && System.currentTimeMillis() - start > 20000 && killedBySystem(ctx, start) -> Detected(gd, start, "killed")
                else -> null
            }
        } catch (_: Throwable) { null }
    }

    /** Процесс игры убила система (нехватка памяти, сигнал, сбой), а не пользователь (закрыл вкладку / смахнул). До Android 11 причину узнать нельзя — не считаем вылетом. */
    private fun killedBySystem(ctx: Context, start: Long): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        return try {
            val infos = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getHistoricalProcessExitReasons(ctx.packageName, 0, 8)
            val g = infos.filter { it.processName.endsWith(":game") && it.timestamp >= start }.maxByOrNull { it.timestamp } ?: return false
            g.reason in setOf(
                android.app.ApplicationExitInfo.REASON_LOW_MEMORY, android.app.ApplicationExitInfo.REASON_CRASH,
                android.app.ApplicationExitInfo.REASON_CRASH_NATIVE, android.app.ApplicationExitInfo.REASON_SIGNALED,
                android.app.ApplicationExitInfo.REASON_ANR, android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE)
        } catch (_: Throwable) { false }
    }

    private fun latestCrashReport(gd: File, since: Long): File? =
        File(gd, "crash-reports").listFiles()?.filter { it.isFile && it.lastModified() >= since - 5000 }?.maxByOrNull { it.lastModified() }

    fun readMod(f: File): Mod {
        var id = ""; var name = ""; val dep = HashSet<String>(); val prov = HashSet<String>()
        try {
            ZipFile(f).use { z ->
                fun text(n: String) = z.getEntry(n)?.let { z.getInputStream(it).bufferedReader().use { r -> r.readText() } }
                val fj = text("fabric.mod.json")
                val qj = text("quilt.mod.json")
                val toml = text("META-INF/neoforge.mods.toml") ?: text("META-INF/mods.toml")
                if (fj != null) {
                    val o = JSONObject(fj); id = o.optString("id"); name = o.optString("name")
                    o.optJSONObject("depends")?.keys()?.forEach { dep.add(it) }
                    o.optJSONArray("provides")?.let { a -> for (i in 0 until a.length()) prov.add(a.optString(i)) }
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
        return Mod(id.ifEmpty { base.lowercase() }, name.ifEmpty { base }, f, dep, prov)
    }

    fun hasRenderMods(gd: File): Boolean = enabledMods(gd).any { it.id in RENDER_MODS }

    private val BUILTIN = setOf("minecraft", "java", "fabricloader", "fabric-loader", "quilt_loader", "quilt_loader_minecraft", "forge", "neoforge", "mixinextras")

    /** Обязательные зависимости Fabric, которых нет среди модов сборки (id проекта на Modrinth обычно совпадает с id мода). */
    fun missingDeps(mods: List<Mod>): List<String> {
        val have = HashSet<String>()
        mods.forEach { have.add(it.id); have.addAll(it.provides) }
        val hasApi = "fabric-api" in have
        val out = LinkedHashSet<String>()
        for (m in mods) for (d in m.depends) {
            if (d in BUILTIN || d in have) continue
            if (d.startsWith("fabric-") && d != "fabric-api") { if (!hasApi) out.add("fabric-api"); continue }   // модули Fabric API лежат внутри fabric-api
            out.add(d)
        }
        return out.toList()
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
        // 1b. «Mod 'Sodium' (sodium) … is incompatible with any version before 1.8.7 of mod 'Iris' (iris)» — устарел Iris, его надо обновить
        val updates = LinkedHashMap<String, Pair<String, String>>()
        val incompat = Regex("[Mm]od '([^']+)' \\(([^)]+)\\)[^\\n]*?is incompatible with any version before (\\S+?)!? of mod '([^']+)' \\(([^)]+)\\)")
        incompat.findAll(text).forEach { m -> if (byId.containsKey(m.groupValues[5])) updates[m.groupValues[5]] = m.groupValues[3].trimEnd('!', '.') to m.groupValues[2] }
        // «Fix: … Remove mod 'Iris' (iris)» — решение, которое предлагает сам Fabric (запасной вариант)
        Regex("Remove mod '([^']+)' \\(([^)]+)\\)").findAll(text).forEach { m -> if (!updates.containsKey(m.groupValues[2])) add(m.groupValues[2], tr("Fabric предлагает убрать этот мод (несовместим с другими)", "Fabric suggests removing this mod (incompatible with others)")) }
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
        val suspects = reason.entries.filter { !updates.containsKey(it.key) }.map { Suspect(byId[it.key]!!, it.value) }
        val missing = LinkedHashSet<String>(missingDeps(mods))
        Regex("[Mm]od '([^']+)' \\(([^)]+)\\)[^\\n]*?which is missing").findAll(text).forEach { m -> if (m.groupValues[2] !in BUILTIN) missing.add(m.groupValues[2]) }
        return Report(gd, mods, suspects, notes.distinct().take(5), !javaError, renderMods.isNotEmpty(), missing.toList(), updates.entries.map { Triple(it.key, it.value.first, it.value.second) })
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

    /** Сравнение версий вида «1.8.7+mc1.21.3»: берёт числа до «+»/«-». */
    fun versionAtLeast(have: String, need: String): Boolean {
        fun parts(v: String) = v.substringBefore('+').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(have); val b = parts(need)
        for (i in 0 until maxOf(a.size, b.size)) { val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }; if (x != y) return x > y }
        return true
    }

    fun modJson(f: File): JSONObject? = try {
        ZipFile(f).use { z -> z.getEntry("fabric.mod.json")?.let { JSONObject(z.getInputStream(it).bufferedReader().use { r -> r.readText() }) } }
    } catch (_: Throwable) { null }

    /** Подходит ли версия под условие Fabric: строка («>=0.6.0 <0.7», «0.6.x», «<1.8.7») или массив (любое из). */
    fun satisfies(ver: String, pred: Any?): Boolean {
        when (pred) {
            null, JSONObject.NULL -> return true
            is org.json.JSONArray -> { for (i in 0 until pred.length()) if (satisfies(ver, pred.get(i))) return true; return pred.length() == 0 }
        }
        val v = ver.substringBefore('+').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun cmp(w: String): Int {
            val p = w.substringBefore('+').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(v.size, p.size)) { val x = v.getOrElse(i) { 0 }; val y = p.getOrElse(i) { 0 }; if (x != y) return x.compareTo(y) }
            return 0
        }
        for (tok in pred.toString().trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val ok = when {
                tok == "*" -> true
                tok.startsWith(">=") -> cmp(tok.substring(2)) >= 0
                tok.startsWith("<=") -> cmp(tok.substring(2)) <= 0
                tok.startsWith(">") -> cmp(tok.substring(1)) > 0
                tok.startsWith("<") -> cmp(tok.substring(1)) < 0
                tok.startsWith("=") -> cmp(tok.substring(1)) == 0
                tok.contains('x') || tok.contains('X') || tok.contains('*') -> {
                    val pre = tok.split('.').takeWhile { it != "x" && it != "X" && it != "*" }.map { it.toIntOrNull() ?: -1 }
                    pre.indices.all { v.getOrElse(it) { 0 } == pre[it] }
                }
                tok.startsWith("~") -> { val p = tok.substring(1).split('.').map { it.toIntOrNull() ?: 0 }; v.getOrElse(0) { 0 } == p[0] && v.getOrElse(1) { 0 } == p.getOrElse(1) { 0 } && cmp(tok.substring(1)) >= 0 }
                tok.startsWith("^") -> v.getOrElse(0) { 0 } == (tok.substring(1).split('.')[0].toIntOrNull() ?: 0) && cmp(tok.substring(1)) >= 0
                else -> cmp(tok) == 0
            }
            if (!ok) return false
        }
        return true
    }

    /** Совместима ли пара: candidate (вышестоящий мод, например Sodium) с target (например Iris), по breaks/depends обеих сторон. */
    fun pairCompatible(candFile: File, candId: String, targetFile: File, targetId: String): Boolean {
        val c = modJson(candFile) ?: return false; val t = modJson(targetFile) ?: return false
        val cv = c.optString("version"); val tv = t.optString("version")
        // candidate «ломает» target?
        c.opt("breaks").let { b -> if (b is JSONObject && b.has(targetId) && satisfies(tv, b.opt(targetId))) return false }
        c.opt("conflicts").let { b -> if (b is JSONObject && b.has(targetId) && satisfies(tv, b.opt(targetId))) return false }
        // target «ломает» candidate или требует другой диапазон?
        t.opt("breaks").let { b -> if (b is JSONObject && b.has(candId) && satisfies(cv, b.opt(candId))) return false }
        t.optJSONObject("depends")?.let { d -> if (d.has(candId) && !satisfies(cv, d.opt(candId))) return false }
        return true
    }

    fun modVersion(f: File): String = try {
        ZipFile(f).use { z -> z.getEntry("fabric.mod.json")?.let { JSONObject(z.getInputStream(it).bufferedReader().use { r -> r.readText() }).optString("version") } ?: "" }
    } catch (_: Throwable) { "" }

    fun restoreAll(gd: File): Int {
        var n = 0
        for (f in disabledFiles(gd)) if (f.renameTo(File(f.parentFile, f.name.removeSuffix(".disabled")))) n++
        return n
    }

    /** Подставляется из MainActivity (язык приложения). */
    var trFn: (String, String) -> String = { ru, _ -> ru }
    private fun tr(ru: String, en: String) = trFn(ru, en)
}

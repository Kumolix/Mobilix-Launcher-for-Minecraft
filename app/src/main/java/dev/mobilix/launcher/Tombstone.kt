package dev.mobilix.launcher

/**
 * Минимальный разбор tombstone (protobuf, Android 12+): сигнал, причина и стек упавшего потока.
 * Поля по android.os.tombstone.proto: Tombstone{tid=6, signal_info=10, abort_message=14, causes=15, threads=16},
 * Thread{id=1, name=2, current_backtrace=4}, BacktraceFrame{rel_pc=1, function_name=4, function_offset=5, file_name=6}.
 */
internal object Tombstone {
    private class Field(val num: Int, val wire: Int, val varint: Long, val bytes: ByteArray?)

    private fun parse(b: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long {
            var r = 0L; var s = 0
            while (i < b.size) {
                val x = b[i++].toInt()
                r = r or ((x and 0x7f).toLong() shl s)
                if ((x and 0x80) == 0) return r
                s += 7
                if (s > 63) break
            }
            throw IllegalArgumentException("varint")
        }
        while (i < b.size) {
            val tag = varint().toInt()
            val num = tag ushr 3
            when (tag and 7) {
                0 -> out.add(Field(num, 0, varint(), null))
                1 -> { if (i + 8 > b.size) throw IllegalArgumentException("fixed64"); i += 8; out.add(Field(num, 1, 0, null)) }
                2 -> {
                    val n = varint().toInt()
                    if (n < 0 || i + n > b.size) throw IllegalArgumentException("len")
                    out.add(Field(num, 2, 0, b.copyOfRange(i, i + n))); i += n
                }
                5 -> { if (i + 4 > b.size) throw IllegalArgumentException("fixed32"); i += 4; out.add(Field(num, 5, 0, null)) }
                else -> throw IllegalArgumentException("wire")
            }
        }
        return out
    }

    private fun str(f: Field) = String(f.bytes ?: ByteArray(0), Charsets.UTF_8)

    /** Строки описания краха; пустой список, если разобрать не удалось. */
    fun describe(raw: ByteArray): List<String> {
        val res = ArrayList<String>()
        val top = parse(raw)
        val tid = top.firstOrNull { it.num == 6 && it.wire == 0 }?.varint ?: -1L
        top.firstOrNull { it.num == 10 && it.wire == 2 }?.let { sig ->
            val s = parse(sig.bytes!!)
            val name = s.firstOrNull { it.num == 2 && it.wire == 2 }?.let { str(it) } ?: "?"
            val code = s.firstOrNull { it.num == 4 && it.wire == 2 }?.let { str(it) } ?: ""
            val addr = s.firstOrNull { it.num == 9 && it.wire == 0 }?.varint
            res.add("Сигнал $name $code" + (if (addr != null) ", адрес 0x" + java.lang.Long.toHexString(addr) else ""))
        }
        top.filter { it.num == 14 && it.wire == 2 }.forEach { res.add("Abort: " + str(it)) }
        top.filter { it.num == 15 && it.wire == 2 }.forEach { c ->
            parse(c.bytes!!).firstOrNull { it.num == 1 && it.wire == 2 }?.let { res.add("Причина: " + str(it)) }
        }
        for (e in top.filter { it.num == 16 && it.wire == 2 }) {
            val ent = parse(e.bytes!!)
            val key = ent.firstOrNull { it.num == 1 && it.wire == 0 }?.varint ?: continue
            if (key != tid) continue
            val th = parse(ent.firstOrNull { it.num == 2 && it.wire == 2 }?.bytes ?: continue)
            res.add("Упавший поток " + (th.firstOrNull { it.num == 2 && it.wire == 2 }?.let { str(it) } ?: "?") + " (tid $tid), стек:")
            var n = 0
            for (fr in th.filter { it.num == 4 && it.wire == 2 }.take(30)) {
                val f = parse(fr.bytes!!)
                val file = f.firstOrNull { it.num == 6 && it.wire == 2 }?.let { str(it) }?.substringAfterLast('/') ?: "?"
                val fn = f.firstOrNull { it.num == 4 && it.wire == 2 }?.let { str(it) } ?: ""
                val off = f.firstOrNull { it.num == 5 && it.wire == 0 }?.varint
                val rel = f.firstOrNull { it.num == 1 && it.wire == 0 }?.varint ?: 0L
                res.add("  #${n++} $file" + (if (fn.isNotEmpty()) " $fn" + (if (off != null) "+$off" else "") else "") + " (+0x" + java.lang.Long.toHexString(rel) + ")")
            }
        }
        return res
    }
}

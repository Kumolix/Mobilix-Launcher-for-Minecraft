package dev.mobilix.launcher

/**
 * Подбор настроек по видеочипу (строка GL_RENDERER). Работает для любых Android-телефонов
 * (Samsung, Xiaomi/Redmi/Poco, Huawei, Nothing, Pixel и др.): важен чип, а не марка.
 * Уровень 4 — самые мощные чипы, 0 — самые слабые. Он же — рекомендуемый уровень мипмапов.
 */
object GpuTier {
    private fun num(g: String, re: String): Int? = Regex(re).find(g)?.groupValues?.get(1)?.toIntOrNull()

    /** Уровень 0..4 или null, если чип незнаком (тогда ничего не меняем). */
    fun tier(gpu: String): Int? {
        val g = gpu.lowercase()
        if (g.isEmpty()) return null
        num(g, "adreno[^0-9]*(\\d{3})")?.let { n ->
            return when {
                n >= 730 -> 4          // 8 Gen 1 и новее
                n >= 650 -> 3          // 650/660/690, 710/720/725
                n >= 630 -> 2          // 630/640/642/643/644
                n >= 620 -> 1
                else -> 0              // 610–619 (в т. ч. 618 в Galaxy A52) и 5xx
            }
        }
        num(g, "xclipse[^0-9]*(\\d{3})")?.let { return if (it >= 920) 4 else 3 }
        num(g, "immortalis-g(\\d+)")?.let { return 4 }
        num(g, "mali-g(\\d+)")?.let { n ->
            return when {
                n >= 720 -> 4
                n == 710 || n == 715 || n == 78 || n == 77 -> 3
                n == 610 || n == 615 || n == 76 -> 2
                n == 72 || n == 71 || n == 68 || n == 57 -> 1
                else -> 0              // G31/G51/G52 и др.
            }
        }
        if (g.contains("maleoon")) return 3
        if (g.contains("mali-t")) return 0
        if (g.contains("powervr")) return if (g.contains("dxt")) 3 else 0
        return null
    }

    /** Разрешение рендера (%) для «Авто». */
    fun scale(tier: Int): Int = when (tier) { 0 -> 70; 1 -> 80; 2 -> 90; else -> 100 }
}

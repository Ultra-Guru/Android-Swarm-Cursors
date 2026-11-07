package io.swarmcursors.core.motion

import kotlin.math.floor

/**
 * 经典改进型 Perlin 噪声（Ken Perlin, 2002）—— 纯 Kotlin 实现。
 *
 * 用途：为光标轨迹叠加约 2px 振幅的连续微抖动，模拟手部自然震颤，
 * 让「机械直线」变成「有机轨迹」。同一噪声实例在相邻时间参数下输出
 * 平滑连续的值，这是抖动不产生高频闪烁的关键。
 *
 * 输出范围 [-1, 1]；整数格点处恒为 0。
 */
class PerlinNoise(seed: Long = 0L) {

    private val perm = IntArray(512)

    init {
        val source = IntArray(256) { it }
        val rnd = kotlin.random.Random(seed)
        // Fisher–Yates 洗牌置换表（同 seed 结果确定 → 引擎行为可复现可测试）
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = source[i]
            source[i] = source[j]
            source[j] = t
        }
        for (i in 0 until 512) perm[i] = source[i and 255]
    }

    /** 2D 噪声（z=0 的 3D 退化形式）。 */
    fun noise2(x: Double, y: Double): Double = noise3(x, y, 0.0)

    /** 3D 噪声，输出 [-1, 1]。 */
    fun noise3(x: Double, y: Double, z: Double): Double {
        val xFloor = floor(x)
        val yFloor = floor(y)
        val zFloor = floor(z)

        val xi = xFloor.toInt() and 255
        val yi = yFloor.toInt() and 255
        val zi = zFloor.toInt() and 255

        val xf = x - xFloor
        val yf = y - yFloor
        val zf = z - zFloor

        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)

        val a = perm[xi] + yi
        val aa = perm[a] + zi
        val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi
        val ba = perm[b] + zi
        val bb = perm[b + 1] + zi

        val x1 = lerp(grad(perm[aa], xf, yf, zf), grad(perm[ba], xf - 1, yf, zf), u)
        val x2 = lerp(grad(perm[ab], xf, yf - 1, zf), grad(perm[bb], xf - 1, yf - 1, zf), u)
        val y1 = lerp(x1, x2, v)

        val x3 = lerp(grad(perm[aa + 1], xf, yf, zf - 1), grad(perm[ba + 1], xf - 1, yf, zf - 1), u)
        val x4 = lerp(grad(perm[ab + 1], xf, yf - 1, zf - 1), grad(perm[bb + 1], xf - 1, yf - 1, zf - 1), u)
        val y2 = lerp(x3, x4, v)

        return lerp(y1, y2, w)
    }

    private fun fade(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)

    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

    private fun grad(hash: Int, x: Double, y: Double, z: Double): Double {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }
}

package io.swarmcursors.core.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PerlinNoiseTest {

    @Test
    fun `output bounded to unit range`() {
        val n = PerlinNoise(seed = 42L)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (i in 0 until 10_000) {
            val x = i * 0.137
            val y = i * 0.091
            val v = n.noise2(x, y)
            if (v < min) min = v
            if (v > max) max = v
        }
        assertTrue("min=$min 越界", min >= -1.0)
        assertTrue("max=$max 越界", max <= 1.0)
        // 真实使用区间：随机采样的取值应明显铺开（不是常量函数）
        assertTrue("值域太窄（min=$min max=$max）", max - min > 0.5)
    }

    @Test
    fun `same seed is deterministic`() {
        val a = PerlinNoise(seed = 7L)
        val b = PerlinNoise(seed = 7L)
        for (i in 0 until 1000) {
            assertEquals(a.noise2(i * 0.11, i * 0.07), b.noise2(i * 0.11, i * 0.07), 0.0)
        }
    }

    @Test
    fun `different seeds diverge`() {
        val a = PerlinNoise(seed = 1L)
        val b = PerlinNoise(seed = 2L)
        var diff = 0
        for (i in 0 until 1000) {
            // 双坐标都变化：避免 1D 切片上两套置换表的巧合重合
            if (abs(a.noise2(i * 0.13, i * 0.29) - b.noise2(i * 0.13, i * 0.29)) > 1e-9) diff++
        }
        assertTrue("两种子输出几乎相同（diff=$diff/1000）", diff > 900)
    }

    @Test
    fun `integer lattice is zero`() {
        val n = PerlinNoise(seed = 3L)
        assertEquals(0.0, n.noise2(0.0, 0.0), 1e-12)
        assertEquals(0.0, n.noise2(5.0, 7.0), 1e-12)
    }

    @Test
    fun `adjacent parameters are smooth`() {
        val n = PerlinNoise(seed = 9L)
        var maxStep = 0.0
        var prev = n.noise2(0.0, 0.0)
        for (i in 1 until 5000) {
            val v = n.noise2(i * 0.01, 0.0)
            val step = abs(v - prev)
            if (step > maxStep) maxStep = step
            prev = v
        }
        // 步长 0.01 时相邻输出差应远小于 1（连续性 —— 抖动不闪的根基）
        assertTrue("噪声不连续（maxStep=$maxStep）", maxStep < 0.2)
    }
}

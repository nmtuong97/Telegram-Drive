package com.nmtuong.telegramdrive.feature.preview

import org.junit.Test
import org.junit.Assert.assertEquals

class GestureUtilsTest {
    @Test
    fun accumulateSeek_computesCorrectlyForLeftHalf() {
        val (accumulated, direction) = GestureUtils.calculateSeekAccumulation(0L, true, 0)
        assertEquals(-10_000L, accumulated)
        assertEquals(-1, direction)
    }

    @Test
    fun accumulateSeek_computesCorrectlyForRightHalf() {
        val (accumulated, direction) = GestureUtils.calculateSeekAccumulation(0L, false, 0)
        assertEquals(10_000L, accumulated)
        assertEquals(1, direction)
    }

    @Test
    fun accumulateSeek_resetsWhenChangingDirectionToRight() {
        val (accumulated, direction) = GestureUtils.calculateSeekAccumulation(-20_000L, false, -1)
        assertEquals(10_000L, accumulated) // 0 + 10_000
        assertEquals(1, direction)
    }

    @Test
    fun accumulateSeek_resetsWhenChangingDirectionToLeft() {
        val (accumulated, direction) = GestureUtils.calculateSeekAccumulation(20_000L, true, 1)
        assertEquals(-10_000L, accumulated) // 0 - 10_000
        assertEquals(-1, direction)
    }

    @Test
    fun accumulateSeek_accumulatesInSameDirection() {
        val (accumulated, direction) = GestureUtils.calculateSeekAccumulation(20_000L, false, 1)
        assertEquals(30_000L, accumulated)
        assertEquals(1, direction)
    }
}

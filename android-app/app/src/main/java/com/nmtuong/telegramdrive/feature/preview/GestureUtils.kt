package com.nmtuong.telegramdrive.feature.preview

object GestureUtils {
    fun calculateSeekAccumulation(currentAccumulated: Long, isLeftHalf: Boolean, currentDirection: Int): Pair<Long, Int> {
        var newAccumulated = currentAccumulated
        var newDirection = currentDirection

        if (isLeftHalf) {
            if (newDirection == 1) newAccumulated = 0L
            newAccumulated -= 10_000L
            newDirection = -1
        } else {
            if (newDirection == -1) newAccumulated = 0L
            newAccumulated += 10_000L
            newDirection = 1
        }
        return Pair(newAccumulated, newDirection)
    }
}

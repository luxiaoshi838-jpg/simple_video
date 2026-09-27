package com.luxiaoshi.jianbo

import com.luxiaoshi.jianbo.data.VideoGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class FolderSortLogicTest {
    private val groups = listOf(
        VideoGroup("a", "A", emptyList(), VideoGroup.Source.AUTO),
        VideoGroup("b", "B", emptyList(), VideoGroup.Source.AUTO),
        VideoGroup("c", "C", emptyList(), VideoGroup.Source.MANUAL),
    )

    @Test
    fun defaultModeReturnsCurrentRepositoryOrderUntouched() {
        val result = sortGroupsForDisplay(groups, recentFirst = false) { 999L }
        assertSame(groups, result)
        assertEquals(listOf("a", "b", "c"), result.map { it.key })
    }

    @Test
    fun recentModeMovesMostRecentlyPlayedFoldersUpAndKeepsStableTies() {
        val playedAt = mapOf("a" to 100L, "b" to 300L, "c" to 300L)
        val result = sortGroupsForDisplay(groups, recentFirst = true) {
            playedAt[it] ?: 0L
        }
        assertEquals(listOf("b", "c", "a"), result.map { it.key })
        assertSame(groups[1].videos, result[0].videos)
        assertSame(groups[2].videos, result[1].videos)
    }
}

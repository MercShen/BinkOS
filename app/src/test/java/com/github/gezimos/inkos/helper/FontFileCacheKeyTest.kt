package com.github.gezimos.inkos.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FontFileCacheKeyTest {
    @Test
    fun keyCombinesPathLengthAndMtime() {
        assertEquals("/a/b.ttf|10|20", FontFileCache.keyFor("/a/b.ttf", 10L, 20L))
    }

    @Test
    fun keyChangesWhenFileChanges() {
        assertNotEquals(
            FontFileCache.keyFor("/a/b.ttf", 10L, 20L),
            FontFileCache.keyFor("/a/b.ttf", 11L, 20L)
        )
        assertNotEquals(
            FontFileCache.keyFor("/a/b.ttf", 10L, 20L),
            FontFileCache.keyFor("/a/b.ttf", 10L, 21L)
        )
    }
}

package com.pdfpaper.engine.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LruCacheTest {

    @Test
    fun storesAndReturnsValues() {
        val cache = LruCache<String, ByteArray>(1024) { it.size.toLong() }
        assertEquals(null, cache.put("a", ByteArray(10)))
        assertEquals(10, cache.get("a")!!.size)
        assertEquals(10L, cache.sizeBytes)
        assertEquals(1, cache.size)
    }

    @Test
    fun putReportsPreviousValueAndKeepsAccountingStraight() {
        val cache = LruCache<Int, ByteArray>(1024) { it.size.toLong() }
        cache.put(1, ByteArray(100))
        val previous = cache.put(1, ByteArray(40))
        assertEquals(100, previous!!.size)
        assertEquals(40L, cache.sizeBytes)
        assertEquals(1, cache.size)
    }

    @Test
    fun evictsLeastRecentlyUsedOnByteBudget() {
        val cache = LruCache<Int, ByteArray>(250) { it.size.toLong() }
        cache.put(1, ByteArray(100))
        cache.put(2, ByteArray(100))
        cache.put(3, ByteArray(100)) // budget exceeded → 1 goes
        assertNull(cache.get(1))
        assertEquals(listOf(2, 3), cache.keys())
        assertEquals(200L, cache.sizeBytes)
        assertEquals(1L, cache.evictionCount)
    }

    @Test
    fun getRefreshesRecency() {
        val cache = LruCache<Int, ByteArray>(250) { it.size.toLong() }
        cache.put(1, ByteArray(100))
        cache.put(2, ByteArray(100))
        cache.get(1) // 1 becomes newest
        cache.put(3, ByteArray(100)) // 2 is now the least recently used
        assertNull(cache.get(2))
        assertEquals(listOf(1, 3), cache.keys())
    }

    @Test
    fun oversizedEntryIsKeptAndEvictsTheRest() {
        val cache = LruCache<Int, ByteArray>(100) { it.size.toLong() }
        cache.put(1, ByteArray(100))
        cache.put(2, ByteArray(500)) // bigger than the whole budget
        assertEquals(1, cache.size)
        assertNull(cache.get(1))
        assertEquals(500, cache.get(2)!!.size)
        assertEquals(500L, cache.sizeBytes)
    }

    @Test
    fun removeAndClearUpdateAccounting() {
        val cache = LruCache<Int, ByteArray>(1024) { it.size.toLong() }
        cache.put(1, ByteArray(100))
        cache.put(2, ByteArray(200))
        assertEquals(200, cache.remove(2)!!.size)
        assertEquals(100L, cache.sizeBytes)
        cache.clear()
        assertEquals(0L, cache.sizeBytes)
        assertEquals(0, cache.size)
        assertNull(cache.get(1))
    }

    @Test
    fun resizeEvictsDownToTheNewBudget() {
        val cache = LruCache<Int, ByteArray>(1000) { it.size.toLong() }
        cache.put(1, ByteArray(300))
        cache.put(2, ByteArray(300))
        cache.put(3, ByteArray(300))
        cache.resize(400)
        // Only the most recently used entry survives: capacity dropped below
        // two entries, and the newest one is never evicted.
        assertEquals(1, cache.size)
        assertEquals(300L, cache.sizeBytes)
        assertNull(cache.get(1))
        assertEquals(3, cache.keys().single())
    }

    @Test
    fun valuesAreChargedAtLeastOneByte() {
        val cache = LruCache<Int, String>(3) { 0L }
        cache.put(1, "a")
        cache.put(2, "b")
        cache.put(3, "c")
        cache.put(4, "d")
        assertNull(cache.get(1))
        assertEquals(3, cache.size)
    }
}

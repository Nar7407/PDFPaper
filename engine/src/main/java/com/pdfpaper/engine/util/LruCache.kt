package com.pdfpaper.engine.util

/**
 * Small byte-bounded LRU cache in access order.
 *
 * Pure Kotlin on purpose: the viewer needs to bound bitmap memory without
 * tying the engine to `android.util.LruCache`, and the eviction policy stays
 * unit-testable. The most recently used entry is never evicted, even when it
 * alone exceeds the budget (a single page bitmap must still be displayable).
 */
class LruCache<K : Any, V : Any>(
    maxBytes: Long,
    /** Byte cost of a value; values are never charged less than 1 byte. */
    private val sizeOf: (V) -> Long = { 1L }
) {

    private val entries = LinkedHashMap<K, V>(16, 0.75f, true)
    private var capacity = if (maxBytes > 0L) maxBytes else 1L
    private var used = 0L
    private var evicted = 0L

    val maxBytes: Long get() = synchronized(this) { capacity }

    /** Total bytes currently held. */
    val sizeBytes: Long get() = synchronized(this) { used }

    val size: Int get() = synchronized(this) { entries.size }

    /** Number of entries dropped by [trim] since construction. */
    val evictionCount: Long get() = synchronized(this) { evicted }

    @Synchronized
    fun get(key: K): V? = entries[key]

    @Synchronized
    fun contains(key: K): Boolean = entries.containsKey(key)

    /** Inserts or replaces [key]; returns the previous value for it. */
    @Synchronized
    fun put(key: K, value: V): V? {
        val bytes = cost(value)
        val previous = entries.put(key, value)
        used += bytes
        if (previous != null) used -= cost(previous)
        trim()
        return previous
    }

    @Synchronized
    fun remove(key: K): V? {
        val previous = entries.remove(key) ?: return null
        used -= cost(previous)
        return previous
    }

    @Synchronized
    fun clear() {
        entries.clear()
        used = 0L
    }

    /** Keys from least to most recently used (eviction order). */
    @Synchronized
    fun keys(): List<K> = entries.keys.toList()

    /** Changes the budget and evicts immediately if needed. */
    @Synchronized
    fun resize(newMaxBytes: Long) {
        capacity = if (newMaxBytes > 0L) newMaxBytes else 1L
        trim()
    }

    private fun trim() {
        val iterator = entries.entries.iterator()
        while (used > capacity && entries.size > 1 && iterator.hasNext()) {
            val entry = iterator.next()
            used -= cost(entry.value)
            iterator.remove()
            evicted++
        }
    }

    private fun cost(value: V): Long = sizeOf(value).coerceAtLeast(1L)
}

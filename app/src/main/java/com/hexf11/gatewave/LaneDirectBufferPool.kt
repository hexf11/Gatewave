package com.hexf11.gatewave

import java.nio.ByteBuffer
import java.util.ArrayDeque

/** Direct-buffer cache owned and observed by one selector lane. */
internal class LaneDirectBufferPool(private val maximumPooledBytes: Int) {
    private val available = HashMap<Int, ArrayDeque<ByteBuffer>>()
    private var pooledByteCount = 0
    private var hitCount = 0L
    private var allocationCount = 0L

    fun acquire(size: Int): ByteBuffer {
        val buffer = available[size]?.pollFirst()
        if (buffer != null) {
            hitCount++
            pooledByteCount -= buffer.capacity()
            buffer.clear()
            return buffer
        }
        allocationCount++
        return ByteBuffer.allocateDirect(size)
    }

    fun release(buffer: ByteBuffer) {
        buffer.clear()
        if (pooledByteCount + buffer.capacity() > maximumPooledBytes) return
        available.getOrPut(buffer.capacity()) { ArrayDeque() }.addLast(buffer)
        pooledByteCount += buffer.capacity()
    }

    fun clear() {
        available.clear()
        pooledByteCount = 0
    }

    fun pooledBytes(): Int = pooledByteCount

    fun hits(): Long = hitCount

    fun allocations(): Long = allocationCount
}

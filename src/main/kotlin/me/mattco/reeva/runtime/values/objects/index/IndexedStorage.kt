package me.mattco.reeva.runtime.values.objects.index

import me.mattco.reeva.runtime.values.JSValue
import me.mattco.reeva.runtime.values.objects.Descriptor

interface IndexedStorage {
    val size: Int
    val arrayLikeSize: Int

    fun hasIndex(index: Int): Boolean
    fun get(index: Int): Descriptor?
    fun set(index: Int, value: JSValue, attributes: Int)
    fun remove(index: Int)

    fun insert(index: Int, value: JSValue, attributes: Int)
    fun removeFirst(): Descriptor
    fun removeLast(): Descriptor

    fun setArrayLikeSize(size: Int)

    companion object {
        const val MIN_PACKED_RESIZE_AMOUNT = 20
        const val SPARSE_ARRAY_THRESHOLD = 200
    }
}
package me.mattco.jsthing.compiler

import me.mattco.jsthing.utils.expect

class ByteClassLoader : ClassLoader() {
    private val classes = mutableMapOf<String, ByteArray>()

    fun addClass(className: String, bytes: ByteArray) {
        classes[className] = bytes
    }

    override fun findClass(name: String?): Class<*> {
        expect(name in classes)
        val bytes = classes[name]!!
        return defineClass(name, bytes, 0, bytes.size)
    }
}
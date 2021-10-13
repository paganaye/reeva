package com.reevajs.reeva.runtime.builtins

import com.reevajs.reeva.core.Realm
import com.reevajs.reeva.runtime.JSValue
import com.reevajs.reeva.runtime.collections.JSArguments
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

interface Builtin {
    val handle: MethodHandle

    companion object {
        val METHOD_TYPE: MethodType =
            MethodType.methodType(JSValue::class.java, Realm::class.java, JSArguments::class.java)

        fun forClass(clazz: Class<*>, name: String) = object : Builtin {
            override val handle: MethodHandle = MethodHandles.publicLookup().findStatic(clazz, name, METHOD_TYPE)
        }
    }
}
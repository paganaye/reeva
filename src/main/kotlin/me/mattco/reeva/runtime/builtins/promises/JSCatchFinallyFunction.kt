package me.mattco.reeva.runtime.builtins.promises

import me.mattco.reeva.core.Realm
import me.mattco.reeva.core.ThrowException
import me.mattco.reeva.runtime.JSValue
import me.mattco.reeva.runtime.Operations
import me.mattco.reeva.runtime.functions.JSFunction
import me.mattco.reeva.runtime.functions.JSNativeFunction
import me.mattco.reeva.runtime.objects.JSObject
import me.mattco.reeva.runtime.primitives.JSUndefined
import me.mattco.reeva.utils.JSArguments
import me.mattco.reeva.utils.argument
import me.mattco.reeva.utils.toValue

class JSCatchFinallyFunction private constructor(
    realm: Realm,
    private val ctor: JSFunction,
    private val onFinally: JSFunction,
) : JSNativeFunction(realm, "", 1) {
    override fun call(thisValue: JSValue, arguments: JSArguments): JSValue {
        val result = Operations.call(onFinally, JSUndefined)
        val promise = Operations.promiseResolve(ctor, result)
        val valueThunk = fromLambda(realm, "", 0) { _, _ -> throw ThrowException(arguments.argument(0)) }
        return Operations.invoke(promise, "then".toValue(), listOf(valueThunk))
    }

    override fun construct(arguments: JSArguments, newTarget: JSValue): JSValue {
        throw IllegalStateException("Unexpected construction of JSCatchFinallyFunction")
    }

    companion object {
        fun create(realm: Realm, ctor: JSFunction, onFinally: JSFunction) =
            JSCatchFinallyFunction(realm, ctor, onFinally).also { it.init() }
    }
}

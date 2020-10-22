package me.mattco.reeva.runtime.annotations

import me.mattco.reeva.runtime.values.JSValue
import me.mattco.reeva.runtime.values.objects.Attributes

typealias NativeFunctionSignature = (thisValue: JSValue, arguments: List<JSValue>) -> JSValue

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION)
annotation class JSMethod(
    val name: String,
    val length: Int,
    val attributes: Int = Attributes.defaultAttributes
)
package me.mattco.reeva.runtime.values.objects

import me.mattco.reeva.runtime.Agent
import me.mattco.reeva.runtime.Agent.Companion.checkError
import me.mattco.reeva.runtime.Operations
import me.mattco.reeva.runtime.Realm
import me.mattco.reeva.runtime.annotations.*
import me.mattco.reeva.runtime.contexts.ExecutionContext
import me.mattco.reeva.runtime.environment.FunctionEnvRecord
import me.mattco.reeva.runtime.environment.GlobalEnvRecord
import me.mattco.reeva.runtime.values.JSValue
import me.mattco.reeva.runtime.values.functions.*
import me.mattco.reeva.runtime.values.objects.Descriptor.Companion.HAS_WRITABLE
import me.mattco.reeva.runtime.values.objects.Descriptor.Companion.WRITABLE
import me.mattco.reeva.runtime.values.objects.index.IndexedProperties
import me.mattco.reeva.runtime.values.primitives.*
import me.mattco.reeva.utils.*

open class JSObject protected constructor(
    val realm: Realm,
    prototype: JSValue? = null
) : JSValue() {
    private val storage = mutableMapOf<StringOrSymbol, Descriptor>()
    internal val indexedProperties = IndexedProperties()
    private var extensible: Boolean = true

    private var prototype: JSValue = JSNull

    init {
        if (prototype != null)
            this.prototype = prototype
    }

    // To facilitate classes which must set their prototypes in the init()
    // call instead of the class constructor
    protected fun internalSetPrototype(prototype: JSValue) {
        this.prototype = prototype
    }

    data class NativeMethodPair(
        var attributes: Int,
        var getter: NativeGetterSignature? = null,
        var setter: NativeSetterSignature? = null,
    )

    data class NativeAccessorPair(
        var attributes: Int,
        var getter: JSFunction? = null,
        var setter: JSFunction? = null,
    )

    open fun init() {
        defineOwnProperty("prototype", prototype, 0)

        configureInstanceProperties()
    }

    // This method exists to be called directly by subclass who cannot call their
    // super.init() method due to prototype complications
    protected fun configureInstanceProperties() {
        // TODO: This is probably terrible for performance, but very cool :)
        // A better way to do it would be to use an annotation processor, and bake
        // these properties into the class's "init" method as direct calls to the
        // appropriate "defineXYZ" method intead of having to do all this reflection
        // every single time a property is instantiated

        val nativeProperties = mutableMapOf<PropertyKey, NativeMethodPair>()

        this::class.java.declaredMethods.filter {
            it.isAnnotationPresent(JSNativePropertyGetter::class.java)
        }.forEach { method ->
            val getter = method.getAnnotation(JSNativePropertyGetter::class.java)
            val methodPair = NativeMethodPair(attributes = getter.attributes, getter = { thisValue ->
                method.invoke(this, thisValue) as JSValue
            })
            val key = if (getter.name.startsWith("@@")) {
                realm.wellknownSymbols[getter.name]?.let(::PropertyKey) ?:
                throw IllegalArgumentException("No well known symbol found with name ${getter.name}")
            } else PropertyKey(getter.name)
            expect(key !in nativeProperties)
            nativeProperties[key] = methodPair
        }

        this::class.java.declaredMethods.filter {
            it.isAnnotationPresent(JSNativePropertySetter::class.java)
        }.forEach { method ->
            val setter = method.getAnnotation(JSNativePropertySetter::class.java)
            val key = if (setter.name.startsWith("@@")) {
                realm.wellknownSymbols[setter.name]?.let(::PropertyKey) ?:
                throw IllegalArgumentException("No well known symbol found with name ${setter.name}")
            } else PropertyKey(setter.name)
            val methodPair = if (key in nativeProperties) {
                nativeProperties[key]!!.also {
                    expect(it.attributes == setter.attributes)
                }
            } else {
                val t = NativeMethodPair(setter.attributes)
                nativeProperties[key] = t
                t
            }
            methodPair.setter = { thisValue, value -> method.invoke(this, thisValue, value) }
        }

        nativeProperties.forEach { (name, methods) ->
            defineNativeProperty(name, methods.attributes, methods.getter, methods.setter)
        }

        val nativeAccessors = mutableMapOf<PropertyKey, NativeAccessorPair>()

        this::class.java.declaredMethods.filter {
            it.isAnnotationPresent(JSNativeAccessorGetter::class.java)
        }.forEach { method ->
            val getter = method.getAnnotation(JSNativeAccessorGetter::class.java)
            val methodPair = NativeAccessorPair(
                attributes = getter.attributes,
                JSNativeFunction.fromLambda(realm, "TODO", 0) { thisValue, _ ->
                    method.invoke(this, thisValue) as JSValue
                }
            )
            val key = if (getter.name.startsWith("@@")) {
                realm.wellknownSymbols[getter.name]?.let(::PropertyKey) ?:
                throw IllegalArgumentException("No well known symbol found with name ${getter.name}")
            } else PropertyKey(getter.name)
            expect(key !in nativeAccessors)
            nativeAccessors[key] = methodPair
        }

        this::class.java.declaredMethods.filter {
            it.isAnnotationPresent(JSNativeAccessorSetter::class.java)
        }.forEach { method ->
            val setter = method.getAnnotation(JSNativeAccessorSetter::class.java)
            val key = if (setter.name.startsWith("@@")) {
                realm.wellknownSymbols[setter.name]?.let(::PropertyKey) ?:
                throw IllegalArgumentException("No well known symbol found with name ${setter.name}")
            } else PropertyKey(setter.name)
            val methodPair = if (key in nativeAccessors) {
                nativeAccessors[key]!!.also {
                    expect(it.attributes == setter.attributes)
                }
            } else {
                val t = NativeAccessorPair(setter.attributes)
                nativeAccessors[key] = t
                t
            }
            methodPair.setter = JSNativeFunction.fromLambda(realm, "TODO", 1) { thisValue, arguments ->
                method.invoke(this, thisValue, arguments.argument(0)) as JSValue
            }
        }

        nativeAccessors.forEach { (name, methods) ->
            defineNativeAccessor(name, methods.attributes, methods.getter, methods.setter)
        }

        this::class.java.declaredMethods.filter {
            it.isAnnotationPresent(JSMethod::class.java)
        }.forEach {
            val annotation = it.getAnnotation(JSMethod::class.java)
            val key = if (annotation.name.startsWith("@@")) {
                realm.wellknownSymbols[annotation.name]?.let(::PropertyKey) ?:
                throw IllegalArgumentException("No well known symbol found with name ${annotation.name}")
            } else PropertyKey(annotation.name)

            defineNativeFunction(
                key,
                annotation.length,
                annotation.attributes
            ) { thisValue, arguments ->
                it.invoke(this, thisValue, arguments) as JSValue
            }
        }
    }

    @JSThrows
    @ECMAImpl("[[GetPrototypeOf]]", "9.1.1")
    open fun getPrototype() = prototype

    @JSThrows
    @ECMAImpl("[[SetPrototypeOf]]", "9.1.2")
    open fun setPrototype(newPrototype: JSValue): Boolean {
        ecmaAssert(newPrototype.isObject || newPrototype.isNull)
        if (newPrototype.sameValue(prototype))
            return true

        if (!extensible)
            return false

        var p = newPrototype
        while (true) {
            if (p.isNull)
                break
            if (p.sameValue(this))
                return false
            // TODO: Handle 9.1.2.1.8.c.i?
            p = (p as JSObject).getPrototype()
            checkError() ?: return false
        }

        prototype = p
        return true
    }

    @JSThrows fun hasProperty(property: String): Boolean = hasProperty(property.key())
    @JSThrows fun hasProperty(property: JSSymbol) = hasProperty(property.key())
    @JSThrows fun hasProperty(property: Int) = hasProperty(property.key())

    @JSThrows
    @ECMAImpl("[[HasProperty]]", "9.1.7")
    fun hasProperty(property: PropertyKey): Boolean {
        val hasOwn = getOwnPropertyDescriptor(property)
        if (hasOwn != null)
            return true
        val parent = getPrototype()
        checkError() ?: return false
        if (parent != JSNull)
            return (parent as JSObject).hasProperty(property)
        return false
    }

    @JSThrows
    @ECMAImpl("[[IsExtensible]]", "9.1.3")
    open fun isExtensible() = extensible

    @JSThrows
    @ECMAImpl("[[PreventExtensions]]", "9.1.4")
    open fun preventExtensions(): Boolean {
        extensible = false
        return true
    }

    @JSThrows fun getOwnPropertyDescriptor(property: String) = getOwnPropertyDescriptor(property.key())
    @JSThrows fun getOwnPropertyDescriptor(property: JSSymbol) = getOwnPropertyDescriptor(property.key())
    @JSThrows fun getOwnPropertyDescriptor(property: Int) = getOwnPropertyDescriptor(property.key())

    @JSThrows
    open fun getOwnPropertyDescriptor(property: PropertyKey): Descriptor? {
        return internalGet(property)
    }

    @JSThrows fun getOwnProperty(property: String) = getOwnProperty(property.key())
    @JSThrows fun getOwnProperty(property: JSSymbol) = getOwnProperty(property.key())
    @JSThrows fun getOwnProperty(property: Int) = getOwnProperty(property.key())

    @JSThrows
    @ECMAImpl("[[GetOwnProperty]]", "9.1.5")
    open fun getOwnProperty(property: PropertyKey): JSValue {
        return internalGet(property)?.toObject(realm, this) ?: JSUndefined
    }

    @JSThrows fun defineOwnProperty(property: String, value: JSValue, attributes: Int = Descriptor.defaultAttributes) = defineOwnProperty(property.key(), Descriptor(value, attributes))
    @JSThrows fun defineOwnProperty(property: JSSymbol, value: JSValue, attributes: Int = Descriptor.defaultAttributes) = defineOwnProperty(property.key(), Descriptor(value, attributes))
    @JSThrows fun defineOwnProperty(property: Int, value: JSValue, attributes: Int = Descriptor.defaultAttributes) = defineOwnProperty(property.key(), Descriptor(value, attributes))

    @JSThrows
    @ECMAImpl("[[DefineOwnProperty]]", "9.1.6")
    open fun defineOwnProperty(property: PropertyKey, descriptor: Descriptor): Boolean {
        return validateAndApplyPropertyDescriptor(property, descriptor)
    }

    @JSThrows
    private fun validateAndApplyPropertyDescriptor(property: PropertyKey, newDesc: Descriptor): Boolean {
        val extensible = isExtensible()
        checkError() ?: return false
        val currentDesc = getOwnPropertyDescriptor(property)

        if (currentDesc == null || currentDesc.getRawValue() == JSEmpty) {
            if (!extensible)
                return false
            internalSet(property, newDesc.copy())
            return true
        }

        // Doesn't play nice with a manually set undefined
//        if (newDesc.isEmpty)
//            return true

        if (currentDesc.run { hasConfigurable && !isConfigurable }) {
            if (newDesc.isConfigurable)
                return false
            if (newDesc.hasEnumerable && currentDesc.isEnumerable != newDesc.isEnumerable)
                return false
        }

        if (currentDesc.isDataDescriptor != newDesc.isDataDescriptor) {
            if (currentDesc.run { hasConfigurable && !isConfigurable })
                return false
            if (currentDesc.isDataDescriptor) {
                internalSet(property, Descriptor(
                    JSUndefined,
                    currentDesc.attributes and (WRITABLE or HAS_WRITABLE).inv(),
                    newDesc.getter,
                    newDesc.setter,
                ))
            } else {
                internalSet(property, Descriptor(
                    newDesc.getActualValue(this),
                    currentDesc.attributes and (WRITABLE or HAS_WRITABLE).inv(),
                    null,
                    null,
                ))
            }
        } else if (currentDesc.isDataDescriptor && newDesc.isDataDescriptor) {
            if (currentDesc.run { hasConfigurable && hasWritable && !isConfigurable && !isWritable }) {
                if (newDesc.isWritable)
                    return false
                if (!newDesc.getActualValue(this).sameValue(currentDesc.getActualValue(this)))
                    return false
            }
        } else if (currentDesc.run { hasConfigurable && !isConfigurable }) {
            val currentSetter = currentDesc.setter
            val newSetter = newDesc.setter
            if (newSetter != null && (currentSetter == null || !newSetter.sameValue(currentSetter)))
                return false
            val currentGetter = currentDesc.setter
            val newGetter = newDesc.setter
            if (newGetter != null && (currentGetter == null || !newGetter.sameValue(currentGetter)))
                return false
            return true
        }

        if (newDesc.isDataDescriptor) {
            // To distinguish undefined from a non-specified property
            currentDesc.setActualValue(this, newDesc.getActualValue(this))
        }

        currentDesc.getter = newDesc.getter
        currentDesc.setter = newDesc.setter

        if (newDesc.hasConfigurable)
            currentDesc.setConfigurable(newDesc.isConfigurable)
        if (newDesc.hasEnumerable)
            currentDesc.setEnumerable(newDesc.isEnumerable)
        if (newDesc.hasWritable)
            currentDesc.setWritable(newDesc.isWritable)

        internalSet(property, currentDesc)

        return true
    }

    @JSThrows fun get(property: String, receiver: JSValue = this) = get(property.key(), receiver)
    @JSThrows fun get(property: JSSymbol, receiver: JSValue = this) = get(property.key(), receiver)
    @JSThrows fun get(property: Int, receiver: JSValue = this) = get(property.key(), receiver)

    @JSThrows
    @JvmOverloads @ECMAImpl("[[Get]]", "9.1.8")
    open fun get(property: PropertyKey, receiver: JSValue = this): JSValue {
        val desc = getOwnPropertyDescriptor(property)
        if (desc == null) {
            val parent = getPrototype()
            checkError() ?: return INVALID_VALUE
            if (parent == JSNull)
                return JSUndefined
            return (parent as JSObject).get(property, receiver)
        }
        if (desc.isAccessorDescriptor)
            return desc.getter?.call(this, emptyList()) ?: JSUndefined
        return desc.getActualValue(receiver)
    }

    @JSThrows fun set(property: String, value: JSValue, receiver: JSValue = this) = set(property.key(), value, receiver)
    @JSThrows fun set(property: JSSymbol, value: JSValue, receiver: JSValue = this) = set(property.key(), value, receiver)
    @JSThrows fun set(property: Int, value: JSValue, receiver: JSValue = this) = set(property.key(), value, receiver)

    @JSThrows
    @JvmOverloads @ECMAImpl("[[Set]]", "9.1.9")
    open fun set(property: PropertyKey, value: JSValue, receiver: JSValue = this): Boolean {
        val ownDesc = getOwnPropertyDescriptor(property)
        return ordinarySetWithOwnDescriptor(property, value, receiver, ownDesc)
    }

    @JSThrows
    @ECMAImpl("OrdinarySetWithOwnDescriptor", "9.1.9.2")
    private fun ordinarySetWithOwnDescriptor(property: PropertyKey, value: JSValue, receiver: JSValue, ownDesc_: Descriptor?): Boolean {
        var ownDesc = ownDesc_
        if (ownDesc == null) {
            val parent = getPrototype()
            if (parent != JSNull)
                return (parent as JSObject).set(property, value, receiver)
            ownDesc = Descriptor(JSUndefined, Descriptor.defaultAttributes)
        }
        if (ownDesc.isDataDescriptor) {
            if (!ownDesc.isWritable)
                return false
            if (receiver !is JSObject)
                return false
            val existingDescriptor = receiver.getOwnPropertyDescriptor(property)
            if (existingDescriptor != null) {
                if (existingDescriptor.isAccessorDescriptor)
                    return false
                if (!existingDescriptor.isWritable)
                    return false
                val valueDesc = Descriptor(value, 0)
                return receiver.defineOwnProperty(property, valueDesc)
            }
            return receiver.defineOwnProperty(property, Descriptor(value, Descriptor.defaultAttributes))
        }
        expect(ownDesc.isAccessorDescriptor)
        val setter = ownDesc.setter ?: return false
        Operations.call(setter, receiver, listOf(value))
        checkError() ?: return false
        return true
    }

    @JSThrows fun delete(property: String) = delete(property.key())
    @JSThrows fun delete(property: JSSymbol) = delete(property.key())
    @JSThrows fun delete(property: Int) = delete(property.key())

    @ECMAImpl("[[Delete]]", "9.1.10")
    open fun delete(property: PropertyKey): Boolean {
        val desc = getOwnPropertyDescriptor(property) ?: return true
        if (desc.isConfigurable)
            return internalDelete(property)
        return false
    }

    @JSThrows
    @ECMAImpl("[[OwnPropertyKeys]]", "9.1.11")
    open fun ownPropertyKeys(): List<PropertyKey> {
        // TODO: Ordering is wrong here
        return indexedProperties.indices().map(::PropertyKey) + storage.keys.map {
            if (it.isString) PropertyKey(it.asString) else PropertyKey(it.asSymbol)
        }
    }

    fun defineNativeAccessor(key: PropertyKey, attributes: Int, getter: JSFunction?, setter: JSFunction?) {
        val value = JSAccessor(getter, setter)
        internalSet(key, Descriptor(value, attributes))
    }

    fun defineNativeProperty(key: PropertyKey, attributes: Int, getter: NativeGetterSignature?, setter: NativeSetterSignature?) {
        val value = JSNativeProperty(getter, setter)
        internalSet(key, Descriptor(value, attributes))
    }

    fun defineNativeFunction(key: PropertyKey, length: Int, attributes: Int, function: NativeFunctionSignature) {
        val name = if (key.isString) key.asString else "[${key.asSymbol.descriptiveString()}]"
        val obj = JSNativeFunction.fromLambda(realm, name, length, function)
        internalSet(key, Descriptor(obj, attributes))
    }

    @JSThrows
    protected fun internalGet(property: PropertyKey): Descriptor? {
        val stringOrSymbol = when {
            property.isInt -> {
                if (property.asInt >= 0)
                    return indexedProperties.getDescriptor(property.asInt)
                StringOrSymbol(property.asInt.toString())
            }
            property.isDouble -> StringOrSymbol(property.asDouble.toString())
            property.isSymbol -> StringOrSymbol(property.asSymbol)
            else -> StringOrSymbol(property.asString)
        }

        return storage[stringOrSymbol]
    }

    protected fun internalSet(property: PropertyKey, descriptor: Descriptor) {
        val stringOrSymbol = when {
            property.isString -> {
                property.asString.toIntOrNull()?.let {
                    if (it >= 0) {
                        indexedProperties.set(this, it, descriptor.getActualValue(this), descriptor.attributes)
                        return
                    }
                }
                StringOrSymbol(property.asString)
            }
            property.isInt -> {
                if (property.asInt >= 0) {
                    indexedProperties.set(this, property.asInt, descriptor.getActualValue(this), descriptor.attributes)
                    return
                }
                StringOrSymbol(property.asInt.toString())
            }
            property.isDouble -> StringOrSymbol(property.asDouble.toString())
            property.isSymbol -> StringOrSymbol(property.asSymbol)
            else -> unreachable()
        }

        storage[stringOrSymbol] = descriptor
    }

    private fun internalDelete(property: PropertyKey): Boolean {
        val stringOrSymbol = when {
            property.isInt -> {
                if (property.asInt >= 0)
                    return indexedProperties.remove(property.asInt)
                StringOrSymbol(property.asInt.toString())
            }
            property.isDouble -> StringOrSymbol(property.asDouble.toString())
            property.isSymbol -> StringOrSymbol(property.asSymbol)
            else -> StringOrSymbol(property.asString)
        }

        storage.remove(stringOrSymbol)
        return true
    }

    enum class PropertyKind {
        Key,
        Value,
        KeyValue
    }

    data class StringOrSymbol private constructor(private val value: Any) {
        val isString = value is String
        val isSymbol = value is JSSymbol

        val asString by lazy { value as String }
        val asSymbol by lazy { value as JSSymbol }

        val asValue by lazy {
            if (isString) JSString(asString) else asSymbol
        }

        constructor(value: String) : this(value as Any)
        constructor(value: JSString) : this(value.string)
        constructor(value: JSSymbol) : this(value as Any)

        constructor(key: PropertyKey) : this(when {
            key.isInt -> key.asInt.toString()
            key.isDouble -> key.asDouble.toString()
            key.isString -> key.asString
            else -> key.asSymbol
        })

        override fun toString(): String {
            if (isString)
                return asString
            return asSymbol.toString()
        }

        companion object {
            val INVALID_KEY = StringOrSymbol(0)
        }
    }

    companion object {
        val INVALID_OBJECT by lazy { JSObject(Agent.runningContext.realm) }

        @JvmStatic
        @JvmOverloads
        fun create(realm: Realm, proto: JSObject = realm.objectProto) = JSObject(realm, proto).also { it.init() }

        protected fun thisBinding(context: ExecutionContext): JSValue {
            val env = context.lexicalEnv ?: shouldThrowError()
            if (!env.hasThisBinding())
                shouldThrowError()
            if (env is FunctionEnvRecord)
                return env.getThisBinding()
            if (env is GlobalEnvRecord)
                return env.getThisBinding()
            unreachable()
        }
    }
}

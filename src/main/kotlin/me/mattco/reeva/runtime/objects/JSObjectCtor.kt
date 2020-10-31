package me.mattco.reeva.runtime.objects

import me.mattco.reeva.core.Agent
import me.mattco.reeva.runtime.Operations
import me.mattco.reeva.core.Realm
import me.mattco.reeva.runtime.annotations.ECMAImpl
import me.mattco.reeva.runtime.annotations.JSMethod
import me.mattco.reeva.runtime.annotations.JSThrows
import me.mattco.reeva.runtime.JSValue
import me.mattco.reeva.runtime.functions.JSNativeFunction
import me.mattco.reeva.runtime.primitives.JSFalse
import me.mattco.reeva.runtime.primitives.JSNull
import me.mattco.reeva.runtime.primitives.JSTrue
import me.mattco.reeva.runtime.primitives.JSUndefined
import me.mattco.reeva.utils.*

class JSObjectCtor private constructor(realm: Realm) : JSNativeFunction(realm, "ObjectConstructor", 1) {
    init {
        isConstructable = true
    }

    @JSThrows
    override fun call(thisValue: JSValue, arguments: JSArguments): JSValue {
        return construct(arguments, JSUndefined)
    }

    override fun construct(arguments: JSArguments, newTarget: JSValue): JSValue {
        val value = arguments.argument(0)
        if (newTarget != JSUndefined && newTarget != Agent.runningContext.function)
            return Operations.ordinaryCreateFromConstructor(newTarget, realm.objectProto)
        if (value.isUndefined || value.isNull)
            return JSObject.create(realm)
        return Operations.toObject(value)
    }

    @JSMethod("assign", 2, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun assign(thisValue: JSValue, arguments: JSArguments): JSValue {
        val target = Operations.toObject(arguments.argument(0))
        if (arguments.size == 1)
            return target
        arguments.subList(1, arguments.size).forEach {
            if (it == JSUndefined || it == JSNull)
                return@forEach
            val from = Operations.toObject(it)
            from.ownPropertyKeys().forEach { key ->
                val desc = from.getOwnPropertyDescriptor(key)
                if (desc != null && desc.isEnumerable) {
                    val value = from.get(key)
                    if (!target.set(key, value))
                        throwTypeError("TODO: message")
                }
            }
        }

        return target
    }

    @JSMethod("create", 2, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun create(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject && obj != JSNull)
            throwTypeError("TODO: message")
        val newObj = create(Agent.runningContext.realm, obj)
        if (arguments.size > 1) {
            objectDefineProperties(newObj, arguments.argument(1))
        }
        return newObj
    }

    @JSMethod("defineProperties", 2, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun defineProperties(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            throwTypeError("Object.defineProperties expects an object as its first argument")
        return objectDefineProperties(obj, arguments.argument(1))
    }

    @JSMethod("defineProperty", 3, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun defineProperty(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            throwTypeError("Object.defineProperty expects an object as its first argument")
        val key = Operations.toPropertyKey(arguments.argument(1))
        val desc = Descriptor.fromObject(arguments.argument(2))
        Operations.definePropertyOrThrow(obj, key.asValue, desc)
        return obj
    }

    @JSMethod("entries", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun entries(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = Operations.toObject(arguments.argument(0))
        val names = Operations.enumerableOwnPropertyNames(obj, PropertyKind.KeyValue)
        return Operations.createArrayFromList(names)
    }

    @JSMethod("freeze", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun freeze(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            return obj
        if (!Operations.setIntegrityLevel(obj, Operations.IntegrityLevel.Frozen))
            throwTypeError("TODO: message")
        return obj
    }

    @JSMethod("fromEntries", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun fromEntries(thisValue: JSValue, arguments: JSArguments): JSValue {
        val iterable = arguments.argument(0)
        Operations.requireObjectCoercible(iterable)
        val obj = JSObject.create(realm)
        val adder = fromLambda(realm, "", 0) { thisValue, arguments ->
            val key = arguments.argument(0)
            val value = arguments.argument(1)
            ecmaAssert(thisValue is JSObject)
            Operations.createDataPropertyOrThrow(thisValue, key, value)
            return@fromLambda JSUndefined
        }
        return Operations.addEntriesFromIterable(obj, iterable, adder)
    }

    @JSMethod("getOwnPropertyDescriptor", 2, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun getOwnPropertyDescriptor(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = Operations.toObject(arguments.argument(0))
        val key = Operations.toPropertyKey(arguments.argument(1))
        val desc = obj.getOwnPropertyDescriptor(key) ?: return JSUndefined
        return desc.toObject(realm, JSUndefined)
    }

    @JSMethod("getOwnPropertyDescriptors", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun getOwnPropertyDescriptors(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = Operations.toObject(arguments.argument(0))
        val descriptors = JSObject.create(realm)
        obj.ownPropertyKeys().forEach { key ->
            val desc = obj.getOwnPropertyDescriptor(key)!!
            val descObj = desc.toObject(realm, JSUndefined)
            Operations.createDataPropertyOrThrow(descriptors, key, descObj)
        }
        return descriptors
    }

    @JSMethod("getOwnPropertyNames", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun getOwnPropertyNames(thisValue: JSValue, arguments: JSArguments): JSValue {
        return getOwnPropertyKeys(arguments.argument(0), false)
    }

    @JSMethod("getOwnPropertySymbols", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun getOwnPropertySymbols(thisValue: JSValue, arguments: JSArguments): JSValue {
        return getOwnPropertyKeys(arguments.argument(0), true)
    }

    @JSMethod("getPrototypeOf", 0, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun getPrototypeOf(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = Operations.toObject(arguments.argument(0))
        return obj.getPrototype()
    }

    @JSMethod("is", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun `is`(thisValue: JSValue, arguments: JSArguments): JSValue {
        return arguments.argument(0).sameValue(arguments.argument(1)).toValue()
    }

    @JSMethod("isExtensible", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun isExtensible(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            return JSFalse
        return obj.isExtensible().toValue()
    }

    @JSMethod("isFrozen", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun isFrozen(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            return JSTrue
        return obj.isFrozen.toValue()
    }

    @JSMethod("isSealed", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun isSealed(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            return JSTrue
        return obj.isSealed.toValue()
    }

    @JSMethod("keys", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun keys(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = Operations.toObject(arguments.argument(0))
        val nameList = Operations.enumerableOwnPropertyNames(obj, JSObject.PropertyKind.Key)
        return Operations.createArrayFromList(nameList)
    }

    @JSMethod("preventExtensions", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun preventExtensions(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            return obj
        if (!obj.preventExtensions())
            throwTypeError("TODO: message")
        return obj
    }

    @JSMethod("seal", 1, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun seal(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        if (obj !is JSObject)
            return obj
        if (!Operations.setIntegrityLevel(obj, Operations.IntegrityLevel.Sealed))
            throwTypeError("TODO: message")
        return obj
    }

    @JSMethod("setPrototypeOf", 2, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun setPrototypeOf(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = arguments.argument(0)
        Operations.requireObjectCoercible(obj)
        val proto = arguments.argument(1)
        if (proto !is JSObject && proto !is JSNull)
            throwTypeError("the second argument to Object.setPrototypeOf must be an object or null")
        if (obj !is JSObject)
            return obj
        if (!obj.setPrototype(proto))
            throwTypeError("unable to set prototype of object")
        return obj
    }

    @JSMethod("values", 0, Descriptor.CONFIGURABLE or Descriptor.WRITABLE)
    fun values(thisValue: JSValue, arguments: JSArguments): JSValue {
        val obj = Operations.toObject(arguments.argument(0))
        val nameList = Operations.enumerableOwnPropertyNames(obj, JSObject.PropertyKind.Value)
        return Operations.createArrayFromList(nameList)
    }

    private fun getOwnPropertyKeys(target: JSValue, isSymbols: Boolean): JSValue {
        val obj = Operations.toObject(target)
        val keyList = mutableListOf<JSValue>()
        obj.ownPropertyKeys().forEach { key ->
            if (key.isSymbol xor !isSymbols)
                return@forEach
            keyList.add(key.asValue)
        }
        return Operations.createArrayFromList(keyList)
    }

    @ECMAImpl("19.1.2.3.1")
    private fun objectDefineProperties(target: JSObject, properties: JSValue): JSObject {
        val props = Operations.toObject(properties)
        val descriptors = mutableListOf<Pair<PropertyKey, Descriptor>>()
        props.ownPropertyKeys().forEach { key ->
            val propDesc = props.getOwnPropertyDescriptor(key)!!
            if (propDesc.getRawValue() != JSUndefined && propDesc.isEnumerable) {
                val descObj = props.get(key)
                descriptors.add(key to Descriptor.fromObject(descObj))
            }
        }
        descriptors.forEach { (key, descriptor) ->
            Operations.definePropertyOrThrow(target, key.asValue, descriptor)
        }
        return target
    }

    companion object {
        // Special object: do not init
        fun create(realm: Realm) = JSObjectCtor(realm)
    }
}
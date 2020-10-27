package me.mattco.reeva.runtime.values.errors

import me.mattco.reeva.runtime.Realm
import me.mattco.reeva.runtime.values.objects.JSObject

class JSEvalErrorObject private constructor(realm: Realm, message: String? = null) : JSErrorObject(realm, message, realm.evalErrorProto) {
    companion object {
        @JvmStatic
        fun create(realm: Realm, message: String? = null) = JSEvalErrorObject(realm, message).also { it.init() }
    }
}

class JSEvalErrorProto private constructor(realm: Realm) : JSObject(realm, realm.errorProto) {
    override fun init() {
        super.init()
        defineOwnProperty("constructor", realm.evalErrorCtor)
    }

    companion object {
        fun create(realm: Realm) = JSEvalErrorProto(realm).also { it.init() }
    }
}

class JSEvalErrorCtor private constructor(realm: Realm) : JSErrorCtor(realm, "EvalError") {
    override fun constructErrorObj(): JSErrorObject {
        return JSEvalErrorObject.create(realm)
    }

    companion object {
        fun create(realm: Realm) = JSEvalErrorCtor(realm).also { it.init() }
    }
}

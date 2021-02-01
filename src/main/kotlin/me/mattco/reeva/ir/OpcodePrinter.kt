package me.mattco.reeva.ir

import me.mattco.reeva.utils.unreachable

object OpcodePrinter {
    fun printFunctionInfo(info: FunctionInfo) {
        val name = if (info.isTopLevelScript) {
            "top-level script"
        } else "function \"${info.name}\""
        println("FunctionInfo for $name")

        println("Parameter count: ${info.argCount}")
        println("Register count: ${info.registerCount}")

        println("Bytecode:")
        info.code.forEach {
            println("    " + stringifyOpcode(it, info.argCount))
        }

        println("Constant pool (size = ${info.constantPool.size})")
        info.constantPool.forEachIndexed { index, value ->
            print("    $index: ")

            when (value) {
                is Int -> "Int $value"
                is Double -> "Double $value"
                is String -> "String \"$value\""
                else -> unreachable()
            }.also(::println)
        }
    }

    fun stringifyOpcode(opcode: Opcode, argCount: Int): String {
        return buildString {
            append(opcode::class.simpleName)
            opcode::class.java.declaredFields.filter {
                it.name != "INSTANCE"
            }.forEach {
                it.isAccessible = true
                append(' ')
                append(formatArgument(it.get(opcode) as Int, it.name, argCount))
            }
        }
    }

    private fun formatArgument(value: Int, fieldName: String, argCount: Int) = fieldName.toLowerCase().let {
        when {
            "cp" in it -> "[$value]"
            "reg" in it -> if (value < argCount) {
                "a${argCount - value - 1}"
            } else "r${value - argCount}"
            else -> "#$value"
        }
    }
}

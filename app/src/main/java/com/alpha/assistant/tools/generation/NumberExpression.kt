package com.alpha.assistant.tools.generation

import com.alpha.assistant.tools.ToolValueSchema
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

internal class NumberExpression private constructor(
    val json: JSONObject,
    val references: Set<String>,
    private val evaluate: ((String) -> Double?) -> Double?,
) {
    fun value(read: (String) -> Double?): Double? = evaluate(read)?.takeIf {
        it.isFinite() && abs(it) <= ToolValueSchema.MAX_SAFE_NUMBER
    }

    companion object {
        private enum class Operator(val wire: String, val arity: Int) {
            ADD("add", 2), SUBTRACT("subtract", 2), MULTIPLY("multiply", 2), DIVIDE("divide", 2),
            MIN("min", 2), MAX("max", 2), POWER("power", 2), ROOT("sqrt", 1), ABS("abs", 1),
            FLOOR("floor", 1), CEIL("ceil", 1), ROUND("round", 1), LESS("less", 2),
            GREATER("greater", 2), EQUAL("equal", 2), IF("if", 3);

            fun apply(a: Double, b: Double): Double = when (this) {
                ADD -> a + b
                SUBTRACT -> a - b
                MULTIPLY -> a * b
                DIVIDE -> a / b
                MIN -> minOf(a, b)
                MAX -> maxOf(a, b)
                POWER -> a.pow(b)
                ROOT -> sqrt(a)
                ABS -> abs(a)
                FLOOR -> floor(a)
                CEIL -> ceil(a)
                ROUND -> round(a)
                LESS -> if (a < b) 1.0 else 0.0
                GREATER -> if (a > b) 1.0 else 0.0
                EQUAL -> if (a == b) 1.0 else 0.0
                IF -> error("Conditional evaluation is lazy.")
            }
        }

        fun parse(value: Any?, path: String): NumberExpression {
            var count = 0
            fun read(raw: Any?, at: String, depth: Int): NumberExpression {
                requireParts(++count <= 64 && depth <= 8, at, "at most 64 expression nodes and 8 levels")
                val node = raw as? JSONObject ?: throw PartsContractFailure(at, "a numeric expression object")
                return when {
                    node.has("aggregate") -> {
                        val aggregate = node.opt("aggregate")
                        requireParts(aggregate in setOf("count", "sum"), at, "count or sum over declared records")
                        node.requireKeys(at, setOf("aggregate", "need") + if (aggregate == "sum") setOf("field") else emptySet())
                        val need = planText(node.opt("need"), "$at.need", 24)
                        requireParts(ToolValueSchema.name(need), "$at.need", "a declared records need")
                        val field = if (aggregate == "sum") planText(node.opt("field"), "$at.field", 24).also {
                            requireParts(ToolValueSchema.name(it), "$at.field", "a declared numeric record field")
                        } else null
                        val reference = "$need:$aggregate" + field?.let { ":$it" }.orEmpty()
                        NumberExpression(node, setOf(reference)) { lookup -> lookup(reference) }
                    }
                    node.has("number") -> {
                        node.requireKeys(at, setOf("number"))
                        val number = planNumber(node.opt("number"), "$at.number")
                        NumberExpression(node, emptySet()) { number }
                    }
                    node.has("ref") -> {
                        node.requireKeys(at, setOf("ref"))
                        val id = planText(node.opt("ref"), "$at.ref", 24)
                        requireParts(ToolValueSchema.name(id), "$at.ref", "a declared value identifier")
                        NumberExpression(node, setOf(id)) { lookup -> lookup(id) }
                    }
                    else -> {
                        node.requireKeys(at, setOf("op", "args"))
                        val op = Operator.entries.firstOrNull { it.wire == node.opt("op") }
                            ?: throw PartsContractFailure("$at.op", "a supported numeric operation")
                        val args = node.items("args", op.arity..op.arity, "$at.args").mapIndexed { index, arg ->
                            read(arg, "$at.args[$index]", depth + 1)
                        }
                        NumberExpression(node, args.flatMap { it.references }.toSet()) { lookup ->
                            val a = args[0].value(lookup)
                            when {
                                a == null -> null
                                op == Operator.IF -> args[if (a != 0.0) 1 else 2].value(lookup)
                                op.arity == 1 -> op.apply(a, 0.0)
                                else -> args[1].value(lookup)?.let { b -> op.apply(a, b) }
                            }
                        }
                    }
                }
            }
            return read(value, path, 1)
        }
    }
}

/** Dependencies outside this map have already been validated as inputs. */
internal fun dependencyOrder(dependencies: Map<String, Set<String>>, path: String): List<String> {
    val visiting = mutableSetOf<String>()
    val ordered = linkedSetOf<String>()
    fun visit(id: String) {
        if (id in ordered || id !in dependencies) return
        requireParts(visiting.add(id), path, "acyclic value dependencies")
        dependencies.getValue(id).forEach(::visit)
        visiting.remove(id)
        ordered.add(id)
    }
    dependencies.keys.forEach(::visit)
    return ordered.toList()
}

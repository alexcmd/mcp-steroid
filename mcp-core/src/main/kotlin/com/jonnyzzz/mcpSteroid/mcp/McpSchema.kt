package com.jonnyzzz.mcpSteroid.mcp

import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class InputSchemaParamSpec(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean,
)

data class InputSchemaElement<R>(
    val spec: InputSchemaParamSpec,
    val parser: InputSchemaParamParser<R>
) {
    companion object
}

interface InputSchemaParamParser<R> {
    @Throws(ToolCallErrorException::class)
    fun parseParameter(context: ToolCallContext): R
}

fun InputSchemaElement.Companion.param(name: String) = InputSchemaElement(
    spec = InputSchemaParamSpec(name = name, description = "Not Set", type = "Error", required = false),
    parser = object : InputSchemaParamParser<Nothing> {
        override fun parseParameter(context: ToolCallContext): Nothing {
            throw ToolCallErrorException("Not implemented for $name")
        }
    }
)

fun <R> InputSchemaElement<R>.description(description: String) = InputSchemaElement<R>(
    spec = spec.copy(description = description),
    parser
)

fun InputSchemaElement<Nothing>.boolean() = InputSchemaElement(
    spec = spec.copy(type = "boolean"),
    parser = object : InputSchemaParamParser<Boolean?> {
        override fun parseParameter(context: ToolCallContext): Boolean? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.booleanOrNull
        }
    }
)

fun InputSchemaElement<Nothing>.string() = InputSchemaElement(
    spec = spec.copy(type = "string"),
    parser = object : InputSchemaParamParser<String?> {
        override fun parseParameter(context: ToolCallContext): String? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.contentOrNull
        }
    }
)

fun InputSchemaElement<Nothing>.int() = InputSchemaElement(
    spec = spec.copy(type = "integer"),
    parser = object : InputSchemaParamParser<Int?> {
        override fun parseParameter(context: ToolCallContext): Int? {
            return context.params.arguments[spec.name]?.jsonPrimitive?.intOrNull
        }
    }
)

fun <R : Any> InputSchemaElement<R?>.required(): InputSchemaElement<R> {
    val that = this
    return InputSchemaElement(
        this.spec.copy(required = true),
        object : InputSchemaParamParser<R> {
            override fun parseParameter(context: ToolCallContext): R {
                return that.parser.parseParameter(context)
                    ?: throw ToolCallErrorException("Parameter ${spec.name} of type ${spec.type} is required")
            }
        }
    )
}

fun InputSchemaElement.Companion.buildSchema(vararg elements: InputSchemaElement<*>) = buildSchema(elements.toList())

fun InputSchemaElement.Companion.buildSchema(elements: List<InputSchemaElement<*>>) = buildJsonObject {
    put("type", "object")
    putJsonObject("properties") {
        elements.map { it.spec }.forEach { element ->
            putJsonObject(element.name) {
                put("type", element.type)
                put("description", element.description)

            }
        }
    }
    putJsonArray("required") {
        elements.map { it.spec }.filter { it.required }.forEach { element ->
            add(element.name)
        }
    }
}

@Throws(ToolCallErrorException::class)
operator fun <R> ToolCallContext.get(p: InputSchemaElement<R>) = p.parser.parseParameter(this)


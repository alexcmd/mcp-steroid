/* Copyright 2025-2026 Eugene Petrenko (mcp@jonnyzzz.com); Copyright 2025-2026 JetBrains. Use of this source code is governed by the Apache 2.0 license. */
package com.jonnyzzz.mcpSteroid.server

import com.jonnyzzz.mcpSteroid.mcp.McpTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Shared invariants for every `*ToolSpec.inputSchema` exposed by the registry.
 *
 * The schema is hand-written `buildJsonObject { … }` in each Spec — there is
 * no compile-time check that the result is a well-formed JSON Schema. These
 * assertions cover the minimum every MCP tool client (Claude Code, Codex,
 * Gemini, devrig) relies on:
 *
 *  1. Serialises to JSON and parses back without loss.
 *  2. Top-level `"type"` is the string `"object"`.
 *  3. `"properties"` is a JsonObject (may be empty).
 *  4. If `"required"` is present, it is a JsonArray of strings and every
 *     name listed appears as a key in `"properties"`.
 *
 * Use from a per-Spec test:
 *
 * ```
 * @Test fun `inputSchema is valid JSON Schema`() {
 *     assertToolSpecHasValidJsonSchema(ListProjectsToolSpec { unreachableHandler() })
 * }
 * ```
 */
fun assertToolSpecHasValidJsonSchema(tool: McpTool) {
    val schema: JsonObject = tool.inputSchema

    // 1. JSON round-trip.
    val rendered = Json.encodeToString(JsonObject.serializer(), schema)
    val reparsed = Json.parseToJsonElement(rendered)
    assertEquals(schema, reparsed, "${tool.name}: inputSchema does not round-trip through JSON")

    // 2. type=object.
    val type = schema["type"]
    assertNotNull(type, "${tool.name}: inputSchema missing `type` field")
    val typePrimitive = type as? JsonPrimitive
        ?: error("${tool.name}: inputSchema.type must be a JSON primitive; was $type")
    assertTrue(typePrimitive.isString, "${tool.name}: inputSchema.type must be a string")
    assertEquals("object", typePrimitive.content, "${tool.name}: inputSchema.type must be \"object\"")

    // 3. properties is an object.
    val properties = schema["properties"]
    assertNotNull(properties, "${tool.name}: inputSchema missing `properties` field")
    val propertiesObject = properties as? JsonObject
        ?: error("${tool.name}: inputSchema.properties must be a JSON object; was $properties")

    // 4. required is an array of strings; every entry is a known property.
    val required = schema["required"] ?: return
    val requiredArray = required as? JsonArray
        ?: error("${tool.name}: inputSchema.required must be a JSON array; was $required")
    requiredArray.forEachIndexed { index, element ->
        val asPrimitive = element as? JsonPrimitive
            ?: error("${tool.name}: inputSchema.required[$index] must be a JSON primitive; was $element")
        assertTrue(asPrimitive.isString, "${tool.name}: inputSchema.required[$index] must be a string")
        val name = asPrimitive.contentOrNull
            ?: error("${tool.name}: inputSchema.required[$index] is a non-content primitive")
        assertTrue(
            propertiesObject.containsKey(name),
            "${tool.name}: inputSchema.required[$index]=\"$name\" is not present in inputSchema.properties",
        )
    }

    // Every entry in properties must itself be a JsonObject — clients depend
    // on `properties.<name>.type` to render forms / validate args.
    propertiesObject.entries.forEach { (propName, propValue) ->
        propValue as? JsonObject
            ?: error("${tool.name}: inputSchema.properties.\"$propName\" must be a JSON object; was $propValue")
    }
}

/** Convenience for `*ToolSpec` constructor lambdas that must never be invoked. */
fun unreachableHandler(): Nothing =
    error("handler factory must not be invoked from an inputSchema test")

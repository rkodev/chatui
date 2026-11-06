package dev.chatui.feature.conversations.export

import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Small JSON Schema validator for the subset used by
 * `contracts/schema/chat-export.schema.json`.
 *
 * It intentionally lives in test sources: production code validates the
 * archive shape with kotlinx.serialization, while this validator proves the
 * generated JSON matches the checked-in contract.
 */
object ExportSchemaValidator {
    fun validate(value: JsonElement, schema: JsonObject): List<String> =
        validate(value, schema, schema, "$")

    private fun validate(
        value: JsonElement,
        node: JsonObject,
        root: JsonObject,
        path: String,
    ): List<String> {
        val ref = node["\$ref"]?.jsonPrimitive?.contentOrNull
        if (ref != null) {
            return validate(value, resolveRef(ref, root), root, path)
        }

        val oneOf = node["oneOf"]?.jsonArray
        if (oneOf != null) {
            val matches = oneOf.count { branch ->
                validate(value, branch.jsonObject, root, path).isEmpty()
            }
            return if (matches == 1) {
                emptyList()
            } else {
                listOf("$path: expected exactly one oneOf branch, got $matches")
            }
        }

        val anyOf = node["anyOf"]?.jsonArray
        if (anyOf != null) {
            val matches = anyOf.any { branch ->
                validate(value, branch.jsonObject, root, path).isEmpty()
            }
            return if (matches) emptyList() else listOf("$path: no anyOf branch matched")
        }

        val const = node["const"]
        if (const != null && value != const) {
            return listOf("$path: expected const $const")
        }

        val enum = node["enum"]?.jsonArray
        if (enum != null && enum.none { it == value }) {
            return listOf("$path: not one of enum values")
        }

        val type = node["type"]
        if (type != null) {
            val types = if (type is JsonArray) {
                type.map { it.jsonPrimitive.content }
            } else {
                listOf(type.jsonPrimitive.content)
            }
            if (types.none { matchesType(value, it) }) {
                return listOf("$path: expected type ${types.joinToString("|")}")
            }
        }

        if (value is JsonNull) return emptyList()

        val errors = mutableListOf<String>()

        if (value is JsonPrimitive && value.isString) {
            val text = value.content
            val minLength = node["minLength"]?.jsonPrimitive?.intOrNull
            if (minLength != null && text.length < minLength) {
                errors += "$path: shorter than minLength"
            }
            val pattern = node["pattern"]?.jsonPrimitive?.contentOrNull
            if (pattern != null && !Regex(pattern).matches(text)) {
                errors += "$path: does not match pattern"
            }
            val format = node["format"]?.jsonPrimitive?.contentOrNull
            if (format == "date-time" && runCatching { Instant.parse(text) }.isFailure) {
                errors += "$path: invalid date-time"
            }
        }

        if (value is JsonPrimitive && !value.isString) {
            val number = value.doubleOrNull
            if (number != null) {
                val minimum = node["minimum"]?.jsonPrimitive?.doubleOrNull
                if (minimum != null && number < minimum) {
                    errors += "$path: below minimum"
                }
                val maximum = node["maximum"]?.jsonPrimitive?.doubleOrNull
                if (maximum != null && number > maximum) {
                    errors += "$path: above maximum"
                }
            }
        }

        if (value is JsonArray) {
            val items = node["items"]?.jsonObject
            if (items != null) {
                value.forEachIndexed { index, item ->
                    errors += validate(item, items, root, "$path/$index")
                }
            }
            if (node["uniqueItems"]?.jsonPrimitive?.booleanOrNull == true) {
                val encoded = value.map { it.toString() }
                if (encoded.size != encoded.toSet().size) {
                    errors += "$path: duplicate array items"
                }
            }
        }

        if (value is JsonObject) {
            val properties = node["properties"]?.jsonObject ?: JsonObject(emptyMap())
            val required = node["required"]?.jsonArray
                ?.map { it.jsonPrimitive.content }
                ?: emptyList()

            required.forEach { key ->
                if (key !in value) errors += "$path: missing required property $key"
            }

            val additional = node["additionalProperties"]
            value.forEach { (key, child) ->
                val property = properties[key]
                if (property != null) {
                    errors += validate(child, property.jsonObject, root, "$path/$key")
                } else if (additional is JsonPrimitive && additional.booleanOrNull == false) {
                    errors += "$path: unexpected property $key"
                } else if (additional is JsonObject) {
                    errors += validate(child, additional, root, "$path/$key")
                }
            }
        }

        return errors
    }

    private fun matchesType(value: JsonElement, type: String): Boolean = when (type) {
        "null" -> value is JsonNull
        "string" -> value is JsonPrimitive && value.isString
        "number" -> value is JsonPrimitive && !value.isString && value.doubleOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value.intOrNull != null
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        else -> true
    }

    private fun resolveRef(ref: String, root: JsonObject): JsonObject {
        require(ref.startsWith("#/")) { "Unsupported schema ref $ref" }
        var current: JsonElement = root
        ref.removePrefix("#/").split('/').forEach { segment ->
            current = current.jsonObject[segment]
                ?: error("Missing schema ref segment $segment")
        }
        return current.jsonObject
    }
}

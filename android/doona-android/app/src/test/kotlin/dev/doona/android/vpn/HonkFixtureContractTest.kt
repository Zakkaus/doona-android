package dev.doona.android.vpn

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import java.io.File

/**
 * Every fixture under `src/test/resources/honk/` is a body honk's native API can send: it validates against the response
 * schema of its operation in honk's OpenAPI document (`native_api_openapi.yaml`, copied from daeuniverse/honk#483).
 * [FIXTURES] names the operation of each file; a file it does not name fails, so a new fixture comes with its operation.
 */
class HonkFixtureContractTest {
    private val spec: Map<*, *> = checkNotNull(javaClass.getResourceAsStream("/native_api_openapi.yaml")).use {
        Yaml(SafeConstructor(LoaderOptions().apply { codePointLimit = SPEC_LIMIT })).load<Map<*, *>>(it)
    }

    @Test
    fun everyFixtureMatchesHonksSchema() {
        val root = File(checkNotNull(javaClass.getResource("/honk")).toURI())
        val files = root.walk().filter { it.isFile }.map { it.relativeTo(root).path.removeSuffix(".json") }.sorted().toList()
        assertTrue("no fixtures under honk/", files.isNotEmpty())
        val problems = files.flatMap { name ->
            val target = FIXTURES.entries.filter { it.key.matches(name) }.map { it.value }
            if (target.size != 1) return@flatMap listOf("$name: named by ${target.size} entries of FIXTURES, not 1")
            val body = Json.parseToJsonElement(File(root, "$name.json").readText())
            Validator().apply { check(schemaOf(target.single()), body, name) }.problems
        }
        assertEquals("", problems.joinToString("\n"))
    }

    /** The validator rejects what the schema forbids: a missing field, a wrong type, a value outside an enum. */
    @Test
    fun theValidatorRejectsBodiesOutsideTheSchema() {
        val rules = schemaOf("GET /api/v1/rules 200")
        val body = Json.parseToJsonElement(HonkFixtures.body("rules/running")).let { it as JsonObject }
        assertEquals(emptyList<String>(), Validator().apply { check(rules, body, "ok") }.problems)
        val missing = JsonObject(body - "fallback")
        assertTrue(Validator().apply { check(rules, missing, "missing") }.problems.isNotEmpty())
        val wrongType = JsonObject(body + ("generation_id" to JsonPrimitive(1)))
        assertTrue(Validator().apply { check(rules, wrongType, "type") }.problems.isNotEmpty())
        val group = schemaOf("GET /api/v1/groups/{group_id} 200")
        val detail = Json.parseToJsonElement(HonkFixtures.body("groups/group-proxy")) as JsonObject
        val policy = JsonObject(mapOf("kind" to JsonPrimitive("fastest"), "native" to JsonPrimitive("urltest")))
        assertTrue(Validator().apply { check(group, JsonObject(detail + ("policy" to policy)), "enum") }.problems.isNotEmpty())
    }

    /** The JSON schema of `METHOD path status`, or of `#/components/schemas/Name`. */
    private fun schemaOf(target: String): Any {
        if (target.startsWith("#/")) return resolve(target)
        X_HONK[target]?.let { return Yaml(SafeConstructor(LoaderOptions())).load<Map<*, *>>(it) }
        val (method, path, status) = target.split(" ")
        val paths = spec["paths"] as Map<*, *>
        val operation = ((paths[path] as Map<*, *>)[method.lowercase()] as Map<*, *>)
        var response = (operation["responses"] as Map<*, *>)[status] as Map<*, *>
        (response["\$ref"] as String?)?.let { response = resolve(it) as Map<*, *> }
        return checkNotNull(((response["content"] as Map<*, *>)["application/json"] as Map<*, *>)["schema"]) { "no schema for $target" }
    }

    private fun resolve(ref: String): Any = ref.removePrefix("#/").split("/").fold<String, Any>(spec) { node, key -> (node as Map<*, *>)[key]!! }

    /**
     * JSON Schema 2020-12 as honk's document uses it: types, objects, arrays, strings, numbers, enum and const, and the
     * combinators. `format`, `description` and annotations are not checked.
     */
    private inner class Validator {
        val problems = mutableListOf<String>()

        fun check(schema: Any?, value: JsonElement, at: String) {
            valid(schema, value, at, record = true)
        }

        private fun valid(schema: Any?, value: JsonElement, at: String, record: Boolean): Boolean {
            if (schema == true || schema == null) return true
            if (schema == false) return fail(record, "$at: no value is allowed")
            val s = schema as Map<*, *>
            var ok = true
            fun require(condition: Boolean, problem: () -> String) {
                if (!condition) ok = fail(record, "$at: ${problem()}") && ok
            }
            (s["\$ref"] as String?)?.let { ok = valid(resolve(it), value, at, record) && ok }
            s["type"]?.let { type ->
                val types = if (type is List<*>) type.map { it as String } else listOf(type as String)
                require(types.any { isType(value, it) }) { "${kind(value)} is not $types" }
            }
            s["enum"]?.let { values -> require((values as List<*>).any { same(it, value) }) { "$value is not one of $values" } }
            if (s.containsKey("const")) require(same(s["const"], value)) { "$value is not ${s["const"]}" }
            (s["allOf"] as List<*>?)?.forEach { ok = valid(it, value, at, record) && ok }
            (s["anyOf"] as List<*>?)?.let { options -> require(options.any { valid(it, value, at, false) }) { "matches no anyOf" } }
            (s["oneOf"] as List<*>?)?.let { options ->
                val n = options.count { valid(it, value, at, false) }
                require(n == 1) { "matches $n oneOf options" }
            }
            s["not"]?.let { require(!valid(it, value, at, false)) { "matches its not" } }
            s["if"]?.let { condition ->
                val branch = if (valid(condition, value, at, false)) s["then"] else s["else"]
                if (branch != null) ok = valid(branch, value, at, record) && ok
            }
            when (value) {
                is JsonObject -> ok = obj(s, value, at, record) && ok
                is JsonArray -> ok = array(s, value, at, record) && ok
                is JsonPrimitive -> ok = primitive(s, value, at, record) && ok
            }
            return ok
        }

        private fun obj(s: Map<*, *>, value: JsonObject, at: String, record: Boolean): Boolean {
            var ok = true
            (s["required"] as List<*>?)?.forEach { if (it !in value) ok = fail(record, "$at: no $it") && ok }
            (s["minProperties"] as Int?)?.let { if (value.size < it) ok = fail(record, "$at: fewer than $it fields") && ok }
            val properties = s["properties"] as Map<*, *>? ?: emptyMap<Any, Any>()
            val patterns = (s["patternProperties"] as Map<*, *>?).orEmpty().map { (k, v) -> Regex(k as String) to v }
            for ((key, field) in value) {
                val matched = patterns.filter { it.first.containsMatchIn(key) }
                if (key in properties) ok = valid(properties[key], field, "$at.$key", record) && ok
                matched.forEach { ok = valid(it.second, field, "$at.$key", record) && ok }
                if (key !in properties && matched.isEmpty() && s.containsKey("additionalProperties")) {
                    ok = valid(s["additionalProperties"], field, "$at.$key", record) && ok
                }
            }
            return ok
        }

        private fun array(s: Map<*, *>, value: JsonArray, at: String, record: Boolean): Boolean {
            var ok = true
            s["items"]?.let { items -> value.forEachIndexed { i, item -> ok = valid(items, item, "$at[$i]", record) && ok } }
            (s["minItems"] as Int?)?.let { if (value.size < it) ok = fail(record, "$at: fewer than $it items") && ok }
            if (s["uniqueItems"] == true && value.toSet().size != value.size) ok = fail(record, "$at: repeated items") && ok
            s["contains"]?.let { c -> if (value.none { valid(c, it, at, false) }) ok = fail(record, "$at: contains none") && ok }
            return ok
        }

        private fun primitive(s: Map<*, *>, value: JsonPrimitive, at: String, record: Boolean): Boolean {
            var ok = true
            if (value.isString) {
                val text = value.content
                val length = text.codePointCount(0, text.length)
                (s["minLength"] as Int?)?.let { if (length < it) ok = fail(record, "$at: shorter than $it") && ok }
                (s["maxLength"] as Int?)?.let { if (length > it) ok = fail(record, "$at: longer than $it") && ok }
                (s["pattern"] as String?)?.let { if (!Regex(it).containsMatchIn(text)) ok = fail(record, "$at: '$text' does not match $it") && ok }
            } else {
                value.doubleOrNull?.let { n ->
                    (s["minimum"] as Number?)?.let { if (n < it.toDouble()) ok = fail(record, "$at: $n < $it") && ok }
                    (s["maximum"] as Number?)?.let { if (n > it.toDouble()) ok = fail(record, "$at: $n > $it") && ok }
                }
            }
            return ok
        }

        private fun fail(record: Boolean, problem: String): Boolean {
            if (record) problems += problem
            return false
        }
    }

    private fun isType(value: JsonElement, type: String): Boolean = when (type) {
        "null" -> value is JsonNull
        "object" -> value is JsonObject
        "array" -> value is JsonArray
        "string" -> value is JsonPrimitive && value.isString
        "boolean" -> value is JsonPrimitive && !value.isString && value.booleanOrNull != null
        "integer" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.longOrNull != null
        "number" -> value is JsonPrimitive && !value.isString && value !is JsonNull && value.doubleOrNull != null
        else -> error("type $type")
    }

    private fun kind(value: JsonElement) = when (value) {
        is JsonNull -> "null"
        is JsonObject -> "an object"
        is JsonArray -> "an array"
        is JsonPrimitive -> if (value.isString) "a string" else "the value ${value.content}"
    }

    /** A YAML value (from the schema) equal to a JSON one. */
    private fun same(expected: Any?, value: JsonElement): Boolean = when (expected) {
        null -> value is JsonNull
        is String -> value is JsonPrimitive && value.isString && value.content == expected
        is Boolean -> value is JsonPrimitive && !value.isString && value.booleanOrNull == expected
        is Number -> value is JsonPrimitive && !value.isString && value.doubleOrNull == expected.toDouble()
        is List<*> -> value is JsonArray && value.size == expected.size && expected.indices.all { same(expected[it], value[it]) }
        is Map<*, *> -> value is JsonObject && value.size == expected.size && expected.all { (k, v) -> same(v, value[k as String] ?: return false) }
        else -> false
    }

    private companion object {
        /** honk's document is about 480 KB; snakeyaml reads 3 MB by default but say so rather than rely on it. */
        const val SPEC_LIMIT = 8 * 1024 * 1024

        /** Each fixture (path under honk/, no `.json`) and the operation whose response it is. */
        val FIXTURES: Map<Regex, String> = mapOf(
            "errors/.*" to "#/components/schemas/ErrorResponse",
            "groups/(list|rv-sample|rv-sample-long|honk-groups)" to "GET /api/v1/groups 200",
            "groups/(group-proxy|(rv-sample(-long)?|honk-groups)-g-.+)" to "GET /api/v1/groups/{group_id} 200",
            "nodes/.*" to "GET /api/v1/nodes 200",
            "providers/.*" to "GET /api/v1/providers 200",
            "rules/.*" to "GET /api/v1/rules 200",
            "dns-rules/.*" to "GET /api/v1/dns/rules 200",
            "runtime/runtime-.*" to "GET /api/v1/runtime 200",
            "runtime/outbounds-.*" to "GET /api/v1/runtime/outbounds 200",
            "runtime/traffic-history-.*" to "GET /api/v1/runtime/traffic/history 200",
            "config/.*" to "GET /api/v1/config 200",
            "config-replace/.*" to "PUT /api/v1/config/sources/{source_id} 202",
            "operations/.*" to "GET /api/v1/operations/{operation_id} 200",
            "probes/.*" to "POST /api/v1/probes 202",
            "geodata/(loaded|packaged|rv-sample)" to "GET /api/v1/geodata 200",
            "geodata/update-queued" to "POST /api/v1/geodata/update 202",
            "geodata/update-in-flight" to "#/components/schemas/ErrorResponse",
            "revisions/.*" to "GET /api/v1/x-honk/config/revisions 200",
        ).mapKeys { Regex(it.key) }

        /**
         * honk's `x-honk` routes are not in its OpenAPI document; these schemas follow api.md (section "Configuration db")
         * and the handler (`native_api/config/revisions.rs`) of the same commit.
         */
        val X_HONK: Map<String, String> = mapOf(
            "GET /api/v1/x-honk/config/revisions 200" to """
                type: object
                required: [active, max_revisions, revisions]
                properties:
                  active: {type: [integer, 'null']}
                  max_revisions: {type: integer}
                  revisions:
                    type: array
                    items:
                      type: object
                      required: [revision, parent, created_at, principal, origin, content_sha256, bytes, sources]
                      properties:
                        revision: {type: integer, minimum: 1}
                        parent: {type: [integer, 'null']}
                        created_at: {type: string}
                        principal: {type: string}
                        origin: {enum: [import, write, activate]}
                        content_sha256: {type: string}
                        bytes: {type: integer}
                        sources:
                          type: array
                          items:
                            type: object
                            required: [path, sha256]
                            properties:
                              path: {type: string}
                              sha256: {type: string}
            """.trimIndent(),
        )
    }
}

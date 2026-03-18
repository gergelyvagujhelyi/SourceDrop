package dev.sourcedrop.app.sourceadapters

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun resolveJsonPath_simpleField() {
        val element = json.parseToJsonElement("""{"version": "1.2.3"}""")
        assertEquals("1.2.3", JsonAdapter.resolveJsonPath(element, "version"))
    }

    @Test
    fun resolveJsonPath_nestedField() {
        val element = json.parseToJsonElement("""{"data": {"latest": {"version": "2.0.0"}}}""")
        assertEquals("2.0.0", JsonAdapter.resolveJsonPath(element, "data.latest.version"))
    }

    @Test
    fun resolveJsonPath_arrayIndex() {
        val element = json.parseToJsonElement("""[{"version": "1.0"}, {"version": "2.0"}]""")
        assertEquals("2.0", JsonAdapter.resolveJsonPath(element, "1.version"))
    }

    @Test
    fun resolveJsonPath_missingField() {
        val element = json.parseToJsonElement("""{"version": "1.0"}""")
        assertNull(JsonAdapter.resolveJsonPath(element, "missing"))
    }

    @Test
    fun resolveJsonPath_numericValue() {
        val element = json.parseToJsonElement("""{"build": 42}""")
        assertEquals("42", JsonAdapter.resolveJsonPath(element, "build"))
    }

    @Test
    fun resolveJsonPath_deeplyNested() {
        val element = json.parseToJsonElement("""
            {"a": {"b": {"c": {"d": "found"}}}}
        """)
        assertEquals("found", JsonAdapter.resolveJsonPath(element, "a.b.c.d"))
    }

    @Test
    fun resolveJsonPath_missingIntermediateField() {
        val element = json.parseToJsonElement("""{"a": {"b": "value"}}""")
        assertNull(JsonAdapter.resolveJsonPath(element, "a.missing.c"))
    }
}

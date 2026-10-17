package com.shilapi.xcertplay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class JapaneseTranslationsTest {
    private val placeholder = Regex("%(?:\\d+\\$)?[dsf]")

    @Test fun everyEnglishStringHasAJapaneseTranslationWithMatchingArguments() {
        // Like Android lint, strings the default locale marks translatable="false" need no translation.
        val fixed = nonTranslatable()
        val english = load("values") - fixed
        val japanese = load("values-ja") - fixed
        assertEquals("Japanese resource keys", english.keys, japanese.keys)
        for ((name, source) in english) {
            assertEquals(name, placeholders(source), placeholders(japanese.getValue(name)))
        }
    }

    private fun placeholders(value: String) = placeholder.findAll(value).map { it.value }.toList().sorted()

    private fun nonTranslatable(): Set<String> = File("src/main/res/values")
        .listFiles { file -> file.extension == "xml" }.orEmpty()
        .flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            (0 until nodes.length).map { nodes.item(it).attributes }
                .filter { it.getNamedItem("translatable")?.nodeValue == "false" }
                .map { it.getNamedItem("name").nodeValue }
        }.toSet()

    private fun load(locale: String): Map<String, String> {
        // Strings are spread across several files, not just strings.xml.
        val files = File("src/main/res/$locale")
            .listFiles { file -> file.extension == "xml" }
            ?.sortedBy { it.name }
            ?: error("Missing locale resources: $locale")
        val result = mutableMapOf<String, String>()
        for (file in files) {
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = document.getElementsByTagName("string")
            for (index in 0 until nodes.length) {
                val node = nodes.item(index)
                val key = node.attributes.getNamedItem("name").nodeValue
                check(result.put(key, node.textContent) == null) { "Duplicate translation: $locale/$key" }
            }
        }
        return result
    }
}

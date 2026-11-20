package dev.keycorrect.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Guards against a [KeyboardSettings] field that is forgotten in [SettingsRepository] (not read,
 * not written, or missing from the typed key sets that export and import use). Fields are found by
 * reflection, so a new constructor property is covered without touching this test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsCompletenessTest {
    @get:Rule val tmp = TemporaryFolder()

    private var storeCount = 0

    private fun newRepo(scope: CoroutineScope): SettingsRepository {
        val file = File(tmp.root, "s${storeCount++}.preferences_pb")
        return SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file })
    }

    private val constructor: Constructor<*> =
        KeyboardSettings::class
            .java
            .declaredConstructors
            .filter { c -> c.parameterTypes.none { it.name.endsWith("DefaultConstructorMarker") } }
            .maxByOrNull { it.parameterCount }!!

    /** The constructor properties' backing fields, in constructor order. */
    private val fields: List<Field> =
        KeyboardSettings::class
            .java
            .declaredFields
            .filter { !Modifier.isStatic(it.modifiers) }
            .take(constructor.parameterCount)
            .onEach { it.isAccessible = true }

    private val defaults = KeyboardSettings()

    private val defaultArgs: List<Any?> = fields.map { it.get(defaults) }

    private fun build(args: List<Any?>): KeyboardSettings =
        constructor.newInstance(*args.toTypedArray()) as KeyboardSettings

    /** Values that differ from [default] for one field; the first that survives sanitizing wins. */
    private fun candidates(default: Any?): List<Any?> =
        when (default) {
            is Boolean -> listOf(!default)
            is Int -> listOf(1, -1, 2, 5, 10, -10, 100, 1000).map { default + it }
            is Float -> listOf(0.1f, -0.1f, 0.25f, -0.25f, 0.5f).map { default + it }
            is Enum<*> -> default.javaClass.enumConstants.filter { it != default }
            is String -> listOf("test_value", "test|value")
            is Set<*> -> listOf(setOf("test.engine|abc123", "phone_portrait|123456"))
            is List<*> -> listOf(List(ThemeColors.COUNT) { 0xFF000000.toInt() or (it * 0x111111) })
            is Map<*, *> ->
                listOf(
                    SizeProfile.ALL.withIndex().associate { (i, p) ->
                        p to
                            SizeSettings(
                                60 + i,
                                0.6f + i * 0.05f,
                                0.8f + i * 0.05f,
                                i,
                                1 + i,
                                i % 2 == 0,
                            )
                    }
                )
            else -> error("no candidate values for ${default?.javaClass}")
        }

    private fun changed(index: Int): KeyboardSettings {
        for (candidate in candidates(defaultArgs[index])) {
            val s = build(defaultArgs.toMutableList().also { it[index] = candidate })
            if (s != defaults && s.sanitized() == s) return s
        }
        error("no valid non-default value found for ${fields[index].name}")
    }

    @Test
    fun everyFieldIsStoredAndReadBack() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            for (i in fields.indices) {
                val repo = newRepo(scope)
                val s = changed(i)
                repo.update { s }
                assertWithMessage("${fields[i].name} was not round-tripped by SettingsRepository")
                    .that(repo.settings.first())
                    .isEqualTo(s)
            }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun everyFieldTogetherSurvivesExportAndImport() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val all = build(fields.indices.map { fields[it].get(changed(it)) })
            assertThat(all.sanitized()).isEqualTo(all)
            val a = newRepo(scope)
            a.update { all }
            assertThat(a.settings.first()).isEqualTo(all)
            val b = newRepo(scope)
            b.applyValues(a.exportValues(), replace = true)
            assertThat(b.settings.first()).isEqualTo(all)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun everyStoredKeyIsInExactlyOneTypedSet() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher())
        try {
            val sets =
                mapOf(
                    "BOOLEAN" to SettingsRepository.BOOLEAN_KEYS,
                    "INT" to SettingsRepository.INT_KEYS,
                    "FLOAT" to SettingsRepository.FLOAT_KEYS,
                    "STRING" to SettingsRepository.STRING_KEYS,
                    "STRING_SET" to SettingsRepository.STRING_SET_KEYS,
                )
            val everything = newRepo(scope)
            everything.update { build(fields.indices.map { fields[it].get(changed(it)) }) }
            val exported = everything.exportValues()
            for ((key, value) in exported) {
                val type =
                    when (value) {
                        is Boolean -> "BOOLEAN"
                        is Int -> "INT"
                        is Float -> "FLOAT"
                        is String -> "STRING"
                        is Set<*> -> "STRING_SET"
                        else -> error("unexpected value type ${value.javaClass}")
                    }
                assertWithMessage("key '$key' must be in exactly one typed set")
                    .that(sets.filterValues { key in it }.keys)
                    .containsExactly(type)
            }
            val allKeys = sets.values.flatten()
            assertThat(allKeys.toSet()).isEqualTo(exported.keys)
            assertThat(allKeys).hasSize(exported.size)
        } finally {
            scope.cancel()
        }
    }
}

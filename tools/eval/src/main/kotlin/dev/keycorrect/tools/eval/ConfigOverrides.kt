package dev.keycorrect.tools.eval

import dev.keycorrect.core.decoder.ClassicConfig
import java.lang.reflect.Modifier

/**
 * Applies `section.field=value` overrides to a [ClassicConfig] (sections: `spatial`, `edits`,
 * `search`, `scoring`, `autocorrect`, `phrase`, `user`; fields are the constructor properties of
 * the section's config class, whatever they are called today). Done by reflection over the data
 * classes so the harness keeps working when the decoder adds or renames tunables. The alias
 * `spatial.sigma` sets both `sigmaXFactor` and `sigmaYFactor`. A value `x1.5` multiplies the
 * current value (so sweeps need not know defaults); a key `a.x+a.y` sets several fields at once.
 * Unknown keys fail loudly (a typo must not silently run the defaults).
 */
object ConfigOverrides {
    /** Parses `a.b=1,c.d=2` into an ordered map. */
    fun parse(spec: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (part in spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            val eq = part.indexOf('=')
            require(eq > 0) { "bad override '$part', expected section.field=value" }
            out[part.substring(0, eq).trim()] = part.substring(eq + 1).trim()
        }
        return out
    }

    fun apply(base: ClassicConfig, overrides: Map<String, String>): ClassicConfig {
        var c = base
        for ((compound, value) in overrides) {
            for (key in compound.split('+')) {
                c =
                    if (key == "spatial.sigma") {
                        applyOne(
                            applyOne(c, "spatial.sigmaXFactor", value),
                            "spatial.sigmaYFactor",
                            value,
                        )
                    } else {
                        applyOne(c, key, value)
                    }
            }
        }
        return c
    }

    /** Every supported key of the current [ClassicConfig], for `--help`. */
    val KEYS: List<String> by lazy {
        val c = ClassicConfig()
        buildList {
            add("spatial.sigma")
            for (section in properties(c)) {
                val value = section.value ?: continue
                for (f in properties(value)) add("${section.name}.${f.name}")
            }
        }
    }

    private class Prop(val name: String, val value: Any?)

    /** Constructor properties of a data class instance, in declaration order. */
    private fun properties(o: Any): List<Prop> {
        val ctor = constructorOf(o.javaClass)
        val fields =
            o.javaClass.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) }
                .take(ctor.parameterCount)
        return fields.map { f ->
            f.isAccessible = true
            Prop(f.name, f.get(o))
        }
    }

    private fun constructorOf(cls: Class<*>) =
        cls.constructors
            .filter { c ->
                c.parameterTypes.none { it.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
            }
            .maxByOrNull { it.parameterCount } ?: error("no constructor for ${cls.name}")

    /** Copy of data class [o] with property [name] replaced by [value]. */
    private fun copyWith(o: Any, name: String, value: Any?): Any {
        val props = properties(o)
        require(props.any { it.name == name }) { "no field '$name' in ${o.javaClass.simpleName}" }
        return constructorOf(o.javaClass)
            .newInstance(*props.map { if (it.name == name) value else it.value }.toTypedArray())
    }

    private fun applyOne(c: ClassicConfig, key: String, v: String): ClassicConfig {
        val dot = key.indexOf('.')
        require(dot > 0) { "unknown config key '$key' (try --help)" }
        val sectionName = key.substring(0, dot)
        val field = key.substring(dot + 1)
        val section =
            properties(c).firstOrNull { it.name == sectionName }
                ?: throw IllegalArgumentException(
                    "unknown config section '$sectionName' (try --help)"
                )
        val current =
            properties(section.value!!).firstOrNull { it.name == field }
                ?: throw IllegalArgumentException("unknown config key '$key' (try --help)")
        val parsed = parseValue(key, current.value, v)
        return copyWith(c, sectionName, copyWith(section.value!!, field, parsed)) as ClassicConfig
    }

    /**
     * Applies flat `field=value` overrides (same value syntax, `xN` multiplies) to any data class
     * whose constructor properties are primitives, e.g. `PersonalModelConfig` or
     * `ClassifierConfig`; unknown fields and non-primitive fields fail loudly.
     */
    fun <T : Any> applyFlat(base: T, overrides: Map<String, String>): T {
        var c: Any = base
        for ((key, v) in overrides) {
            val current =
                properties(c).firstOrNull { it.name == key }
                    ?: throw IllegalArgumentException(
                        "unknown field '$key' in ${c.javaClass.simpleName}"
                    )
            c = copyWith(c, key, parseValue(key, current.value, v))
        }
        @Suppress("UNCHECKED_CAST")
        return c as T
    }

    private fun parseValue(key: String, currentValue: Any?, v: String): Any {
        val current = Prop(key, currentValue)
        val factor = if (v.startsWith("x")) v.substring(1).toDoubleOrNull() else null
        val parsed: Any =
            when (current.value) {
                is Float ->
                    if (factor != null) (current.value as Float * factor).toFloat()
                    else v.toFloatOrNull()
                is Double ->
                    if (factor != null) current.value as Double * factor else v.toDoubleOrNull()
                is Int ->
                    if (factor != null) Math.round((current.value as Int) * factor).toInt()
                    else v.toIntOrNull()
                is Long -> v.toLongOrNull()
                is Boolean -> v.toBooleanStrictOrNull()
                else ->
                    throw IllegalArgumentException(
                        "$key cannot be overridden from the command line"
                    )
            }
                ?: throw IllegalArgumentException(
                    "$key: '$v' is not a valid ${current.value!!.javaClass.simpleName}"
                )
        return parsed
    }
}

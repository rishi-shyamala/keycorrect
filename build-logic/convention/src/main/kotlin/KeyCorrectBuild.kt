import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.kotlin.dsl.getByType

/** Build-wide constants shared by all convention plugins. */
object KeyCorrectBuild {
    const val COMPILE_SDK = 37
    const val TARGET_SDK = 37
    const val MIN_SDK = 31
    const val NDK_VERSION = "29.0.14206865"
    val JAVA_VERSION = JavaVersion.VERSION_17
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

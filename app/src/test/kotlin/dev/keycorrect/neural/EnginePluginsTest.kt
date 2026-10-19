package dev.keycorrect.neural

import com.google.common.truth.Truth.assertThat
import dev.keycorrect.core.settings.KeyboardSettings
import dev.keycorrect.engine.EngineContract
import kotlinx.coroutines.test.runTest
import org.junit.Test

class EnginePluginsTest {
    private val a = PluginFacts("p.a/p.a.Svc", "p.a", "Alpha", setOf("c1"), requestsInternet = false)
    private val b = PluginFacts("p.b/p.b.Svc", "p.b", "beta", setOf("c2"), requestsInternet = true)

    private class FakeSource(var facts: List<PluginFacts>) : PluginSource {
        override fun all() = facts

        override fun find(component: String) = facts.firstOrNull { it.component == component }
    }

    private val source = FakeSource(listOf(b, a))
    private val plugins = EnginePlugins(source)

    @Test
    fun listsPluginsSortedByLabelAndUnapprovedByDefault() {
        val list = plugins.list(emptySet())

        assertThat(list.map { it.label }).containsExactly("Alpha", "beta").inOrder()
        assertThat(list.none { it.approved }).isTrue()
    }

    @Test
    fun approvalNeedsTheSameComponentAndTheSameCertificate() {
        val approved = EngineApprovals.approve(emptySet(), a.component, "c1")

        assertThat(plugins.isApproved(a.component, approved)).isTrue()
        assertThat(plugins.isApproved(b.component, approved)).isFalse()
        assertThat(plugins.list(approved).single { it.approved }.component).isEqualTo(a.component)
    }

    @Test
    fun aChangedCertificateRevokesTheApproval() {
        val approved = EngineApprovals.approve(emptySet(), a.component, "c1")
        source.facts = listOf(a.copy(certDigests = setOf("other")))

        assertThat(plugins.isApproved(a.component, approved)).isFalse()
        assertThat(plugins.find(a.component, approved)?.approved).isFalse()
    }

    @Test
    fun anUnreadableCertificateIsNeverApproved() {
        source.facts = listOf(a.copy(certDigests = emptySet()))
        val approved = setOf("${a.component}|")

        assertThat(plugins.isApproved(a.component, approved)).isFalse()
        assertThat(plugins.list(approved).single().cert).isNull()
    }

    @Test
    fun aPluginThatIsNotInstalledIsNotApproved() {
        val approved = EngineApprovals.approve(emptySet(), "gone/gone.Svc", "c1")

        assertThat(plugins.isApproved("gone/gone.Svc", approved)).isFalse()
        assertThat(plugins.find("gone/gone.Svc", approved)).isNull()
    }

    @Test
    fun theInternetFlagIsCarried() {
        val list = plugins.list(emptySet())

        assertThat(list.single { it.packageName == "p.b" }.requestsInternet).isTrue()
        assertThat(list.single { it.packageName == "p.a" }.requestsInternet).isFalse()
    }

    @Test
    fun approvingAgainReplacesTheOldPinAndRevokeRemovesIt() {
        val first = EngineApprovals.approve(setOf("x/y|1"), a.component, "old")
        val second = EngineApprovals.approve(first, a.component, "new")

        assertThat(second).containsExactly("x/y|1", "${a.component}|new")
        assertThat(EngineApprovals.revoke(second, a.component)).containsExactly("x/y|1")
    }

    @Test
    fun severalSignersPinToOneStableValue() {
        assertThat(EngineApprovals.pin(emptySet())).isNull()
        assertThat(EngineApprovals.pin(setOf("one"))).isEqualTo("one")
        val pin = EngineApprovals.pin(setOf("b", "a"))
        assertThat(pin).isEqualTo(EngineApprovals.pin(setOf("a", "b")))
        assertThat(pin).isNotEqualTo(EngineApprovals.pin(setOf("a", "c")))
        assertThat(pin).hasLength(64)
    }

    @Test
    fun theCertificateIsShownInGroupsOfFour() {
        assertThat(EngineApprovals.shortCert("0123456789abcdef0123")).isEqualTo("0123 4567 89ab cdef")
        assertThat(EngineApprovals.shortCert(null)).isEmpty()
    }

    // ------------------------------------------------------------------ chooseEngine

    private fun choose(
        rerank: Boolean = true,
        strength: Float = 0.5f,
        engine: String = KeyboardSettings.ENGINE_BUILTIN,
        builtin: Boolean = true,
        approved: Set<String> = emptySet(),
    ) =
        chooseEngine(rerank, strength, engine, { builtin }, { plugins.isApproved(it, approved) })

    @Test
    fun builtinIsUsedWhenAvailable() {
        assertThat(choose()).isEqualTo(EngineTarget.Builtin)
        assertThat(choose(builtin = false)).isEqualTo(EngineTarget.None)
    }

    @Test
    fun nothingRunsWithRerankOffOrZeroStrength() {
        assertThat(choose(rerank = false)).isEqualTo(EngineTarget.None)
        assertThat(choose(strength = 0f)).isEqualTo(EngineTarget.None)
    }

    @Test
    fun anApprovedPluginIsUsedEvenWithoutThePack() {
        val approved = EngineApprovals.approve(emptySet(), a.component, "c1")

        assertThat(choose(engine = a.component, builtin = false, approved = approved))
            .isEqualTo(EngineTarget.Plugin(a.component))
    }

    @Test
    fun anUnapprovedPluginFallsBackToBuiltin() {
        assertThat(choose(engine = a.component)).isEqualTo(EngineTarget.Builtin)
        assertThat(choose(engine = a.component, builtin = false)).isEqualTo(EngineTarget.None)
    }

    @Test
    fun aPluginWhoseCertificateChangedFallsBackToBuiltin() {
        val approved = EngineApprovals.approve(emptySet(), a.component, "c1")
        source.facts = listOf(a.copy(certDigests = setOf("rotated")))

        assertThat(choose(engine = a.component, approved = approved))
            .isEqualTo(EngineTarget.Builtin)
    }

    // ------------------------------------------------------------------ status

    @Test
    fun statusIsReadFromTheEngineInfo() {
        assertThat(lmStatusOf(null)).isEqualTo(LmStatus.NoAnswer)
        assertThat(lmStatusOf(EngineInfo(EngineContract.STATUS_READY, "SmolLM2 135M")))
            .isEqualTo(LmStatus.Ready("SmolLM2 135M"))
        assertThat(lmStatusOf(EngineInfo(EngineContract.STATUS_READY, " ")))
            .isEqualTo(LmStatus.Ready(null))
        assertThat(lmStatusOf(EngineInfo(EngineContract.STATUS_LOADING, null)))
            .isEqualTo(LmStatus.Loading)
        assertThat(lmStatusOf(EngineInfo(EngineContract.STATUS_NO_MODEL, null)))
            .isEqualTo(LmStatus.NoModel)
        assertThat(lmStatusOf(EngineInfo(EngineContract.errorStatus("OutOfMemory"), null)))
            .isEqualTo(LmStatus.Error("OutOfMemory"))
        assertThat(lmStatusOf(EngineInfo("weird", null))).isEqualTo(LmStatus.NoAnswer)
    }

    @Test
    fun awaitStatusWaitsForLoadingAndEarlyNoModel() = runTest {
        val answers =
            ArrayDeque(
                listOf(
                    null,
                    EngineInfo(EngineContract.STATUS_NO_MODEL, null),
                    EngineInfo(EngineContract.STATUS_LOADING, null),
                    EngineInfo(EngineContract.STATUS_READY, "m"),
                )
            )
        var pauses = 0

        val status = awaitStatus({ answers.removeFirst() }, { pauses++ })

        assertThat(status).isEqualTo(LmStatus.Ready("m"))
        assertThat(pauses).isEqualTo(3)
    }

    @Test
    fun awaitStatusAcceptsNoModelOnceSettledAndGivesUpEventually() = runTest {
        val noModel = EngineInfo(EngineContract.STATUS_NO_MODEL, null)
        assertThat(awaitStatus({ noModel }, {})).isEqualTo(LmStatus.NoModel)

        var calls = 0
        val status = awaitStatus({ calls++; null }, {}, maxPolls = 4)
        assertThat(status).isEqualTo(LmStatus.NoAnswer)
        assertThat(calls).isEqualTo(4)
    }
}

package dev.keycorrect.core.transfer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportPlanTest {
    private val settings = sampleBundle().settings

    @Test
    fun rotationAndSmallNoiseAreNotADifference() {
        assertThat(
                ScreenComparison.differs(
                    phone,
                    phone.copy(screenWidthPx = 2400, screenHeightPx = 1080),
                )
            )
            .isFalse()
        assertThat(ScreenComparison.differs(phone, phone.copy(screenHeightPx = 2340)))
            .isFalse() // 2.5%
        assertThat(ScreenComparison.differs(phone, phone.copy(model = "Other", keyHeightDp = 70)))
            .isFalse()
    }

    @Test
    fun realDifferences() {
        assertThat(
                ScreenComparison.differs(
                    phone,
                    phone.copy(
                        screenWidthPx = 1440,
                        screenHeightPx = 3120,
                        densityDpi = 560,
                        smallestWidthDp = 411,
                    ),
                )
            )
            .isTrue()
        assertThat(ScreenComparison.differs(phone, phone.copy(densityDpi = 480))).isTrue()
        assertThat(ScreenComparison.differs(phone, phone.copy(smallestWidthDp = 600))).isTrue()
        assertThat(ScreenComparison.differs(phone, phone.copy(screenHeightPx = 2200))).isTrue()
    }

    @Test
    fun matchingScreenAppliesEverything() {
        val plan = ImportPlan.create(phone, phone, ImportMode.Merge)
        assertThat(plan.screenDiffers).isFalse()
        assertThat(plan.sections)
            .containsExactly(Section.Settings, Section.Dictionary, Section.Learning, Section.Touch)
            .inOrder()
        assertThat(plan.skipped).isEmpty()
        assertThat(plan.settingsToApply(settings)).isEqualTo(settings.values)
        assertThat(plan.sectionsToWipe).isEmpty()
    }

    @Test
    fun differingScreenSkipsTouchAndScreenTiedSettings() {
        val tablet = phone.copy(screenWidthPx = 1600, screenHeightPx = 2560, smallestWidthDp = 800)
        val plan = ImportPlan.create(phone, tablet, ImportMode.Merge)
        assertThat(plan.screenDiffers).isTrue()
        assertThat(plan.sections).doesNotContain(Section.Touch)
        assertThat(plan.skipped).containsExactly(Section.Touch)
        assertThat(plan.settingsToApply(settings).keys)
            .containsExactly("haptics_enabled", "cursor_speed")
        assertThat(plan.skippedSettingKeys(settings))
            .containsExactly("key_height_dp", "bottom_key_width")
    }

    @Test
    fun importAnywayBringsThemIn() {
        val tablet = phone.copy(densityDpi = 280)
        val plan = ImportPlan.create(phone, tablet, ImportMode.Replace, importTouchAnyway = true)
        assertThat(plan.screenDiffers).isTrue()
        assertThat(plan.sections).contains(Section.Touch)
        assertThat(plan.settingsToApply(settings)).isEqualTo(settings.values)
        assertThat(plan.sectionsToWipe).containsExactlyElementsIn(plan.sections)
    }

    @Test
    fun replaceWithDifferingScreenKeepsLocalTouch() {
        val plan = ImportPlan.create(phone, phone.copy(densityDpi = 280), ImportMode.Replace)
        assertThat(plan.sectionsToWipe)
            .containsExactly(Section.Settings, Section.Dictionary, Section.Learning)
    }

    @Test
    fun importAnywayIsIgnoredWhenScreensMatch() {
        val plan =
            ImportPlan.create(
                sampleBundle().manifest,
                phone,
                ImportMode.Merge,
                importTouchAnyway = true,
            )
        assertThat(plan.screenDiffers).isFalse()
        assertThat(plan.includesScreenTied).isTrue()
    }

    @Test
    fun screenTiedKeysAreFlagged() {
        assertThat(settings.screenTiedKeys).containsExactly("key_height_dp", "bottom_key_width")
    }
}

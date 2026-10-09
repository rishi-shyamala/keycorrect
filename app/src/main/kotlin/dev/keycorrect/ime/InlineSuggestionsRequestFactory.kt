package dev.keycorrect.ime

import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.os.Bundle
import android.util.Size
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.inline.InlinePresentationSpec
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.common.ImageViewStyle
import androidx.autofill.inline.common.TextViewStyle
import androidx.autofill.inline.common.ViewStyle
import androidx.autofill.inline.v1.InlineSuggestionUi
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import dev.keycorrect.R

/**
 * Builds the [InlineSuggestionsRequest] that asks the autofill service for chips styled like the
 * keyboard (docs/PLAN.md §15.2).
 */
internal object InlineSuggestionsRequestFactory {
    /**
     * Upper bound on chips. The strip scrolls so unlimited would work, but every chip is a remote
     * surface (SurfaceControlViewHost); 6 covers a few accounts plus the pinned password-manager
     * icon without burning memory.
     */
    const val MAX_SUGGESTIONS = 6

    private const val MIN_WIDTH_DP = 48
    private const val MIN_HEIGHT_DP = 32
    private const val MAX_HEIGHT_DP = 36

    /**
     * Returns null if the autofill service's renderer does not support the v1 UI template we style
     * for (the platform then falls back to the dropdown menu).
     */
    fun create(context: Context, uiExtras: Bundle): InlineSuggestionsRequest? {
        if (UiVersions.INLINE_UI_VERSION_1 !in UiVersions.getVersions(uiExtras)) return null
        val metrics = context.resources.displayMetrics
        fun dp(value: Int) = (value * metrics.density).toInt()
        val spec =
            InlinePresentationSpec.Builder(
                    Size(dp(MIN_WIDTH_DP), dp(MIN_HEIGHT_DP)),
                    Size(metrics.widthPixels / 2, dp(MAX_HEIGHT_DP)),
                )
                .setStyle(buildStyles(context, colorSchemeFor(context)))
                .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(MAX_SUGGESTIONS)
            .build()
    }

    private fun colorSchemeFor(context: Context): ColorScheme {
        val night =
            context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        return if (night) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    }

    // setBackground/setPadding/setLayoutMargin are the documented public styling API (see the
    // InlineSuggestionUi javadoc) but are declared on the @RestrictTo base class
    // ViewStyle.BaseBuilder, which trips lint (androidx.autofill known false positive).
    @Suppress("RestrictedApi")
    private fun buildStyles(context: Context, colors: ColorScheme): Bundle {
        val density = context.resources.displayMetrics.density
        fun px(dp: Int) = (dp * density).toInt()
        // The drawable is white and tinted, so one resource serves every colour scheme.
        fun chipBackground() =
            Icon.createWithResource(context, R.drawable.inline_suggestion_chip)
                .setTint(colors.surfaceContainerHighest.toArgb())
        val text = colors.onSurface.toArgb()

        val style =
            InlineSuggestionUi.newStyleBuilder()
                .setChipStyle(
                    ViewStyle.Builder()
                        .setBackground(chipBackground())
                        .setPadding(px(12), 0, px(12), 0)
                        .build()
                )
                .setTitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(text)
                        .setTextSize(14f)
                        .setLayoutMargin(px(4), 0, px(4), 0)
                        .build()
                )
                .setSubtitleStyle(
                    TextViewStyle.Builder()
                        .setTextColor(colors.onSurfaceVariant.toArgb())
                        .setTextSize(12f)
                        .setLayoutMargin(0, 0, px(4), 0)
                        .build()
                )
                .setStartIconStyle(
                    ImageViewStyle.Builder().setMaxWidth(px(20)).setMaxHeight(px(20)).build()
                )
                .setEndIconStyle(
                    ImageViewStyle.Builder().setMaxWidth(px(20)).setMaxHeight(px(20)).build()
                )
                // The pinned "open password manager" chip: icon only, same pill background.
                .setSingleIconChipStyle(
                    ViewStyle.Builder()
                        .setBackground(chipBackground())
                        .setPadding(px(8), 0, px(8), 0)
                        .build()
                )
                .setSingleIconChipIconStyle(
                    ImageViewStyle.Builder().setMaxWidth(px(20)).setMaxHeight(px(20)).build()
                )
                .build()
        return UiVersions.newStylesBuilder().addStyle(style).build()
    }
}

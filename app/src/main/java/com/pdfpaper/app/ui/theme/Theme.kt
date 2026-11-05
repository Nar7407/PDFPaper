package com.pdfpaper.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.ripple.LocalRippleTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.pdfpaper.app.data.AppearancePrefs
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.ui.motion.LocalReduceMotion
import com.pdfpaper.app.ui.motion.NoIndication
import com.pdfpaper.app.ui.motion.NoRippleTheme
import com.pdfpaper.app.ui.viewer.turn.TurnSystem
import kotlin.random.Random

/**
 * Task T63: true while the reader chose the direct-sunlight profile. The
 * viewer uses it to pin the window brightness to maximum; the theme uses it
 * for a black-on-white palette and larger type.
 */
val LocalSunlightMode = staticCompositionLocalOf { false }

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = DarkSecondaryContainer,
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = DarkTertiaryContainer,
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = DarkBackground,
    onBackground = DarkOnBackground,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    background = LightBackground,
    onBackground = LightOnBackground,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    error = LightError,
    onError = LightOnError
)

/**
 * Task T63: sunlight means a white page and black ink. The normal light theme
 * already comes close, but its grey surfaces lose their edge outdoors; this
 * scheme is deliberately harder (pure white, near-black text).
 */
private val SunlightColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFFFFF),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF000000),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF2F2F2),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF000000),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF2F2F2),
    onTertiaryContainer = Color(0xFF000000),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F2),
    onSurfaceVariant = Color(0xFF1A1A1A),
    outline = Color(0xFF000000),
    outlineVariant = Color(0xFF595959),
    error = Color(0xFF8B0000),
    onError = Color(0xFFFFFFFF)
)

/**
 * The AMOLED black scheme: the dark palette with pure-black surfaces, so an
 * AMOLED panel draws no light behind them. Neutral like the rest of the shell
 * — the accent colours belong to Code style.
 */
private val AmoledColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = Color(0xFF000000),
    onPrimaryContainer = DarkOnPrimaryContainer,
    secondary = DarkSecondary,
    onSecondary = DarkOnSecondary,
    secondaryContainer = Color(0xFF000000),
    onSecondaryContainer = DarkOnSecondaryContainer,
    tertiary = DarkTertiary,
    onTertiary = DarkOnTertiary,
    tertiaryContainer = Color(0xFF000000),
    onTertiaryContainer = DarkOnTertiaryContainer,
    background = Color(0xFF000000),
    onBackground = DarkOnBackground,
    surface = Color(0xFF000000),
    onSurface = DarkOnSurface,
    surfaceVariant = Color(0xFF000000),
    onSurfaceVariant = DarkOnSurfaceVariant,
    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,
    error = DarkError,
    onError = DarkOnError
)

/**
 * The Code style scheme for [palette]: the editor background and surface as the
 * shell's neutrals, the two text colours for reading, and the five accents in
 * the Material accent roles (blue = buttons/links, green = selected items,
 * purple = chips' containers, orange = error) so chrome reads like syntax
 * highlighting. Every text/accent-over-background pair was checked against
 * WCAG AA 4.5:1 by CodePaletteContrastTest.
 *
 * Every role is set explicitly, so one factory serves both dark and light
 * palettes: the palette's own background is the difference.
 */
private fun codeColorScheme(palette: CodePalette): ColorScheme {
    val background = Color(palette.background.toULong() shl 32)
    val surface = Color(palette.surface.toULong() shl 32)
    val textPrimary = Color(palette.textPrimary.toULong() shl 32)
    val textSecondary = Color(palette.textSecondary.toULong() shl 32)
    val blue = Color(palette.blue.toULong() shl 32)
    val green = Color(palette.green.toULong() shl 32)
    val orange = Color(palette.orange.toULong() shl 32)
    val purple = Color(palette.purple.toULong() shl 32)
    // The content of an accent-filled button: the palette background, which the
    // contrast test covers as "button text".
    val contentOnAccent = if (palette.darkBackground) background else surface
    // Every role is explicit, so one factory describes both light and dark
    // palettes; the palette's own background decides the difference.
    return darkColorScheme(
        primary = blue,
        onPrimary = contentOnAccent,
        primaryContainer = surface.blend(blue, 0.24f),
        onPrimaryContainer = blue,
        secondary = green,
        onSecondary = contentOnAccent,
        secondaryContainer = surface.blend(green, 0.24f),
        onSecondaryContainer = green,
        tertiary = purple,
        onTertiary = contentOnAccent,
        tertiaryContainer = surface.blend(purple, 0.24f),
        onTertiaryContainer = purple,
        error = orange,
        onError = contentOnAccent,
        errorContainer = surface.blend(orange, 0.24f),
        onErrorContainer = orange,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surface,
        onSurfaceVariant = textSecondary,
        outline = surface.blend(textSecondary, 0.45f),
        outlineVariant = surface.blend(textSecondary, 0.22f),
        inverseSurface = textPrimary,
        inverseOnSurface = background,
        inversePrimary = background.blend(blue, 0.5f),
        surfaceTint = blue,
        scrim = Color.Black
    )
}

/** Interpolates this colour towards [other] by [fraction]; used for tints. */
private fun Color.blend(other: Color, fraction: Float): Color =
    androidx.compose.ui.graphics.lerp(this, other, fraction)

/**
 * The Code style palette for this process. It is resolved on the first
 * composition of the app theme (a cold start) from the stored last index and
 * the rotation choice, then fixed: rotating again on an activity recreation or
 * a settings change would repaint the chrome while the reader is using it.
 */
private object CodeStyleLaunch {

    private var resolvedIndex: Int? = null

    fun forLaunch(context: Context, prefs: AppearancePrefs): CodePalette {
        resolvedIndex?.let { return CodePalettes.at(it) }
        val next = CodePaletteRotation.indexForLaunch(
            count = CodePalettes.ALL.size,
            lastIndex = prefs.lastPaletteIndex,
            changeEachLaunch = prefs.changePaletteEachLaunch,
            rotation = prefs.rotation,
            chosenIndex = prefs.paletteIndex,
            random = Random.Default
        )
        if (prefs.changePaletteEachLaunch) {
            // Remembered so the next cold start carries the rotation on.
            AppSettings.setLastCodePaletteIndex(context, next)
        }
        resolvedIndex = next
        return CodePalettes.at(next)
    }
}

/**
 * The app root (T63): reads the display preferences that affect the whole
 * shell, re-reads them whenever the app returns to the foreground, and
 * provides the reduce-motion locals. Sunlight forces a light theme even when
 * the system is dark.
 *
 * The appearance choice (Settings → Appearance) is read here too: Light /
 * Dark / AMOLED black repaint the shell instantly, and Code style resolves its
 * palette once per process so the colours never rotate while the app is in use.
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // The reader's own switch or Android's "Remove animations": either one
    // stops chrome motion, so the shell never animates against the system.
    fun chromeMotionReduced(): Boolean =
        AppSettings.reduceMotion(context) || TurnSystem.animationsRemoved(context)
    var reduceMotion by remember { mutableStateOf(chromeMotionReduced()) }
    var sunlight by remember { mutableStateOf(AppSettings.sunlightMode(context)) }
    var appearance by remember { mutableStateOf(AppSettings.appearancePrefs(context)) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                reduceMotion = chromeMotionReduced()
                sunlight = AppSettings.sunlightMode(context)
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // T63: Settings is a destination inside this activity, so returning from
    // it does not fire ON_START. Listening to the settings file is what makes
    // the switch take effect immediately, before the reader leaves the screen.
    val settings = AppSettings.preferences(context)
    DisposableEffect(settings) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            reduceMotion = chromeMotionReduced()
            sunlight = AppSettings.sunlightMode(context)
            appearance = AppSettings.appearancePrefs(context)
        }
        settings.registerOnSharedPreferenceChangeListener(listener)
        onDispose { settings.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    // The launch palette is fixed for the process, so nothing rotates while the
    // app is in use. A palette the reader picked by hand applies at once.
    val codePalette = remember(
        appearance.choice,
        appearance.changePaletteEachLaunch,
        appearance.paletteIndex
    ) {
        when {
            appearance.choice != AppearanceChoice.CODE_STYLE -> null
            appearance.changePaletteEachLaunch -> CodeStyleLaunch.forLaunch(context, appearance)
            else -> CodePalettes.at(appearance.paletteIndex)
        }
    }
    val appearanceTheme = AppearanceTheme.resolve(
        choice = appearance.choice,
        systemDark = systemDark && !sunlight,
        palette = codePalette
    )
    CompositionLocalProvider(
        LocalReduceMotion provides reduceMotion,
        LocalSunlightMode provides sunlight,
        LocalCodeAccents provides CodeAccents.of(codePalette)
    ) {
        PDFPaperTheme(
            darkTheme = appearanceTheme.darkBackground,
            sunlight = sunlight,
            amoled = appearance.choice == AppearanceChoice.AMOLED_BLACK,
            codePalette = codePalette
        ) {
            // Inside MaterialTheme, so the reduce-motion provider wins over the
            // theme's own indication (Material 3 installs a ripple there).
            CompositionLocalProvider(
                LocalIndication provides if (reduceMotion) NoIndication else LocalIndication.current,
                LocalRippleTheme provides if (reduceMotion) NoRippleTheme else LocalRippleTheme.current
            ) {
                content()
            }
        }
    }
}

/**
 * Deliberately black-and-white Material 3 theme. Dynamic wallpaper colour is
 * not used: PDFPaper keeps the same neutral palette on every device.
 *
 * [sunlight] (T63) forces the high-contrast light palette and larger type; the
 * caller decides when to use it (the app root does), so a preview can still
 * ask for an ordinary light theme. [codePalette] (Settings → Appearance) wins
 * over both: the shell takes the Code style palette instead of the neutrals.
 */
@Composable
fun PDFPaperTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    sunlight: Boolean = false,
    amoled: Boolean = false,
    codePalette: CodePalette? = null,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        codePalette != null -> codeColorScheme(codePalette)
        amoled -> AmoledColorScheme
        sunlight -> SunlightColorScheme
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme || sunlight
                isAppearanceLightNavigationBars = !darkTheme || sunlight
            }
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = if (sunlight) SunlightTypography else Typography,
        content = content
    )
}

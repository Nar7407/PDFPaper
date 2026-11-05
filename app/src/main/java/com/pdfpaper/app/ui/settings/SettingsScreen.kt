package com.pdfpaper.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pdfpaper.app.data.AppSettings
import com.pdfpaper.app.annot.AnnotationRepository
import com.pdfpaper.app.data.AppearancePrefs
import com.pdfpaper.app.data.AutosaveMode
import com.pdfpaper.app.data.LinkTapBehavior
import com.pdfpaper.app.data.pdfColorOf
import com.pdfpaper.app.data.rgbOf
import com.pdfpaper.app.data.save.VersionStore
import com.pdfpaper.app.ui.theme.AppearanceChoice
import com.pdfpaper.app.ui.theme.CodePalette
import com.pdfpaper.app.ui.theme.CodePalettes
import com.pdfpaper.app.ui.theme.PaletteRotation
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pdfpaper.app.launcher.LauncherSurfaces
import kotlinx.coroutines.launch
import com.pdfpaper.app.ui.UiTags
import com.pdfpaper.app.ui.components.AppTopBar
import com.pdfpaper.app.ui.search.IndexSettingsCard
import com.pdfpaper.app.ui.search.SearchViewModel
import com.pdfpaper.app.ui.viewer.turn.TurnAction
import com.pdfpaper.app.ui.viewer.turn.TurnAnimation
import com.pdfpaper.app.ui.viewer.turn.TurnMode
import com.pdfpaper.app.ui.viewer.turn.TurnSettings
import com.pdfpaper.engine.eink.EinkDither
import com.pdfpaper.engine.eink.EinkProfile
import com.pdfpaper.engine.eink.EinkSettings
import com.pdfpaper.engine.render.PageThemeMode
import kotlin.math.roundToInt

/**
 * Minimal settings surface (task UI-2). The author name is the only setting
 * used by comments, replies and dynamic stamps today; keeping it here means
 * those tools never invent a name.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val indexViewModel: SearchViewModel = viewModel()
    var name by rememberSaveable { mutableStateOf(AppSettings.authorName(context)) }
    var saved by rememberSaveable { mutableStateOf(false) }
    var linkBehavior by rememberSaveable {
        mutableStateOf(AppSettings.linkTapBehavior(context))
    }
    var showLauncherNames by rememberSaveable {
        mutableStateOf(AppSettings.showLauncherNames(context))
    }
    var blockActions by rememberSaveable {
        mutableStateOf(AppSettings.blockDocumentActions(context))
    }
    var safetyBanner by rememberSaveable {
        mutableStateOf(AppSettings.showSafetyBanner(context))
    }
    var autosaveMode by rememberSaveable {
        mutableStateOf(AppSettings.autosaveMode(context))
    }
    var versionHistory by rememberSaveable {
        mutableStateOf(AppSettings.versionHistoryEnabled(context))
    }
    var versionsCleared by rememberSaveable { mutableStateOf(false) }
    // Task T32/T32b: the page theme is a data class, so plain remember is right.
    var pageTheme by remember { mutableStateOf(AppSettings.pageTheme(context)) }
    var followSystemTheme by rememberSaveable {
        mutableStateOf(AppSettings.pageThemeFollowsSystem(context))
    }
    var bookmarkOutline by rememberSaveable {
        mutableStateOf(AppSettings.bookmarkOutlineEnabled(context))
    }
    var annotationIndexDeleted by rememberSaveable { mutableStateOf(false) }
    // Task T58: TurnSettings is not Saveable, and re-reading it is cheap.
    var turn by remember { mutableStateOf(AppSettings.turnSettings(context)) }
    // Task T63: the E-Ink display, motion and sunlight preferences.
    var eink by remember { mutableStateOf(AppSettings.einkSettings(context)) }
    var einkEnabled by rememberSaveable { mutableStateOf(AppSettings.einkEnabled(context)) }
    var einkAutoClear by rememberSaveable {
        mutableStateOf(AppSettings.einkAutoClearTurns(context))
    }
    var reduceMotion by rememberSaveable { mutableStateOf(AppSettings.reduceMotion(context)) }
    var sunlight by rememberSaveable { mutableStateOf(AppSettings.sunlightMode(context)) }
    // Settings → Appearance: the app chrome theme. It is a data class, so a
    // plain remember is enough; every change is written immediately and the app
    // theme's settings listener repaints the shell without a restart.
    var appearance by remember { mutableStateOf(AppSettings.appearancePrefs(context)) }
    val updateAppearance: (AppearancePrefs) -> Unit = { next ->
        appearance = next
        AppSettings.setAppearancePrefs(context, next)
    }
    // Task T38b: the reading session preference.
    var alwaysPageOne by rememberSaveable {
        mutableStateOf(AppSettings.alwaysStartAtPageOne(context))
    }
    val scope = rememberCoroutineScope()
    // Every reading option saves immediately: the viewer re-reads the settings
    // on ON_START, so a change here reaches an open document without a restart.
    val updateTurn: (TurnSettings) -> Unit = { next ->
        turn = next.normalised()
        AppSettings.setTurnSettings(context, turn)
    }
    val updateEink: (EinkSettings) -> Unit = { next ->
        eink = next.normalized()
        AppSettings.setEinkSettings(context, eink)
    }

    Scaffold(
        topBar = { AppTopBar(title = "Settings", onBack = onBack) },
        containerColor = MaterialTheme.colorScheme.background,
        modifier = Modifier.testTag(UiTags.SETTINGS_SCREEN)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.SETTINGS_APPEARANCE)
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "The colour of the app around your documents. It never changes the " +
                            "pages themselves — the Page theme card below does that.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    AppearanceChoice.entries.forEach { choice ->
                        ReadingChoice(
                            label = choice.label,
                            description = appearanceDescription(choice),
                            selected = appearance.choice == choice,
                            tag = UiTags.appearanceOption(choice.name),
                            onSelect = { updateAppearance(appearance.copy(choice = choice)) }
                        )
                    }
                    if (appearance.choice == AppearanceChoice.CODE_STYLE) {
                        Spacer(Modifier.height(8.dp))
                        ReadingToggle(
                            label = "Change palette each launch",
                            description = "A different palette at every cold start. The colours " +
                                "never change while the app is open.",
                            checked = appearance.changePaletteEachLaunch,
                            tag = UiTags.SETTINGS_CODE_EACH_LAUNCH,
                            onChange = {
                                updateAppearance(appearance.copy(changePaletteEachLaunch = it))
                            }
                        )
                        if (appearance.changePaletteEachLaunch) {
                            Text("Rotation", style = MaterialTheme.typography.labelLarge)
                            PaletteRotation.entries.forEach { rotation ->
                                ReadingChoice(
                                    label = rotation.label,
                                    description = if (rotation == PaletteRotation.IN_ORDER) {
                                        "Walk through the six palettes and start again."
                                    } else {
                                        "A random palette, never the same as last time."
                                    },
                                    selected = appearance.rotation == rotation,
                                    tag = UiTags.paletteRotationOption(rotation.name),
                                    onSelect = {
                                        updateAppearance(appearance.copy(rotation = rotation))
                                    }
                                )
                            }
                            if (appearance.lastPaletteIndex >= 0) {
                                Text(
                                    "This launch: " +
                                        CodePalettes.at(appearance.lastPaletteIndex).name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            Text("Palette", style = MaterialTheme.typography.labelLarge)
                            CodePalettes.ALL.forEach { palette ->
                                ReadingChoice(
                                    label = palette.name,
                                    description = paletteDescription(palette),
                                    selected = appearance.paletteIndex == palette.index,
                                    tag = UiTags.codePaletteOption(palette.name),
                                    onSelect = {
                                        updateAppearance(appearance.copy(paletteIndex = palette.index))
                                    }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Text(
                text = "Your name",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Added to comments, replies and dynamic stamps. It never leaves this device.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = name,
                onValueChange = {
                    name = it
                    saved = false
                },
                label = { Text("Author name") },
                placeholder = { Text(AppSettings.DEFAULT_AUTHOR) },
                singleLine = true,
                supportingText = { Text("Shown to other reviewers in the PDF.") },
                modifier = Modifier.fillMaxWidth().testTag(UiTags.SETTINGS_AUTHOR)
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    AppSettings.setAuthorName(context, name)
                    name = AppSettings.authorName(context)
                    saved = true
                },
                modifier = Modifier.testTag(UiTags.SETTINGS_SAVE)
            ) {
                Text(if (saved) "Saved" else "Save name")
            }
            Spacer(Modifier.height(28.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Links and references", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Choose what happens when you tap an internal link, citation or " +
                            "figure reference.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinkBehaviorOption(
                        label = "Preview first",
                        description = "Show a preview card and keep your place.",
                        selected = linkBehavior == LinkTapBehavior.PREVIEW_FIRST,
                        tag = UiTags.SETTINGS_LINK_PREVIEW,
                        onSelect = {
                            linkBehavior = LinkTapBehavior.PREVIEW_FIRST
                            AppSettings.setLinkTapBehavior(context, linkBehavior)
                        }
                    )
                    LinkBehaviorOption(
                        label = "Jump directly",
                        description = "Go to the destination without the preview card.",
                        selected = linkBehavior == LinkTapBehavior.JUMP_DIRECTLY,
                        tag = UiTags.SETTINGS_LINK_JUMP,
                        onSelect = {
                            linkBehavior = LinkTapBehavior.JUMP_DIRECTLY
                            AppSettings.setLinkTapBehavior(context, linkBehavior)
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Resume where you left off", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "PDFPaper remembers the page, zoom and reading mode of every " +
                            "document, and opens it there next time. This is app data, never " +
                            "written into the PDF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReadingToggle(
                        label = "Always start at page 1",
                        description = "Every document opens at its first page. Zoom and " +
                            "reading mode are still remembered.",
                        checked = alwaysPageOne,
                        tag = UiTags.SETTINGS_ALWAYS_PAGE_ONE,
                        onChange = {
                            alwaysPageOne = it
                            AppSettings.setAlwaysStartAtPageOne(context, it)
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            SpeechSettingsCard()
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Reading", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "One-handed page turning for the viewer. These gestures are off " +
                            "while you annotate, draw, select text or fill a form.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReadingToggle(
                        label = "Turn pages with the volume keys",
                        description = "Volume down goes forward, volume up goes back, and " +
                            "holding a key keeps turning. Off by default.",
                        checked = turn.volumeKeys,
                        tag = UiTags.SETTINGS_READING_VOLUME,
                        onChange = { updateTurn(turn.copy(volumeKeys = it)) }
                    )
                    ReadingToggle(
                        label = "Swap the volume direction",
                        description = "Volume up goes forward instead. Only applies while reading.",
                        checked = turn.invertVolume,
                        tag = UiTags.SETTINGS_READING_INVERT,
                        enabled = turn.volumeKeys,
                        onChange = { updateTurn(turn.copy(invertVolume = it)) }
                    )
                    ReadingToggle(
                        label = "Tap the page edges to turn",
                        description = "A tap on the right edge goes forward, the left edge " +
                            "goes back. A tap in the middle shows or hides the toolbar.",
                        checked = turn.edgeTaps,
                        tag = UiTags.SETTINGS_READING_EDGE,
                        onChange = { updateTurn(turn.copy(edgeTaps = it)) }
                    )
                    ReadingToggle(
                        label = "Left-handed",
                        description = "Swaps the two side edges so the thumb reaches forward.",
                        checked = turn.leftHanded,
                        tag = UiTags.SETTINGS_READING_LEFT_HANDED,
                        enabled = turn.edgeTaps,
                        onChange = { updateTurn(turn.copy(leftHanded = it)) }
                    )
                    ReadingToggle(
                        label = "Also use the top and bottom edges",
                        description = "Adds a band at the top (back) and the bottom (forward). " +
                            "The side edges keep priority.",
                        checked = turn.verticalZones,
                        tag = UiTags.SETTINGS_READING_VERTICAL,
                        enabled = turn.edgeTaps,
                        onChange = { updateTurn(turn.copy(verticalZones = it)) }
                    )
                    ReadingToggle(
                        label = "Light haptic tick on a turn",
                        description = "A short vibration confirms every page turn.",
                        checked = turn.hapticTick,
                        tag = UiTags.SETTINGS_READING_HAPTIC,
                        onChange = { updateTurn(turn.copy(hapticTick = it)) }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "What an edge tap does",
                        style = MaterialTheme.typography.labelLarge
                    )
                    TurnAction.entries.forEach { action ->
                        ReadingChoice(
                            label = action.label,
                            description = when (action) {
                                TurnAction.PAGE -> "Jump to the next or previous page."
                                TurnAction.SCREEN ->
                                    "Move one screen, keeping a few lines so you do not lose your place."
                            },
                            selected = turn.action == action,
                            tag = UiTags.turnActionOption(action.name),
                            enabled = turn.edgeTaps,
                            onSelect = { updateTurn(turn.copy(action = action)) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Reading mode", style = MaterialTheme.typography.labelLarge)
                    TurnMode.entries.forEach { mode ->
                        ReadingChoice(
                            label = mode.label,
                            description = mode.description,
                            selected = turn.mode == mode,
                            tag = UiTags.turnModeOption(mode.name),
                            onSelect = { updateTurn(turn.copy(mode = mode)) }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Edge zone width: ${turn.zonePercent}% of the page",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = turn.zonePercent.toFloat(),
                        onValueChange = { updateTurn(turn.copy(zonePercent = it.roundToInt())) },
                        valueRange = TurnSettings.MIN_ZONE_PERCENT.toFloat()..
                            TurnSettings.MAX_ZONE_PERCENT.toFloat(),
                        steps = (TurnSettings.MAX_ZONE_PERCENT - TurnSettings.MIN_ZONE_PERCENT) / 5 - 1,
                        enabled = turn.edgeTaps,
                        modifier = Modifier.testTag(UiTags.SETTINGS_READING_ZONE)
                    )
                    Text(
                        "Lines kept when scrolling: ${turn.overlapLines}",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Slider(
                        value = turn.overlapLines.toFloat(),
                        onValueChange = { updateTurn(turn.copy(overlapLines = it.roundToInt())) },
                        valueRange = TurnSettings.MIN_OVERLAP_LINES.toFloat()..
                            TurnSettings.MAX_OVERLAP_LINES.toFloat(),
                        steps = TurnSettings.MAX_OVERLAP_LINES - TurnSettings.MIN_OVERLAP_LINES - 1,
                        enabled = turn.edgeTaps && turn.action == TurnAction.SCREEN,
                        modifier = Modifier.testTag(UiTags.SETTINGS_READING_OVERLAP)
                    )
                    ReadingToggle(
                        label = "Animate page turns",
                        description = if (reduceMotion) {
                            "Off while Reduce motion is on; Android's \"Remove animations\" " +
                                "setting also wins over this switch."
                        } else {
                            "Android's \"Remove animations\" accessibility setting " +
                                "always wins over this switch."
                        },
                        checked = turn.animation == TurnAnimation.ANIMATED,
                        enabled = !reduceMotion,
                        tag = UiTags.SETTINGS_READING_ANIMATE,
                        onChange = {
                            updateTurn(
                                turn.copy(
                                    animation = if (it) TurnAnimation.ANIMATED else TurnAnimation.INSTANT
                                )
                            )
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Page theme", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Repaints the page itself, not the app. Paper turns black, text " +
                            "and line art turn a soft off-white, and photos keep their own " +
                            "colours. True black uses #000000 so an AMOLED screen draws " +
                            "no power for the background.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    PageThemeMode.entries.forEach { mode ->
                        ReadingChoice(
                            label = mode.label,
                            description = when (mode) {
                                PageThemeMode.OFF ->
                                    "Show the document exactly as it was made."
                                PageThemeMode.DARK ->
                                    "Dark grey paper (#121212) that is easier on the eyes."
                                PageThemeMode.TRUE_BLACK ->
                                    "Pure black paper for AMOLED screens."
                            },
                            selected = pageTheme.mode == mode,
                            tag = UiTags.settingsThemeMode(mode.name),
                            onSelect = {
                                pageTheme = pageTheme.copy(mode = mode)
                                AppSettings.setPageTheme(context, pageTheme)
                            }
                        )
                    }
                    ReadingToggle(
                        label = "Follow the system dark theme",
                        description = "Turn the page theme on in dark mode and off in light " +
                            "mode. A document can still override this.",
                        checked = followSystemTheme,
                        tag = UiTags.SETTINGS_THEME_FOLLOW_SYSTEM,
                        onChange = {
                            followSystemTheme = it
                            AppSettings.setPageThemeFollowsSystem(context, it)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Text("Text and line colour", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ThemeInkPresets.forEach { preset ->
                            val selected = rgbOf(pageTheme.ink) == preset.rgb
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    pageTheme = pageTheme.copy(ink = pdfColorOf(preset.rgb))
                                    AppSettings.setPageTheme(context, pageTheme)
                                },
                                label = { Text(preset.label) },
                                modifier = Modifier.testTag(
                                    UiTags.settingsThemeInk(preset.hex)
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "Photo brightness: ${(pageTheme.dim * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        "Photos and diagrams keep their colours at 100%. Lower this only " +
                            "when a bright picture is uncomfortable at night.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = pageTheme.dim.toFloat(),
                        onValueChange = {
                            pageTheme = pageTheme.copy(dim = it.toDouble())
                            AppSettings.setPageTheme(context, pageTheme)
                        },
                        valueRange = 0.3f..1f,
                        modifier = Modifier.testTag(UiTags.SETTINGS_THEME_DIM)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            // Task T63: E-Ink display, reduce motion and direct sunlight. They
            // are device preferences (one panel, one pair of eyes); a document
            // can override only its E-Ink profile, never the motion rule.
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("E-Ink / reflex display", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Reduces the page for a reflective panel. Pure 1-bit is crisp and " +
                            "ghost-free but can hurt small text, so pick the profile that suits " +
                            "the document. Nothing is written into the PDF.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReadingToggle(
                        label = "E-Ink / high-contrast mode",
                        description = "The viewer's menu has the same switch for a quick toggle.",
                        checked = einkEnabled,
                        tag = UiTags.SETTINGS_EINK_ENABLED,
                        onChange = {
                            einkEnabled = it
                            AppSettings.setEinkEnabled(context, it)
                        }
                    )
                    EinkProfile.entries.forEach { profile ->
                        ReadingChoice(
                            label = profile.label,
                            description = profile.description,
                            selected = eink.profile == profile,
                            enabled = einkEnabled,
                            tag = UiTags.settingsEinkProfile(profile.name),
                            onSelect = { updateEink(eink.copy(profile = profile)) }
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Contrast threshold: ${(eink.threshold * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        "Lower keeps faint detail; higher makes scans solid black.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Slider(
                        value = eink.threshold.toFloat(),
                        onValueChange = { updateEink(eink.copy(threshold = it.toDouble())) },
                        valueRange = EinkSettings.MIN_THRESHOLD.toFloat()..
                            EinkSettings.MAX_THRESHOLD.toFloat(),
                        enabled = einkEnabled && eink.profile != EinkProfile.OFF,
                        modifier = Modifier.testTag(UiTags.SETTINGS_EINK_THRESHOLD)
                    )
                    Text("Picture dithering", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EinkDither.entries.forEach { dither ->
                            FilterChip(
                                selected = eink.dither == dither,
                                enabled = einkEnabled &&
                                    eink.profile == EinkProfile.TEXT_BITONAL,
                                onClick = { updateEink(eink.copy(dither = dither)) },
                                label = { Text(dither.label) },
                                modifier = Modifier.testTag(UiTags.einkDitherChip(dither.name))
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Clear the screen every", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "A black-then-white flash removes the ghost of the previous page. " +
                            "The viewer also has a Clear screen button.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0, 1, 5, 10, 20).forEach { turns ->
                            FilterChip(
                                selected = einkAutoClear == turns,
                                onClick = {
                                    einkAutoClear = turns
                                    AppSettings.setEinkAutoClearTurns(context, turns)
                                },
                                label = { Text(if (turns == 0) "Never" else "$turns") },
                                modifier = Modifier.testTag(UiTags.einkAutoClearChip(turns))
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Motion and sunlight", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Built for a reflective screen: no decorative motion, then a hard " +
                            "light theme for reading outdoors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    ReadingToggle(
                        label = "Reduce motion",
                        description = "No fades, ripples, page transitions or scroll coasting. " +
                            "Turns land on whole pages instantly.",
                        checked = reduceMotion,
                        tag = UiTags.SETTINGS_REDUCE_MOTION,
                        onChange = {
                            reduceMotion = it
                            AppSettings.setReduceMotion(context, it)
                        }
                    )
                    ReadingToggle(
                        label = "Direct sunlight",
                        description = "A white, black-on-white theme with larger text, and full " +
                            "screen brightness while reading.",
                        checked = sunlight,
                        tag = UiTags.SETTINGS_SUNLIGHT,
                        onChange = {
                            sunlight = it
                            AppSettings.setSunlightMode(context, it)
                        }
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Saving", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Annotations, comments, stamps and form values are written back to " +
                            "the original file with an incremental update when the file is " +
                            "writable. A read-only file asks once, then remembers the copy.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    SaveModeOption(
                        label = "Save in place automatically",
                        description = "Recommended. Save shortly after an edit and when leaving the viewer.",
                        selected = autosaveMode == AutosaveMode.IN_PLACE,
                        onSelect = {
                            autosaveMode = AutosaveMode.IN_PLACE
                            AppSettings.setAutosaveMode(context, autosaveMode)
                        }
                    )
                    SaveModeOption(
                        label = "Ask each time",
                        description = "Offer Save in place or Save a copy before every write.",
                        selected = autosaveMode == AutosaveMode.ASK,
                        onSelect = {
                            autosaveMode = AutosaveMode.ASK
                            AppSettings.setAutosaveMode(context, autosaveMode)
                        }
                    )
                    SaveModeOption(
                        label = "Always save a copy",
                        description = "Keep the original untouched; never auto-create one.",
                        selected = autosaveMode == AutosaveMode.COPY,
                        onSelect = {
                            autosaveMode = AutosaveMode.COPY
                            AppSettings.setAutosaveMode(context, autosaveMode)
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Keep previous versions", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Up to three pre-save copies per file, in app-private storage.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = versionHistory,
                            onCheckedChange = {
                                versionHistory = it
                                AppSettings.setVersionHistoryEnabled(context, it)
                                if (!it) {
                                    VersionStore(context, "").clearAll()
                                    versionsCleared = true
                                }
                            }
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            VersionStore(context, "").clearAll()
                            versionsCleared = true
                        }
                    ) {
                        Text(if (versionsCleared) "Versions cleared" else "Clear stored versions")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Annotations", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The Annotations tab lists highlights, notes, ink, stamps and bookmarks " +
                            "of your indexed folders. The index stays on this device and is " +
                            "excluded from backups; your PDFs are not changed by indexing.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                bookmarkOutline = !bookmarkOutline
                                AppSettings.setBookmarkOutlineEnabled(context, bookmarkOutline)
                            }
                            .testTag(UiTags.SETTINGS_ANNOT_BOOKMARKS)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Also write bookmarks into the PDF outline",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "A bookmark is kept in the app; with this on it is also " +
                                    "written to the document so other readers show it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = bookmarkOutline, onCheckedChange = null)
                    }
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                AnnotationRepository.get(context).deleteAll()
                                annotationIndexDeleted = true
                            }
                        },
                        modifier = Modifier.testTag(UiTags.SETTINGS_ANNOT_DELETE)
                    ) {
                        Text(
                            if (annotationIndexDeleted) {
                                "Annotation index deleted"
                            } else {
                                "Delete annotation index"
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Opening PDFs", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "PDFPaper can appear in the system Open with list, but Android " +
                            "chooses the default app. This opens Android's app settings; " +
                            "PDFPaper cannot set itself as the default.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { openDefaultApps(context) },
                        modifier = Modifier.testTag(UiTags.SETTINGS_DEFAULT_APPS)
                    ) {
                        Text("Set PDFPaper as default for PDFs")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Launcher and widget", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Launcher shortcuts and the home-screen widget are visible to " +
                            "anyone who can see this device. They show a generic name unless " +
                            "you allow file names.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showLauncherNames = !showLauncherNames
                                AppSettings.setShowLauncherNames(context, showLauncherNames)
                                LauncherSurfaces.refresh(context)
                            }
                            .testTag(UiTags.SETTINGS_LAUNCHER_NAMES)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Show file names on launcher/widget",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (showLauncherNames) {
                                    "Names are shown. Anyone who sees the screen can read them."
                                } else {
                                    "Names are hidden. Shortcuts say \"Recent PDF 1\" instead " +
                                        "(default)."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = showLauncherNames, onCheckedChange = null)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Document safety", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "The safety check looks for scripts, actions, attachments and " +
                            "misleading links. It is a heuristic check, not antivirus software.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                blockActions = !blockActions
                                AppSettings.setBlockDocumentActions(context, blockActions)
                            }
                            .testTag(UiTags.SETTINGS_SAFETY_BLOCK)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Block document actions and scripts",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                "PDFPaper never runs JavaScript, form submissions, launch " +
                                    "actions or links. ON by default.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = blockActions, onCheckedChange = null)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                safetyBanner = !safetyBanner
                                AppSettings.setShowSafetyBanner(context, safetyBanner)
                            }
                            .testTag(UiTags.SETTINGS_SAFETY_BANNER)
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Show the safety banner",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                if (safetyBanner) {
                                    "A medium or high finding shows a banner over the page."
                                } else {
                                    "Findings stay in the Safety check screen only."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = safetyBanner, onCheckedChange = null)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            SignatureStorageSettings()
            Spacer(Modifier.height(20.dp))
            IndexSettingsCard(indexViewModel)
            Spacer(Modifier.height(20.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Privacy", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Documents, comments and OCR run on this device. OCR and scanning use " +
                            "Google Play services (ML Kit); language models may download on first use.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * Android has no API for an app to become the default PDF handler; only the
 * user can do that. The best an app can do is open the right system screen.
 */
private fun openDefaultApps(context: Context) {
    val defaults = Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val launched = runCatching { context.startActivity(defaults) }.isSuccess
    if (launched) return
    val details = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(details) }
}

@Composable
private fun SaveModeOption(
    label: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** One ink choice offered in the Page theme card (T32b). */
private class ThemeInkPreset(val label: String, val hex: String, val rgb: Int)

/** Soft off-white is the default; pure white and a warm tint are the alternates. */
private val ThemeInkPresets = listOf(
    ThemeInkPreset("Soft white", "E6E6E6", 0xE6E6E6),
    ThemeInkPreset("Pure white", "FFFFFF", 0xFFFFFF),
    ThemeInkPreset("Warm", "F2E8D5", 0xF2E8D5)
)

/** One plain-language line per appearance choice (Settings → Appearance). */
private fun appearanceDescription(choice: AppearanceChoice): String = when (choice) {
    AppearanceChoice.FOLLOW_SYSTEM -> "Use the phone's light or dark setting."
    AppearanceChoice.LIGHT -> "Always light, whatever the system says."
    AppearanceChoice.DARK -> "Always dark, whatever the system says."
    AppearanceChoice.AMOLED_BLACK -> "Pure black surfaces, so an AMOLED screen draws no power."
    AppearanceChoice.CODE_STYLE -> "A calm palette in the colours of a code editor."
}

/** What a Code style palette looks like, in one line. */
private fun paletteDescription(palette: CodePalette): String =
    if (palette.darkBackground) "Dark palette with five accent colours."
    else "Light palette with five accent colours."

@Composable
private fun ReadingToggle(
    label: String,
    description: String,
    checked: Boolean,
    tag: String,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onChange(!checked) }
            .testTag(tag)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun ReadingChoice(
    label: String,
    description: String,
    selected: Boolean,
    tag: String,
    enabled: Boolean = true,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect
            )
            .testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Column(Modifier.padding(start = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LinkBehaviorOption(
    label: String,
    description: String,
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag(tag)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

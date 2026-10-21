package com.custom.astrion.guide

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.tap
import com.custom.astrion.viewmodel.GuidePress

private enum class Mode { HOME, TOUR, PLAY }

/** One walkthrough step: press [keys] (any of them) the [kind] way. */
private data class TourStep(
    val keys: Set<HardwareKey>,
    val kind: PressKind,
    val title: String,
    val prompt: String,
    /** The ask on its own, for the "not that one" nudge. */
    val hint: String,
)

/**
 * The button guide, opened by holding ☰. Three views:
 *  - HOME: the reference. "How do I…" questions first, then every
 *    button's press and hold jobs. Pressing a real button jumps to PLAY to explain it;
 *    Back closes the guide.
 *  - TOUR: a short walkthrough that asks for real presses on the real
 *    buttons (drawn one at a time) and says what each would have done.
 *  - PLAY: press anything, see what it does.
 *
 * Nothing is sent to Home Assistant while the guide is open: InputViewModel
 * routes every key to [press] instead of its binding.
 *
 * NOT a Dialog, for the same reason as IR mode: a Dialog takes key focus off
 * MainActivity and the hardware buttons would stop reaching us.
 */
@Composable
fun GuideOverlay(
    catalog: GuideCatalog,
    pageNames: List<String>,
    press: GuidePress?,
    onClose: () -> Unit,
) {
    var mode by remember { mutableStateOf(Mode.HOME) }
    var stepIndex by remember { mutableIntStateOf(0) }
    // The press the current view is showing; cleared when the view changes.
    var shown by remember { mutableStateOf<GuidePress?>(null) }
    val steps = remember(catalog) { tourSteps(catalog) }

    LaunchedEffect(press?.epoch) {
        // The guide teaches press and hold only, so a double press reads as a press.
        val p = (press ?: return@LaunchedEffect).let { if (it.kind == PressKind.DOUBLE) it.copy(kind = PressKind.TAP) else it }
        when (mode) {
            Mode.HOME -> if (p.key == HardwareKey.BACK && p.kind == PressKind.TAP) onClose() else {
                mode = Mode.PLAY
                shown = p
            }
            Mode.TOUR -> {
                // Back leaves the walkthrough, unless a step is asking for it.
                val step = steps.getOrNull(stepIndex - 1)
                if (p.key == HardwareKey.BACK && p.kind == PressKind.TAP && step?.keys?.contains(HardwareKey.BACK) != true) {
                    mode = Mode.HOME
                    shown = null
                } else {
                    shown = p
                }
            }
            Mode.PLAY -> shown = p
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.pageBg)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}, // nothing underneath should take a tap
            ),
    ) {
        AnimatedContent(targetState = mode, label = "guide") { m ->
            when (m) {
                Mode.HOME -> HomeView(
                    catalog = catalog,
                    onClose = onClose,
                    onTour = { stepIndex = 0; shown = null; mode = Mode.TOUR },
                    onPlay = { shown = null; mode = Mode.PLAY },
                )
                Mode.TOUR -> TourView(
                    catalog = catalog,
                    pageNames = pageNames,
                    steps = steps,
                    index = stepIndex,
                    shown = shown,
                    onNext = { stepIndex++; shown = null },
                    onPlay = { shown = null; mode = Mode.PLAY },
                    onExit = { shown = null; mode = Mode.HOME },
                )
                Mode.PLAY -> PlayView(
                    catalog = catalog,
                    shown = shown,
                    onBack = { shown = null; mode = Mode.HOME },
                )
            }
        }
    }
}

// ---- HOME -------------------------------------------------------------------

@Composable
private fun HomeView(
    catalog: GuideCatalog,
    onClose: () -> Unit,
    onTour: () -> Unit,
    onPlay: () -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    // One how-to open at a time, so the list stays a list of questions.
    var openTip by remember { mutableIntStateOf(-1) }
    Column(Modifier.fillMaxSize()) {
        Header("Guide", "How this remote works", onClose)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The way in for someone new: the walkthrough, up front.
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(AstrionTheme.colors.accentContainer)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "New here?",
                    color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.title,
                    fontFamily = AstrionTheme.headingFont, fontWeight = FontWeight.Bold,
                )
                Text(
                    "Two minutes on the real buttons. Nothing you press actually happens, so press away.",
                    color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.size.small,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton("Start walkthrough", Modifier.weight(1.3f), onTour)
                    SecondaryButton("Try a button", Modifier.weight(1f), onPlay)
                }
            }

            Segmented(listOf("How do I\u2026", "Every button"), tab) { tab = it }

            if (tab == 0) {
                catalog.tips.forEachIndexed { i, tip ->
                    TipCard(tip, open = i == openTip, onToggle = { openTip = if (openTip == i) -1 else i }) {
                        if (tip.icon == "plan" || tip.icon == "map") catalog.plan?.let { FloorplanExample(it, Modifier.fillMaxWidth()) }
                        if (tip.icon == "voice" || tip.icon == "mic") VoiceExamples(catalog)
                    }
                }
            } else {
                PressKinds()
                KeyGroup.entries.forEach { group ->
                    val inGroup = catalog.keys.filter { it.group == group && it.shows() }
                    if (inGroup.isEmpty()) return@forEach
                    SectionTitle(group.title)
                    inGroup.forEach { KeyCard(it) }
                }
                Text(
                    "Or press any real button to look it up.",
                    color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.label,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * The guide only teaches press and hold. Double presses still work (and the
 * catalog still knows them); they're just not something a newcomer needs.
 */
private val TAUGHT = listOf(PressKind.TAP, PressKind.HOLD)

private fun KeyHelp.shows() = tap != null || hold != null

/** Press and hold: the vocabulary everything else uses. */
@Composable
private fun PressKinds() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            PressKind.TAP to "Once",
            PressKind.HOLD to "1.5 s, until it ticks",
        ).forEach { (kind, how) ->
            Row(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AstrionTheme.cardBg)
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PressGlyph(kind, AstrionTheme.accent)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        kind.word, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.small,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                    Text(how, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label, maxLines = 2)
                }
            }
        }
    }
}

@Composable
private fun KeyCard(help: KeyHelp, highlight: PressKind? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AstrionTheme.cardBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        KeyCap(help)
        TAUGHT.forEach { kind ->
            val action = help.action(kind) ?: return@forEach
            ActionLine(kind, action, emphasised = kind == highlight)
        }
    }
}

@Composable
private fun ActionLine(kind: PressKind, action: String, emphasised: Boolean = false) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (emphasised) AstrionTheme.colors.accentContainer else Color.Transparent)
            // Same padding either way, so the highlight doesn't nudge the text.
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            PressGlyph(kind, if (emphasised) AstrionTheme.colors.accentBright else AstrionTheme.textMuted)
        }
        Spacer(Modifier.width(6.dp))
        Text(
            kind.word, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label,
            modifier = Modifier.width(78.dp), maxLines = 1,
        )
        Text(
            action, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.small,
            modifier = Modifier.weight(1f),
        )
    }
}

/** A "how do I…" row: the question, and its steps when opened. */
@Composable
private fun TipCard(tip: GuideTip, open: Boolean, onToggle: () -> Unit, extra: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (open) AstrionTheme.cardBg else AstrionTheme.colors.surfaceSunken),
    ) {
        Row(
            Modifier.fillMaxWidth().tap(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(tipIcon(tip.icon), contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                tip.title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.bodyLarge,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Glyphs.up else Glyphs.down,
                contentDescription = if (open) "Close" else "Open",
                tint = AstrionTheme.textMuted, modifier = Modifier.size(22.dp),
            )
        }
        if (open) {
            Column(
                Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tip.steps.forEachIndexed { i, step ->
                    Row {
                        Text(
                            "${i + 1}", color = AstrionTheme.accent, fontSize = AstrionTheme.size.small,
                            fontWeight = FontWeight.Bold, modifier = Modifier.width(20.dp),
                        )
                        Text(step, color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.size.small, modifier = Modifier.weight(1f))
                    }
                }
                extra()
            }
        }
    }
}

private const val VOICE_STEP = "voice"

/** The walkthrough's voice page: how to start talking, then things to say. */
@Composable
private fun VoiceCard(catalog: GuideCatalog) {
    catalog.wakeWord?.let { Body("On the dock, just say “$it”. The remote beeps and lights up, then say what you want.") }
    Body("Anywhere else, press the mic button first.")
    VoiceExamples(catalog)
}

@Composable
private fun VoiceExamples(catalog: GuideCatalog) {
    if (catalog.voiceExamples.isEmpty()) return
    SectionTitle("Try saying")
    // Wrapping chips would need FlowRow; a simple two-column grid reads as well.
    catalog.voiceExamples.chunked(2).forEach { pair ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pair.forEach { phrase ->
                Text(
                    "“$phrase”",
                    color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.small,
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AstrionTheme.raised)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            if (pair.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

// ---- TOUR -------------------------------------------------------------------

private fun tourSteps(catalog: GuideCatalog): List<TourStep> {
    fun has(key: HardwareKey, kind: PressKind) = catalog.forKey(key)?.action(kind) != null
    fun name(key: HardwareKey) = catalog.forKey(key)?.name ?: key.name
    val colours = setOf(HardwareKey.CUSTOM_1, HardwareKey.CUSTOM_2, HardwareKey.CUSTOM_3, HardwareKey.CUSTOM_4)
    val out = mutableListOf<TourStep>()
    // Info only (no keys): the floorplan, before the buttons. Drawn by TourView.
    if (catalog.hasFloorplan) out += TourStep(
        emptySet(), PressKind.TAP, FLOORPLAN_STEP,
        "The map on the Main page is the flat from above, live. Here's what's on it:",
        "",
    )
    if (has(HardwareKey.LIGHT, PressKind.TAP)) out += TourStep(
        setOf(HardwareKey.LIGHT), PressKind.TAP, "Page buttons",
        "The four buttons with pictures jump straight to a page. Press ${name(HardwareKey.LIGHT)}.",
        "Press ${name(HardwareKey.LIGHT)}.",
    )
    if (has(HardwareKey.LIGHT, PressKind.HOLD)) out += TourStep(
        setOf(HardwareKey.LIGHT), PressKind.HOLD, "Hold for a second job",
        "Most buttons do something else when you hold them. Hold ${name(HardwareKey.LIGHT)} until it ticks.",
        "Hold ${name(HardwareKey.LIGHT)}.",
    )
    if (colours.any { has(it, PressKind.TAP) }) out += TourStep(
        colours, PressKind.TAP, "Colour buttons",
        "The four coloured buttons are shortcuts. Press any one.",
        "Press a coloured button.",
    )
    if (has(HardwareKey.VOLUME_UP, PressKind.TAP)) out += TourStep(
        setOf(HardwareKey.VOLUME_UP), PressKind.TAP, "Volume",
        "Volume works from any page. Press Volume +.",
        "Press Volume +.",
    )
    if (catalog.wakeWord != null || catalog.voiceExamples.isNotEmpty()) out += TourStep(
        emptySet(), PressKind.TAP, "Talk to it", VOICE_STEP, "",
    )
    if (has(HardwareKey.MENU, PressKind.HOLD)) out += TourStep(
        setOf(HardwareKey.MENU), PressKind.HOLD, "The one to remember",
        "Hold ${name(HardwareKey.MENU)}. That brings this guide back, any time.",
        "Hold ${name(HardwareKey.MENU)}.",
    )
    return out
}

private const val FLOORPLAN_STEP = "The map"

@Composable
private fun TourView(
    catalog: GuideCatalog,
    pageNames: List<String>,
    steps: List<TourStep>,
    index: Int,
    shown: GuidePress?,
    onNext: () -> Unit,
    onPlay: () -> Unit,
    onExit: () -> Unit,
) {
    // 0 = welcome, 1..n = steps, n+1 = done.
    val total = steps.size + 2
    Column(Modifier.fillMaxSize()) {
        Header("Walkthrough", "Step ${index + 1} of $total", onExit)
        Progress(index, total)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                index == 0 -> {
                    BigTitle("Welcome")
                    Body("This remote runs the house: lights, music, the TV, the aircon and the front door.")
                    Body(
                        "The screen works like a phone. Swipe left or right to change page" +
                            (if (pageNames.isNotEmpty()) " (${pageNames.joinToString(" · ")})" else "") +
                            " and tap things to switch them.",
                    )
                    Body("Now a few buttons. Nothing you press will actually happen.")
                    PrimaryButton("Let's go", Modifier.fillMaxWidth(), onNext)
                }
                index <= steps.size -> {
                    val step = steps[index - 1]
                    BigTitle(step.title)
                    if (step.prompt != VOICE_STEP) step.prompt.split("\n\n").forEach { Body(it) }
                    if (step.title == FLOORPLAN_STEP) catalog.plan?.let { FloorplanExample(it, Modifier.fillMaxWidth()) }
                    if (step.prompt == VOICE_STEP) VoiceCard(catalog)
                    val infoOnly = step.keys.isEmpty()
                    if (!infoOnly) StepKeys(catalog, step.keys)
                    val done = infoOnly || (shown != null && shown.key in step.keys && shown.kind == step.kind)
                    if (infoOnly) {
                        // nothing to press: just read and go on
                    } else if (shown == null) {
                        Waiting(step.kind)
                    } else {
                        Feedback(catalog, shown, correct = done, wanted = step)
                    }
                    if (done) {
                        PrimaryButton("Next", Modifier.fillMaxWidth(), onNext)
                    } else {
                        Text(
                            "Skip this step",
                            color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.small,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .tap(onClick = onNext)
                                .padding(vertical = 10.dp),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> {
                    BigTitle("That's it")
                    Body("Hold ${catalog.forKey(HardwareKey.MENU)?.name ?: "Menu"} any time to open this guide again. It has short how-tos and every button.")
                    Body("Stuck? Back closes whatever is open.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Try any button", Modifier.weight(1f), onPlay)
                        PrimaryButton("Finish", Modifier.weight(1f), onExit)
                    }
                }
            }
        }
    }
}

/**
 * Just the button(s) the step wants, drawn big with their real glyph and
 * name. The colour step shows all four, since any of them will do.
 */
@Composable
private fun StepKeys(catalog: GuideCatalog, keys: Set<HardwareKey>) {
    val many = keys.size > 1
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        keys.sortedBy { it.ordinal }.forEach { key ->
            Column(
                Modifier
                    .then(if (many) Modifier.weight(1f) else Modifier.width(150.dp))
                    .clip(RoundedCornerShape(16.dp))
                    .background(AstrionTheme.colors.void)
                    .padding(vertical = if (many) 12.dp else 18.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KeyIcon(key, if (many) 30.dp else 48.dp, AstrionTheme.textOnControl)
                Text(
                    catalog.forKey(key)?.name ?: key.name,
                    color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.small,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun Waiting(kind: PressKind) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(AstrionTheme.cardBg)
            .padding(vertical = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PressGlyph(kind, AstrionTheme.accent, scale = 1.6f)
        Text("Your turn\u2026", color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.small)
    }
}

/** What the press would have done; on a wrong press, a nudge toward the right one. */
@Composable
private fun Feedback(catalog: GuideCatalog, press: GuidePress, correct: Boolean, wanted: TourStep?) {
    val help = catalog.forKey(press.key)
    val action = help?.action(press.kind)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (correct) AstrionTheme.colors.accentContainer else AstrionTheme.cardBg)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (correct) {
                Box(
                    Modifier.size(24.dp).clip(CircleShape).background(AstrionTheme.accentStrong),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.check, contentDescription = null, tint = AstrionTheme.textPrimary, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                "${press.kind.word} · ${help?.name ?: press.key.name}",
                color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.bodyLarge, fontWeight = FontWeight.SemiBold,
            )
        }
        Text(
            action ?: "That one isn't set up to do anything.",
            color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.title,
            fontFamily = AstrionTheme.headingFont,
        )
        if (!correct && wanted != null) {
            val sameKey = press.key in wanted.keys
            Text(
                if (sameKey) "Close! That was a ${press.kind.word.lowercase()}. Try a ${wanted.kind.word.lowercase()}."
                else "Not that one. ${wanted.hint}",
                color = AstrionTheme.blush, fontSize = AstrionTheme.size.small,
            )
        }
    }
}

// ---- PLAY -------------------------------------------------------------------

@Composable
private fun PlayView(catalog: GuideCatalog, shown: GuidePress?, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Header("Try a button", "Nothing happens while you're in here", onBack)
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (shown == null) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(Icons.Filled.SettingsRemote, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(44.dp))
                    Text(
                        "Press or hold any button",
                        color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.title,
                        fontFamily = AstrionTheme.headingFont, textAlign = TextAlign.Center,
                    )
                    Text(
                        "and this screen says what it does.",
                        color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.small,
                    )
                }
            } else {
                Feedback(catalog, shown, correct = false, wanted = null)
                catalog.forKey(shown.key)?.let { help ->
                    SectionTitle("Everything ${help.name} does")
                    KeyCard(help, highlight = shown.kind)
                }
            }
        }
        Box(Modifier.padding(12.dp)) {
            SecondaryButton("Back to the guide", Modifier.fillMaxWidth(), onBack)
        }
    }
}

// ---- pieces -----------------------------------------------------------------

@Composable
private fun Header(title: String, subtitle: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.displaySm,
                fontFamily = AstrionTheme.headingFont, fontWeight = FontWeight.Bold,
            )
            Text(subtitle, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label)
        }
        HitTarget(
            visual = 38.dp,
            onClick = onClose,
            face = Modifier.clip(CircleShape).background(AstrionTheme.raised),
        ) {
            Icon(Glyphs.close, contentDescription = "Close", tint = AstrionTheme.textPrimary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun Progress(index: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        repeat(total) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= index) AstrionTheme.accent else AstrionTheme.raised),
            )
        }
    }
}

/**
 * The physical button, drawn as a key cap. The colour buttons get their
 * colour: they're the one place the real red/green/blue/yellow matter.
 */
@Composable
private fun KeyCap(help: KeyHelp) {
    Row(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(AstrionTheme.raised)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KeyIcon(help.key, 18.dp)
        Spacer(Modifier.width(7.dp))
        Text(
            help.name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.small,
            fontWeight = FontWeight.SemiBold, letterSpacing = 0.02.em,
        )
    }
}


/** One dot, two dots, or a long bar: press, double press, hold. */
@Composable
private fun PressGlyph(kind: PressKind, colour: Color, scale: Float = 1f) {
    val d = (7 * scale).dp
    Row(horizontalArrangement = Arrangement.spacedBy((4 * scale).dp), verticalAlignment = Alignment.CenterVertically) {
        when (kind) {
            PressKind.TAP -> Box(Modifier.size(d).clip(CircleShape).background(colour))
            PressKind.DOUBLE -> repeat(2) { Box(Modifier.size(d).clip(CircleShape).background(colour)) }
            PressKind.HOLD -> Box(Modifier.width(d * 3).height(d).clip(CircleShape).background(colour))
        }
    }
}

@Composable
private fun Segmented(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AstrionTheme.colors.surfaceSunken)
            .padding(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .height(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) AstrionTheme.accentStrong else Color.Transparent)
                    .tap { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.small, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = AstrionTheme.colors.onSurfaceVariant, fontSize = AstrionTheme.size.label,
        fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.em,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

@Composable
private fun BigTitle(text: String) {
    Text(
        text, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.displayXs,
        fontFamily = AstrionTheme.headingFont, fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun Body(text: String) {
    Text(text, color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.size.body)
}

@Composable
private fun PrimaryButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AstrionTheme.accentStrong)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AstrionTheme.colors.onPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun SecondaryButton(text: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(AstrionTheme.raised)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = AstrionTheme.textOnControl, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

private fun tipIcon(name: String): ImageVector = when (name) {
    "light" -> Glyphs.bulb
    "music" -> Glyphs.note
    "tv" -> Glyphs.tv
    "climate" -> Glyphs.snow
    "door", "lock" -> Glyphs.lock
    "auto", "motion" -> Glyphs.motion
    "voice", "mic" -> Glyphs.mic
    "help" -> Glyphs.help
    "plan", "map" -> Glyphs.plan
    "search" -> Glyphs.search
    "speakers", "zones" -> Glyphs.speaker
    "link" -> Glyphs.link
    "remote", "ir" -> Icons.Filled.SettingsRemote
    "people", "radar" -> Icons.Filled.Radar
    else -> Glyphs.help
}

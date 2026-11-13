package com.alpha.assistant.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.alpha.assistant.R

private const val ONBOARDING_STEPS = 3

/** Example requests for a new user. A tap gives the request text to [pick]. */
@Composable internal fun ExampleRequests(pick: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Glyph.Cart to R.string.desktop_example_list, Glyph.Check to R.string.desktop_example_habits,
            Glyph.Timer to R.string.desktop_example_timer).forEach { (glyph, id) ->
            val text = stringResource(id)
            SuggestionChip(onClick = { pick(text) }, label = { Text(text) },
                icon = { AlphaIcon(glyph, Modifier.size(18.dp), accent = true) })
        }
    }
}

/** The first-run flow: what Alphy does, the DeepSeek key, and a first request. */
@Composable internal fun OnboardingPane(model: ShellViewModel) {
    var step by rememberSaveable { mutableIntStateOf(1) }
    BackHandler(step > 1) { step-- }
    val scroll = rememberScrollState()
    LaunchedEffect(step) { scroll.scrollTo(0) }
    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.onboarding_step, step, ONBOARDING_STEPS),
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            LinearProgressIndicator(progress = { step.toFloat() / ONBOARDING_STEPS }, Modifier.fillMaxWidth())
            when (step) {
                1 -> {
                    WelcomeCard()
                    Feature(Glyph.Search, R.string.onboarding_feature_answers)
                    Feature(Glyph.Grid, R.string.onboarding_feature_apps)
                    Feature(Glyph.Repeat, R.string.onboarding_feature_more)
                    NameSettings(model)
                    Button(onClick = { step = 2 }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_next)) }
                }
                2 -> {
                    StepTitle(R.string.onboarding_key_title)
                    Text(stringResource(R.string.onboarding_key_text), style = MaterialTheme.typography.bodyLarge.merge(RussianHyphenation))
                    DeepSeekKeyPanel(model, scroll)
                    val ready = model.keyStatus == KeyStatus.Ready
                    Button(onClick = { step = 3 }, Modifier.fillMaxWidth(), enabled = ready) { Text(stringResource(R.string.onboarding_next)) }
                    if (!ready) TextButton(onClick = { model.finishOnboarding() }, Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.onboarding_later))
                    }
                }
                else -> {
                    StepTitle(R.string.onboarding_ready_title)
                    Text(stringResource(R.string.onboarding_ready_text), style = MaterialTheme.typography.bodyLarge)
                    ExampleRequests { model.finishOnboarding(it) }
                    Button(onClick = { model.finishOnboarding() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.onboarding_start)) }
                }
            }
        }
    }
}

@Composable private fun WelcomeCard() {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer) {
        // The desktop greeting card uses the same rule for the mascot.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val showMascot = maxWidth >= 320.dp && LocalDensity.current.fontScale < 1.5f
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (showMascot) Image(painterResource(R.drawable.alphy_welcome), contentDescription = null,
                    modifier = Modifier.size(104.dp).clip(MaterialTheme.shapes.large))
                Text(stringResource(R.string.onboarding_welcome_title), Modifier.semantics { heading() },
                    style = MaterialTheme.typography.headlineMedium.merge(RussianHyphenation))
            }
        }
    }
}

@Composable private fun StepTitle(@StringRes title: Int) {
    Text(stringResource(title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineMedium.merge(RussianHyphenation))
}

@Composable private fun Feature(glyph: Glyph, @StringRes text: Int) {
    Row(Modifier.heightIn(min = 40.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        AlphaIcon(glyph, accent = true)
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge.merge(RussianHyphenation))
    }
}

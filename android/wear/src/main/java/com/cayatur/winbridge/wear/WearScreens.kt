package com.cayatur.winbridge.wear

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.cayatur.winbridge.protocol.WearAutomation
import com.cayatur.winbridge.protocol.WearCommands
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Runs an automation from the wrist.
 *
 * Only the ones the PC has already approved arrive here, and one marked as
 * needing confirmation still gets confirmed on the PC. A two-centimetre screen
 * cannot show a command line, so it does not pretend to be the place that
 * decision is made — it is a button, and the PC is the gate.
 */
@Composable
fun WearAutomationsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val automations by WearExtras.automations.collectAsStateWithLifecycle()
    var lastRun by remember { mutableStateOf<String?>(null) }

    Box(Modifier.fillMaxSize()) {
        TimeText()
        ScalingLazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = rememberScalingLazyListState(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    context.getString(R.string.wear_automations),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }

            lastRun?.let { name ->
                item {
                    Text(
                        context.getString(R.string.wear_sent, name),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.primary,
                    )
                }
            }

            if (automations.items.isEmpty()) {
                item {
                    Text(
                        context.getString(R.string.wear_no_automations),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }

            items(automations.items) { automation ->
                AutomationChip(automation) {
                    lastRun = automation.name
                    scope.launch {
                        WearState.send(context, "${WearCommands.AUTOMATION}:${automation.id}")
                    }
                }
            }

            item {
                Chip(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    onClick = onBack,
                    colors = ChipDefaults.secondaryChipColors(),
                    label = { Text(context.getString(R.string.wear_back)) },
                )
            }
        }
    }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.items(
    values: List<WearAutomation>,
    content: @Composable (WearAutomation) -> Unit,
) {
    values.forEach { value -> item { content(value) } }
}

@Composable
private fun AutomationChip(automation: WearAutomation, onRun: () -> Unit) {
    val accent = when (automation.risk) {
        "dangerous" -> Color(0xFF8A1F2A)
        "shell" -> Color(0xFF7A5A14)
        "elevated-input" -> Color(0xFF2A3E6B)
        else -> Color(0xFF25252C)
    }

    Chip(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
        onClick = onRun,
        colors = ChipDefaults.chipColors(backgroundColor = accent),
        label = {
            Text(automation.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
        },
    )
}

/**
 * A trackpad for the PC.
 *
 * Relative movement rather than absolute, because a watch face is a couple of
 * centimetres across and a desktop is not: mapping one onto the other would put
 * every pixel of the screen within a few millimetres of every other. Movement is
 * sent as a fraction of the pad, and the PC scales it.
 */
@Composable
fun WearTrackpadScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                context.getString(R.string.wear_trackpad),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(90.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1D1D22))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            // Sub-pixel jitter would flood the Data Layer with
                            // messages that move the pointer nowhere.
                            if (abs(drag.x) < 1f && abs(drag.y) < 1f) return@detectDragGestures

                            val dx = drag.x / size.width
                            val dy = drag.y / size.height
                            scope.launch {
                                WearState.send(context, "${WearCommands.MOUSE}:move:$dx,$dy")
                            }
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                scope.launch { WearState.send(context, "${WearCommands.MOUSE}:click:left") }
                            },
                            onLongPress = {
                                scope.launch { WearState.send(context, "${WearCommands.MOUSE}:click:right") }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(context.getString(R.string.wear_trackpad_hint), fontSize = 10.sp, textAlign = TextAlign.Center)
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("◀") { scope.launch { WearState.send(context, "${WearCommands.KEY}:left") } }
                SmallChip("▶") { scope.launch { WearState.send(context, "${WearCommands.KEY}:right") } }
                SmallChip("F5") { scope.launch { WearState.send(context, "${WearCommands.KEY}:f5") } }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Alt↹") {
                    scope.launch { WearState.send(context, "${WearCommands.KEY}:tab+alt") }
                }
                SmallChip("Esc") { scope.launch { WearState.send(context, "${WearCommands.KEY}:escape") } }
                SmallChip(context.getString(R.string.wear_back)) { onBack() }
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, onClick: () -> Unit) {
    Chip(
        modifier = Modifier.width(56.dp),
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        label = { Text(label, fontSize = 11.sp, maxLines = 1) },
    )
}

/**
 * Speech on the watch, using the system recogniser.
 *
 * The text is parsed on the phone rather than here: the matcher needs the
 * automation names, the open window titles and the current volume, and none of
 * those live on the watch.
 */
class WearVoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)

        val listen = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        try {
            startActivityForResult(listen, REQUEST)
        } catch (_: Exception) {
            finish()
        }
    }

    @Deprecated("Deprecated in favour of the result APIs, which a plain Activity cannot use here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST) return

        val heard = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (resultCode == RESULT_OK && !heard.isNullOrBlank()) {
            kotlinx.coroutines.runBlocking {
                WearState.send(applicationContext, "${WearCommands.VOICE}:$heard")
            }
        }
        finish()
    }

    private companion object {
        const val REQUEST = 6120
    }
}

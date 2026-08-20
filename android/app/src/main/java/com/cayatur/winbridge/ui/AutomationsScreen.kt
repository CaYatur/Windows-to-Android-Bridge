package com.cayatur.winbridge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cayatur.winbridge.R
import com.cayatur.winbridge.WinBridgeApp
import com.cayatur.winbridge.protocol.AutoDeleteRequest
import com.cayatur.winbridge.protocol.AutoGetRequest
import com.cayatur.winbridge.protocol.AutoListRequest
import com.cayatur.winbridge.protocol.AutoRunRequest
import com.cayatur.winbridge.protocol.AutoSaveRequest
import com.cayatur.winbridge.protocol.AutoStep
import com.cayatur.winbridge.protocol.Automation
import com.cayatur.winbridge.protocol.AutomationSummary
import com.cayatur.winbridge.protocol.StepTypes
import kotlinx.coroutines.launch

/**
 * The automation list and editor.
 *
 * The editor stores the automation on the PC, never here. That is not a
 * simplification — it is what makes approval mean anything. If the phone held
 * the definition and sent it at run time, the bytes a person approved and the
 * bytes that executed would be two different things.
 *
 * Step types the host did not advertise are hidden rather than shown and
 * refused, so a phone that is a version ahead does not offer an editor for
 * something that cannot run.
 */
@Composable
fun AutomationsSection() {
    val app = WinBridgeApp.instance
    val scope = rememberCoroutineScope()

    val catalog by app.state.automations.collectAsStateWithLifecycle()
    val draft by app.state.automationDraft.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Automation?>(null) }
    var lastEvent by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { app.client.sendMessage(AutoListRequest()) }

    LaunchedEffect(Unit) {
        app.state.automationEvents.collect { event ->
            lastEvent = event.message ?: event.phase
        }
    }
    LaunchedEffect(Unit) {
        app.state.automationResults.collect { result ->
            lastEvent = result.error ?: result.output?.take(200) ?: "done"
        }
    }
    LaunchedEffect(Unit) {
        app.state.automationSaves.collect { saved ->
            lastEvent = saved.reason ?: saved.state
            if (saved.state != "invalid") editing = null
        }
    }

    // The host answers auto.get with the full definition; opening the editor is
    // therefore a round trip rather than a local copy that could drift.
    LaunchedEffect(draft) {
        draft?.automation?.let { editing = it }
    }

    val current = editing
    if (current != null) {
        AutomationEditor(
            automation = current,
            allowedTypes = catalog?.stepTypes.orEmpty(),
            shellEnabled = catalog?.shellEnabled == true,
            onChange = { editing = it },
            onCancel = { editing = null },
            onSave = {
                scope.launch { app.client.sendMessage(AutoSaveRequest(automation = it)) }
            },
        )
        return
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.automations_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (catalog?.authoringAllowed == true) {
                FilledTonalButton(onClick = { editing = blankAutomation() }) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.automations_new))
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        if (catalog?.shellEnabled == false) {
            Hint(stringResource(R.string.automations_shell_off))
        }

        lastEvent?.let { Hint(it) }

        val items = catalog?.items.orEmpty()
        if (items.isEmpty()) {
            Hint(stringResource(R.string.automations_empty))
            return@Column
        }

        items.forEach { item ->
            AutomationCard(
                item = item,
                onRun = { scope.launch { app.client.sendMessage(AutoRunRequest(id = item.id)) } },
                onEdit = { scope.launch { app.client.sendMessage(AutoGetRequest(id = item.id)) } },
                onDelete = { scope.launch { app.client.sendMessage(AutoDeleteRequest(id = item.id)) } },
            )
        }
    }
}

@Composable
private fun AutomationCard(
    item: AutomationSummary,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    item.description?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                RiskChip(item.risk)
            }

            if (!item.approved && item.risk != "safe") {
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.automations_pending),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = onRun, enabled = item.enabled) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.automations_run))
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onEdit) { Icon(Icons.Filled.Edit, contentDescription = null) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = null) }
            }
        }
    }
}

@Composable
private fun RiskChip(risk: String) {
    val (colour, label) = when (risk) {
        "dangerous" -> Color(0xFF8A1F2A) to stringResource(R.string.automations_risk_dangerous)
        "shell" -> Color(0xFF7A5A14) to stringResource(R.string.automations_risk_shell)
        "elevated-input" -> Color(0xFF2A3E6B) to stringResource(R.string.automations_risk_input)
        else -> Color(0xFF25252C) to stringResource(R.string.automations_risk_safe)
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(colour)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}

// ---------------------------------------------------------------------------
// Editor
// ---------------------------------------------------------------------------

private fun blankAutomation() = Automation(
    id = "",
    name = "",
    steps = listOf(AutoStep(id = "s0", type = StepTypes.NOTIFY, text = "Hello from my phone")),
)

@Composable
private fun AutomationEditor(
    automation: Automation,
    allowedTypes: List<String>,
    shellEnabled: Boolean,
    onChange: (Automation) -> Unit,
    onCancel: () -> Unit,
    onSave: (Automation) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text("Automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = automation.name,
            onValueChange = { onChange(automation.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = automation.description.orEmpty(),
            onValueChange = { onChange(automation.copy(description = it.ifBlank { null })) },
            label = { Text("Description") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            Checkbox(
                checked = automation.confirmEachRun,
                onCheckedChange = { onChange(automation.copy(confirmEachRun = it)) },
            )
            Text("Ask on the PC before every run")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = automation.requireUnlocked,
                onCheckedChange = { onChange(automation.copy(requireUnlocked = it)) },
            )
            Text("Only when the PC is unlocked")
        }

        Spacer(Modifier.height(14.dp))
        Text("Steps", fontWeight = FontWeight.SemiBold)

        StepList(
            steps = automation.steps,
            allowedTypes = allowedTypes,
            shellEnabled = shellEnabled,
            depth = 0,
            onChange = { onChange(automation.copy(steps = it)) },
        )

        Spacer(Modifier.height(18.dp))
        Row {
            Button(
                onClick = { onSave(automation) },
                enabled = automation.name.isNotBlank() && automation.steps.isNotEmpty(),
            ) { Text("Save to PC") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        }

        Spacer(Modifier.height(10.dp))
        Hint(
            "Saved automations live on the PC. Anything that runs commands or " +
                "controls windows has to be approved there before it will run.",
        )
    }
}

@Composable
private fun StepList(
    steps: List<AutoStep>,
    allowedTypes: List<String>,
    shellEnabled: Boolean,
    depth: Int,
    onChange: (List<AutoStep>) -> Unit,
) {
    Column(Modifier.padding(start = (depth * 10).dp)) {
        steps.forEachIndexed { index, step ->
            StepCard(
                step = step,
                allowedTypes = allowedTypes,
                shellEnabled = shellEnabled,
                depth = depth,
                canMoveUp = index > 0,
                canMoveDown = index < steps.lastIndex,
                onChange = { updated -> onChange(steps.toMutableList().also { it[index] = updated }) },
                onRemove = { onChange(steps.toMutableList().also { it.removeAt(index) }) },
                onMove = { delta ->
                    val target = index + delta
                    if (target in steps.indices) {
                        onChange(
                            steps.toMutableList().also {
                                val moved = it.removeAt(index)
                                it.add(target, moved)
                            },
                        )
                    }
                },
            )
        }

        TextButton(onClick = {
            onChange(steps + AutoStep(id = "s${steps.size}_${depth}", type = StepTypes.NOTIFY))
        }) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text("Add step")
        }
    }
}

@Composable
private fun StepCard(
    step: AutoStep,
    allowedTypes: List<String>,
    shellEnabled: Boolean,
    depth: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onChange: (AutoStep) -> Unit,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    OutlinedCard(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    TextButton(onClick = { expanded = true }) {
                        Text(labelFor(step.type))
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        // Only what the host said it understands, minus shell
                        // when the PC has it turned off — an editor that offers
                        // a step which cannot run is a trap.
                        allowedTypes
                            .filter { shellEnabled || it != StepTypes.SHELL }
                            .forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(labelFor(type)) },
                                    onClick = {
                                        expanded = false
                                        onChange(step.copy(type = type))
                                    },
                                )
                            }
                    }
                }

                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move up")
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move down")
                }
                IconButton(onClick = onRemove) {
                    Icon(Icons.Filled.Close, contentDescription = "Remove")
                }
            }

            StepFields(step, onChange)

            if (step.type in StepTypes.CONTAINERS) {
                if (step.type == StepTypes.IF) {
                    Text("Then", style = MaterialTheme.typography.labelMedium)
                    StepList(step.then, allowedTypes, shellEnabled, depth + 1) {
                        onChange(step.copy(then = it))
                    }
                    Text("Else", style = MaterialTheme.typography.labelMedium)
                    StepList(step.otherwise, allowedTypes, shellEnabled, depth + 1) {
                        onChange(step.copy(otherwise = it))
                    }
                } else {
                    Text("Do", style = MaterialTheme.typography.labelMedium)
                    StepList(step.body2, allowedTypes, shellEnabled, depth + 1) {
                        onChange(step.copy(body2 = it))
                    }
                }
            }
        }
    }
}

/**
 * The fields a given step type actually uses.
 *
 * Driven off the type rather than showing every field on every card: the step
 * model is one flat shape so it survives two JSON stacks, but a card with
 * twenty mostly-irrelevant boxes would be unusable.
 */
@Composable
private fun StepFields(step: AutoStep, onChange: (AutoStep) -> Unit) {
    @Composable
    fun field(label: String, value: String?, onValue: (String) -> Unit, mono: Boolean = false) {
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onValue,
            label = { Text(label) },
            textStyle = if (mono) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
    }

    @Composable
    fun number(label: String, value: Double, onValue: (Double) -> Unit) {
        OutlinedTextField(
            value = if (value == 0.0) "" else value.toLong().toString(),
            onValueChange = { onValue(it.toDoubleOrNull() ?: 0.0) },
            label = { Text(label) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        )
    }

    when (step.type) {
        StepTypes.SHELL -> {
            field("Command", step.command, { onChange(step.copy(command = it)) }, mono = true)
            field("Shell (cmd, powershell, exec)", step.shell, { onChange(step.copy(shell = it)) })
            field("Store output in variable", step.name, { onChange(step.copy(name = it.ifBlank { null })) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = step.onErrorContinue,
                    onCheckedChange = { onChange(step.copy(onErrorContinue = it)) },
                )
                Text("Keep going if it fails")
            }
        }

        StepTypes.OPEN -> field("URL or path", step.target, { onChange(step.copy(target = it)) })

        StepTypes.WINDOW -> {
            field("Window or process", step.target, { onChange(step.copy(target = it)) })
            field("Action (focus, close, minimize, maximize)", step.action, { onChange(step.copy(action = it)) })
        }

        StepTypes.PROCESS -> {
            field("Program", step.target, { onChange(step.copy(target = it)) })
            field("Action (start, kill)", step.action, { onChange(step.copy(action = it)) })
        }

        StepTypes.KEY -> {
            field("Key", step.key, { onChange(step.copy(key = it)) })
            field("Modifiers, comma separated", step.mods.joinToString(","), {
                onChange(step.copy(mods = it.split(',').map(String::trim).filter(String::isNotEmpty)))
            })
        }

        StepTypes.TYPE_TEXT, StepTypes.NOTIFY, StepTypes.LOG,
        StepTypes.PHONE_NOTIFY, StepTypes.PHONE_CLIP,
        -> field("Text", step.text, { onChange(step.copy(text = it)) })

        StepTypes.VOLUME -> {
            field("Action (set, mute, unmute, up, down)", step.action, { onChange(step.copy(action = it)) })
            number("Level", step.number) { onChange(step.copy(number = it)) }
        }

        StepTypes.MEDIA -> field("Action (play, pause, next, prev)", step.action, { onChange(step.copy(action = it)) })

        StepTypes.POWER -> field("Action (lock, sleep, shutdown, restart)", step.action, { onChange(step.copy(action = it)) })

        StepTypes.FILE -> {
            field("Action (read, write, copy, move, delete, exists)", step.action, { onChange(step.copy(action = it)) })
            field("Path", step.path, { onChange(step.copy(path = it)) }, mono = true)
            field("Destination", step.destination, { onChange(step.copy(destination = it)) }, mono = true)
            field("Text (for write)", step.text, { onChange(step.copy(text = it)) })
        }

        StepTypes.HTTP -> {
            field("URL", step.url, { onChange(step.copy(url = it)) }, mono = true)
            field("Method", step.method, { onChange(step.copy(method = it)) })
            field("Body", step.body, { onChange(step.copy(body = it)) }, mono = true)
        }

        StepTypes.DELAY -> number("Milliseconds", step.number) { onChange(step.copy(number = it)) }

        StepTypes.SET -> {
            field("Variable", step.name, { onChange(step.copy(name = it)) })
            field("Expression", step.value, { onChange(step.copy(value = it)) }, mono = true)
        }

        StepTypes.IF, StepTypes.WHILE ->
            field("Condition", step.condition, { onChange(step.copy(condition = it)) }, mono = true)

        StepTypes.REPEAT -> number("Times", step.count.toDouble()) { onChange(step.copy(count = it.toInt())) }

        StepTypes.FOREACH -> {
            field("Items expression", step.items, { onChange(step.copy(items = it)) }, mono = true)
            field("Variable name", step.variable, { onChange(step.copy(variable = it)) })
        }

        StepTypes.PHONE_RING -> number("Seconds", step.number) { onChange(step.copy(number = it)) }

        StepTypes.SCREENSHOT, StepTypes.DESCRIBE -> {
            field("Monitor (blank for primary)", step.target, { onChange(step.copy(target = it)) })
            field("Store in variable", step.name, { onChange(step.copy(name = it.ifBlank { null })) })
        }

        else -> Unit
    }

    if (step.type !in setOf(StepTypes.BREAK, StepTypes.CONTINUE, StepTypes.RETURN)) {
        field("Note", step.note, { onChange(step.copy(note = it.ifBlank { null })) })
    }
}

private fun labelFor(type: String): String = when (type) {
    StepTypes.SHELL -> "Run a command"
    StepTypes.OPEN -> "Open"
    StepTypes.WINDOW -> "Window"
    StepTypes.PROCESS -> "Process"
    StepTypes.KEY -> "Press a key"
    StepTypes.TYPE_TEXT -> "Type text"
    StepTypes.MOUSE -> "Mouse"
    StepTypes.MEDIA -> "Media"
    StepTypes.VOLUME -> "Volume"
    StepTypes.POWER -> "Power"
    StepTypes.CLIP_GET -> "Read the PC clipboard"
    StepTypes.CLIP_SET -> "Set the PC clipboard"
    StepTypes.NOTIFY -> "Show a message on the PC"
    StepTypes.FILE -> "File"
    StepTypes.HTTP -> "HTTP request"
    StepTypes.DELAY -> "Wait"
    StepTypes.SET -> "Set a variable"
    StepTypes.IF -> "If"
    StepTypes.WHILE -> "While"
    StepTypes.REPEAT -> "Repeat"
    StepTypes.FOREACH -> "For each"
    StepTypes.BREAK -> "Break"
    StepTypes.CONTINUE -> "Continue"
    StepTypes.RETURN -> "Stop"
    StepTypes.LOG -> "Log"
    StepTypes.SCREENSHOT -> "Screenshot"
    StepTypes.DESCRIBE -> "Describe the screen"
    StepTypes.PHONE_NOTIFY -> "Notify this phone"
    StepTypes.PHONE_RING -> "Ring this phone"
    StepTypes.PHONE_CLIP -> "Copy to this phone"
    StepTypes.CALL -> "Run another automation"
    else -> type
}

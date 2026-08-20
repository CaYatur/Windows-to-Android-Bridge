package com.cayatur.winbridge.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Everything a step can be. Strings rather than an enum because the host may be
 * a version ahead or behind: an unknown type is refused by name at validation
 * time with something the user can read, instead of deserialising to whatever
 * happens to be zero.
 */
object StepTypes {
    const val SHELL = "shell"
    const val OPEN = "open"
    const val WINDOW = "window"
    const val PROCESS = "process"
    const val KEY = "key"
    const val TYPE_TEXT = "type"
    const val MOUSE = "mouse"
    const val MEDIA = "media"
    const val VOLUME = "volume"
    const val POWER = "power"
    const val CLIP_GET = "clip.get"
    const val CLIP_SET = "clip.set"
    const val NOTIFY = "notify"
    const val FILE = "file"
    const val HTTP = "http"
    const val DELAY = "delay"
    const val SET = "set"
    const val IF = "if"
    const val WHILE = "while"
    const val REPEAT = "repeat"
    const val FOREACH = "foreach"
    const val BREAK = "break"
    const val CONTINUE = "continue"
    const val RETURN = "return"
    const val LOG = "log"
    const val SCREENSHOT = "screenshot"
    const val DESCRIBE = "describe"
    const val PHONE_NOTIFY = "phone.notify"
    const val PHONE_RING = "phone.ring"
    const val PHONE_CLIP = "phone.clip"
    const val CALL = "call"

    /** Step types that nest other steps. The editor renders these as containers. */
    val CONTAINERS = setOf(IF, WHILE, REPEAT, FOREACH)

    /** Steps that can change the machine outside the app. These drive the risk labels. */
    val PRIVILEGED = setOf(SHELL, PROCESS, FILE, HTTP, POWER, CALL)
}

/**
 * One step. A single flat shape with mostly-null fields rather than a
 * polymorphic hierarchy: it survives round-tripping through two JSON stacks and
 * an editor on a phone without either side needing a type registry, and the
 * validator decides which fields a given type actually requires.
 */
@Serializable
data class AutoStep(
    val id: String = "",
    val type: String = "",
    val note: String? = null,
    val enabled: Boolean = true,
    val onErrorContinue: Boolean = false,

    // shell / process
    val shell: String? = null,
    val command: String? = null,
    val args: List<String> = emptyList(),
    @SerialName("cwd") val workingDirectory: String? = null,
    val timeoutMs: Int = 30_000,
    val elevated: Boolean = false,
    val hidden: Boolean = true,
    val capture: Boolean = true,

    // generic operands
    val action: String? = null,
    val target: String? = null,
    val text: String? = null,
    val value: String? = null,
    val name: String? = null,
    val path: String? = null,
    @SerialName("dest") val destination: String? = null,
    val key: String? = null,
    val mods: List<String> = emptyList(),
    val number: Double = 0.0,
    val url: String? = null,
    val method: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,

    // control flow
    @SerialName("cond") val condition: String? = null,
    val then: List<AutoStep> = emptyList(),
    @SerialName("else") val otherwise: List<AutoStep> = emptyList(),
    @SerialName("do") val body2: List<AutoStep> = emptyList(),
    val count: Int = 0,
    val items: String? = null,
    @SerialName("var") val variable: String? = null,
)

/**
 * A saved automation. [bodyHash] is what approval is bound to, so editing
 * anything that can execute revokes the approval automatically rather than
 * relying on the editor to remember to ask again.
 */
@Serializable
data class Automation(
    val id: String = "",
    val name: String = "",
    @SerialName("desc") val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val enabled: Boolean = true,
    val steps: List<AutoStep> = emptyList(),
    @SerialName("vars") val variables: Map<String, String> = emptyMap(),
    @SerialName("confirm") val confirmEachRun: Boolean = false,
    val requireUnlocked: Boolean = true,
    val createdBy: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val approved: Boolean = false,
    val bodyHash: String? = null,
    val risk: String? = null,
    val shortcut: String? = null,
)

@Serializable
data class AutomationSummary(
    val id: String = "",
    val name: String = "",
    @SerialName("desc") val description: String? = null,
    val icon: String? = null,
    val color: String? = null,
    val enabled: Boolean = true,
    val approved: Boolean = false,
    @SerialName("confirm") val confirmEachRun: Boolean = false,
    @SerialName("steps") val stepCount: Int = 0,
    val risk: String = "safe",
    val updatedAt: String? = null,
)

@Serializable
data class AutoListRequest(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_LIST,
)

/**
 * Everything the phone needs to render the automation screen without guessing:
 * what exists, what this host will actually let it do, and which step types it
 * understands. A phone a version ahead hides step types the host does not list
 * rather than offering an editor for something that cannot run.
 */
@Serializable
data class AutoCatalog(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_CATALOG,
    val items: List<AutomationSummary> = emptyList(),
    val stepTypes: List<String> = emptyList(),
    val shellEnabled: Boolean = false,
    val trustMode: String = "strict",
    val deviceTrusted: Boolean = false,
    val authoringAllowed: Boolean = false,
    val allowlist: List<String> = emptyList(),
    val functions: List<String> = emptyList(),
    val variables: List<String> = emptyList(),
)

@Serializable
data class AutoGetRequest(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_GET,
    val id: String = "",
)

@Serializable
data class AutoDefinition(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_DEF,
    val automation: Automation? = null,
    val error: String? = null,
)

@Serializable
data class AutoSaveRequest(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_SAVE,
    val automation: Automation? = null,
)

@Serializable
data class AutoSaved(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_SAVED,
    val id: String = "",
    val state: String = "saved",
    val reason: String? = null,
    val summary: AutomationSummary? = null,
)

@Serializable
data class AutoDeleteRequest(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_DELETE,
    val id: String = "",
)

@Serializable
data class AutoRunRequest(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_RUN,
    val id: String = "",
    val args: Map<String, String> = emptyMap(),
    val dryRun: Boolean = false,
)

@Serializable
data class AutoEvent(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_EVENT,
    val runId: String = "",
    @SerialName("id") val automationId: String = "",
    val phase: String = "",
    val stepIndex: Int = -1,
    val stepId: String? = null,
    val stepType: String? = null,
    val level: String = "info",
    val message: String? = null,
    val at: Long = 0,
)

@Serializable
data class AutoResult(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_RESULT,
    val runId: String = "",
    @SerialName("id") val automationId: String = "",
    val ok: Boolean = false,
    val error: String? = null,
    val output: String? = null,
    @SerialName("steps") val stepsRun: Int = 0,
    val durationMs: Long = 0,
    @SerialName("vars") val variables: Map<String, String> = emptyMap(),
)

@Serializable
data class AutoCancelRequest(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_CANCEL,
    val runId: String = "",
)

@Serializable
data class AutoLogEntry(
    val at: String? = null,
    @SerialName("id") val automationId: String = "",
    val name: String = "",
    val device: String = "",
    val ok: Boolean = false,
    val durationMs: Long = 0,
    val detail: String? = null,
)

@Serializable
data class AutoLog(
    @SerialName("t") val type: String = MessageTypesV2.AUTO_LOG,
    val items: List<AutoLogEntry> = emptyList(),
)

package com.example.supportrouter.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.supportrouter.ApiException
import com.example.supportrouter.Incident
import com.example.supportrouter.ServiceStatus
import com.example.supportrouter.SupportApi
import com.example.supportrouter.UIOption
import com.example.supportrouter.journey.DeviceContext
import com.example.supportrouter.journey.Guidance
import com.example.supportrouter.journey.LoginIssue
import com.example.supportrouter.journey.LoginScenario
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** LOGIN = unauthenticated login-support journey; GENERAL = opened from the Call us page. */
enum class AskMode { LOGIN, GENERAL }

/** Who provided a summary value (shown as a tag, as in the mockup). */
enum class Source(val label: String) { YOU("You provided"), SYSTEM("System detected"), SUGGESTED("Suggested") }

data class SummaryField(val key: String, val label: String, val value: String, val source: Source)

/** Everything that can appear in the Ask Lloyds conversation. */
sealed interface ChatItem {
    val id: Long

    data class Bot(override val id: Long, val text: String) : ChatItem
    data class User(override val id: Long, val text: String) : ChatItem
    data class Status(override val id: Long, val status: ServiceStatus?, val loading: Boolean) : ChatItem
    data class IssuePicker(override val id: Long, val selected: String?, val active: Boolean) : ChatItem
    data class Suggestion(override val id: Long, val issue: LoginIssue, val index: Int, val active: Boolean) : ChatItem
    data class Outcome(override val id: Long, val answer: Boolean?) : ChatItem
    data class Options(override val id: Long, val options: List<UIOption>, val active: Boolean) : ChatItem
    data class Summary(
        override val id: Long,
        val fields: List<SummaryField>,
        val submitting: Boolean = false,
        val error: String? = null,
    ) : ChatItem
    data class Resolved(override val id: Long) : ChatItem
}

/**
 * Drives the Ask Lloyds conversation (no ViewModel: the Activity handles
 * config changes itself, so a remembered plain class survives rotation).
 *
 * Guided steps come from the hard-coded [Guidance]. Free text goes to the
 * Support Router (/v1/chat). Service status and incidents use the mock
 * ServiceNow endpoints.
 */
class AskLloydsController(
    private val api: SupportApi,
    private val scope: CoroutineScope,
    val mode: AskMode,
    private val scenario: LoginScenario?,
    private val login: LoginState,
    val device: DeviceContext,
    private val onIncidentRaised: (Incident) -> Unit,
) {
    val items = mutableStateListOf<ChatItem>()
    var busy by mutableStateOf(false)
        private set
    /** Incremented to ask the screen to focus the text input. */
    var focusInputRequests by mutableIntStateOf(0)
        private set
    /** Title + message for an informational dialog. */
    var info by mutableStateOf<Pair<String, String>?>(null)

    private var seq = 0L
    private var status: ServiceStatus? = null
    private var issue: LoginIssue? = null
    private val stepsTried = mutableListOf<String>()
    private var lastFreeText: String? = null

    val loginAttempts get() = login.attempts

    private fun nextId() = ++seq
    private fun bot(text: String) = items.add(ChatItem.Bot(nextId(), text))
    private fun user(text: String) = items.add(ChatItem.User(nextId(), text))

    private inline fun <reified T : ChatItem> update(id: Long, change: (T) -> T) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) (items[i] as? T)?.let { items[i] = change(it) }
    }

    // ------------------------------------------------------------------------
    // Start
    // ------------------------------------------------------------------------

    fun start() {
        if (items.isNotEmpty()) return
        if (mode == AskMode.LOGIN) {
            bot("I can help you get back into your account. I've checked a few things already.")
        } else {
            bot("Hi, I'm Ask Lloyds. I can help with your accounts, cards, payments and the app. What can I help you with?")
        }
        val statusId = nextId()
        items.add(ChatItem.Status(statusId, null, loading = true))
        scope.launch {
            // Each Ask Lloyds conversation is its own server session, so an
            // old chat's context never leaks into this one.
            withContext(Dispatchers.IO) { api.startNewConversation() }
            val s = runCatching { withContext(Dispatchers.IO) { api.serviceStatus() } }.getOrNull()
            status = s
            update<ChatItem.Status>(statusId) { it.copy(status = s, loading = false) }
            if (mode == AskMode.LOGIN) {
                if (s?.affectsLogin == true) {
                    bot("There's a known issue that may be affecting logins. You can still try the steps below, or check back later.")
                }
                bot("What seems to be the issue?")
                items.add(ChatItem.IssuePicker(nextId(), selected = null, active = true))
            } else {
                focusInputRequests++
            }
        }
    }

    fun restart() {
        items.clear()
        issue = null
        stepsTried.clear()
        lastFreeText = null
        start()
    }

    // ------------------------------------------------------------------------
    // Guided flow (hard-coded guidance)
    // ------------------------------------------------------------------------

    fun chooseIssue(pickerId: Long, chosen: LoginIssue) {
        update<ChatItem.IssuePicker>(pickerId) { it.copy(selected = chosen.id, active = false) }
        user(chosen.label)
        issue = chosen
        if (chosen.steps.isEmpty()) {
            bot("No problem. Tell me in your own words what's happening and I'll do my best to help.")
            focusInputRequests++
        } else {
            items.add(ChatItem.Suggestion(nextId(), chosen, 0, active = true))
        }
    }

    fun triedIt(item: ChatItem.Suggestion) {
        update<ChatItem.Suggestion>(item.id) { it.copy(active = false) }
        stepsTried += item.issue.steps[item.index].title
        user("I've tried this")
        bot("Did it work?")
        items.add(ChatItem.Outcome(nextId(), answer = null))
    }

    fun differentOption(item: ChatItem.Suggestion) {
        update<ChatItem.Suggestion>(item.id) { it.copy(active = false) }
        user("Choose a different option")
        val next = item.index + 1
        if (next < item.issue.steps.size) {
            items.add(ChatItem.Suggestion(nextId(), item.issue, next, active = true))
        } else {
            bot("That's all the suggestions I have for this issue.")
            prepareSummary(apology = false)
        }
    }

    fun answerOutcome(item: ChatItem.Outcome, worked: Boolean) {
        update<ChatItem.Outcome>(item.id) { it.copy(answer = worked) }
        if (worked) {
            user("Yes - I can now log in")
            bot("Great! You can continue using the app.")
            items.add(ChatItem.Resolved(nextId()))
        } else {
            user("No - I still need help")
            prepareSummary(apology = true)
        }
    }

    // ------------------------------------------------------------------------
    // Free text -> Support Router
    // ------------------------------------------------------------------------

    fun sendText(raw: String) {
        val text = raw.trim()
        if (text.isEmpty() || busy) return
        deactivateOptions()
        user(text)
        lastFreeText = text
        busy = true
        scope.launch {
            try {
                val res = withContext(Dispatchers.IO) { api.chatWithRecovery(text) }
                bot(res.botReply)
                items.add(ChatItem.Options(nextId(), res.options, active = true))
            } catch (e: ApiException) {
                bot(
                    when (e.status) {
                        0 -> "Sorry, I can't reach Ask Lloyds right now. Check your connection, or speak to a colleague."
                        401 -> "Ask Lloyds isn't configured correctly (invalid app key)."
                        429 -> "You're sending messages quickly. Please wait a moment and try again."
                        else -> "Sorry, something went wrong. You can try again or speak to a colleague."
                    }
                )
                items.add(ChatItem.Options(nextId(), emptyList(), active = true))
            } finally {
                busy = false
            }
        }
    }

    /** Buttons returned by /v1/chat (see docs/MOBILE_INTEGRATION.md §6). */
    fun tapOption(item: ChatItem.Options, opt: UIOption, dial: () -> Unit) {
        if (!item.active || busy) return
        when {
            opt.id == "clarify.other" -> focusInputRequests++
            opt.id.startsWith("clarify.") -> sendText(opt.label)
            opt.id == "handoff.human" || PERSON_SUFFIXES.any { opt.id.endsWith(it) } -> dial()
            opt.id == "unknown.start_over" -> restart()
            else -> info = opt.label to "In the full app this button opens the screen mapped to:\n\n${opt.id}"
        }
    }

    private fun deactivateOptions() {
        items.indices.forEach { i ->
            (items[i] as? ChatItem.Options)?.takeIf { it.active }?.let { items[i] = it.copy(active = false) }
        }
    }

    // ------------------------------------------------------------------------
    // Support summary -> incident
    // ------------------------------------------------------------------------

    fun prepareSummary(apology: Boolean = true) {
        if (items.any { it is ChatItem.Summary }) return
        deactivateOptions()
        bot(
            (if (apology) "I'm sorry that didn't resolve it. " else "") +
                "Let's raise a support request so our team can help. I've prepared a summary with the details you've already provided."
        )
        items.add(ChatItem.Summary(nextId(), buildSummary()))
    }

    private fun buildSummary(): List<SummaryField> = buildList {
        val issueText = when {
            issue != null && issue != Guidance.SOMETHING_ELSE -> issue!!.label
            lastFreeText != null -> lastFreeText!!
            scenario != null -> scenario.errorTitle
            else -> "Unable to log in"
        }
        add(SummaryField("issue", "Issue", issueText, Source.YOU))
        val stage = issue?.journeyStage ?: scenario?.journeyStage
            ?: if (mode == AskMode.LOGIN) "Login" else "Ask Lloyds"
        add(SummaryField("journey_stage", "Journey stage", stage, Source.SYSTEM))
        scenario?.referenceCode?.takeIf { it.isNotBlank() }?.let {
            add(SummaryField("error_code", "Error code", it, Source.SYSTEM))
        }
        add(SummaryField("steps_tried", "Action tried", stepsTried.joinToString("; ").ifEmpty { "None yet" }, Source.YOU))
        add(SummaryField("app_version", "App version", device.appVersion, Source.SYSTEM))
        add(SummaryField("device", "Device", device.device, Source.SYSTEM))
        add(SummaryField("network", "Network", device.network, Source.SYSTEM))
        add(SummaryField("attempts", "Login attempts", login.attempts.toString(), Source.SYSTEM))
        add(SummaryField("service_status", "Service status", statusText(), Source.SYSTEM))
        issue?.suggestedCause?.let { add(SummaryField("suggested_cause", "Suggested cause", it, Source.SUGGESTED)) }
        login.userId.trim().takeIf { it.isNotEmpty() }?.let {
            add(SummaryField("customer_reference", "User ID (not verified)", it, Source.YOU))
        }
    }

    private fun statusText() = when {
        status == null -> "Not checked"
        status!!.isKnownIssue -> "Known issue: ${status!!.message}"
        else -> "No known outage"
    }

    fun raiseIncident(summaryId: Long, fields: List<SummaryField>, message: String) {
        update<ChatItem.Summary>(summaryId) { it.copy(submitting = true, error = null) }
        val v = fields.associate { it.key to it.value.trim() }
        val body = JSONObject().apply {
            put("issue", v["issue"].orEmpty().ifBlank { "Unable to log in" }.take(200))
            v["journey_stage"]?.let { put("journey_stage", it.take(100)) }
            v["error_code"]?.let { put("error_code", it.take(40)) }
            put("steps_tried", JSONArray(
                v["steps_tried"].orEmpty().split(";").map { it.trim().take(200) }
                    .filter { it.isNotEmpty() && it != "None yet" }.take(10)
            ))
            put("context", JSONObject().apply {
                v["app_version"]?.let { put("app_version", it.take(40)) }
                v["device"]?.let { put("device", it.take(120)) }
                v["network"]?.let { put("network", it.take(60)) }
                v["attempts"]?.toIntOrNull()?.let { put("attempts", it) }
            })
            v["service_status"]?.let { put("service_status", it.take(100)) }
            v["suggested_cause"]?.let { put("suggested_cause", it.take(200)) }
            v["customer_reference"]?.let { put("customer_reference", it.take(64)) }
            message.trim().takeIf { it.isNotEmpty() }?.let { put("message", it.take(1000)) }
            put("confirmed", true)
        }
        scope.launch {
            try {
                val incident = withContext(Dispatchers.IO) { api.createIncident(body) }
                update<ChatItem.Summary>(summaryId) { it.copy(submitting = false) }
                onIncidentRaised(incident)
            } catch (e: ApiException) {
                update<ChatItem.Summary>(summaryId) {
                    it.copy(submitting = false, error = "Couldn't raise the incident (${e.message}). Please try again.")
                }
            }
        }
    }

    companion object {
        private val PERSON_SUFFIXES = listOf(".human", "_agent", ".technician", ".fraud_team")
    }
}

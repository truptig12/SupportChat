package com.example.supportrouter.journey

/*
 * Journey content for the unauthenticated login-support flow.
 *
 * ⚠️ PLACEHOLDER COPY. The issue list, guidance steps, error codes and phone
 * numbers below are demo content written for this prototype. Replace them
 * with approved wording / real numbers before any customer sees them.
 */

// ---------------------------------------------------------------------------
// Login outcomes (debug "scenario picker" on the Login screen)
// ---------------------------------------------------------------------------

enum class LoginScenario(
    val pickerLabel: String,
    val errorTitle: String,
    val errorBody: String,
    val referenceCode: String,
    val journeyStage: String,
) {
    TECHNICAL_ERROR(
        pickerLabel = "Technical error",
        errorTitle = "Technical error occurred",
        errorBody = "We couldn't log you in right now. You can try again or get help.",
        referenceCode = "TE-1001",
        journeyStage = "Login - technical error",
    ),
    OTP_NOT_RECEIVED(
        pickerLabel = "OTP not received",
        errorTitle = "Your security code has not arrived yet",
        errorBody = "We sent a security code to your phone but it hasn't been entered yet. You can try again or get help.",
        referenceCode = "OTP-2001",
        journeyStage = "Login - OTP verification",
    ),
    ACCOUNT_LOCKED(
        pickerLabel = "Account locked",
        errorTitle = "Your account is locked",
        errorBody = "For your security we've locked your account after several unsuccessful attempts. You can get help to unlock it.",
        referenceCode = "LCK-3001",
        journeyStage = "Login - account locked",
    ),
    SUCCESS(
        pickerLabel = "Success",
        errorTitle = "",
        errorBody = "",
        referenceCode = "",
        journeyStage = "Login",
    ),
}

// ---------------------------------------------------------------------------
// "What seems to be the issue?" choices + guided next steps
// ---------------------------------------------------------------------------

data class GuidanceStep(
    val title: String,
    val body: String,
    val why: String,
    val icon: StepIcon,
)

enum class StepIcon { CLOCK, PHONE, LOCK, WIFI, KEY }

data class LoginIssue(
    val id: String,
    val label: String,
    val journeyStage: String,
    /** Shown in the support summary as the suggested cause. Rule-based, not a diagnosis. */
    val suggestedCause: String?,
    val steps: List<GuidanceStep>,
)

object Guidance {
    val OTP = LoginIssue(
        id = "otp_not_received",
        label = "I haven't received my security code (OTP)",
        journeyStage = "Login - OTP verification",
        suggestedCause = "Possible OTP delivery issue",
        steps = listOf(
            GuidanceStep(
                "Wait 2 minutes before requesting another code",
                "Codes can be delayed. Please wait 2 minutes and check your messages, including spam.",
                "Security codes are occasionally delayed by mobile networks. Requesting several codes " +
                    "quickly can make the earlier ones invalid.",
                StepIcon.CLOCK,
            ),
            GuidanceStep(
                "Check your phone can receive messages",
                "Make sure you have signal, flight mode is off and your message inbox isn't full.",
                "Codes are sent by text message, so they can't arrive without mobile signal or inbox space.",
                StepIcon.PHONE,
            ),
        ),
    )

    val FORGOTTEN = LoginIssue(
        id = "forgotten_details",
        label = "I've forgotten my details",
        journeyStage = "Login - credentials",
        suggestedCause = "Forgotten user ID or password",
        steps = listOf(
            GuidanceStep(
                "Reset your password",
                "Use 'Forgotten your password?' on the login screen to set a new one.",
                "If you've forgotten or think your password is incorrect, resetting it is the quickest way back in.",
                StepIcon.KEY,
            ),
            GuidanceStep(
                "Recover your user ID",
                "Use 'Forgotten your user ID?' on the login screen to get a reminder.",
                "Your user ID is different from your password, and it can be recovered on its own.",
                StepIcon.KEY,
            ),
        ),
    )

    val LOCKED = LoginIssue(
        id = "account_locked",
        label = "My account is locked",
        journeyStage = "Login - account locked",
        suggestedCause = "Account locked after unsuccessful attempts",
        steps = listOf(
            GuidanceStep(
                "Reset your password to unlock",
                "Resetting your password from the login screen can unlock your account.",
                "Accounts are locked to protect you after several unsuccessful attempts.",
                StepIcon.LOCK,
            ),
        ),
    )

    val TECHNICAL = LoginIssue(
        id = "technical_error",
        label = "I'm seeing a technical error",
        journeyStage = "Login - technical error",
        suggestedCause = "Possible app or connection issue",
        steps = listOf(
            GuidanceStep(
                "Check your device",
                "Make sure your app is up to date, then close and reopen it.",
                "Older app versions can fail to connect after an update to our systems.",
                StepIcon.PHONE,
            ),
            GuidanceStep(
                "Check your connection",
                "Switch between Wi-Fi and mobile data, or turn off your VPN if you're using one.",
                "Some networks and VPNs block secure banking connections.",
                StepIcon.WIFI,
            ),
        ),
    )

    val SOMETHING_ELSE = LoginIssue(
        id = "something_else",
        label = "Something else",
        journeyStage = "Login - other",
        suggestedCause = null,
        steps = emptyList(),
    )

    val all = listOf(OTP, FORGOTTEN, LOCKED, TECHNICAL, SOMETHING_ELSE)
}

// ---------------------------------------------------------------------------
// Phone numbers for the "Call us" page (dialer opens pre-filled, never auto-calls)
// ---------------------------------------------------------------------------

/** ⚠️ PLACEHOLDER NUMBERS: replace every value with the real line. */
object ContactNumbers {
    const val YOUR_ACCOUNTS = "00000000001"
    const val INTERNET_BANKING = "00000000002"
    const val OTHER_QUERY = "00000000003"
    const val SUSPECTED_FRAUD = "00000000004"
    const val LOST_OR_STOLEN_CARDS = "00000000005"
    const val EMERGENCY_CASH_ABROAD = "00000000006"
    const val MEDICAL_ASSISTANCE_ABROAD = "00000000007"
    /** "Speak to a colleague instead" from the login-support journey. */
    const val LOGIN_SUPPORT = INTERNET_BANKING
}

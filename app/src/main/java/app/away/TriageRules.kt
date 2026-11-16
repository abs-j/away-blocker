package app.away

/** On-device urgency rules. Pure Kotlin so they can be unit tested without Android. */
object TriageRules {
    private val lowValueCategories = setOf("promo", "social", "recommendation")
    private val otp = Regex(
        "(?i)\\b(otp|verification code|security code|one[- ]time (pass)?code|passcode|2fa|login code|code)\\b\\D{0,30}\\b\\d{4,8}\\b"
    )
    private val promoWords = Regex("(?i)promo|\\bsale\\b|% off|coupon|discount")

    /**
     * On-device urgency rules, used when no AI key is set (and as a quick path before the AI).
     * Each pattern is a time-sensitive situation; the label becomes the digest reason.
     */
    private val urgentRules = listOf(
        "emergency" to Regex("(?i)\\b(emergency|urgent|asap|ambulance|hospital|accident)\\b"),
        "asks you to call" to Regex("(?i)\\b(call me|call back|ring me|pick up|answer your phone|are you (ok|okay|there))\\b"),
        "needs you now" to Regex("(?i)\\b(right now|come (now|quick|asap)|where are you|need you|help me)\\b"),
        "travel change" to Regex("(?i)\\b(gate (change|changed)|boarding|flight (delayed|cancell?ed)|your (driver|ride|uber) is (here|arriving)|delivery (is )?arriving)\\b"),
        "security alert" to Regex("(?i)\\b(fraud|suspicious|unusual (activity|sign.?in)|declined|security alert|new (sign.?in|login))\\b"),
    )

    /** True when a VIP name appears as a whole word (so "Mom" does not match "moment"). */
    fun vipHit(combined: String, vips: List<String>): Boolean = vips.any { v ->
        v.isNotBlank() &&
            Regex("(?i)(?<![A-Za-z0-9])" + Regex.escape(v) + "(?![A-Za-z0-9])").containsMatchIn(combined)
    }

    /** The on-device verdict, or null when the notification is left to the AI or the default. */
    fun local(title: String, text: String, category: String?, vips: List<String>): Verdict? {
        val combined = "$title $text"
        if (category != null && category in lowValueCategories) return Verdict(false, "social or promo category")
        if (otp.containsMatchIn(combined) && !promoWords.containsMatchIn(combined)) {
            return Verdict(true, "verification code")
        }
        if (vipHit(combined, vips)) return Verdict(true, "VIP contact")
        if (promoWords.containsMatchIn(combined)) return null
        return urgentRules.firstOrNull { it.second.containsMatchIn(combined) }?.let { Verdict(true, it.first) }
    }
}

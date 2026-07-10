package com.amar.vault

import android.content.Intent
import android.net.Uri

object NerActionEngine {

    data class DetectedAction(
        val label: String,
        val value: String,
        val intent: Intent
    )

    private val patterns = mapOf(
        // Phone: handles OCR noise — spaces/dashes within digits, +91 prefix,
        // and also catches numbers that OCR split across lines.
        // Matches: 9468858375, +91 94688 58375, 94688-58375, +919468858375
        // Also catches numbers with leading 0 for landlines: 0291-2512345
        "Phone"   to Regex("""(?<![a-zA-Z0-9])(?:\+91[\s\-]?)?[6-9][\d\s\-]{8,13}(?<!\s)(?![a-zA-Z0-9])"""),
        "URL"     to Regex("""https?://[^\s]+"""),
        "Email"   to Regex("""[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}"""),
        "Flight"  to Regex("""(?<![A-Z0-9])(?:AI|6E|SG|UK|G8|QP|IX|I5|S5|YB|YS|IG|HR|OG|ZO|AA|UA|DL|BA|EK|QR|TK|LH|AF|KL|SQ|CX|MH|TG|NH|JL|OZ|KE|CA|MU|CZ|FM)[0-9]{1,4}(?![A-Z0-9])"""),
        "OrderId" to Regex("""(?:order|booking|ref)[\s#:]+([A-Z0-9\-]{6,20})""", RegexOption.IGNORE_CASE),
        "UPI"     to Regex("""(?:upi://pay\?pa=)?([a-zA-Z0-9.\-_]{2,}\s?@\s?[a-zA-Z0-9]{2,})""", RegexOption.IGNORE_CASE),
        "PAN"     to Regex("""[A-Z]{5}[0-9]{4}[A-Z]"""),
        "Amount"  to Regex("""(?:Rs\.?|₹)\s?[\d,]+(?:\.\d{2})?"""),
    )

    // Pattern to extract QR payloads stored by IndexingPipeline
    private val qrDataPattern = Regex("""qr_data:(\S+)""")

    fun detect(text: String): List<DetectedAction> {
        val actions = mutableListOf<DetectedAction>()

        // ── 1. Standard NER patterns ────────────────────────────────────
        for ((type, regex) in patterns) {
            for (match in regex.findAll(text)) {
                val rawValue = if (match.groupValues.size > 1 && match.groupValues[1].isNotBlank()) {
                    match.groupValues[1]
                } else {
                    match.value
                }

                val cleanValue = rawValue.replace(Regex("\\s"), "")
                if (cleanValue.isBlank()) continue

                // Phone validation: strip to digits, must be 10 or 12 (with 91 prefix)
                if (type == "Phone") {
                    val digitsOnly = cleanValue.replace(Regex("[^0-9]"), "")
                    val normalizedPhone = when {
                        digitsOnly.length == 12 && digitsOnly.startsWith("91") -> "+$digitsOnly"
                        digitsOnly.length == 10 -> "+91$digitsOnly"
                        digitsOnly.length == 11 && digitsOnly.startsWith("0") -> "+91${digitsOnly.drop(1)}"
                        else -> continue // not a valid Indian mobile number
                    }
                    actions.add(DetectedAction("Phone", normalizedPhone, buildIntent("Phone", normalizedPhone)))
                    continue
                }

                actions.add(
                    DetectedAction(
                        label  = type,
                        value  = cleanValue,
                        intent = buildIntent(type, cleanValue)
                    )
                )
            }
        }

        // ── 2. QR code payloads (stored as qr_data:xxx during indexing) ─
        for (match in qrDataPattern.findAll(text)) {
            val payload = match.groupValues[1].trim()
            if (payload.isBlank()) continue

            val qrAction = buildQrAction(payload)
            if (qrAction != null) {
                actions.add(qrAction)
            }
        }

        return actions.distinctBy { it.value }
    }

    /**
     * Builds an action from a decoded QR payload.
     *
     * Handles:
     * - UPI URLs → opens UPI payment app (GPay, PhonePe, Paytm, etc.)
     * - HTTP URLs → opens browser
     * - Plain text → copy action
     */
    private fun buildQrAction(payload: String): DetectedAction? {
        return when {
            // UPI deep link — direct to payment app
            payload.startsWith("upi://", ignoreCase = true) -> {
                // Extract payee name for display
                val uri = Uri.parse(payload)
                val payeeName = uri.getQueryParameter("pn") ?: ""
                val upiId = uri.getQueryParameter("pa") ?: ""
                val amount = uri.getQueryParameter("am") ?: ""

                val displayValue = when {
                    payeeName.isNotBlank() && amount.isNotBlank() -> "$payeeName ₹$amount"
                    payeeName.isNotBlank() -> payeeName
                    upiId.isNotBlank() -> upiId
                    else -> "UPI Payment"
                }

                DetectedAction(
                    label  = "Pay",
                    value  = displayValue,
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }

            // HTTP/HTTPS URL
            payload.startsWith("http://", ignoreCase = true) ||
                    payload.startsWith("https://", ignoreCase = true) -> {
                DetectedAction(
                    label  = "Open",
                    value  = payload.take(30),
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload))
                )
            }

            // WiFi QR (WIFI:T:WPA;S:NetworkName;P:password;;)
            payload.startsWith("WIFI:", ignoreCase = true) -> {
                val ssid = Regex("""S:([^;]+)""").find(payload)?.groupValues?.get(1) ?: "WiFi"
                DetectedAction(
                    label  = "WiFi",
                    value  = ssid,
                    intent = Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                )
            }

            // Phone number in QR
            payload.startsWith("tel:", ignoreCase = true) -> {
                val number = payload.removePrefix("tel:").removePrefix("TEL:")
                DetectedAction(
                    label  = "Call",
                    value  = number,
                    intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))
                )
            }

            // SMS
            payload.startsWith("smsto:", ignoreCase = true) ||
                    payload.startsWith("sms:", ignoreCase = true) -> {
                DetectedAction(
                    label  = "SMS",
                    value  = payload.substringAfter(":").take(15),
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload))
                )
            }

            // Email
            payload.startsWith("mailto:", ignoreCase = true) -> {
                val email = payload.removePrefix("mailto:")
                DetectedAction(
                    label  = "Email",
                    value  = email.take(20),
                    intent = Intent(Intent.ACTION_SENDTO, Uri.parse(payload))
                )
            }

            // Geo location
            payload.startsWith("geo:", ignoreCase = true) -> {
                DetectedAction(
                    label  = "Map",
                    value  = "Location",
                    intent = Intent(Intent.ACTION_VIEW, Uri.parse(payload))
                )
            }

            // Any other text — offer to copy
            payload.length >= 3 -> {
                DetectedAction(
                    label  = "QR",
                    value  = payload.take(20),
                    intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, payload)
                    }
                )
            }

            else -> null
        }
    }

    private fun buildIntent(type: String, value: String): Intent {
        return when (type) {
            "Phone"  -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$value"))
            "URL"    -> Intent(Intent.ACTION_VIEW, Uri.parse(value))
            "Email"  -> Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$value"))
            "Flight" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.flightradar24.com/$value"))
            "UPI"    -> Intent(Intent.ACTION_VIEW, Uri.parse("upi://pay?pa=$value")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            else     -> Intent(Intent.ACTION_SEND).apply {
                this.type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, value)
            }
        }
    }
}
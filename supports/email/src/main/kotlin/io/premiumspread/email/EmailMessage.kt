package io.premiumspread.email

data class EmailMessage(
    val to: String,
    val subject: String,
    val text: String,
    /** Durable delivery 식별자. 지정하면 SMTP Message-ID에 동일한 값이 포함된다. */
    val deliveryId: String? = null,
)

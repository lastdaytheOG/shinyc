package com.amar.vault

data class MetadataTypeConfig(
    val type: String,
    val isHardConstraintable: Boolean, // e.g., AMOUNT, DATE = true
    val isSoftBoostable: Boolean,      // e.g., ORGANIZATION, APP = true
    val confidenceThreshold: Float     // Minimum confidence to apply
)

object MetadataTypeRegistry {
    val DATE = MetadataTypeConfig("DATE", true, false, 0.9f)
    val AMOUNT = MetadataTypeConfig("AMOUNT", true, false, 0.8f)
    val CATEGORY = MetadataTypeConfig("CATEGORY", true, true, 0.9f)
    val ORGANIZATION = MetadataTypeConfig("ORGANIZATION", false, true, 0.7f)
    val SOURCE_APP = MetadataTypeConfig("SOURCE_APP", false, true, 0.8f)
    val PAYMENT_APP = MetadataTypeConfig("PAYMENT_APP", false, true, 0.8f)
    val STATUS = MetadataTypeConfig("STATUS", false, true, 0.8f)
    val DOCUMENT_TYPE = MetadataTypeConfig("DOCUMENT_TYPE", false, true, 0.8f)
    val PHONE = MetadataTypeConfig("PHONE", true, true, 0.9f)
    val EMAIL = MetadataTypeConfig("EMAIL", true, true, 0.9f)
    val UPI = MetadataTypeConfig("UPI", true, true, 0.9f)
    
    // Future placeholders
    val ORDER_ID = MetadataTypeConfig("ORDER_ID", true, true, 0.9f)
    val PNR = MetadataTypeConfig("PNR", true, true, 0.9f)
    val FLIGHT = MetadataTypeConfig("FLIGHT", true, true, 0.9f)
    val INVOICE_NUMBER = MetadataTypeConfig("INVOICE_NUMBER", true, true, 0.9f)
    val BANK_ACCOUNT = MetadataTypeConfig("BANK_ACCOUNT", true, true, 0.9f)
    val LOCATION = MetadataTypeConfig("LOCATION", false, true, 0.7f)

    val allConfigs = listOf(
        DATE, AMOUNT, CATEGORY, ORGANIZATION, SOURCE_APP, PAYMENT_APP, STATUS, DOCUMENT_TYPE,
        PHONE, EMAIL, UPI, ORDER_ID, PNR, FLIGHT, INVOICE_NUMBER, BANK_ACCOUNT, LOCATION
    )

    fun getConfig(type: String): MetadataTypeConfig? = allConfigs.find { it.type == type }
}

package io.kmpkt.example.scoped.domain.model

/** Catalog item synchronized from a store's upstream ERP. */
data class Product(
    val sku: String,
    val name: String,
    val priceCents: Long,
)

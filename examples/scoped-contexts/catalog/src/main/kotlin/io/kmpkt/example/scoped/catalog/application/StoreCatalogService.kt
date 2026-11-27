package io.kmpkt.example.scoped.catalog.application

import io.kmpkt.example.scoped.domain.model.Product

/** Store-scoped read service exposing the synchronized catalog for mobile POS clients. */
class StoreCatalogService(
    private val storage: StoreCatalogStorage,
) {
    fun listProducts(): List<Product> = storage.findAll()
}

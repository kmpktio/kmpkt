package io.kmpkt.example.scoped.catalog.application

import io.kmpkt.example.scoped.domain.model.Product
import io.kmpkt.example.scoped.outbound.filesystem.StoreFileSystem

/** Store-isolated catalog storage persisting serialized catalog snapshots in [StoreFileSystem]. */
class StoreCatalogStorage(
    private val fileSystem: StoreFileSystem,
) {
    fun replaceAll(products: List<Product>) {
        val snapshot = products.joinToString("\n") { "${it.sku}|${it.name}|${it.priceCents}" }
        fileSystem.write("catalog.tsv", snapshot)
    }

    fun findAll(): List<Product> =
        fileSystem
            .read("catalog.tsv")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val (sku, name, price) = line.split("|")
                Product(sku = sku, name = name, priceCents = price.toLong())
            }.sortedBy { it.sku }
            .toList()
}

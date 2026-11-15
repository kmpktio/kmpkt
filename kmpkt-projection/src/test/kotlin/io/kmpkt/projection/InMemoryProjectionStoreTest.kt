package io.kmpkt.projection

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

private data class ItemRow(
    val listId: String,
    val name: String,
    val count: Int = 0,
)

private fun store() = InMemoryProjectionStore<ItemRow> { row -> row.listId to row.name }

class InMemoryProjectionStoreTest :
    FunSpec({
        test("a Put under a new key adds a row, under an existing one replaces it") {
            // Arrange
            val store = store()

            // Act
            store.apply(listOf(Put(ItemRow("rewe", "Milch", count = 1))))
            store.apply(listOf(Put(ItemRow("rewe", "Milch", count = 2))))

            // Assert
            store.findAll() shouldContainExactly listOf(ItemRow("rewe", "Milch", count = 2))
        }

        test("rows of different keys live side by side") {
            // Arrange
            val store = store()

            // Act
            store.apply(
                listOf(
                    Put(ItemRow("rewe", "Milch")),
                    Put(ItemRow("rewe", "Brot")),
                    Put(ItemRow("dm", "Milch")),
                ),
            )

            // Assert
            store.findAll() shouldContainExactlyInAnyOrder
                listOf(ItemRow("rewe", "Milch"), ItemRow("rewe", "Brot"), ItemRow("dm", "Milch"))
        }

        test("a partial match selects every row that holds those values") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch")), Put(ItemRow("rewe", "Brot")), Put(ItemRow("dm", "Milch"))))

            // Act + Assert
            store.findAll(match(ItemRow::listId to "rewe")) shouldContainExactlyInAnyOrder
                listOf(ItemRow("rewe", "Milch"), ItemRow("rewe", "Brot"))
        }

        test("an empty match selects everything") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch")), Put(ItemRow("dm", "Milch"))))

            // Act + Assert
            store.findAll(Match()).size shouldBe 2
        }

        test("Remove takes every row the match matches, and leaves the rest") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch")), Put(ItemRow("rewe", "Brot")), Put(ItemRow("dm", "Milch"))))

            // Act
            store.apply(listOf(Remove(match(ItemRow::listId to "rewe"))))

            // Assert
            store.findAll() shouldContainExactly listOf(ItemRow("dm", "Milch"))
        }

        test("changes are applied in order, so Remove then Put is not the reverse") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch", count = 5))))

            // Act - what a "replace this list's rows" projector returns
            store.apply(
                listOf(
                    Remove(match(ItemRow::listId to "rewe")),
                    Put(ItemRow("rewe", "Brot")),
                ),
            )

            // Assert
            store.findAll() shouldContainExactly listOf(ItemRow("rewe", "Brot"))
        }

        test("findOrNull answers the single matching row, or null") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch"))))

            // Act + Assert
            store.findOrNull(match(ItemRow::listId to "rewe", ItemRow::name to "Milch")) shouldBe ItemRow("rewe", "Milch")
            store.findOrNull(match(ItemRow::listId to "dm")).shouldBeNull()
        }

        test("findOrNull refuses to guess when the match is not unique") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch")), Put(ItemRow("rewe", "Brot"))))

            // Act + Assert
            shouldThrow<IllegalArgumentException> { store.findOrNull(match(ItemRow::listId to "rewe")) }
        }

        test("clear discards everything, which costs nothing for something derived") {
            // Arrange
            val store = store()
            store.apply(listOf(Put(ItemRow("rewe", "Milch"))))

            // Act
            store.clear()

            // Assert
            store.findAll().shouldBeEmpty()
        }
    })

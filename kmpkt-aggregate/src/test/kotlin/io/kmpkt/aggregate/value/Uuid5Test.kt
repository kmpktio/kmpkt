package io.kmpkt.aggregate.value

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
private val dnsNamespace = Uuid.parse("6ba7b810-9dad-11d1-80b4-00c04fd430c8")

@OptIn(ExperimentalUuidApi::class)
class Uuid5Test :
    FunSpec({
        test("matches RFC 4122's own example, which is what makes it interoperable") {
            // Arrange + Act - the DNS namespace with "www.example.org" is the published test vector, so this
            // pins the implementation against the spec rather than against itself
            val id = Uuid.v5(dnsNamespace, "www.example.org")

            // Assert
            id.toString() shouldBe "74738ff5-5367-5958-9aee-98fffdcd1876"
        }

        test("the same namespace and name always give the same id") {
            // Act + Assert - the whole point: a stable id derived from an external one
            Uuid.v5(dnsNamespace, "list-42") shouldBe Uuid.v5(dnsNamespace, "list-42")
        }

        test("a different name gives a different id") {
            // Act + Assert
            Uuid.v5(dnsNamespace, "list-42") shouldNotBe Uuid.v5(dnsNamespace, "list-43")
        }

        test("the same name in a different namespace gives a different id") {
            // Arrange - which is why each kind of name wants its own namespace
            val other = Uuid.parse("6ba7b811-9dad-11d1-80b4-00c04fd430c8")

            // Act + Assert
            Uuid.v5(dnsNamespace, "list-42") shouldNotBe Uuid.v5(other, "list-42")
        }

        test("declares version 5 and the RFC variant, so other tools read it as a UUID") {
            // Act
            val id = Uuid.v5(dnsNamespace, "list-42")
            val bytes = id.toByteArray()

            // Assert - version in the high nibble of byte 6, variant in the top bits of byte 8
            (bytes[6].toInt() and 0xf0) shouldBe 0x50
            (bytes[8].toInt() and 0xc0) shouldBe 0x80
        }

        test("an empty name is a name like any other") {
            // Act + Assert
            Uuid.v5(dnsNamespace, "") shouldBe Uuid.v5(dnsNamespace, "")
        }
    })

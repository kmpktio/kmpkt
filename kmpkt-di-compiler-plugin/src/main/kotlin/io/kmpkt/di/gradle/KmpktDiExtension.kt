package io.kmpkt.di.gradle

import org.gradle.api.model.ObjectFactory
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.property

public open class KmpktDiExtension(
    objects: ObjectFactory,
) {
    /** Group coordinate used when resolving `kmpkt-di`, `kmpkt-di-compiler`, and `kmpkt-di-compiler-api` artifacts. */
    public val groupId: Property<String> = objects.property<String>().convention("io.kmpkt")

    /** Version of `kmpkt-di` artifacts to add automatically. */
    public val version: Property<String> = objects.property<String>().convention("0.1.0")
}

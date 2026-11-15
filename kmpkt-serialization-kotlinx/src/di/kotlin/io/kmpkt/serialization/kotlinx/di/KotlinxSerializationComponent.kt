package io.kmpkt.serialization.kotlinx.di

import io.kmpkt.di.compiler.ContainerBuilder
import io.kmpkt.di.compiler.SimpleComponent
import io.kmpkt.serialization.kotlinx.createPersistenceJson

public class KotlinxSerializationComponent : SimpleComponent {
    override fun ContainerBuilder.register() {
        factory(::createPersistenceJson)
    }
}

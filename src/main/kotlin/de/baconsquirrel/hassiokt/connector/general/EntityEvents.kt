package de.baconsquirrel.hassiokt.connector.general

sealed class EntityEvent {
    abstract val id: String
    abstract val event: String
}

data class SimpleEntityEvent(override val id: String, override val event: String) : EntityEvent()

package de.baconsquirrel.hassiokt.connector.general

sealed class EntityState {
    abstract val id: String
    abstract val state: String
}

data class SimpleEntityState(override val id: String, override val state: String) : EntityState()

data class SunEntityState(override val id: String, override val state: String, val elevation: Double) : EntityState() {
    val aboveHorizon: Boolean = this.state.toLowerCase() == "above horizon"
}

package de.baconsquirrel.hassiokt.connector.websocket

import com.beust.klaxon.JsonArray
import com.beust.klaxon.JsonObject
import com.beust.klaxon.json
import de.baconsquirrel.hassiokt.connector.general.BooleanTurnOffMessage
import de.baconsquirrel.hassiokt.connector.general.BooleanTurnOnMessage
import de.baconsquirrel.hassiokt.connector.general.EntityEvent
import de.baconsquirrel.hassiokt.connector.general.EntityState
import de.baconsquirrel.hassiokt.connector.general.LightTurnOffMessage
import de.baconsquirrel.hassiokt.connector.general.LightTurnOnMessage
import de.baconsquirrel.hassiokt.connector.general.MediaPlayerPauseMessage
import de.baconsquirrel.hassiokt.connector.general.MediaPlayerPlayMediaMessage
import de.baconsquirrel.hassiokt.connector.general.MediaPlayerPlayMessage
import de.baconsquirrel.hassiokt.connector.general.MediaPlayerStopMessage
import de.baconsquirrel.hassiokt.connector.general.MediaPlayerTurnOffMessage
import de.baconsquirrel.hassiokt.connector.general.RgbColor
import de.baconsquirrel.hassiokt.connector.general.ServiceMessage
import de.baconsquirrel.hassiokt.connector.general.SimpleEntityEvent
import de.baconsquirrel.hassiokt.connector.general.SimpleEntityState
import de.baconsquirrel.hassiokt.connector.general.SunEntityState
import de.baconsquirrel.hassiokt.connector.general.SwitchTurnOffMessage
import de.baconsquirrel.hassiokt.connector.general.SwitchTurnOnMessage
import de.baconsquirrel.hassiokt.connector.general.TimerCancelMessage
import de.baconsquirrel.hassiokt.connector.general.TimerStartMessage

internal fun JsonArray<JsonObject>.toEntityStateObjects(): List<EntityState> =
    this.mapNotNull { it.toEntityStateObject() }

internal fun JsonObject.toEntityStateObject(): EntityState? {
    val id = this.string("entity_id") ?: return null
    val state = this.string("state") ?: this.obj("new_state")?.string("state") ?: return null
    val attributes = this.obj("attributes") ?: this.obj("new_state")?.obj("attributes")

    return when (id) {
        "sun.sun" -> SunEntityState(id, state, attributes?.double("elevation") ?: return null)
        else -> SimpleEntityState(id, state)
    }
}

internal fun JsonObject.toEntityEventObject(): EntityEvent? {
    return SimpleEntityEvent(
        id = this.string("id") ?: return null,
        event = this.get("event")?.toString() ?: return null
    )
}

//
//

internal fun ServiceMessage.toJsonCommandMessages(): Sequence<JsonObject> {
    val baseObj: JsonObject = json {
        obj("type" to "call_service", "domain" to domain.name.toLowerCase(), "service" to service.name.toLowerCase())
    }

    return when (this) {
        is LightTurnOffMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is LightTurnOnMessage -> this.providePropertyAdditions().map { addition -> baseObj + addition }

        is SwitchTurnOffMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is SwitchTurnOnMessage -> sequenceOf(baseObj + this.providePropertyAddition())

        is BooleanTurnOffMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is BooleanTurnOnMessage -> sequenceOf(baseObj + this.providePropertyAddition())

        is MediaPlayerTurnOffMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is MediaPlayerPlayMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is MediaPlayerPauseMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is MediaPlayerStopMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is MediaPlayerPlayMediaMessage -> sequenceOf(baseObj + this.providePropertyAddition())

        is TimerStartMessage -> sequenceOf(baseObj + this.providePropertyAddition())
        is TimerCancelMessage -> sequenceOf(baseObj + this.providePropertyAddition())
    }
}

private fun LightTurnOffMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun LightTurnOnMessage.providePropertyAdditions(): Sequence<JsonObject> = sequenceOf(
    brightness.let { json { obj("service_data" to obj("entity_id" to entityId, "brightness" to it)) } },
    rgbColor?.let { json { obj("service_data" to obj("entity_id" to entityId, "rgb_color" to it.toJsonArray())) } },
    kelvin?.let { json { obj("service_data" to obj("entity_id" to entityId, "kelvin" to it)) } }
).filterNotNull()

private fun SwitchTurnOffMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun SwitchTurnOnMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }

private fun BooleanTurnOffMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun BooleanTurnOnMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }

private fun MediaPlayerTurnOffMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun MediaPlayerPlayMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun MediaPlayerPauseMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun MediaPlayerStopMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun MediaPlayerPlayMediaMessage.providePropertyAddition(): JsonObject =
    json {
        obj(
            "service_data" to obj(
                "entity_id" to entityId,
                "media_content_id" to contentUrl,
                "media_content_type" to contentType.name.toLowerCase()
            )
        )
    }

private fun TimerStartMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }
private fun TimerCancelMessage.providePropertyAddition(): JsonObject = json { obj("service_data" to obj("entity_id" to entityId)) }

//
//

private fun RgbColor.toJsonArray(): JsonArray<Any?> = json { array(red, green, blue) }
private operator fun JsonObject.plus(other: JsonObject): JsonObject = JsonObject(this.map + other.map)
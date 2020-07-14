package de.baconsquirrel.hassiokt.connector.general

enum class ServiceDomain { LIGHT, SWITCH, INPUT_BOOLEAN, MEDIA_PLAYER, TIMER }
enum class ServiceType { TURN_ON, TURN_OFF, MEDIA_PLAY, MEDIA_PAUSE, MEDIA_STOP, PLAY_MEDIA, START, CANCEL }

sealed class ServiceMessage {
    abstract val domain: ServiceDomain
    abstract val service: ServiceType

    abstract override fun toString(): String
}

//
//

class LightTurnOnMessage(val entityId: String, val brightness: Int = 255, val rgbColor: RgbColor? = null, val kelvin: Int? = null) :
        ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.LIGHT
    override val service: ServiceType =
        ServiceType.TURN_ON

    override fun toString() = "LightTurnOnMessage(entityId=$entityId, brightness=$brightness, rgbColor=$rgbColor, kelvin=$kelvin)"
}

class LightTurnOffMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.LIGHT
    override val service: ServiceType =
        ServiceType.TURN_OFF

    override fun toString() = "LightTurnOffMessage(entityId=$entityId)"
}

//
//

class SwitchTurnOnMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.SWITCH
    override val service: ServiceType =
        ServiceType.TURN_ON

    override fun toString() = "SwitchTurnOnMessage(entityId=$entityId)"
}

class SwitchTurnOffMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.SWITCH
    override val service: ServiceType =
        ServiceType.TURN_OFF

    override fun toString() = "SwitchTurnOffMessage(entityId=$entityId)"
}

//
//

class BooleanTurnOnMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.INPUT_BOOLEAN
    override val service: ServiceType =
        ServiceType.TURN_ON

    override fun toString() = "BooleanTurnOnMessage(entityId=$entityId)"
}

class BooleanTurnOffMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.INPUT_BOOLEAN
    override val service: ServiceType =
        ServiceType.TURN_OFF

    override fun toString() = "BooleanTurnOffMessage(entityId=$entityId)"
}

//
//

enum class MediaContentType { MUSIC }

class MediaPlayerTurnOffMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.MEDIA_PLAYER
    override val service: ServiceType =
        ServiceType.TURN_OFF

    override fun toString() = "MediaPlayerTurnOffMessage(entityId=$entityId)"
}

class MediaPlayerPlayMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.MEDIA_PLAYER
    override val service: ServiceType =
        ServiceType.MEDIA_PLAY

    override fun toString() = "MediaPlayerPlayMessage(entityId=$entityId)"
}

class MediaPlayerPauseMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.MEDIA_PLAYER
    override val service: ServiceType =
        ServiceType.MEDIA_PAUSE

    override fun toString() = "MediaPlayerPauseMessage(entityId=$entityId)"
}

class MediaPlayerStopMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.MEDIA_PLAYER
    override val service: ServiceType =
        ServiceType.MEDIA_STOP

    override fun toString() = "MediaPlayerStopMessage(entityId=$entityId)"
}

class MediaPlayerPlayMediaMessage(val entityId: String, val contentUrl: String, val contentType: MediaContentType) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.MEDIA_PLAYER
    override val service: ServiceType =
        ServiceType.PLAY_MEDIA

    override fun toString() = "MediaPlayerPlayMessage(entityId=$entityId, contentUrl=$contentUrl, contentType=$contentType)"
}

//
//

class TimerStartMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.TIMER
    override val service: ServiceType =
        ServiceType.START

    override fun toString() = "TimerStartMessage(entityId=$entityId)"
}

class TimerCancelMessage(val entityId: String) : ServiceMessage() {
    override val domain: ServiceDomain =
        ServiceDomain.TIMER
    override val service: ServiceType =
        ServiceType.CANCEL

    override fun toString() = "TimerCancelMessage(entityId=$entityId)"
}

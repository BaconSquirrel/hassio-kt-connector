package de.baconsquirrel.hassiokt.connector.general

data class RgbColor(val red: Int, val green: Int, val blue: Int) {
    companion object {
        val WHITE = RgbColor(255, 255, 255)
    }
}
package app.domik.ui.dashboard

import androidx.annotation.StringRes
import app.domik.R
import app.domik.data.LightIcon
import app.domik.ui.components.DeviceIcon

/** Rysunek ikony (wektory z `res/drawable`) dla rodzaju ikony kafelka. */
fun LightIcon.toDeviceIcon(): DeviceIcon = when (this) {
    LightIcon.BULB -> DeviceIcon.Bulb
    LightIcon.SCONCE -> DeviceIcon.Sconce
    LightIcon.PENDANT -> DeviceIcon.Pendant
    LightIcon.FLOOR -> DeviceIcon.FloorLamp
    LightIcon.SPOT -> DeviceIcon.Spot
    LightIcon.LED -> DeviceIcon.LedStrip
    LightIcon.OUTDOOR -> DeviceIcon.OutdoorLamp
    LightIcon.SOCKET -> DeviceIcon.Socket
    LightIcon.FAN -> DeviceIcon.Fan
    LightIcon.PUMP -> DeviceIcon.Pump
    LightIcon.ALARM -> DeviceIcon.Alarm
    LightIcon.SWITCH -> DeviceIcon.Switch
}

/** Nazwa ikony (arkusz wyboru, czytniki ekranu). */
@StringRes
fun LightIcon.labelRes(): Int = when (this) {
    LightIcon.BULB -> R.string.icon_bulb
    LightIcon.SCONCE -> R.string.icon_sconce
    LightIcon.PENDANT -> R.string.icon_pendant
    LightIcon.FLOOR -> R.string.icon_floor
    LightIcon.SPOT -> R.string.icon_spot
    LightIcon.LED -> R.string.icon_led
    LightIcon.OUTDOOR -> R.string.icon_outdoor
    LightIcon.SOCKET -> R.string.icon_socket
    LightIcon.FAN -> R.string.icon_fan
    LightIcon.PUMP -> R.string.icon_pump
    LightIcon.ALARM -> R.string.icon_alarm
    LightIcon.SWITCH -> R.string.icon_switch
}

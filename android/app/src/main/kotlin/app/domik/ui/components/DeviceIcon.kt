package app.domik.ui.components

import androidx.annotation.DrawableRes
import app.domik.R

/**
 * Typ ikony urządzenia na kafelku: wariant wypełniony dla stanu włączonego,
 * konturowy dla wyłączonego. Typ jest wybierany automatycznie z `icon_id` i nazwy wyjścia albo ręcznie przez
 * użytkownika (`LightIcon` → [DeviceIcon] w `LightIconUi.kt`).
 */
enum class DeviceIcon(@DrawableRes val filled: Int, @DrawableRes val outline: Int) {
    /** Żarówka. */
    Bulb(R.drawable.ic_lightbulb, R.drawable.ic_lightbulb_outline),

    /** Lampa wisząca / żyrandol. */
    Pendant(R.drawable.ic_pendant, R.drawable.ic_pendant_outline),

    /** Gniazdko. */
    Socket(R.drawable.ic_outlet, R.drawable.ic_outlet_outline),

    /** Termometr / strefa ogrzewania. */
    Thermostat(R.drawable.ic_thermostat, R.drawable.ic_thermostat_outline),

    /** Roleta (wypełniona, gdy jedzie; konturowa, gdy stoi). */
    Shutter(R.drawable.ic_shutter, R.drawable.ic_shutter_outline),

    /** Kinkiet. */
    Sconce(R.drawable.ic_sconce, R.drawable.ic_sconce_outline),

    /** Lampa stojąca. */
    FloorLamp(R.drawable.ic_floor_lamp, R.drawable.ic_floor_lamp_outline),

    /** Oczko / spot. */
    Spot(R.drawable.ic_spot, R.drawable.ic_spot_outline),

    /** Taśma LED. */
    LedStrip(R.drawable.ic_led_strip, R.drawable.ic_led_strip_outline),

    /** Lampa zewnętrzna (ogród, taras, elewacja). */
    OutdoorLamp(R.drawable.ic_outdoor_lamp, R.drawable.ic_outdoor_lamp_outline),

    /** Wentylator. */
    Fan(R.drawable.ic_fan, R.drawable.ic_fan_outline),

    /** Pompa. */
    Pump(R.drawable.ic_pump, R.drawable.ic_pump_outline),

    /** Alarm (tarcza). */
    Alarm(R.drawable.ic_alarm, R.drawable.ic_alarm_outline),

    /** Ogólny włącznik. */
    Switch(R.drawable.ic_switch, R.drawable.ic_switch_outline),
    ;

    /** Miękka poświata przy włączeniu – tylko źródła światła. */
    val hasGlow: Boolean
        get() = when (this) {
            Bulb, Pendant, Sconce, FloorLamp, Spot, LedStrip, OutdoorLamp -> true
            Socket, Thermostat, Shutter, Fan, Pump, Alarm, Switch -> false
        }
}

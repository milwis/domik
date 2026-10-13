package app.domik.integrations.emodul

import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EmodulFormatTest {
    private val pl = Locale.forLanguageTag("pl-PL")
    private val en = Locale.US

    @Test
    fun `dziesiate czesci stopnia i wartosci niewazne`() {
        assertEquals(12.0, EmodulFormat.celsiusFromTenths(120)!!, 1e-9)
        assertEquals(-5.5, EmodulFormat.celsiusFromTenths(-55)!!, 1e-9)
        assertEquals(150.0, EmodulFormat.celsiusFromTenths(1500)!!, 1e-9)
        assertNull(EmodulFormat.celsiusFromTenths(1501))
        assertNull(EmodulFormat.celsiusFromTenths(-501))
        assertNull(EmodulFormat.celsiusFromTenths(32767))
        assertNull(EmodulFormat.celsiusFromTenths(null))
    }

    @Test
    fun `temperatura zadana zaworu i procenty maja zakresy`() {
        assertEquals(28, EmodulFormat.setpointCelsius(28))
        assertEquals(0, EmodulFormat.setpointCelsius(0))
        assertNull(EmodulFormat.setpointCelsius(121))
        assertNull(EmodulFormat.setpointCelsius(-1))
        assertEquals(100, EmodulFormat.percent(100))
        assertNull(EmodulFormat.percent(101))
        assertNull(EmodulFormat.percent(-1))
    }

    @Test
    fun `przecinek dla pl i kropka dla en`() {
        assertEquals(FormattedValue("12,5°", "12,5 °C"), EmodulFormat.temperature(12.5, 1, pl))
        assertEquals(FormattedValue("12.5°", "12.5 °C"), EmodulFormat.temperature(12.5, 1, en))
        assertEquals(FormattedValue("65°", "65 °C"), EmodulFormat.temperature(65.0, 0, pl))
        assertEquals("1,50", EmodulFormat.number(1.5, 2, pl))
    }

    @Test
    fun `jednostki widzetu`() {
        assertEquals(FormattedValue("65,0°", "65,0 °C"), EmodulFormat.widget(650, 7, pl))
        assertEquals(FormattedValue("21°", "21 °C"), EmodulFormat.widget(21, 6, pl))
        assertEquals("78 %", EmodulFormat.widget(78, 8, pl)!!.short)
        assertEquals("1:05", EmodulFormat.widget(65, 13, pl)!!.short)
        assertEquals("17:37", EmodulFormat.widget(17 * 60 + 37, 13, pl)!!.short)
        assertEquals("zał.", EmodulFormat.widget(1, 28, pl)!!.short)
        assertEquals("wył.", EmodulFormat.widget(0, 28, pl)!!.short)
        assertEquals("on", EmodulFormat.widget(1, 28, en)!!.short)
        assertEquals("5,0", EmodulFormat.widget(50, 4, pl)!!.short)
        assertEquals("0,50", EmodulFormat.widget(50, 5, pl)!!.short)
        assertEquals("12 s", EmodulFormat.widget(12, 1, pl)!!.short)
        assertEquals("3 dni", EmodulFormat.widget(3, 14, pl)!!.short)
        assertEquals("1500 obr/min", EmodulFormat.widget(1500, 27, pl)!!.short)
        assertEquals("42", EmodulFormat.widget(42, 0, pl)!!.short)
        assertEquals("42", EmodulFormat.widget(42, 999, pl)!!.short)
    }

    @Test
    fun `jednostka minus 1 i nieprawidlowa temperatura nie daja tekstu`() {
        assertNull(EmodulFormat.widget(5, -1, pl))
        assertNull(EmodulFormat.widget(32767, 7, pl))
    }
}

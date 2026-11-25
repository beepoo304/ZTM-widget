package pl.beepoo.ztmwidget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdipTest {
    private fun res(name: String) = javaClass.classLoader!!.getResource(name).readText()

    @Test
    fun parsesRealBoard() {
        val b = Sdip.parseBoard(res("sd.html"), 1L)
        assertEquals("12309", b.stopId)
        assertEquals("Katowice Rondo", b.name)
        assertEquals("01t", b.code)
        // Tylko zakładka „Następne odjazdy”, bez powtórzeń z zakładki „wg linii”.
        assertEquals(213, b.departures.size)
        assertEquals(Departure("T7", "Bytom Szkoła Medyczna", "3 min", atStop = false, realtime = true), b.departures[0])
        assertEquals("T16", b.departures.last().line)
        assertEquals("23:32", b.departures.last().time)
    }

    @Test
    fun vehicleAtStopAndTimetable() {
        val html = """
            <input id='stop-id' type='hidden' value='1'><input id='stop-name' type='hidden' value='A &amp; B'><input id='stop-code' type='hidden' value='2'>
            <div id='stop-departures-1' class='stop-departures'>
            <div id='9' class='departure status-2'><div class='vehicle-icon linetype-3'></div><div class='line'>910</div>
              <div class='destination' title='Katowice Dworzec'>Katowice Dworzec</div><div class='time'>0 min</div></div>
            <div id='10' class='departure status-1'><div class='vehicle-icon linetype-3'></div><div class='line'>30</div>
              <div class='destination' title='Giszowiec'>Giszowiec</div><div class='time'>&gt;&gt;&gt;</div></div>
            <div id='8' class='departure status-8'><div class='vehicle-icon linetype-3'></div><div class='line'>6</div>
              <div class='destination' title='X'>Plac &quot;Wolności&quot;</div><div class='time'>14:42</div></div>
            </div>
            <div id='line-departures-1'><div id='7' class='departure status-0'><div class='line'>99</div><div class='destination'>Z</div><div class='time'>1 min</div></div></div>
        """.trimIndent()
        val b = Sdip.parseBoard(html, 5L)
        assertEquals("A & B", b.name)
        assertEquals(3, b.departures.size)
        // "0 min" (status 2) to na przystanku jeszcze "1 min", a nie ">>>".
        assertFalse(b.departures[0].atStop)
        assertEquals("1 min", b.departures[0].time)
        assertTrue(b.departures[1].atStop)
        assertFalse(b.departures[2].atStop)
        assertFalse(b.departures[2].realtime)
        assertEquals("14:42", b.departures[2].time)
        assertEquals("Plac \"Wolności\"", b.departures[2].destination)
    }

    @Test
    fun parsesStops() {
        val json = """{"stops":[{"id":"12309","alt_id":"01t","name":"Katowice Rondo","address":"","lat":50.26440848,"lon":19.02366522,"linetype":"0","municipality":"KATOWICE","platform":""},
            {"id":"x","alt_id":"1","name":"Bez pozycji","linetype":"3"}]}"""
        val s = Sdip.parseStops(json)
        assertEquals(1, s.size)
        assertEquals("01", s[0].shortCode)
        assertTrue(s[0].isTram)
    }

    @Test
    fun foldsPolish() {
        assertEquals("chorzow welnowiec zrodla", Sdip.fold("Chorzów Wełnowiec ŹRÓDŁA"))
    }
}

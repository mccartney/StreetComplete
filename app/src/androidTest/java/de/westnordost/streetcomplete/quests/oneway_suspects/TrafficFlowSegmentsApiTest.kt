package de.westnordost.streetcomplete.quests.oneway_suspects

import de.westnordost.streetcomplete.data.osm.mapdata.BoundingBox
import de.westnordost.streetcomplete.data.osm.mapdata.LatLon
import de.westnordost.streetcomplete.quests.oneway_suspects.data.ONEWAY_API_URL
import de.westnordost.streetcomplete.quests.oneway_suspects.data.TrafficFlowSegment
import de.westnordost.streetcomplete.quests.oneway_suspects.data.TrafficFlowSegmentsApi
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrafficFlowSegmentsApiTest {

    @Test fun parseEmptyDoesNotResultInError() {
        val result = TrafficFlowSegmentsApi.parse("{\"segments\":[]}")
        assertTrue(result.isEmpty())
    }

    @Test fun parseTwoOfDifferentWay() {
        val result = TrafficFlowSegmentsApi.parse("""
        {"segments":[
            {"wayId":1,"fromPosition":{"lon":1, "lat":2},"toPosition":{"lon":5, "lat":6}},
            {"wayId":2,"fromPosition":{"lon":3, "lat":4},"toPosition":{"lon":7, "lat":8}},
        ]}
        """.trimIndent())
        val expected = mapOf(
            1L to listOf(TrafficFlowSegment(LatLon(2.0, 1.0), LatLon(6.0, 5.0))),
            2L to listOf(TrafficFlowSegment(LatLon(4.0, 3.0), LatLon(8.0, 7.0)))
        )
        assertMapsAreEqual(expected, result)
    }

    @Test fun parseTwoOfSameWay() {
        val result = TrafficFlowSegmentsApi.parse("""
        {"segments":[
            {"wayId":1,"fromPosition":{"lon":1, "lat":2},"toPosition":{"lon":5, "lat":6}},
            {"wayId":1,"fromPosition":{"lon":3, "lat":4},"toPosition":{"lon":7, "lat":8}},
        ]}
        """.trimIndent())
        val expected = mapOf(1L to listOf(
            TrafficFlowSegment(LatLon(2.0, 1.0), LatLon(6.0, 5.0)),
            TrafficFlowSegment(LatLon(4.0, 3.0), LatLon(8.0, 7.0))
        ))
        assertMapsAreEqual(expected, result)
    }

    @Test fun withSomeRealData() {
        // should just not crash...
        TrafficFlowSegmentsApi(ONEWAY_API_URL).get(BoundingBox(-34.0, 18.0, -33.0, 19.0))
    }

    private fun <K,V> assertMapsAreEqual(expected: Map<K,V>, actual: Map<K, V>) {
        assertEquals(expected.size, actual.size)
        val expectedEntries = expected.entries
        val actualEntries = actual.entries
        expectedEntries.forEach{ assertContains(actualEntries, it) }
        actualEntries.forEach{ assertContains(expectedEntries, it) }
    }
}

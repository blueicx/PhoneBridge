package com.phonebridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplorationLogTest {
    @Test
    fun realityLogRequestKeepsCursorOpaqueAndBoundsPageSize() {
        val client = WorkspaceClient()
        assertEquals("/api/reality/log?limit=50&cursor=eyJvY2N1cnJlZEF0IjoxfQ", client.realityLogPath("eyJvY2N1cnJlZEF0IjoxfQ"))
        assertEquals("/api/reality/log?limit=100", client.realityLogPath(limit = 100))
        assertFalse(runCatching { client.realityLogPath(limit = 101) }.isSuccess)
        assertFalse(runCatching { client.realityLogPath("x&limit=999") }.isSuccess)
    }

    @Test
    fun offlineOutboxClueIsPendingWithoutRewardOrFreeText() {
        val local = ExplorationLogParser.fromOutbox(
            outbox(
                clueEventId = "reality-lens:v2:2026-09-30:cell:3:4:light:offline_01",
                clueType = "light",
                region = "cell:3:4",
                createdAt = 100L
            ),
            progressRevision = 4L
        )

        assertEquals(ExplorationLogStatus.PENDING, local?.status)
        assertEquals("reality-lens:v2:2026-09-30:cell:3:4:light:offline_01", local?.eventId)
        assertEquals("cell:3:4", local?.coarseRegion)
        assertEquals("light", local?.clueType)
        assertEquals(100L, local?.occurredAt)
        assertNull(local?.reward)
        assertNull(local?.observation)
        assertNull(local?.reason)
    }

    @Test
    fun acceptedOrDuplicateAckCannotInventRewardAndConfirmedReceiptWins() {
        val acknowledged = outbox(
            clueEventId = "reality-lens:v2:2026-09-30:cell:1:2:object:object_01",
            clueType = "object",
            region = "cell:1:2",
            acknowledged = true,
            businessStatus = "duplicate",
            createdAt = 200L
        )
        val withoutReceipt = ExplorationLogParser.fromOutbox(acknowledged, progressRevision = 4L)
        assertEquals(ExplorationLogStatus.PENDING, withoutReceipt?.status)
        assertTrue(withoutReceipt?.acknowledged == true)
        assertNull(withoutReceipt?.reward)

        val fixture = loadFixture()
        val page = ExplorationLogParser.parsePage(fixture.getJSONObject("serverPage"))
        val result = ExplorationLogStore().applyPage(page, listOf(acknowledged), progressRevision = 4L)
        assertEquals(1, result.entries.size)
        assertEquals(ExplorationLogStatus.CONFIRMED, result.entries.single().status)
        assertEquals(3, result.entries.single().reward?.xp)
        assertEquals("sprite", result.entries.single().moteId)
    }

    @Test
    fun rejectedOutboxShowsSafeReasonWithoutConfirmedReward() {
        val rejected = ExplorationLogParser.fromOutbox(
            outbox(
                clueEventId = "reality-lens:v2:2026-09-30:cell:3:4:location:offline_02",
                clueType = "location",
                region = "cell:3:4",
                acknowledged = true,
                businessStatus = "rejected",
                businessReason = "offline_event_expired",
                createdAt = 300L
            ),
            progressRevision = 4L
        )

        assertEquals(ExplorationLogStatus.REJECTED, rejected?.status)
        assertEquals("offline_event_expired", rejected?.reason)
        assertNull(rejected?.reward)

        val unsafeReason = ExplorationLogParser.fromOutbox(
            outbox(
                clueEventId = "reality-lens:v2:2026-09-30:cell:3:4:location:offline_03",
                clueType = "location",
                region = "cell:3:4",
                acknowledged = true,
                businessStatus = "rejected",
                businessReason = "PRIVATE free-form detail",
                createdAt = 301L
            ),
            progressRevision = 4L
        )
        assertEquals("rejected", unsafeReason?.reason)
    }

    @Test
    fun overlappingPagesDeduplicateAndKeepNewestFirstOrdering() {
        val first = confirmed("event-a", 100L)
        val overlap = first.copy(observation = "replacement observation")
        val older = confirmed("event-b", 90L)
        val store = ExplorationLogStore()

        store.applyPage(ExplorationLogPage(listOf(first), "cursor-a"), emptyList(), 4L)
        val result = store.applyPage(ExplorationLogPage(listOf(overlap, older), null), emptyList(), 4L)

        assertEquals(listOf("event-a", "event-b"), result.entries.map { it.eventId })
        assertEquals("replacement observation", result.entries.first().observation)
        assertNull(result.nextCursor)
    }

    @Test
    fun progressRevisionChangeClearsCachedPagesAndRejectsStaleOutboxRows() {
        val store = ExplorationLogStore()
        store.applyPage(
            ExplorationLogPage(listOf(confirmed("old-confirmed", 100L)), "old-cursor"),
            emptyList(),
            4L
        )

        val result = store.applyPage(
            ExplorationLogPage(listOf(confirmed("must-reload-from-first-page", 120L)), "stale-cursor"),
            listOf(outbox(
                clueEventId = "reality-lens:v2:2026-09-30:cell:3:4:light:old_01",
                clueType = "light",
                region = "cell:3:4",
                createdAt = 110L,
                progressRevision = 4L
            )),
            5L
        )

        assertTrue(result.revisionChanged)
        assertTrue(result.requiresRefreshFromStart)
        assertTrue(result.entries.isEmpty())
        assertNull(result.nextCursor)
    }

    @Test
    fun latePageFromOlderPrivacyRevisionCannotRestoreDeletedCache() {
        val store = ExplorationLogStore()
        store.applyPage(ExplorationLogPage(listOf(confirmed("current", 200L)), null), emptyList(), 5L)

        val stale = store.applyPage(
            ExplorationLogPage(listOf(confirmed("deleted-old-page", 100L)), "old-cursor"),
            emptyList(),
            4L
        )

        assertEquals(5L, stale.privacyRevision)
        assertTrue(stale.requiresRefreshFromStart)
        assertEquals(listOf("current"), stale.entries.map { it.eventId })
    }

    @Test
    fun sharedFixtureMatchesAndroidParserAndServerFieldAllowlist() {
        val fixture = loadFixture()
        assertEquals("progress", fixture.getString("privacyCategory"))
        assertEquals(4L, fixture.getLong("privacyRevision"))

        val page = ExplorationLogParser.parsePage(fixture.getJSONObject("serverPage"))
        val entry = page.entries.single()
        assertEquals(ExplorationLogStatus.CONFIRMED, entry.status)
        assertEquals("cell:1:2", entry.coarseRegion)
        assertEquals(3, entry.reward?.xp)
        val allowed = (0 until fixture.getJSONArray("allowedServerEntryFields").length())
            .map { fixture.getJSONArray("allowedServerEntryFields").getString(it) }
            .toSet()
        assertEquals(allowed, ExplorationLogEntry.SERVER_FIELDS)
        val pendingFixture = fixture.getJSONObject("pendingOutbox")
        assertEquals(fixture.getString("privacyCategory"), pendingFixture.getString("privacyCategory"))
        assertFalse(pendingFixture.getJSONObject("payload").has("reward"))
        assertFalse(pendingFixture.getJSONObject("payload").has("rawImage"))
        val rejectedFixture = fixture.getJSONObject("rejectedOutbox")
        assertEquals("offline_event_expired", rejectedFixture.getString("businessReason"))
        assertFalse(rejectedFixture.getJSONObject("payload").has("reward"))

        val untrusted = JSONObject(fixture.getJSONObject("serverPage").getJSONArray("entries").getJSONObject(0).toString())
            .put("preciseLatitude", 31.2)
            .put("rawImage", "PRIVATE-IMAGE")
        val untrustedPage = JSONObject().put("entries", org.json.JSONArray().put(untrusted))
        val parsed = ExplorationLogParser.parsePage(untrustedPage).entries.single()
        assertFalse(parsed.toString().contains("PRIVATE-IMAGE"))
        assertFalse(parsed.toString().contains("31.2"))
    }

    private fun confirmed(eventId: String, occurredAt: Long) = ExplorationLogEntry(
        eventId = eventId,
        status = ExplorationLogStatus.CONFIRMED,
        occurredAt = occurredAt,
        coarseRegion = "cell:1:2",
        clueType = "object",
        moteId = "sprite",
        observation = "safe observation",
        reward = ExplorationLogReward(xp = 1)
    )

    private fun outbox(
        clueEventId: String,
        clueType: String,
        region: String,
        acknowledged: Boolean = false,
        businessStatus: String? = null,
        businessReason: String? = null,
        createdAt: Long,
        progressRevision: Long = 4L
    ) = WorkspaceOutboxEntity(
        eventId = "transport-$clueEventId",
        type = WorkspaceEventTypes.MOTE_EXPLORATION,
        payload = JSONObject()
            .put("eventId", clueEventId)
            .put("clueType", clueType)
            .put("region", region)
            .put("offline", true)
            .toString(),
        createdAt = createdAt,
        ack = acknowledged,
        businessStatus = businessStatus,
        businessReason = businessReason,
        privacyCategory = "progress",
        privacyRevision = progressRevision,
        privacyRevisionsJson = JSONObject().put("progress", progressRevision).toString(),
        quarantined = false
    )

    private fun loadFixture(): JSONObject {
        val stream = javaClass.classLoader?.getResourceAsStream("reality-log.json")
            ?: error("shared reality-log fixture missing")
        return stream.bufferedReader().use { JSONObject(it.readText()) }
    }
}

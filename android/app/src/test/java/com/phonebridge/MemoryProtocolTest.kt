package com.phonebridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryProtocolTest {
    @Test
    fun parsesServerMemoryMetadataAndConflicts() {
        val parsed = MemoryProtocol.parseList(
            JSONObject(
                """{"memories":[{"id":"m-1","text":"喜欢夜间散步","source":"conversation","status":"confirmed","createdAt":"2026-10-13T12:00:00.000Z","updatedAt":"2026-10-14T12:00:00.000Z","lastUsedAt":"2026-10-14T12:30:00.000Z","lastUsedReason":"conversation_recall","provenance":["conversation","user"],"possibleConflicts":[{"id":"m-2","text":"不喜欢夜间散步","source":"conversation","similarity":0.75}]}]}"""
            )
        ).single()

        assertEquals("m-1", parsed.id)
        assertEquals("conversation_recall", parsed.lastUsedReason)
        assertEquals(listOf("conversation", "user"), parsed.provenance)
        assertEquals(1, parsed.possibleConflicts.size)
        assertEquals("m-2", parsed.possibleConflicts.single().id)
        assertTrue(parsed.updatedAtMs > parsed.createdAtMs)
    }

    @Test
    fun legacyMemoryFieldsHaveSafeDefaultsAndNumericDatesRemainCompatible() {
        val parsed = MemoryProtocol.parseList(
            JSONObject("""{"memories":[{"id":"legacy","text":"喜欢清晨","createdAt":1000,"updatedAt":2000}]}""")
        ).single()

        assertEquals(1_000L, parsed.createdAtMs)
        assertEquals(2_000L, parsed.updatedAtMs)
        assertEquals("", parsed.lastUsedReason)
        assertEquals(emptyList<String>(), parsed.possibleConflicts)
        assertEquals(listOf("user"), parsed.provenance)
    }

    @Test
    fun remoteMergeKeepsOfflineOnlyMemoriesWithoutDuplicatingIds() {
        val remote = MemoryItem(id = "shared", text = "node copy", createdAtMs = 1L, provenance = listOf("conversation"))
        val localShared = MemoryItem(id = "shared", text = "local stale copy", createdAtMs = 1L, useCount = 4)
        val localOnly = MemoryItem(id = "offline", text = "离线添加", createdAtMs = 2L, pendingSync = true)
        val staleCached = MemoryItem(id = "removed-on-node", text = "节点已删除", createdAtMs = 3L)

        val merged = MemoryProtocol.mergeRemote(listOf(remote), listOf(localShared, localOnly, staleCached))

        assertEquals(setOf("shared", "offline"), merged.map { it.id }.toSet())
        assertEquals("node copy", merged.first { it.id == "shared" }.text)
        assertEquals(4, merged.first { it.id == "shared" }.useCount)
        assertEquals(listOf("conversation"), merged.first { it.id == "shared" }.provenance)
        assertTrue(merged.first { it.id == "offline" }.pendingSync)
        assertFalse(merged.any { it.id == "shared" && it.text == "local stale copy" })
        assertFalse(merged.any { it.id == "removed-on-node" })
    }
}

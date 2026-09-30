package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GoalBoardModelsTest {
    @Test
    fun draftRemainsSeparateFromAcceptanceAndKeepsIdempotencyKeyAcrossEdits() {
        val draft = requireNotNull(GoalBoardProtocol.parseDraft(
            """{"goalId":"goal-1","steps":[{"title":"先做一小步","description":"可观察"}],"providerId":"local","fallbackProvider":"local","fallbackReason":"provider_unavailable","future":"ignored"}""",
            eventId = "goal-accept-001"
        ))

        assertEquals("goal-1", draft.goalId)
        assertEquals("local", draft.providerId)
        assertEquals("provider_unavailable", draft.fallbackReason)
        assertFalse(GoalBoardProtocol.canAccept(draft, "another-goal"))
        assertTrue(GoalBoardProtocol.canAccept(draft, "goal-1"))

        val edited = requireNotNull(GoalBoardProtocol.editStepTitles(draft, "先完成可运行版本\n\n再做一次检查"))
        assertEquals(draft.eventId, edited.eventId)
        assertEquals(listOf("先完成可运行版本", "再做一次检查"), edited.steps.map { it.title })
        assertNull(GoalBoardProtocol.editStepTitles(draft, "\n  "))
        assertNull(GoalBoardProtocol.editStepTitles(draft, (1..9).joinToString("\n") { "步骤 $it" }))
    }

    @Test
    fun snapshotMapsGoalMilestonesAndTaskReferencesIgnoringUnknownFields() {
        val snapshot = GoalBoardProtocol.parseSnapshot(
            """{"goals":[{"id":"goal-1","title":"学会基础日语","description":"按自己的节奏","status":"active","createdAt":"2026-09-30T08:00:00Z","updatedAt":"2026-09-30T08:10:00Z","milestones":[{"id":"mile-1","goalId":"goal-1","title":"完成第一课","description":"听读","status":"running","taskId":"task-1","future":1}],"future":true}],"privacyRevision":3,"taskPrivacyRevision":4,"migrationRequired":false,"futureEnvelope":1}"""
        )
        val refs = GoalBoardProtocol.taskRefs(
            listOf(
                parseWorkspaceJsonObject("""{"id":"task-1","title":"完成第一课","state":"running","metadata":{"goalId":"goal-1","milestoneId":"mile-1","unknown":"ignored"},"unknown":true}"""),
                parseWorkspaceJsonObject("""{"id":"ordinary","metadata":{}}""")
            )
        )

        assertEquals(3L, snapshot.privacyRevision)
        assertEquals(4L, snapshot.taskPrivacyRevision)
        assertEquals("goal-1", snapshot.goals.single().id)
        assertEquals("mile-1", snapshot.goals.single().milestones.single().id)
        assertEquals("task-1", snapshot.goals.single().milestones.single().taskId)
        assertEquals(1, refs.size)
        assertEquals("goal-1", refs.single().goalId)
        assertEquals("mile-1", refs.single().milestoneId)
        assertEquals("running", refs.single().state)
    }

    @Test
    fun acceptancePayloadCarriesEditedStepsAndStableEventId() {
        val draft = GoalBoardDraft(
            goalId = "goal-1",
            eventId = "goal-accept-retry-1",
            steps = listOf(GoalBoardStep("第一步", "说明")),
            providerId = "local"
        )
        val payload = GoalBoardProtocol.acceptancePayload(draft, privacyRevision = 6L, taskPrivacyRevision = 9L)
        val values = parseWorkspaceJsonObject(payload)
        val steps = values["steps"] as List<*>
        val step = steps.single() as Map<*, *>

        assertEquals(draft.eventId, values["eventId"])
        assertEquals(6L, (values["privacyRevision"] as Number).toLong())
        assertEquals(9L, (values["taskPrivacyRevision"] as Number).toLong())
        assertEquals("第一步", step["title"])
        assertEquals("说明", step["description"])
    }

    @Test
    fun deletingOrRenamingAStepNeverMovesAnotherStepsDescriptionToIt() {
        val draft = GoalBoardDraft(
            goalId = "goal-1",
            eventId = "goal-accept-002",
            steps = listOf(
                GoalBoardStep("收集资料", "资料说明"),
                GoalBoardStep("整理方案", "方案说明"),
                GoalBoardStep("执行复盘", "复盘说明")
            )
        )

        val deleted = requireNotNull(GoalBoardProtocol.editStepTitles(draft, "收集资料\n执行复盘"))
        assertEquals(listOf("资料说明", "复盘说明"), deleted.steps.map { it.description })

        val renamed = requireNotNull(GoalBoardProtocol.editStepTitles(draft, "收集资料\n更新方案\n执行复盘"))
        assertEquals(listOf("资料说明", "", "复盘说明"), renamed.steps.map { it.description })
    }

    @Test
    fun sharedGoalAndDraftFixturesMatchTheAndroidProtocol() {
        val snapshot = GoalBoardProtocol.parseSnapshot(fixture("goal-board.json"))
        assertEquals(6L, snapshot.privacyRevision)
        assertEquals(9L, snapshot.taskPrivacyRevision)
        assertEquals("milestone-fixture-001", snapshot.goals.single().milestones.single().id)
        assertEquals("task-fixture-001", snapshot.goals.single().milestones.single().taskId)

        val draft = requireNotNull(GoalBoardProtocol.parseDraft(fixture("goal-draft.json"), "goal-fixture-accept-001"))
        assertEquals("goal-fixture-001", draft.goalId)
        assertEquals("local", draft.providerId)
        assertEquals("provider_unavailable", draft.fallbackReason)
        assertEquals(2, draft.steps.size)
    }

    @Test
    fun sharedGoalTaskReferenceFixtureIgnoresExtensionsAndDefaultsMissingState() {
        val task = parseWorkspaceJsonObject(fixture("goal-task-ref.json"))
        val refs = GoalBoardProtocol.taskRefs(listOf(task))

        assertEquals(1, refs.size)
        assertEquals("task-fixture-001", refs.single().taskId)
        assertEquals("goal-fixture-001", refs.single().goalId)
        assertEquals("milestone-fixture-001", refs.single().milestoneId)
        assertEquals("pending", refs.single().state)
    }

    private fun fixture(name: String): String =
        javaClass.getResourceAsStream("/$name")?.bufferedReader()?.use { it.readText() }
            ?: java.io.File("../../protocol-fixtures/$name").readText()
}

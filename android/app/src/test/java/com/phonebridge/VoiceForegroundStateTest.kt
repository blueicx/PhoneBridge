package com.phonebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceForegroundStateTest {
    @Test
    fun listeningPresentationShowsServiceAliveAndStopAction() {
        val presentation = VoiceForegroundFormatter.present(
            VoiceForegroundState()
                .preparing()
                .listening("离线连续语音聊天中，直接说话即可。")
        )

        assertEquals("PhoneBridge 后台存活", presentation.title)
        assertEquals("麦克风持续监听中", presentation.status)
        assertTrue(presentation.text.contains("节点在线"))
        assertTrue(presentation.text.contains("离线连续语音聊天中"))
        assertTrue(presentation.running)
        assertTrue(presentation.listening)
        assertFalse(presentation.speaking)
        assertEquals("停止监听", presentation.stopActionLabel)
    }

    @Test
    fun processingAndSpeakingStatesUseDistinctLabels() {
        val processing = VoiceForegroundFormatter.present(
            VoiceForegroundState().listening().processing("打开相机")
        )
        val speaking = VoiceForegroundFormatter.present(
            VoiceForegroundState().listening().speaking("好的，已经打开相机。")
        )

        assertEquals("语音处理中", processing.status)
        assertTrue(processing.text.contains("你说：打开相机"))
        assertTrue(processing.running)
        assertFalse(processing.listening)
        assertFalse(processing.speaking)

        assertEquals("正在播报回复", speaking.status)
        assertTrue(speaking.text.contains("好的，已经打开相机"))
        assertTrue(speaking.running)
        assertFalse(speaking.listening)
        assertTrue(speaking.speaking)
    }

    @Test
    fun offlinePresentationKeepsRunningStateVisible() {
        val presentation = VoiceForegroundFormatter.present(
            VoiceForegroundState()
                .listening()
                .withBridgeOnline(false)
                .withProactive("节点建议检查光线与构图。", "attention.camera")
        )

        assertEquals("麦克风持续监听中", presentation.status)
        assertTrue(presentation.text.contains("节点离线"))
        assertTrue(presentation.text.contains("attention.camera"))
        assertTrue(presentation.text.contains("节点建议检查光线与构图"))
        assertTrue(presentation.running)
        assertEquals("停止监听", presentation.stopActionLabel)
    }

    @Test
    fun pausedAndStoppedStatesHideStopAction() {
        val paused = VoiceForegroundFormatter.present(
            VoiceForegroundState().paused("已暂停，请在 App 内重新开启。")
        )
        val stopped = VoiceForegroundFormatter.present(
            VoiceForegroundState().stopped("监听已停止。")
        )

        assertEquals("已暂停", paused.status)
        assertFalse(paused.running)
        assertNull(paused.stopActionLabel)

        assertEquals("已停止", stopped.status)
        assertFalse(stopped.running)
        assertNull(stopped.stopActionLabel)
    }

    @Test
    fun stateTransitionsKeepFlagsConsistent() {
        val stopped = VoiceForegroundState().stopped("监听已停止。")
        val preparing = stopped.preparing()
        val listening = preparing.listening()
        val processing = listening.processing("打开相机")
        val speaking = processing.speaking("好的，已经打开相机。")
        val paused = speaking.paused("已暂停，请在 App 内重新开启。")
        val denied = paused.permissionMissing("请先打开 App 授予麦克风权限。")

        assertEquals(VoiceForegroundPhase.STOPPED, stopped.phase)
        assertFalse(stopped.sessionActive)

        assertEquals(VoiceForegroundPhase.PREPARING, preparing.phase)
        assertTrue(preparing.sessionActive)

        assertEquals(VoiceForegroundPhase.LISTENING, listening.phase)
        assertTrue(listening.listening)
        assertFalse(listening.speaking)

        assertEquals(VoiceForegroundPhase.PROCESSING, processing.phase)
        assertTrue(processing.sessionActive)
        assertFalse(processing.listening)

        assertEquals(VoiceForegroundPhase.SPEAKING, speaking.phase)
        assertTrue(speaking.speaking)

        assertEquals(VoiceForegroundPhase.PAUSED, paused.phase)
        assertFalse(paused.sessionActive)

        assertEquals(VoiceForegroundPhase.STOPPED, denied.phase)
        assertFalse(denied.sessionActive)
        assertFalse(denied.microphonePermissionGranted)
    }
}

package com.phonebridge

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ImmersiveShellState(
    val surface: ImmersiveSurface = ImmersiveSurface.COMPANION,
    val drawerOpen: Boolean = false,
    val selectedTaskId: String? = null,
    val selectedAttentionId: String? = null,
    val selectedChatSessionId: String? = null
)

/** Pure navigation state for the immersive shell; Android views only render it. */
class ImmersiveShellCoordinator(initialSurface: ImmersiveSurface = ImmersiveSurface.COMPANION) {
    private val _state = MutableStateFlow(ImmersiveShellState(surface = initialSurface))
    val state: StateFlow<ImmersiveShellState> = _state.asStateFlow()

    fun setSurface(surface: ImmersiveSurface) {
        _state.value = _state.value.copy(surface = surface)
    }

    fun openDrawer() {
        _state.value = _state.value.copy(drawerOpen = true)
    }

    fun closeDrawer() {
        _state.value = _state.value.copy(drawerOpen = false)
    }

    fun toggleDrawer() {
        _state.value = _state.value.copy(drawerOpen = !_state.value.drawerOpen)
    }

    fun openDeepLink(uri: String): Boolean {
        val link = TimelineDeepLink.parse(uri)
        return when (link.target) {
                DeepLinkTarget.TASK -> {
                    if (link.targetId.isBlank()) return false
                    _state.value = _state.value.copy(
                        drawerOpen = true,
                        selectedTaskId = link.targetId,
                        selectedAttentionId = null,
                        selectedChatSessionId = null
                    )
                    true
                }
                DeepLinkTarget.ATTENTION -> {
                    if (link.targetId.isBlank()) return false
                    _state.value = _state.value.copy(
                        drawerOpen = true,
                        selectedTaskId = null,
                        selectedAttentionId = link.targetId,
                        selectedChatSessionId = null
                    )
                    true
                }
                DeepLinkTarget.CHAT -> {
                    if (link.targetId.isBlank()) return false
                    _state.value = _state.value.copy(
                        drawerOpen = true,
                        selectedTaskId = null,
                        selectedAttentionId = null,
                        selectedChatSessionId = link.targetId
                    )
                    true
                }
                DeepLinkTarget.UNKNOWN -> false
            }
    }

    /** Returns true when the back press was consumed by the shell. */
    fun onBack(): Boolean {
        val current = _state.value
        return when {
            current.drawerOpen -> {
                _state.value = current.copy(drawerOpen = false)
                true
            }
            current.selectedTaskId != null || current.selectedAttentionId != null || current.selectedChatSessionId != null -> {
                _state.value = current.copy(
                    selectedTaskId = null,
                    selectedAttentionId = null,
                    selectedChatSessionId = null
                )
                true
            }
            current.surface != ImmersiveSurface.COMPANION -> {
                _state.value = current.copy(surface = ImmersiveSurface.COMPANION)
                true
            }
            else -> false
        }
    }
}

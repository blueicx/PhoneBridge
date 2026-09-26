package com.phonebridge

enum class RealityTrackingStatus { STOPPED, CHECKING, INSTALLING, STARTING, SEARCHING, TRACKING, FALLBACK }

data class RealityTrackingSnapshot(
    val status: RealityTrackingStatus,
    val motePose: AnchorPose? = null,
    val fallbackReason: String? = null,
    val fps: Float = 0f,
    val anchorPlaced: Boolean = false,
) {
    val isTracking: Boolean get() = status == RealityTrackingStatus.TRACKING
}

object RealityProjection {
    fun fromClipCoordinates(
        clipX: Float,
        clipY: Float,
        clipW: Float,
        width: Int,
        height: Int,
        scale: Float = 1f,
        tracking: Boolean = true,
        anchorPlaced: Boolean = true,
    ): AnchorPose {
        val valid = tracking && anchorPlaced && width > 0 && height > 0 &&
            clipX.isFinite() && clipY.isFinite() && clipW.isFinite() && clipW > 0f &&
            scale.isFinite() && scale > 0f
        if (!valid) return hidden(scale)

        val ndcX = clipX / clipW
        val ndcY = clipY / clipW
        if (!ndcX.isFinite() || !ndcY.isFinite() || ndcX !in -1f..1f || ndcY !in -1f..1f) return hidden(scale)
        return AnchorPose(
            x = (ndcX + 1f) * .5f * width,
            y = (1f - ndcY) * .5f * height,
            scale = scale.coerceIn(.25f, 2f),
            visible = true,
            source = "arcore-session",
        )
    }

    private fun hidden(scale: Float) = AnchorPose(
        x = 0f,
        y = 0f,
        scale = scale.takeIf { it.isFinite() && it > 0f }?.coerceIn(.25f, 2f) ?: 1f,
        visible = false,
        source = "arcore-session",
    )
}

object RealityPlanePlacementPolicy {
    fun canPlace(tracking: Boolean, anchorAlreadyPlaced: Boolean, planeHit: Boolean): Boolean =
        tracking && !anchorAlreadyPlaced && planeHit
}

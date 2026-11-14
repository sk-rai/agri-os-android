package com.agrios.app.ui.home

import com.agrios.app.core.auth.SessionLease
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.remote.dto.CropCycleResponseDto

internal data class HomeCycleScope(val session: AuthStateEntity, val farmerId: String) {
    override fun toString() = "HomeCycleScope(REDACTED)"
}

internal class HomeCycleResult(
    private val scope: HomeCycleScope,
    val lease: SessionLease,
    cycles: List<CropCycleResponseDto>,
    val message: String? = null,
    val readStatuses: String? = null,
) {
    val cycles = cycles.filter { it.farmerId == scope.farmerId }

    fun visibleFor(current: HomeCycleScope?): HomeCycleResult? =
        takeIf { scope == current && lease.state == scope.session }

    fun canOpen(cycleId: String, current: HomeCycleScope?): Boolean =
        visibleFor(current)?.cycles?.any { it.id == cycleId } == true
}

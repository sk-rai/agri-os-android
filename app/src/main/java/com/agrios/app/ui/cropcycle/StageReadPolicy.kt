package com.agrios.app.ui.cropcycle

import com.google.gson.JsonObject
import java.math.BigDecimal

/** Viewing and mutating are deliberately independent decisions. */
fun canViewStageReads(status: String): Boolean = status in setOf(
    "PENDING", "IN_PROGRESS", "ACTIVE", "STARTED", "COMPLETED", "SKIPPED", "FAILED"
)

fun canLogStageActivity(cycleStatus: String?, stageStatus: String?): Boolean =
    cycleStatus in setOf("ACTIVE", "PARTIALLY_TRACKED") &&
        stageStatus in setOf("IN_PROGRESS", "ACTIVE", "STARTED")

data class CycleFinanceRead(
    val activityCount: Int, val actualExpense: BigDecimal, val totalIncome: BigDecimal,
    val totalExpenses: BigDecimal, val profitOrLoss: BigDecimal
)

/** Fail closed on missing totals, incorrect identity/schema or invalid numbers. */
fun parseCycleFinance(stage: JsonObject, profit: JsonObject, cycleId: String, tenant: String): CycleFinanceRead {
    check(stage.get("schema_version")?.asString == "crop_cycle_stage_cost_summary.v1")
    check(profit.get("schema_version")?.asString == "crop_cycle_profit_loss_summary.v1")
    for (body in listOf(stage, profit)) {
        check(body.get("cycle_id")?.asString == cycleId && body.get("tenant_id")?.asString == tenant)
    }
    val s = stage.getAsJsonObject("totals")
    val p = profit.getAsJsonObject("totals")
    fun decimal(body: JsonObject, key: String) = body.get(key).asString.toBigDecimal()
    val count = decimal(s, "activity_count").intValueExact()
    check(count >= 0)
    return CycleFinanceRead(count, decimal(s, "actual_expense"), decimal(p, "total_income"),
        decimal(p, "total_expenses"), decimal(p, "profit_or_loss"))
}

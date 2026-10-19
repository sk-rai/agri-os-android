package com.agrios.app.ui.cropcycle

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class StageReadPolicyTest {
    @Test fun pendingCanBeReadButCannotLog() {
        assertTrue(canViewStageReads("PENDING"))
        for (cycle in listOf("PLANNED", "ACTIVE", "PARTIALLY_TRACKED", "COMPLETED", "ARCHIVED")) {
            assertFalse(canLogStageActivity(cycle, "PENDING"))
        }
    }

    @Test fun mutationRequiresActiveCycleAndActiveStage() {
        for (cycle in listOf("ACTIVE", "PARTIALLY_TRACKED")) {
            for (stage in listOf("IN_PROGRESS", "ACTIVE", "STARTED")) assertTrue(canLogStageActivity(cycle, stage))
        }
        for (stage in listOf("PENDING", "COMPLETED", "SKIPPED", "FAILED", "UNKNOWN")) {
            assertFalse(canLogStageActivity("ACTIVE", stage))
        }
        assertFalse(canLogStageActivity("PLANNED", "ACTIVE"))
        assertFalse(canLogStageActivity(null, null))
        assertFalse(canLogStageActivity("COMPLETED", "ACTIVE"))
    }

    @Test fun viewingDoesNotRequireMutableStateAndUnknownFailsClosed() {
        for (state in listOf("PENDING", "ACTIVE", "IN_PROGRESS", "STARTED", "COMPLETED", "SKIPPED", "FAILED")) {
            assertTrue(canViewStageReads(state))
        }
        assertFalse(canViewStageReads("UNKNOWN"))
    }

    private fun stage() = JsonParser.parseString("""{"schema_version":"crop_cycle_stage_cost_summary.v1","cycle_id":"cycle","tenant_id":"tenant","totals":{"activity_count":0,"actual_expense":"0.00"}}""").asJsonObject
    private fun profit() = JsonParser.parseString("""{"schema_version":"crop_cycle_profit_loss_summary.v1","cycle_id":"cycle","tenant_id":"tenant","totals":{"total_income":"0.00","total_expenses":"0.00","profit_or_loss":"0.00"}}""").asJsonObject
    private fun rejects(block: () -> Unit) {
        var rejected = false
        try { block() } catch (_: Exception) { rejected = true }
        assertTrue("Invalid read must not become zero/success", rejected)
    }

    @Test fun genuineZeroFinanceIsAccepted() {
        val result = parseCycleFinance(stage(), profit(), "cycle", "tenant")
        assertEquals(0, result.activityCount)
        assertEquals("0.00", result.actualExpense.toPlainString())
        assertEquals("0.00", result.totalIncome.toPlainString())
        assertEquals("0.00", result.totalExpenses.toPlainString())
        assertEquals("0.00", result.profitOrLoss.toPlainString())
    }

    @Test fun missingTotalsAndMalformedNumbersAreRejected() {
        val s = stage(); s.getAsJsonObject("totals").remove("actual_expense")
        rejects { parseCycleFinance(s, profit(), "cycle", "tenant") }
        val p = profit(); p.getAsJsonObject("totals").addProperty("total_income", "not-a-number")
        rejects { parseCycleFinance(stage(), p, "cycle", "tenant") }
        val fractional = stage(); fractional.getAsJsonObject("totals").addProperty("activity_count", "0.5")
        rejects { parseCycleFinance(fractional, profit(), "cycle", "tenant") }
    }

    @Test fun wrongSchemaTenantOrCycleCannotBeShownAsFinanceSuccess() {
        rejects { parseCycleFinance(stage(), profit(), "other-cycle", "tenant") }
        rejects { parseCycleFinance(stage(), profit(), "cycle", "other-tenant") }
        val p = profit(); p.addProperty("schema_version", "wrong")
        rejects { parseCycleFinance(stage(), p, "cycle", "tenant") }
    }
}

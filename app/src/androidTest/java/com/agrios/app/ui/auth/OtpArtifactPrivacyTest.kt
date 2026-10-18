package com.agrios.app.ui.auth

import android.app.Application
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.agrios.app.AgriOsApp
import com.agrios.app.core.auth.AuthRepository
import com.agrios.app.core.database.AppDatabase
import com.agrios.app.data.local.dao.AuthDao
import com.agrios.app.data.local.entity.AuthStateEntity
import com.agrios.app.data.remote.api.AgriOsApi
import com.agrios.app.data.remote.dto.AuthResponseDto
import com.agrios.app.data.remote.dto.OtpResponseDto
import com.agrios.app.data.remote.dto.OtpVerifyDto
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import retrofit2.Response
import java.lang.reflect.Proxy

/** Synthetic-only. Must use -PotpPrivacyIsolation=true; no network/disk auth/dumps. */
@RunWith(AndroidJUnit4::class)
class OtpArtifactPrivacyTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val secret = (1..6).joinToString("")
    private lateinit var db: AppDatabase
    private lateinit var vm: AuthViewModel
    private var previousApp: Any? = null
    private var replacedApp = false
    private var verified = false
    private var submittedCorrectly = false

    @Before fun isolateAndRender() {
        val service = instrumentation.uiAutomation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrumentation.uiAutomation.serviceInfo = service
        val context = instrumentation.targetContext
        assertTrue("Use isolated package", context.packageName == "com.agrios.app.otpprivacy")
        assertTrue("No production Application", context.applicationContext.javaClass == Application::class.java)
        assertTrue("No Internet permission", context.checkSelfPermission("android.permission.INTERNET") == PackageManager.PERMISSION_DENIED)
        val providers = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PROVIDERS).providers.orEmpty()
        assertTrue("No startup initializer", providers.none { it.name.contains("InitializationProvider") })
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        // Test-only injection: no production startup or auth storage is touched.
        val instance = AgriOsApp::class.java.getDeclaredField("instance").apply { isAccessible = true }
        previousApp = instance.get(null)
        val app = AgriOsApp()
        AgriOsApp::class.java.getDeclaredField("database").apply { isAccessible = true }.set(app, db)
        instance.set(null, app)
        replacedApp = true
        val state = MutableStateFlow<AuthStateEntity?>(null)
        val dao = object : AuthDao {
            override fun observeAuthState() = state
            override suspend fun getAuthState() = state.value
            override suspend fun saveAuthState(stateValue: AuthStateEntity) { state.value = stateValue }
            override suspend fun clearAuth() { state.value = null }
        }
        val fakeApi = Proxy.newProxyInstance(AgriOsApi::class.java.classLoader, arrayOf(AgriOsApi::class.java)) { _, method, args ->
            when (method.name) {
                "requestOtp" -> Response.success(OtpResponseDto(devOtp = secret))
                "verifyOtp" -> {
                    submittedCorrectly = (args?.firstOrNull() as? OtpVerifyDto)?.otpCode == secret
                    Response.success(AuthResponseDto("synthetic-session", null, "synthetic-user", "synthetic-tenant", "FARMER"))
                }
                else -> error("Unexpected test API method")
            }
        } as AgriOsApi
        compose.runOnUiThread { vm = AuthViewModel(context.applicationContext as Application) }
        // Device-key startup sees an empty in-memory DB and cannot send a request.
        compose.waitUntil(5_000) { vm.uiState.value.step == AuthStep.MOBILE_INPUT }
        AuthViewModel::class.java.getDeclaredField("authRepository").apply { isAccessible = true }
            .set(vm, AuthRepository(fakeApi, dao, "synthetic-device", "synthetic"))
        compose.setContent { MaterialTheme { AuthScreen({ verified = true }, vm) } }
        compose.runOnIdle { vm.updateMobile("9900000000"); vm.requestOtp() }
        compose.waitUntil(5_000) { vm.uiState.value.step == AuthStep.OTP_INPUT }
        compose.waitForIdle()
    }

    @After fun release() {
        if (::vm.isInitialized) compose.runOnIdle { vm.goBackToMobile() }
        if (replacedApp) AgriOsApp::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, previousApp)
        if (::db.isInitialized) db.close()
    }

    @Test fun privacyAndVisualMasking() {
        for (unmerged in listOf(false, true)) {
            val nodes = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = unmerged).fetchSemanticsNodes()
            assertTrue("Nonempty semantics traversal", nodes.isNotEmpty())
            assertTrue("Code absent from semantics", nodes.none { it.config.toString().contains(secret) })
            assertTrue("No development secret message", nodes.none { it.config.toString().contains("Dev OTP:") })
        }
        val root = appRoot()
        assertTrue("Correct accessibility root", root?.packageName?.toString() == "com.agrios.app.otpprivacy")
        assertTrue("Code absent from hierarchy", root != null && !exposes(root, secret))
        assertTrue("No development message in hierarchy", root != null && !exposes(root, "Dev OTP:"))
        // In-memory pixel comparison: same-length different digits must render
        // identically. No screenshot is saved or sent to a report.
        val before = compose.onRoot().captureToImage().toPixelMap()
        compose.runOnIdle { vm.updateOtp(secret.reversed()) }
        compose.waitForIdle()
        val after = compose.onRoot().captureToImage().toPixelMap()
        var identical = before.width == after.width && before.height == after.height
        if (identical) outer@ for (y in 0 until before.height) for (x in 0 until before.width) {
            if (before[x, y] != after[x, y]) { identical = false; break@outer }
        }
        assertTrue("Rendered code masked independently of digits", identical)
        println("OTP_PRIVACY merged=true unmerged=true hierarchy=true dev_message_absent=true pixel_mask=true")
    }

    @Test fun autoFillVerificationClearsProductionUiState() {
        compose.onNodeWithText("Verify / सत्यापित करें").performClick()
        compose.waitUntil(5_000) { verified }
        assertTrue("Correct synthetic in-memory submission", submittedCorrectly)
        assertTrue("Successful verification clears code", vm.uiState.value.otpCode.isEmpty())
        println("OTP_PRIVACY autofill=true successful_state_clear=true")
    }

    @Test fun accessibilityAndKeyboardRemainOperable() {
        val root = appRoot()
        val field = root?.let { findField(it) }
        assertTrue("OTP control discoverable", field != null)
        val actions = field!!.actionList.map { it.id }
        val focus = field.isFocusable && actions.contains(AccessibilityNodeInfo.ACTION_FOCUS)
        val edit = actions.contains(AccessibilityNodeInfo.ACTION_SET_TEXT)
        println("OTP_ACCESSIBILITY discoverable=true focus_action=$focus edit_action=$edit password=${field.isPassword}")
        compose.runOnIdle { vm.updateOtp("") }
        compose.waitForIdle()
        assertTrue("Accessible focus succeeds", field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
        val bounds = android.graphics.Rect().also { field.getBoundsInScreen(it) }
        val now = android.os.SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, android.os.SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            instrumentation.uiAutomation.injectInputEvent(event, true)
            event.recycle()
        }
        instrumentation.waitForIdleSync()
        // Use Compose's IME edit-command path, not emulator hardware key mapping.
        // This tests normal text entry without putting the code in shell arguments.
        compose.onNode(hasSetTextAction()).performTextInput(secret)
        compose.waitUntil(5_000) { vm.uiState.value.otpCode.length == 6 }
        val keyboard = vm.uiState.value.otpCode == secret
        println("OTP_ACCESSIBILITY ime_entry=$keyboard")
        val bundle = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, secret.reversed()) }
        val setText = if (edit) field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle) else false
        compose.waitUntil(5_000) { vm.uiState.value.otpCode == secret.reversed() }
        for (unmerged in listOf(false, true)) {
            val nodes = compose.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = unmerged).fetchSemanticsNodes()
            assertTrue("Edited code absent from semantics", nodes.none { it.config.toString().contains(secret.reversed()) })
        }
        val editedRoot = appRoot()
        assertTrue("Edited code absent from hierarchy", editedRoot != null && !exposes(editedRoot, secret.reversed()))
        // Dismiss the IME through the standard accessibility Back action so the
        // centered form's lower button is not occluded by the keyboard.
        instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        compose.waitForIdle()
        var verify = appRoot()?.let { findVerify(it) }
        while (verify != null && verify.actionList.none { it.id == AccessibilityNodeInfo.ACTION_CLICK }) {
            verify = verify.parent
        }
        val verifyAccessible = verify?.isEnabled == true && verify.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }
        println("OTP_ACCESSIBILITY set_text=$setText verify_action=$verifyAccessible")
        assertTrue("Normal keyboard entry works", keyboard)
        assertTrue("Accessible focus and editing available", focus && edit && setText)
        assertTrue("Verification action accessible", verifyAccessible)
        assertTrue("Accessible verification executes", verify!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        compose.waitUntil(5_000) { verified }
        assertTrue("Accessible verification clears code", vm.uiState.value.otpCode.isEmpty())
    }

    private fun findField(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isPassword) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findField(it)?.let { found -> return found } }
        return null
    }

    private fun findVerify(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.text?.toString()?.contains("Verify") == true ||
            node.contentDescription?.toString()?.contains("Verify") == true) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let {
            findVerify(it)?.let { found -> return found }
        }
        return null
    }

    private fun appRoot(): AccessibilityNodeInfo? {
        instrumentation.waitForIdleSync()
        return instrumentation.uiAutomation.windows.mapNotNull { it.root }
            .firstOrNull { it.packageName?.toString() == "com.agrios.app.otpprivacy" }
            ?: instrumentation.uiAutomation.rootInActiveWindow?.takeIf {
                it.packageName?.toString() == "com.agrios.app.otpprivacy"
            }
    }

    private fun exposes(node: AccessibilityNodeInfo, value: String): Boolean {
        val fields = mutableListOf<CharSequence?>(node.text, node.contentDescription, node.hintText, node.error, node.extras.toString())
        if (android.os.Build.VERSION.SDK_INT >= 28) fields.addAll(listOf(node.tooltipText, node.paneTitle))
        if (fields.any { it?.toString()?.contains(value) == true }) return true
        for (i in 0 until node.childCount) node.getChild(i)?.let { if (exposes(it, value)) return true }
        return false
    }
}

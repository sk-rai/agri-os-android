package com.agrios.app.core.util

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Manages language preference for the app.
 * Stored locally, used for form labels and keyboard hints.
 */
object LanguageManager {

    private const val PREFS_NAME = "agrios_language_prefs"
    private const val KEY_LANGUAGE = "selected_language"
    private const val KEY_SETUP_DONE = "language_setup_done"
    private const val DEFAULT_LANGUAGE = "hi" // Hindi default for rural India

    private lateinit var prefs: SharedPreferences

    val supportedLanguages = listOf(
        LanguageOption("en", "English", "English"),
        LanguageOption("hi", "Hindi", "Hindi"),
        LanguageOption("kn", "Kannada", "English fallback"),
        LanguageOption("mr", "Marathi", "English fallback"),
        LanguageOption("pa", "Punjabi", "English fallback")
    )

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getLanguage(): String = prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE

    fun setLanguage(langCode: String) {
        prefs.edit { putString(KEY_LANGUAGE, langCode) }
    }

    fun isSetupDone(): Boolean = prefs.getBoolean(KEY_SETUP_DONE, false)

    fun markSetupDone() {
        prefs.edit { putBoolean(KEY_SETUP_DONE, true) }
    }

    fun isHindi(): Boolean = getLanguage() == "hi"

    fun backendLabelLanguage(): String = getLanguage()

    /**
     * Get localized string based on current language preference.
     * Pass English and Hindi variants.
     */
    fun localize(en: String, hi: String): String {
        return if (isHindi()) hi else en
    }
}

data class LanguageOption(
    val code: String,
    val displayName: String,
    val helperText: String
)

/**
 * Localized labels for the app.
 * All UI text goes through here for bilingual support.
 */
object Labels {
    // Common
    val save get() = LanguageManager.localize("Save", "सहेजें")
    val back get() = LanguageManager.localize("Back", "वापस")
    val cancel get() = LanguageManager.localize("Cancel", "रद्द करें")
    val next get() = LanguageManager.localize("Next", "आगे")
    val search get() = LanguageManager.localize("Search", "खोजें")
    val select get() = LanguageManager.localize("Select", "चुनें")
    val optional get() = LanguageManager.localize("optional", "वैकल्पिक")
    val required get() = LanguageManager.localize("required", "आवश्यक")

    // Farmer Enrollment
    val enrollFarmer get() = LanguageManager.localize("Enroll Farmer", "किसान नामांकन")
    val mobileNumber get() = LanguageManager.localize("Mobile Number", "मोबाइल नंबर")
    val farmerName get() = LanguageManager.localize("Farmer Name", "किसान का नाम")
    val selectState get() = LanguageManager.localize("Select State", "राज्य चुनें")
    val selectDistrict get() = LanguageManager.localize("Select District", "जिला चुनें")
    val selectBlock get() = LanguageManager.localize("Select Block/Tehsil", "ब्लॉक/तहसील चुनें")
    val selectVillage get() = LanguageManager.localize("Select Village", "गाँव चुनें")
    val cropsByseason get() = LanguageManager.localize("Crops by Season", "मौसम अनुसार फसलें")
    val kharif get() = LanguageManager.localize("Kharif (Monsoon)", "खरीफ (बरसात)")
    val rabi get() = LanguageManager.localize("Rabi (Winter)", "रबी (सर्दी)")
    val zaid get() = LanguageManager.localize("Zaid (Summer)", "जायद (गर्मी)")
    val selectCrops get() = LanguageManager.localize("Select crops", "फसलें चुनें")
    val languagePreference get() = LanguageManager.localize("Language", "भाषा")

    // Parcel
    val registerParcel get() = LanguageManager.localize("Register Parcel", "भूखंड पंजीकरण")
    val area get() = LanguageManager.localize("Area", "क्षेत्रफल")
    val ownership get() = LanguageManager.localize("Ownership", "स्वामित्व")
    val gpsLocation get() = LanguageManager.localize("GPS Location", "GPS स्थान")
    val addParcel get() = LanguageManager.localize("Add Parcel", "भूखंड जोड़ें")
    val noGps get() = LanguageManager.localize("No GPS (area only)", "GPS नहीं (केवल क्षेत्रफल)")
    val pinDrop get() = LanguageManager.localize("Pin Drop (centroid)", "पिन ड्रॉप (केंद्र बिंदु)")
    val walkBoundary get() = LanguageManager.localize("Walk Boundary", "सीमा चलें")

    // Area units
    val bigha get() = LanguageManager.localize("Bigha", "बीघा")
    val biswa get() = LanguageManager.localize("Biswa", "बिस्वा")
    val acre get() = LanguageManager.localize("Acre", "एकड़")
    val hectare get() = LanguageManager.localize("Hectare", "हेक्टेयर")
    val katha get() = LanguageManager.localize("Katha", "कट्ठा")
    val guntha get() = LanguageManager.localize("Guntha", "गुंठा")

    fun getUnitLabel(unit: String): String = when (unit) {
        "BIGHA" -> bigha
        "BISWA" -> biswa
        "ACRE" -> acre
        "HECTARE" -> hectare
        "KATHA" -> katha
        "GUNTHA" -> guntha
        else -> unit
    }

    // Ownership
    val owned get() = LanguageManager.localize("Owned", "स्वामित्व")
    val leased get() = LanguageManager.localize("Leased", "पट्टे पर")
    val shared get() = LanguageManager.localize("Shared", "साझा")

    fun getOwnershipLabel(type: String): String = when (type) {
        "OWNED" -> owned
        "LEASED" -> leased
        "SHARED" -> shared
        else -> type
    }

    // Sync
    val saved get() = LanguageManager.localize("Saved on phone", "फोन पर सहेजा")
    val syncing get() = LanguageManager.localize("Syncing", "सिंक हो रहा")
    val synced get() = LanguageManager.localize("Synced", "सिंक हो गया")
    val syncFailed get() = LanguageManager.localize("Sync failed", "सिंक विफल")
    val needsAttention get() = LanguageManager.localize("Needs attention", "ध्यान दें")
}

package com.example.livenotification

import android.content.Context

/**
 * Per-feature switches for the two capabilities that are still rough.
 *
 * Both default to off: the verification-code capture and the flashlight chip are usable but
 * unfinished, so a fresh install should not silently enable them.
 */
object FeaturePreferences {

    private const val PREF_NAME = "feature_prefs"

    private const val KEY_VERIFICATION = "verification_enabled"
    private const val KEY_FLASHLIGHT = "flashlight_enabled"
    private const val KEY_FLASHLIGHT_CHIP = "flashlight_chip_enabled"

    /** Verification-code capture from notifications and SMS. */
    fun isVerificationEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_VERIFICATION, false)

    fun setVerificationEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_VERIFICATION, enabled).apply()
    }

    /** Flashlight chip / torch listener. */
    fun isFlashlightEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLASHLIGHT, false)

    fun setFlashlightEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLASHLIGHT, enabled).apply()
    }

    /** Show the top-left chip when the torch turns on or off. */
    fun isFlashlightChipEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLASHLIGHT_CHIP, true)

    fun setFlashlightChipEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_FLASHLIGHT_CHIP, enabled).apply()
    }
}

/** Remembers whether the first-run guide has been completed. */
object OnboardingPreference {

    private const val PREF_NAME = "onboarding"
    private const val KEY_DONE = "done"

    fun isDone(context: Context): Boolean =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_DONE, false)

    fun setDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, done).apply()
    }
}

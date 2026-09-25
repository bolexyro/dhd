package com.phonecontrol.assistant.data

import android.content.SharedPreferences
import androidx.core.content.edit

class PermissionSetupRepository(private val preferences: SharedPreferences) {
    fun isOnboardingCompleted(): Boolean =
        preferences.getBoolean(KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED, false)

    fun isNotificationStepHandled(default: Boolean = false): Boolean =
        preferences.getBoolean(KEY_NOTIFICATION_SETUP_STEP_HANDLED, default)

    fun setNotificationStepHandled(handled: Boolean) {
        preferences.edit { putBoolean(KEY_NOTIFICATION_SETUP_STEP_HANDLED, handled) }
    }

    fun markOnboardingCompleted() {
        preferences.edit {
            putBoolean(KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED, true)
            remove(KEY_NOTIFICATION_SETUP_STEP_HANDLED)
        }
    }

    companion object {
        const val PREFERENCES_NAME = "dhd_permission_setup"
        const val KEY_FIRST_RUN_PERMISSION_ONBOARDING_COMPLETED = "first_run_permission_onboarding_completed"
        const val KEY_NOTIFICATION_SETUP_STEP_HANDLED = "notification_setup_step_handled"
    }
}

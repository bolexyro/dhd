package com.phonecontrol.assistant.data

import com.phonecontrol.assistant.testing.InMemorySharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSetupRepositoryTest {
    private val preferences = InMemorySharedPreferences()
    private val repository = PermissionSetupRepository(preferences)

    @Test
    fun `fresh install has not completed onboarding and uses the caller default for the notification step`() {
        assertFalse(repository.isOnboardingCompleted())
        assertFalse(repository.isNotificationStepHandled())
        assertTrue(repository.isNotificationStepHandled(default = true))
    }

    @Test
    fun `handled notification step is stored under its persisted key`() {
        repository.setNotificationStepHandled(true)

        assertTrue(repository.isNotificationStepHandled())
        assertEquals(mapOf("notification_setup_step_handled" to true), preferences.snapshot)
    }

    @Test
    fun `completing onboarding clears the notification step`() {
        repository.setNotificationStepHandled(true)
        repository.markOnboardingCompleted()

        assertTrue(repository.isOnboardingCompleted())
        assertEquals(mapOf("first_run_permission_onboarding_completed" to true), preferences.snapshot)
    }
}

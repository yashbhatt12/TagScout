package com.snainfotech.tagscout.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.snainfotech.tagscout.data.auth.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val isLoading: Boolean = false,
    val error: String? = null,

    // Registration fields
    val name: String = "",
    val email: String = "",
    val mobile: String = "",
    val companyName: String = "",
    val password: String = "",
    val confirmPassword: String = "",

    // Navigation triggers
    val registrationComplete: Boolean = false,
    val loginComplete: Boolean = false,
    val emailVerified: Boolean = false,

    // Verification
    val verificationEmailSent: Boolean = false,
    val resendCooldown: Boolean = false
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    // ── Field updates ──────────────────────────────────────────

    fun updateName(value: String) { _state.value = _state.value.copy(name = value) }
    fun updateEmail(value: String) { _state.value = _state.value.copy(email = value) }
    fun updateMobile(value: String) { _state.value = _state.value.copy(mobile = value) }
    fun updateCompanyName(value: String) { _state.value = _state.value.copy(companyName = value) }
    fun updatePassword(value: String) { _state.value = _state.value.copy(password = value) }
    fun updateConfirmPassword(value: String) { _state.value = _state.value.copy(confirmPassword = value) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    // ── Registration ───────────────────────────────────────────

    fun register() {
        val s = _state.value

        // Validate
        if (s.name.isBlank()) { _state.value = s.copy(error = "Name is required"); return }
        if (s.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(s.email).matches()) {
            _state.value = s.copy(error = "Enter a valid email address"); return
        }
        if (s.mobile.isBlank() || s.mobile.length < 10) {
            _state.value = s.copy(error = "Enter a valid mobile number"); return
        }
        if (s.companyName.isBlank()) { _state.value = s.copy(error = "Company name is required"); return }
        if (s.password.length < 6) {
            _state.value = s.copy(error = "Password must be at least 6 characters"); return
        }
        if (s.password != s.confirmPassword) {
            _state.value = s.copy(error = "Passwords do not match"); return
        }

        _state.value = s.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = authRepository.register(
                email = s.email.trim(),
                password = s.password,
                name = s.name.trim(),
                mobile = s.mobile.trim(),
                companyName = s.companyName.trim()
            )
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        registrationComplete = true,
                        verificationEmailSent = true
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Registration failed"
                    )
                }
            )
        }
    }

    // ── Login ──────────────────────────────────────────────────

    fun login() {
        val s = _state.value

        if (s.email.isBlank()) { _state.value = s.copy(error = "Enter your email"); return }
        if (s.password.isBlank()) { _state.value = s.copy(error = "Enter your password"); return }

        _state.value = s.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = authRepository.login(s.email.trim(), s.password)
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        loginComplete = true
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Login failed"
                    )
                }
            )
        }
    }

    // ── Email verification ─────────────────────────────────────

    fun checkEmailVerified() {
        _state.value = _state.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = authRepository.refreshUser()
            result.fold(
                onSuccess = { verified ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        emailVerified = verified,
                        error = if (!verified) "Email not verified yet. Check your inbox." else null
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        isLoading = false,
                        error = e.message ?: "Could not check verification status"
                    )
                }
            )
        }
    }

    fun resendVerificationEmail() {
        _state.value = _state.value.copy(resendCooldown = true, error = null)

        viewModelScope.launch {
            val result = authRepository.resendVerificationEmail()
            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        verificationEmailSent = true,
                        error = null
                    )
                    // Simple cooldown: reset after 30 seconds
                    kotlinx.coroutines.delay(30_000)
                    _state.value = _state.value.copy(resendCooldown = false)
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        resendCooldown = false,
                        error = e.message ?: "Could not resend email"
                    )
                }
            )
        }
    }

    // ── Logout ─────────────────────────────────────────────────

    fun logout() {
        authRepository.logout()
        _state.value = AuthState() // reset to fresh state
    }
}

class AuthViewModelFactory(
    private val authRepository: AuthRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AuthViewModel(authRepository) as T
    }
}
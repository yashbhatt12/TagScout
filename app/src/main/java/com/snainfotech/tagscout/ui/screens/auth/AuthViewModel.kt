package com.snainfotech.tagscout.ui.screens.auth

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.MultiFactorResolver
import com.snainfotech.tagscout.data.auth.AuthRepository
import com.snainfotech.tagscout.data.auth.LoginResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AuthState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val name: String = "",
    val email: String = "",
    val mobile: String = "",
    val companyName: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val registrationComplete: Boolean = false,
    val loginComplete: Boolean = false,
    val emailVerified: Boolean = false,
    val verificationEmailSent: Boolean = false,
    val resendCooldown: Boolean = false,
    val mfaChallengeRequired: Boolean = false,
    val mfaEnrollmentRequired: Boolean = false,
    val mfaCodeSent: Boolean = false,
    val mfaVerificationId: String? = null,
    val mfaSmsCode: String = "",
    val mfaPhoneNumber: String = "",
    val mfaComplete: Boolean = false
)

class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()
    private var mfaResolver: MultiFactorResolver? = null

    fun updateName(value: String) { _state.value = _state.value.copy(name = value) }
    fun updateEmail(value: String) { _state.value = _state.value.copy(email = value) }
    fun updateMobile(value: String) { _state.value = _state.value.copy(mobile = value) }
    fun updateCompanyName(value: String) { _state.value = _state.value.copy(companyName = value) }
    fun updatePassword(value: String) { _state.value = _state.value.copy(password = value) }
    fun updateConfirmPassword(value: String) { _state.value = _state.value.copy(confirmPassword = value) }
    fun updateMfaSmsCode(value: String) { _state.value = _state.value.copy(mfaSmsCode = value) }
    fun updateMfaPhoneNumber(value: String) { _state.value = _state.value.copy(mfaPhoneNumber = value) }
    fun clearError() { _state.value = _state.value.copy(error = null) }

    // ── Registration ───────────────────────────────────────────

    fun register() {
        val s = _state.value
        if (s.name.isBlank()) { _state.value = s.copy(error = "Name is required"); return }
        if (s.email.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(s.email).matches()) {
            _state.value = s.copy(error = "Enter a valid email address"); return
        }
        if (s.mobile.isBlank() || s.mobile.length < 10) {
            _state.value = s.copy(error = "Enter a valid mobile number"); return
        }
        if (s.companyName.isBlank()) { _state.value = s.copy(error = "Company name is required"); return }
        if (s.password.length < 6) { _state.value = s.copy(error = "Password must be at least 6 characters"); return }
        if (s.password != s.confirmPassword) { _state.value = s.copy(error = "Passwords do not match"); return }

        _state.value = s.copy(isLoading = true, error = null)
        viewModelScope.launch {
            authRepository.register(s.email.trim(), s.password, s.name.trim(), s.mobile.trim(), s.companyName.trim())
                .fold(
                    onSuccess = {
                        _state.value = _state.value.copy(isLoading = false, registrationComplete = true, verificationEmailSent = true)
                    },
                    onFailure = { e ->
                        _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Registration failed")
                    }
                )
        }
    }

    // ── Login (with MFA) ───────────────────────────────────────

    fun login(activity: Activity) {
        val s = _state.value
        if (s.email.isBlank()) { _state.value = s.copy(error = "Enter your email"); return }
        if (s.password.isBlank()) { _state.value = s.copy(error = "Enter your password"); return }

        _state.value = s.copy(isLoading = true, error = null)
        viewModelScope.launch {
            when (val result = authRepository.login(s.email.trim(), s.password)) {
                is LoginResult.Success -> {
                    // MFA enrollment is disabled until Firebase Blaze plan is active.
                    // To re-enable: uncomment the isMfaEnrolled check below and remove
                    // the direct loginComplete = true line.
                    //
                    // if (!authRepository.isMfaEnrolled) {
                    //     _state.value = _state.value.copy(isLoading = false, mfaEnrollmentRequired = true)
                    // } else {
                    //     _state.value = _state.value.copy(isLoading = false, loginComplete = true)
                    // }
                    _state.value = _state.value.copy(isLoading = false, loginComplete = true)
                }
                is LoginResult.MfaRequired -> {
                    mfaResolver = result.resolver
                    authRepository.startMfaChallenge(
                        resolver = result.resolver, activity = activity,
                        onCodeSent = { vid ->
                            _state.value = _state.value.copy(
                                isLoading = false, mfaChallengeRequired = true,
                                mfaCodeSent = true, mfaVerificationId = vid
                            )
                        },
                        onError = { error ->
                            _state.value = _state.value.copy(isLoading = false, error = "MFA error: $error")
                        }
                    )
                }
                is LoginResult.Failure -> {
                    _state.value = _state.value.copy(isLoading = false, error = result.error)
                }
            }
        }
    }

    // ── MFA Enrollment ─────────────────────────────────────────

    fun startMfaEnrollment(activity: Activity) {
        val phone = _state.value.mfaPhoneNumber.trim()
        if (phone.isBlank() || phone.length < 10) {
            _state.value = _state.value.copy(error = "Enter a valid phone number with country code (e.g. +91...)")
            return
        }
        _state.value = _state.value.copy(isLoading = true, error = null)
        authRepository.startMfaEnrollment(
            phoneNumber = phone, activity = activity,
            onCodeSent = { vid ->
                _state.value = _state.value.copy(isLoading = false, mfaCodeSent = true, mfaVerificationId = vid)
            },
            onError = { error ->
                _state.value = _state.value.copy(isLoading = false, error = "Could not send SMS: $error")
            }
        )
    }

    fun completeMfaEnrollment() {
        val s = _state.value
        val vid = s.mfaVerificationId
        if (vid == null || s.mfaSmsCode.length < 6) {
            _state.value = s.copy(error = "Enter the 6-digit code from the SMS"); return
        }
        _state.value = s.copy(isLoading = true, error = null)
        viewModelScope.launch {
            authRepository.completeMfaEnrollment(vid, s.mfaSmsCode).fold(
                onSuccess = { _state.value = _state.value.copy(isLoading = false, mfaComplete = true, loginComplete = true) },
                onFailure = { e -> _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Enrollment failed") }
            )
        }
    }

    // ── MFA Challenge ──────────────────────────────────────────

    fun completeMfaChallenge() {
        val s = _state.value
        val vid = s.mfaVerificationId
        val resolver = mfaResolver
        if (vid == null || resolver == null || s.mfaSmsCode.length < 6) {
            _state.value = s.copy(error = "Enter the 6-digit code from the SMS"); return
        }
        _state.value = s.copy(isLoading = true, error = null)
        viewModelScope.launch {
            authRepository.completeMfaChallenge(resolver, vid, s.mfaSmsCode).fold(
                onSuccess = {
                    mfaResolver = null
                    _state.value = _state.value.copy(isLoading = false, mfaComplete = true, loginComplete = true)
                },
                onFailure = { e -> _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Verification failed") }
            )
        }
    }

    fun skipMfaEnrollment() {
        _state.value = _state.value.copy(mfaEnrollmentRequired = false, loginComplete = true)
    }

    // ── Email verification ─────────────────────────────────────

    fun checkEmailVerified() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            authRepository.refreshUser().fold(
                onSuccess = { verified ->
                    _state.value = _state.value.copy(
                        isLoading = false, emailVerified = verified,
                        error = if (!verified) "Email not verified yet. Check your inbox." else null
                    )
                },
                onFailure = { e -> _state.value = _state.value.copy(isLoading = false, error = e.message ?: "Could not check") }
            )
        }
    }

    fun resendVerificationEmail() {
        _state.value = _state.value.copy(resendCooldown = true, error = null)
        viewModelScope.launch {
            authRepository.resendVerificationEmail().fold(
                onSuccess = {
                    _state.value = _state.value.copy(verificationEmailSent = true, error = null)
                    kotlinx.coroutines.delay(30_000)
                    _state.value = _state.value.copy(resendCooldown = false)
                },
                onFailure = { e -> _state.value = _state.value.copy(resendCooldown = false, error = e.message ?: "Could not resend") }
            )
        }
    }

    fun logout() {
        authRepository.logout()
        mfaResolver = null
        _state.value = AuthState()
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
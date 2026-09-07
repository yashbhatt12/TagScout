package com.snainfotech.tagscout.data.auth

import android.app.Activity
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthMultiFactorException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.MultiFactorResolver
import com.google.firebase.auth.PhoneAuthCredential
import com.google.firebase.auth.PhoneAuthOptions
import com.google.firebase.auth.PhoneAuthProvider
import com.google.firebase.auth.PhoneMultiFactorGenerator
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit

data class UserProfile(
    val name: String = "",
    val email: String = "",
    val mobile: String = "",
    val companyName: String = ""
)

sealed class LoginResult {
    data class Success(val user: FirebaseUser) : LoginResult()
    data class MfaRequired(val resolver: MultiFactorResolver) : LoginResult()
    data class Failure(val error: String) : LoginResult()
}

class AuthRepository {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    val currentUser: FirebaseUser?
        get() = auth.currentUser

    val isLoggedIn: Boolean
        get() = auth.currentUser != null

    val isEmailVerified: Boolean
        get() = auth.currentUser?.isEmailVerified == true

    val isSessionFresh: Boolean
        get() {
            val user = auth.currentUser ?: return false
            val lastSignIn = user.metadata?.lastSignInTimestamp ?: return false
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            return (System.currentTimeMillis() - lastSignIn) < thirtyDaysMs
        }

    val isMfaEnrolled: Boolean
        get() {
            val user = auth.currentUser ?: return false
            return user.multiFactor.enrolledFactors.isNotEmpty()
        }

    // ── Registration ───────────────────────────────────────────

    suspend fun register(
        email: String, password: String,
        name: String, mobile: String, companyName: String
    ): Result<FirebaseUser> {
        return try {
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: return Result.failure(Exception("Registration failed"))
            val profile = hashMapOf(
                "name" to name, "email" to email,
                "mobile" to mobile, "companyName" to companyName,
                "createdAt" to com.google.firebase.Timestamp.now()
            )
            firestore.collection("users").document(user.uid).set(profile).await()
            user.sendEmailVerification().await()
            Result.success(user)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Login (with MFA support) ───────────────────────────────

    suspend fun login(email: String, password: String): LoginResult {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: return LoginResult.Failure("Login failed")
            LoginResult.Success(user)
        } catch (e: FirebaseAuthMultiFactorException) {
            LoginResult.MfaRequired(e.resolver)
        } catch (e: Exception) {
            LoginResult.Failure(e.message ?: "Login failed")
        }
    }

    // ── MFA Enrollment ─────────────────────────────────────────

    fun startMfaEnrollment(
        phoneNumber: String, activity: Activity,
        onCodeSent: (String) -> Unit, onError: (String) -> Unit
    ) {
        val user = auth.currentUser
        if (user == null) { onError("Not logged in"); return }

        user.multiFactor.session.addOnCompleteListener { task ->
            if (!task.isSuccessful) {
                onError(task.exception?.message ?: "Could not start MFA session"); return@addOnCompleteListener
            }
            val options = PhoneAuthOptions.newBuilder(auth)
                .setPhoneNumber(phoneNumber)
                .setTimeout(60L, TimeUnit.SECONDS)
                .setActivity(activity)
                .setMultiFactorSession(task.result)
                .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                    override fun onVerificationCompleted(credential: PhoneAuthCredential) {}
                    override fun onVerificationFailed(e: FirebaseException) {
                        onError(e.message ?: "Verification failed")
                    }
                    override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                        onCodeSent(verificationId)
                    }
                })
                .build()
            PhoneAuthProvider.verifyPhoneNumber(options)
        }
    }

    suspend fun completeMfaEnrollment(
        verificationId: String, smsCode: String, displayName: String = "Phone"
    ): Result<Unit> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
            auth.currentUser?.multiFactor?.enroll(assertion, displayName)?.await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── MFA Challenge ──────────────────────────────────────────

    fun startMfaChallenge(
        resolver: MultiFactorResolver, activity: Activity,
        onCodeSent: (String) -> Unit, onError: (String) -> Unit
    ) {
        val hint = resolver.hints.firstOrNull()
        if (hint == null) { onError("No MFA factors found"); return }

        val options = PhoneAuthOptions.newBuilder(auth)
            .setMultiFactorHint(hint as com.google.firebase.auth.PhoneMultiFactorInfo)
            .setTimeout(60L, TimeUnit.SECONDS)
            .setMultiFactorSession(resolver.session)
            .setActivity(activity)
            .setCallbacks(object : PhoneAuthProvider.OnVerificationStateChangedCallbacks() {
                override fun onVerificationCompleted(credential: PhoneAuthCredential) {}
                override fun onVerificationFailed(e: FirebaseException) {
                    onError(e.message ?: "Verification failed")
                }
                override fun onCodeSent(verificationId: String, token: PhoneAuthProvider.ForceResendingToken) {
                    onCodeSent(verificationId)
                }
            })
            .build()
        PhoneAuthProvider.verifyPhoneNumber(options)
    }

    suspend fun completeMfaChallenge(
        resolver: MultiFactorResolver, verificationId: String, smsCode: String
    ): Result<FirebaseUser> {
        return try {
            val credential = PhoneAuthProvider.getCredential(verificationId, smsCode)
            val assertion = PhoneMultiFactorGenerator.getAssertion(credential)
            val result = resolver.resolveSignIn(assertion).await()
            val user = result.user ?: return Result.failure(Exception("MFA sign-in failed"))
            Result.success(user)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Email verification ─────────────────────────────────────

    suspend fun resendVerificationEmail(): Result<Unit> {
        return try {
            auth.currentUser?.sendEmailVerification()?.await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun refreshUser(): Result<Boolean> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not logged in"))
            user.reload().await()
            Result.success(user.isEmailVerified)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Profile ────────────────────────────────────────────────

    suspend fun getUserProfile(): Result<UserProfile> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not logged in"))
            val doc = firestore.collection("users").document(user.uid).get().await()
            Result.success(UserProfile(
                name = doc.getString("name") ?: "",
                email = doc.getString("email") ?: user.email ?: "",
                mobile = doc.getString("mobile") ?: "",
                companyName = doc.getString("companyName") ?: ""
            ))
        } catch (e: Exception) { Result.failure(e) }
    }

    fun logout() { auth.signOut() }
}
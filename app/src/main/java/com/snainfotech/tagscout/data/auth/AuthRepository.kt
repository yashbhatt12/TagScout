package com.snainfotech.tagscout.data.auth

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await

/**
 * User profile stored in Firestore (fields beyond what Firebase Auth holds natively).
 */
data class UserProfile(
    val name: String = "",
    val email: String = "",
    val mobile: String = "",
    val companyName: String = ""
)

/**
 * Wraps Firebase Auth + Firestore into clean suspend functions.
 *
 * Auth handles: registration, login, email verification, session state.
 * Firestore handles: storing the extra profile fields (name, mobile, company)
 * that Firebase Auth doesn't carry natively.
 */
class AuthRepository {

    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()

    // ── Current state ──────────────────────────────────────────────

    /** The currently signed-in user, or null if not signed in. */
    val currentUser: FirebaseUser?
        get() = auth.currentUser

    /** True if a user is signed in (regardless of email verification). */
    val isLoggedIn: Boolean
        get() = auth.currentUser != null

    /** True if the current user's email has been verified. */
    val isEmailVerified: Boolean
        get() = auth.currentUser?.isEmailVerified == true

    /**
     * True if the session is still fresh (signed in within the last 30 days).
     * Returns false if not logged in.
     */
    val isSessionFresh: Boolean
        get() {
            val user = auth.currentUser ?: return false
            val lastSignIn = user.metadata?.lastSignInTimestamp ?: return false
            val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000
            return (System.currentTimeMillis() - lastSignIn) < thirtyDaysMs
        }

    // ── Registration ───────────────────────────────────────────────

    /**
     * Creates a new account, saves the profile to Firestore, and sends
     * a verification email. Returns a Result wrapping the FirebaseUser.
     */
    suspend fun register(
        email: String,
        password: String,
        name: String,
        mobile: String,
        companyName: String
    ): Result<FirebaseUser> {
        return try {
            // Create the Firebase Auth account
            val result = auth.createUserWithEmailAndPassword(email, password).await()
            val user = result.user ?: return Result.failure(Exception("Registration failed"))

            // Save the extra profile fields to Firestore
            val profile = hashMapOf(
                "name" to name,
                "email" to email,
                "mobile" to mobile,
                "companyName" to companyName,
                "createdAt" to com.google.firebase.Timestamp.now()
            )
            firestore.collection("users").document(user.uid).set(profile).await()

            // Send verification email
            user.sendEmailVerification().await()

            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Login ──────────────────────────────────────────────────────

    /**
     * Signs in an existing user with email + password.
     */
    suspend fun login(email: String, password: String): Result<FirebaseUser> {
        return try {
            val result = auth.signInWithEmailAndPassword(email, password).await()
            val user = result.user ?: return Result.failure(Exception("Login failed"))
            Result.success(user)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Email verification ─────────────────────────────────────────

    /**
     * Resends the verification email to the current user.
     */
    suspend fun resendVerificationEmail(): Result<Unit> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not logged in"))
            user.sendEmailVerification().await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reloads the current user's profile from Firebase to check if
     * email verification status has changed (the local cached user
     * object doesn't update automatically).
     */
    suspend fun refreshUser(): Result<Boolean> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not logged in"))
            user.reload().await()
            Result.success(user.isEmailVerified)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Profile ────────────────────────────────────────────────────

    /**
     * Fetches the user's profile from Firestore.
     */
    suspend fun getUserProfile(): Result<UserProfile> {
        return try {
            val user = auth.currentUser ?: return Result.failure(Exception("Not logged in"))
            val doc = firestore.collection("users").document(user.uid).get().await()
            val profile = UserProfile(
                name = doc.getString("name") ?: "",
                email = doc.getString("email") ?: user.email ?: "",
                mobile = doc.getString("mobile") ?: "",
                companyName = doc.getString("companyName") ?: ""
            )
            Result.success(profile)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ── Logout ─────────────────────────────────────────────────────

    fun logout() {
        auth.signOut()
    }
}
package com.snainfotech.tagscout.data.wms

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.snainfotech.tagscout.data.auth.AuthReady
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * Product photos in Firebase Storage:
 *
 *   companies/{cid}/products/{productId}/{epochMillis}.jpg
 *
 * One photo per product for now (Product.imageUrl). Each upload gets a new
 * file name so cached copies never go stale; the previous file is deleted
 * after the product points at the new one.
 */
class ProductImageRepository(context: Context) {

    private val appContext = context.applicationContext
    private val storage = FirebaseStorage.getInstance()
    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    companion object {
        private const val TAG = "ProductImageRepository"
        const val MAX_EDGE = 1600
        private const val JPEG_QUALITY = 85
        private const val MIN_QUALITY = 55
        /** Comfortably under the 2 MB limit in storage.rules. */
        private const val MAX_BYTES = 1_500_000
    }

    // ── Whole flow ────────────────────────────────────────────

    /** Compress → upload → point the product at it → delete the previous photo. Returns the new URL. */
    suspend fun replaceImage(productId: String, source: Uri, previousUrl: String): Result<String> = runCatching {
        require(productId.isNotBlank()) { "Save the product before adding a photo" }
        val bytes = compress(source).getOrThrow()
        val url = upload(productId, bytes).getOrThrow()
        setImageUrl(productId, url).getOrThrow()
        if (previousUrl.isNotBlank() && previousUrl != url) deleteQuietly(previousUrl)
        url
    }

    /** Clears the product's photo and deletes the file. */
    suspend fun removeImage(productId: String, previousUrl: String): Result<Unit> = runCatching {
        setImageUrl(productId, "").getOrThrow()
        if (previousUrl.isNotBlank()) deleteQuietly(previousUrl)
    }

    // ── Steps ─────────────────────────────────────────────────

    /** Decodes [source] scaled to at most [MAX_EDGE] px (upright), as JPEG bytes. */
    suspend fun compress(source: Uri): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            val decoded = decode(source)
            val scaled = scaleDown(decoded, MAX_EDGE)
            try {
                var quality = JPEG_QUALITY
                var bytes: ByteArray
                do {
                    val out = ByteArrayOutputStream()
                    scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                    bytes = out.toByteArray()
                    quality -= 10
                } while (bytes.size > MAX_BYTES && quality >= MIN_QUALITY)
                bytes
            } finally {
                if (scaled !== decoded) scaled.recycle()
                decoded.recycle()
            }
        }
    }

    suspend fun upload(productId: String, bytes: ByteArray): Result<String> = runCatching {
        val cid = AuthReady.awaitUid() ?: error("Not signed in")
        val ref = storage.reference.child("companies/$cid/products/$productId/${System.currentTimeMillis()}.jpg")
        val metadata = StorageMetadata.Builder().setContentType("image/jpeg").build()
        ref.putBytes(bytes, metadata).await()
        ref.downloadUrl.await().toString()
    }

    private suspend fun setImageUrl(productId: String, url: String): Result<Unit> = runCatching {
        val cid = AuthReady.awaitUid() ?: error("Not signed in")
        firestore.collection("companies").document(cid)
            .collection("products").document(productId)
            .update(
                mapOf(
                    "imageUrl" to url,
                    "updatedAt" to Timestamp.now(),
                    "updatedBy" to (auth.currentUser?.uid ?: "")
                )
            ).await()
    }

    /** Best effort: a leftover file costs a little storage, never a failed save. */
    private suspend fun deleteQuietly(url: String) {
        runCatching { storage.getReferenceFromUrl(url).delete().await() }
            .onFailure { Log.w(TAG, "Could not delete old photo $url", it) }
    }

    // ── Decoding ──────────────────────────────────────────────

    private fun decode(source: Uri): Bitmap =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies EXIF rotation and can scale while decoding.
            val src = ImageDecoder.createSource(appContext.contentResolver, source)
            ImageDecoder.decodeBitmap(src) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                val longest = maxOf(w, h)
                if (longest > MAX_EDGE) {
                    val f = MAX_EDGE.toFloat() / longest
                    decoder.setTargetSize((w * f).toInt().coerceAtLeast(1), (h * f).toInt().coerceAtLeast(1))
                }
                // Software bitmap: hardware bitmaps can't be re-encoded.
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            // Android 8.x: sample down by powers of two while decoding.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            appContext.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            appContext.contentResolver.openInputStream(source)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("Could not read the image")
        }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val f = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * f).toInt().coerceAtLeast(1),
            (bitmap.height * f).toInt().coerceAtLeast(1),
            true
        )
    }
}
package com.snainfotech.tagscout.data.wms

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.server.ServerCalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * GRN (Goods Received Note) import from an Excel file, done on the server.
 *
 * The file is uploaded to Storage (companies/{companyId}/imports/{uid}/…xlsx),
 * then grnImportV1 reads and checks it — every row, with the same rules and
 * messages as before (SKU, EPC, warehouse and bin, serial, tag price, the
 * product type's per-piece fields, batches, EPCs already in stock) — and on
 * confirm re-checks and saves it, creating batches as needed, advancing the
 * GRN number when everything was saved, and deleting the upload.
 *
 * The Download template is still made on the phone (GrnExcelParser).
 */
class GrnService {

    /** One row as the Inward screen shows it. */
    data class ValidatedRow(
        val rowNumber: Int,
        val sku: String,
        val epc: String,
        val serialNumber: String,
        val binCode: String,
        val warehouseName: String,
        val notes: String,
        val errors: List<String>,
    )

    sealed class ValidationResult {
        data class AllClean(val rows: List<ValidatedRow>) : ValidationResult()
        data class HasErrors(
            val rows: List<ValidatedRow>,
            val errorRowCount: Int,
            val totalRowCount: Int
        ) : ValidationResult()
        /** The file itself couldn't be used (not Excel, missing columns, too big…). */
        data class FileError(val message: String) : ValidationResult()
    }

    sealed class CommitResult {
        data class Success(val inwardedCount: Int, val grnReference: String) : CommitResult()
        data class PartialFailure(
            val inwardedCount: Int,
            val failedRowNumber: Int,
            val error: String
        ) : CommitResult()
        data class Failure(val error: String) : CommitResult()
    }

    /** The uploaded file's Storage path; commit() needs it. */
    data class Checked(val path: String, val result: ValidationResult)

    /** Uploads the picked file and asks the server to check every row. Nothing is saved yet. */
    suspend fun validateFile(context: Context, uri: Uri): Checked {
        val companyId = AuthReady.awaitCompanyId() ?: return Checked("", ValidationResult.FileError("Not logged in"))
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Checked("", ValidationResult.FileError("Not logged in"))

        val bytes = try {
            withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
        } catch (e: Exception) {
            null
        } ?: return Checked("", ValidationResult.FileError("Could not read the file"))
        if (bytes.size > MAX_FILE_BYTES) {
            return Checked("", ValidationResult.FileError("The file is over 10 MB. Split it into smaller files."))
        }

        val path = "companies/$companyId/imports/$uid/grn-${System.currentTimeMillis()}.xlsx"
        try {
            FirebaseStorage.getInstance().reference.child(path)
                .putBytes(bytes, StorageMetadata.Builder().setContentType(XLSX).build())
                .await()
        } catch (e: Exception) {
            return Checked("", ValidationResult.FileError("Upload failed: ${e.message ?: "check your connection"}"))
        }

        return try {
            val reply = ServerCalls.call("grnImportV1", mapOf("action" to "validate", "path" to path), timeoutMinutes = 9)
            (reply["fileError"] as? String)?.let { return Checked(path, ValidationResult.FileError(it)) }
            val rows = (reply["rows"] as? List<*>).orEmpty().mapNotNull { raw ->
                val r = raw as? Map<*, *> ?: return@mapNotNull null
                ValidatedRow(
                    rowNumber = (r["rowNumber"] as? Number)?.toInt() ?: 0,
                    sku = r["sku"]?.toString().orEmpty(),
                    epc = r["epc"]?.toString().orEmpty(),
                    serialNumber = r["serialNumber"]?.toString().orEmpty(),
                    binCode = r["binCode"]?.toString().orEmpty(),
                    warehouseName = r["warehouseName"]?.toString().orEmpty(),
                    notes = r["notes"]?.toString().orEmpty(),
                    errors = (r["errors"] as? List<*>).orEmpty().map { it.toString() },
                )
            }
            val errorRows = rows.count { it.errors.isNotEmpty() }
            Checked(path, if (errorRows == 0) ValidationResult.AllClean(rows) else ValidationResult.HasErrors(rows, errorRows, rows.size))
        } catch (e: Exception) {
            Checked(path, ValidationResult.FileError("Couldn't check the file: ${ServerCalls.message(e)}"))
        }
    }

    /** Saves the checked file (the server re-checks it first). */
    suspend fun commit(path: String, grnReference: String): CommitResult {
        if (path.isBlank()) return CommitResult.Failure("Pick the file again")
        return try {
            val reply = ServerCalls.call(
                "grnImportV1",
                mapOf("action" to "commit", "path" to path, "reference" to grnReference.trim()),
                timeoutMinutes = 9
            )
            val inwarded = (reply["inwardedCount"] as? Number)?.toInt() ?: 0
            val failures = (reply["failures"] as? List<*>).orEmpty().mapNotNull { it as? Map<*, *> }
            if (failures.isEmpty()) {
                CommitResult.Success(inwarded, grnReference)
            } else {
                val first = failures.first()
                val row = (first["rowNumber"] as? Number)?.toInt() ?: 0
                CommitResult.PartialFailure(
                    inwardedCount = inwarded,
                    failedRowNumber = row,
                    error = "${failures.size} row(s) not inwarded. First: row $row — ${first["reason"]}"
                )
            }
        } catch (e: Exception) {
            CommitResult.Failure(ServerCalls.message(e))
        }
    }

    private companion object {
        const val MAX_FILE_BYTES = 10 * 1024 * 1024
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}

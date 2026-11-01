package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
import com.snainfotech.tagscout.data.server.ServerCalls
import com.snainfotech.tagscout.data.config.ProductTypeKey
import kotlinx.coroutines.tasks.await

/**
 * A product category or sub-category.
 * Stored at /companies/{companyId}/categories/{categoryId}
 *
 * Two levels only: a top-level category has parentId = "", a sub-category
 * points at a top-level category of the same product type. Products store
 * categoryId / subCategoryId, so renames never touch products.
 */
data class Category(
    val id: String = "",
    val name: String = "",
    val nameKey: String = "",           // name.trim().lowercase(), for uniqueness
    val productType: String = ProductTypeKey.GENERAL.key,
    val parentId: String = "",          // "" = top level
    val order: Int = 0,
    val isActive: Boolean = true,
    val createdAt: Timestamp? = null,
    val createdBy: String = ""
)

fun Category.isTopLevel(): Boolean = parentId.isEmpty()

/**
 * A brand. Stored at /companies/{companyId}/brands/{brandId}
 * Managed list per company; new brands are auto-added on product create or import.
 */
data class Brand(
    val id: String = "",
    val name: String = "",
    val nameKey: String = "",
    val isActive: Boolean = true,
    val createdAt: Timestamp? = null,
    val createdBy: String = ""
)

/**
 * Categories and brands for the signed-in company.
 *
 * Lists are small (tens to a few hundred), so reads load the whole
 * collection and filter/sort in memory — no composite indexes needed.
 */
class CatalogLookupsRepository {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()

    private suspend fun companyId(): String? = AuthReady.awaitCompanyId()

    private suspend fun collection(name: String): CollectionReference? =
        companyId()?.let { firestore.collection("companies").document(it).collection(name) }

    private suspend fun categoriesRef() = collection("categories")
    private suspend fun brandsRef() = collection("brands")

    companion object {
        const val MAX_NAME_LENGTH = 60
        fun nameKeyOf(name: String): String = name.trim().lowercase()
    }

    // ── Mapping ───────────────────────────────────────────────

    private fun DocumentSnapshot.toCategory(): Category {
        val name = getString("name") ?: ""
        return Category(
            id = id,
            name = name,
            nameKey = getString("nameKey") ?: nameKeyOf(name),
            productType = getString("productType") ?: ProductTypeKey.GENERAL.key,
            parentId = getString("parentId") ?: "",
            order = (getLong("order") ?: 0L).toInt(),
            isActive = getBoolean("isActive") ?: true,
            createdAt = getTimestamp("createdAt"),
            createdBy = getString("createdBy") ?: ""
        )
    }

    private fun DocumentSnapshot.toBrand(): Brand {
        val name = getString("name") ?: ""
        return Brand(
            id = id,
            name = name,
            nameKey = getString("nameKey") ?: nameKeyOf(name),
            isActive = getBoolean("isActive") ?: true,
            createdAt = getTimestamp("createdAt"),
            createdBy = getString("createdBy") ?: ""
        )
    }

    // ── Categories: read ──────────────────────────────────────

    /**
     * Categories (top level and sub), sorted by order then name.
     * [type] = null returns every type. [includeInactive] for management
     * screens and for resolving names on existing products.
     */
    suspend fun getCategories(
        type: ProductTypeKey? = null,
        includeInactive: Boolean = false
    ): Result<List<Category>> {
        return try {
            val ref = categoriesRef() ?: return Result.failure(Exception("Not logged in"))
            val list = ref.get().await().documents.map { it.toCategory() }
                .filter { type == null || it.productType == type.key }
                .filter { includeInactive || it.isActive }
                .sortedWith(compareBy<Category> { it.order }.thenBy { it.nameKey })
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Categories: write (on the server: catalogV1) ──────────
    //
    // catalogV1 (functions/src/catalog.ts) applies the same rules as before
    // (names unique among siblings, one level of sub-categories, no delete
    // while in use) and allows owners and managers only.

    /** Creates a category, or a sub-category when [parentId] is given. Returns the new id. */
    suspend fun createCategory(
        name: String,
        type: ProductTypeKey,
        parentId: String = "",
        order: Int = 0
    ): Result<String> = catalog(
        "createCategory", "name" to name, "productType" to type.key, "parentId" to parentId, "order" to order
    ).map { it["id"]?.toString().orEmpty() }

    suspend fun renameCategory(categoryId: String, newName: String): Result<Unit> =
        catalog("renameCategory", "categoryId" to categoryId, "name" to newName).map { }

    suspend fun setCategoryOrder(categoryId: String, order: Int): Result<Unit> =
        catalog("setCategoryOrder", "categoryId" to categoryId, "order" to order).map { }

    /** Hides from pickers without breaking products that use it. */
    suspend fun setCategoryActive(categoryId: String, active: Boolean): Result<Unit> =
        catalog("setCategoryActive", "categoryId" to categoryId, "active" to active).map { }

    /** Blocked while it has sub-categories or any product uses it; deactivate instead. */
    suspend fun deleteCategory(categoryId: String): Result<Unit> =
        catalog("deleteCategory", "categoryId" to categoryId).map { }

    // ── Brands: read ──────────────────────────────────────────

    suspend fun getBrands(includeInactive: Boolean = false): Result<List<Brand>> {
        return try {
            val ref = brandsRef() ?: return Result.failure(Exception("Not logged in"))
            val list = ref.get().await().documents.map { it.toBrand() }
                .filter { includeInactive || it.isActive }
                .sortedBy { it.nameKey }
            Result.success(list)
        } catch (e: Exception) { Result.failure(e) }
    }

    // ── Brands: write (on the server: catalogV1) ──────────────

    /**
     * Returns the brand with this name (case-insensitive), creating it if
     * missing. A deactivated brand with the name is reactivated rather than
     * duplicated. Used by product create and Excel import.
     */
    suspend fun findOrCreateBrand(name: String): Result<Brand> =
        catalog("findOrCreateBrand", "name" to name).map { r ->
            Brand(
                id = r["id"]?.toString().orEmpty(),
                name = r["name"]?.toString().orEmpty(),
                nameKey = r["nameKey"]?.toString().orEmpty(),
                isActive = r["isActive"] as? Boolean ?: true,
                createdBy = r["createdBy"]?.toString().orEmpty()
            )
        }

    suspend fun renameBrand(brandId: String, newName: String): Result<Unit> =
        catalog("renameBrand", "brandId" to brandId, "name" to newName).map { }

    suspend fun setBrandActive(brandId: String, active: Boolean): Result<Unit> =
        catalog("setBrandActive", "brandId" to brandId, "active" to active).map { }

    /** Blocked while any product uses it; deactivate instead. */
    suspend fun deleteBrand(brandId: String): Result<Unit> =
        catalog("deleteBrand", "brandId" to brandId).map { }

    private suspend fun catalog(action: String, vararg fields: Pair<String, Any>): Result<Map<*, *>> = try {
        if (companyId() == null) {
            Result.failure(Exception("Not logged in"))
        } else {
            val data = mapOf<String, Any?>("action" to action) +
                fields.map { (k, v) -> k to (if (v is String) v.trim() else v) }
            Result.success(ServerCalls.call("catalogV1", data))
        }
    } catch (e: Exception) {
        Result.failure(Exception(ServerCalls.message(e), e))
    }
}

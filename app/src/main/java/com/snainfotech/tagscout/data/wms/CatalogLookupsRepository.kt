package com.snainfotech.tagscout.data.wms

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.AggregateSource
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.snainfotech.tagscout.data.auth.AuthReady
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

    private suspend fun companyId(): String? = AuthReady.awaitUid()
    private fun currentUserId(): String = auth.currentUser?.uid ?: ""

    private suspend fun collection(name: String): CollectionReference? =
        companyId()?.let { firestore.collection("companies").document(it).collection(name) }

    private suspend fun categoriesRef() = collection("categories")
    private suspend fun brandsRef() = collection("brands")
    private suspend fun productsRef() = collection("products")

    companion object {
        const val MAX_NAME_LENGTH = 60
        fun nameKeyOf(name: String): String = name.trim().lowercase()
    }

    private fun checkName(name: String, what: String): String? = when {
        name.isBlank() -> "$what name is required"
        name.trim().length > MAX_NAME_LENGTH -> "$what name can be at most $MAX_NAME_LENGTH characters"
        else -> null
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

    // ── Categories: write ─────────────────────────────────────

    /** Creates a category, or a sub-category when [parentId] is given. Returns the new id. */
    suspend fun createCategory(
        name: String,
        type: ProductTypeKey,
        parentId: String = "",
        order: Int = 0
    ): Result<String> {
        return try {
            checkName(name, "Category")?.let { return Result.failure(IllegalArgumentException(it)) }
            val ref = categoriesRef() ?: return Result.failure(Exception("Not logged in"))
            val all = ref.get().await().documents.map { it.toCategory() }

            if (parentId.isNotEmpty()) {
                val parent = all.firstOrNull { it.id == parentId }
                    ?: return Result.failure(IllegalArgumentException("Parent category not found"))
                if (!parent.isTopLevel()) {
                    return Result.failure(IllegalArgumentException("Sub-categories can't have their own sub-categories"))
                }
                if (parent.productType != type.key) {
                    return Result.failure(IllegalArgumentException("Parent category belongs to a different product type"))
                }
            }

            val key = nameKeyOf(name)
            val clash = all.any { it.productType == type.key && it.parentId == parentId && it.nameKey == key }
            if (clash) return Result.failure(IllegalArgumentException("Category '${name.trim()}' already exists here"))

            val doc = ref.add(
                mapOf(
                    "name" to name.trim(),
                    "nameKey" to key,
                    "productType" to type.key,
                    "parentId" to parentId,
                    "order" to order,
                    "isActive" to true,
                    "createdAt" to Timestamp.now(),
                    "createdBy" to currentUserId()
                )
            ).await()
            Result.success(doc.id)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun renameCategory(categoryId: String, newName: String): Result<Unit> {
        return try {
            checkName(newName, "Category")?.let { return Result.failure(IllegalArgumentException(it)) }
            val ref = categoriesRef() ?: return Result.failure(Exception("Not logged in"))
            val all = ref.get().await().documents.map { it.toCategory() }
            val current = all.firstOrNull { it.id == categoryId }
                ?: return Result.failure(Exception("Category not found"))
            val key = nameKeyOf(newName)
            val clash = all.any {
                it.id != categoryId && it.productType == current.productType &&
                        it.parentId == current.parentId && it.nameKey == key
            }
            if (clash) return Result.failure(IllegalArgumentException("Category '${newName.trim()}' already exists here"))

            ref.document(categoryId).update(mapOf("name" to newName.trim(), "nameKey" to key)).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun setCategoryOrder(categoryId: String, order: Int): Result<Unit> {
        return try {
            val ref = categoriesRef() ?: return Result.failure(Exception("Not logged in"))
            ref.document(categoryId).update("order", order).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Hides from pickers without breaking products that use it. */
    suspend fun setCategoryActive(categoryId: String, active: Boolean): Result<Unit> {
        return try {
            val ref = categoriesRef() ?: return Result.failure(Exception("Not logged in"))
            ref.document(categoryId).update("isActive", active).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Blocked while it has sub-categories or any product uses it; deactivate instead. */
    suspend fun deleteCategory(categoryId: String): Result<Unit> {
        return try {
            val ref = categoriesRef() ?: return Result.failure(Exception("Not logged in"))
            val products = productsRef() ?: return Result.failure(Exception("Not logged in"))

            val children = ref.whereEqualTo("parentId", categoryId)
                .count().get(AggregateSource.SERVER).await().count
            if (children > 0) {
                return Result.failure(Exception("Delete or move its $children sub-categor${if (children == 1L) "y" else "ies"} first"))
            }
            val used = products.whereEqualTo("categoryId", categoryId)
                .count().get(AggregateSource.SERVER).await().count +
                    products.whereEqualTo("subCategoryId", categoryId)
                        .count().get(AggregateSource.SERVER).await().count
            if (used > 0) {
                return Result.failure(Exception("$used product(s) use this category. Deactivate it instead."))
            }

            ref.document(categoryId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

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

    // ── Brands: write ─────────────────────────────────────────

    /**
     * Returns the brand with this name (case-insensitive), creating it if
     * missing. A deactivated brand with the name is reactivated rather than
     * duplicated. Used by product create and Excel import.
     */
    suspend fun findOrCreateBrand(name: String): Result<Brand> {
        return try {
            checkName(name, "Brand")?.let { return Result.failure(IllegalArgumentException(it)) }
            val ref = brandsRef() ?: return Result.failure(Exception("Not logged in"))
            val key = nameKeyOf(name)

            val existing = ref.whereEqualTo("nameKey", key).limit(1).get().await().documents.firstOrNull()
            if (existing != null) {
                val brand = existing.toBrand()
                if (!brand.isActive) {
                    existing.reference.update("isActive", true).await()
                    return Result.success(brand.copy(isActive = true))
                }
                return Result.success(brand)
            }

            val data = mapOf(
                "name" to name.trim(),
                "nameKey" to key,
                "isActive" to true,
                "createdAt" to Timestamp.now(),
                "createdBy" to currentUserId()
            )
            val doc = ref.add(data).await()
            Result.success(Brand(id = doc.id, name = name.trim(), nameKey = key, createdBy = currentUserId()))
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun renameBrand(brandId: String, newName: String): Result<Unit> {
        return try {
            checkName(newName, "Brand")?.let { return Result.failure(IllegalArgumentException(it)) }
            val ref = brandsRef() ?: return Result.failure(Exception("Not logged in"))
            val key = nameKeyOf(newName)
            val clash = ref.whereEqualTo("nameKey", key).limit(2).get().await()
                .documents.any { it.id != brandId }
            if (clash) return Result.failure(IllegalArgumentException("Brand '${newName.trim()}' already exists"))

            ref.document(brandId).update(mapOf("name" to newName.trim(), "nameKey" to key)).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun setBrandActive(brandId: String, active: Boolean): Result<Unit> {
        return try {
            val ref = brandsRef() ?: return Result.failure(Exception("Not logged in"))
            ref.document(brandId).update("isActive", active).await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }

    /** Blocked while any product uses it; deactivate instead. */
    suspend fun deleteBrand(brandId: String): Result<Unit> {
        return try {
            val ref = brandsRef() ?: return Result.failure(Exception("Not logged in"))
            val products = productsRef() ?: return Result.failure(Exception("Not logged in"))
            val used = products.whereEqualTo("brandId", brandId)
                .count().get(AggregateSource.SERVER).await().count
            if (used > 0) {
                return Result.failure(Exception("$used product(s) use this brand. Deactivate it instead."))
            }
            ref.document(brandId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) { Result.failure(e) }
    }
}
package com.snainfotech.tagscout.data.wms

/**
 * Fixed unit-of-measure list — the same for every customer.
 *
 * Stored on products as `unitOfMeasure` = [key] (e.g. "piece", "kilogram").
 * [key] must never be renamed once data uses it.
 *
 * Products created before this list existed hold free text ("pcs", "kg",
 * "meters", ...). [parse] maps those leniently, so no migration is needed;
 * the next time such a product is saved, it is written back with the
 * canonical key.
 */
enum class UnitOfMeasure(
    val key: String,
    val singular: String,
    val plural: String,
    /** Short form for tight UI and labels. */
    val symbol: String,
    val group: UomGroup,
    /** Extra spellings accepted on read and on Excel import (compared normalised). */
    private val aliases: List<String> = emptyList()
) {
    // Count
    PIECE("piece", "Piece", "Pieces", "pcs", UomGroup.COUNT, listOf("pcs", "pc", "pce", "nos", "no", "unit", "units", "ea", "each")),
    PAIR("pair", "Pair", "Pairs", "pr", UomGroup.COUNT, listOf("pr", "prs")),
    SET("set", "Set", "Sets", "set", UomGroup.COUNT, listOf("sets")),
    DOZEN("dozen", "Dozen", "Dozens", "doz", UomGroup.COUNT, listOf("doz", "dz")),

    // Packaging
    PACK("pack", "Pack", "Packs", "pk", UomGroup.PACKAGING, listOf("pk", "pkt", "packet", "packets")),
    BOX("box", "Box", "Boxes", "box", UomGroup.PACKAGING, listOf("bx", "boxes")),
    CASE("case", "Case", "Cases", "cs", UomGroup.PACKAGING, listOf("cs", "cases")),
    CARTON("carton", "Carton", "Cartons", "ctn", UomGroup.PACKAGING, listOf("ctn", "ctns", "cartons")),
    BAG("bag", "Bag", "Bags", "bag", UomGroup.PACKAGING, listOf("bags")),
    BOTTLE("bottle", "Bottle", "Bottles", "btl", UomGroup.PACKAGING, listOf("btl", "bottles")),
    ROLL("roll", "Roll", "Rolls", "roll", UomGroup.PACKAGING, listOf("rl", "rolls")),
    PALLET("pallet", "Pallet", "Pallets", "plt", UomGroup.PACKAGING, listOf("plt", "pallets")),

    // Weight
    GRAM("gram", "Gram", "Grams", "g", UomGroup.WEIGHT, listOf("g", "gm", "gms", "gr", "grams", "gramme")),
    KILOGRAM("kilogram", "Kilogram", "Kilograms", "kg", UomGroup.WEIGHT, listOf("kg", "kgs", "kilo", "kilos", "kilograms")),
    CARAT("carat", "Carat", "Carats", "ct", UomGroup.WEIGHT, listOf("ct", "cts", "carats")),

    // Volume
    MILLILITRE("millilitre", "Millilitre", "Millilitres", "ml", UomGroup.VOLUME, listOf("ml", "milliliter", "milliliters", "millilitres")),
    LITRE("litre", "Litre", "Litres", "L", UomGroup.VOLUME, listOf("l", "ltr", "ltrs", "liter", "liters", "litres")),

    // Length and area
    CENTIMETRE("centimetre", "Centimetre", "Centimetres", "cm", UomGroup.LENGTH_AREA, listOf("cm", "centimeter", "centimeters", "centimetres")),
    METRE("metre", "Metre", "Metres", "m", UomGroup.LENGTH_AREA, listOf("m", "mtr", "mtrs", "meter", "meters", "metres")),
    SQUARE_METRE("square_metre", "Square metre", "Square metres", "m²", UomGroup.LENGTH_AREA, listOf("sqm", "sq m", "sq.m", "m2", "m²", "square meter", "square meters", "square metres"));

    /** "1 Piece", "3 Pieces" */
    fun label(quantity: Number): String =
        if (quantity.toDouble() == 1.0) singular else plural

    companion object {
        val DEFAULT = PIECE

        private fun normalise(raw: String): String =
            raw.trim().lowercase().replace(Regex("[\\s._-]+"), " ").trim()

        private val lookup: Map<String, UnitOfMeasure> by lazy {
            buildMap {
                UnitOfMeasure.entries.forEach { uom ->
                    // Canonical key and names first, then aliases; first one wins.
                    (listOf(uom.key, uom.singular, uom.plural) + uom.aliases)
                        .map { normalise(it) }
                        .forEach { putIfAbsent(it, uom) }
                }
            }
        }

        /**
         * Lenient read of a stored or imported value. Null for blank or
         * unrecognised input — the caller decides whether to keep the raw
         * text (display of legacy data) or reject it (Excel import, forms).
         */
        fun parse(raw: String?): UnitOfMeasure? {
            if (raw.isNullOrBlank()) return null
            return lookup[normalise(raw)]
        }

        /** For reading Firestore: legacy or missing values fall back to [DEFAULT]. */
        fun parseOrDefault(raw: String?): UnitOfMeasure = parse(raw) ?: DEFAULT

        fun inGroup(group: UomGroup): List<UnitOfMeasure> = entries.filter { it.group == group }
    }
}

enum class UomGroup(val displayName: String) {
    COUNT("Count"),
    PACKAGING("Packaging"),
    WEIGHT("Weight"),
    VOLUME("Volume"),
    LENGTH_AREA("Length and area")
}
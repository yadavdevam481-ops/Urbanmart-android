package com.example.data

import com.example.model.Product
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

class InventoryRepository {

    private val stockLevels = ConcurrentHashMap<String, Int>()
    private val authoritativeCatalog = ConcurrentHashMap<String, Product>()

    private val _productsFlow = MutableStateFlow<List<Product>>(emptyList())
    val productsFlow: StateFlow<List<Product>> = _productsFlow.asStateFlow()

    init {
        seedInitialCatalog()
    }

    private fun seedInitialCatalog() {
        val initialItems = listOf(
            // Produce
            Product(
                id = "P01",
                name = "Honeycrisp Apples",
                category = "Produce",
                priceInCents = 299,
                availableStock = 24,
                unit = "1 lb (approx. 3 apples)",
                description = "Crisp, sweet, and locally harvested crisp apples from organic orchards.",
                badge = "Organic",
                rating = 4.9,
                imageEmoji = "🍎"
            ),
            Product(
                id = "P02",
                name = "Ripe Hass Avocados",
                category = "Produce",
                priceInCents = 399,
                availableStock = 18,
                unit = "Bag of 4",
                description = "Perfect for creamy guacamole, toast, or fresh salads.",
                badge = "Farm Fresh",
                rating = 4.7,
                imageEmoji = "🥑"
            ),
            Product(
                id = "P03",
                name = "Organic Baby Spinach",
                category = "Produce",
                priceInCents = 249,
                availableStock = 15,
                unit = "11 oz clamshell",
                description = "Triple-washed tender baby spinach leaves, packed with iron and vitamins.",
                badge = "Superfood",
                rating = 4.8,
                imageEmoji = "🥬"
            ),
            Product(
                id = "P04",
                name = "Golden Ripe Bananas",
                category = "Produce",
                priceInCents = 129,
                availableStock = 30,
                unit = "1 bunch (approx. 2 lbs)",
                description = "Naturally sweet, potassium-rich fresh Cavendish bananas.",
                badge = "Best Value",
                rating = 4.6,
                imageEmoji = "🍌"
            ),
            Product(
                id = "P05",
                name = "Fresh Blueberries",
                category = "Produce",
                priceInCents = 449,
                availableStock = 8,
                unit = "1 pint (16 oz)",
                description = "Plump, antioxidant-rich berries bursting with fresh summer flavor.",
                badge = "Limited Stock",
                rating = 4.9,
                imageEmoji = "🫐"
            ),

            // Dairy & Eggs
            Product(
                id = "D01",
                name = "Pasture-Raised Brown Eggs",
                category = "Dairy & Eggs",
                priceInCents = 499,
                availableStock = 12,
                unit = "1 Dozen Grade A Large",
                description = "Certified humane pasture-raised hens with rich golden yolks.",
                badge = "Pasture-Raised",
                rating = 4.9,
                imageEmoji = "🥚"
            ),
            Product(
                id = "D02",
                name = "Organic Whole Milk",
                category = "Dairy & Eggs",
                priceInCents = 389,
                availableStock = 20,
                unit = "1 Gallon (128 fl oz)",
                description = "Creamy, whole pasteurized milk from grass-fed cows with no hormones.",
                badge = "Organic",
                rating = 4.8,
                imageEmoji = "🥛"
            ),
            Product(
                id = "D03",
                name = "Greek Plain Yogurt",
                category = "Dairy & Eggs",
                priceInCents = 349,
                availableStock = 14,
                unit = "32 oz tub",
                description = "Strained traditional Greek whole milk yogurt with 18g protein per serving.",
                badge = "High Protein",
                rating = 4.8,
                imageEmoji = "🥣"
            ),
            Product(
                id = "D04",
                name = "Sharp Aged Cheddar",
                category = "Dairy & Eggs",
                priceInCents = 429,
                availableStock = 10,
                unit = "8 oz block",
                description = "Naturally aged for 12 months for a bold, distinctive tangy bite.",
                badge = "Artisan",
                rating = 4.9,
                imageEmoji = "🧀"
            ),

            // Bakery
            Product(
                id = "B01",
                name = "Artisan Sourdough Loaf",
                category = "Bakery",
                priceInCents = 499,
                availableStock = 9,
                unit = "24 oz crusty loaf",
                description = "Slow fermented for 36 hours with wild sourdough starter, baked daily.",
                badge = "Baked Fresh",
                rating = 5.0,
                imageEmoji = "🍞"
            ),
            Product(
                id = "B02",
                name = "French Butter Croissants",
                category = "Bakery",
                priceInCents = 399,
                availableStock = 6,
                unit = "Pack of 4",
                description = "Flaky, golden layered pastry made with 100% pure European butter.",
                badge = "Bakery Pick",
                rating = 4.9,
                imageEmoji = "🥐"
            ),
            Product(
                id = "B03",
                name = "Whole Grain Seeded Bread",
                category = "Bakery",
                priceInCents = 329,
                availableStock = 16,
                unit = "20 oz sliced loaf",
                description = "Nutrient-packed loaf featuring pumpkin, flax, sunflower, and chia seeds.",
                badge = "Fiber Rich",
                rating = 4.7,
                imageEmoji = "🥖"
            ),

            // Pantry
            Product(
                id = "P11",
                name = "Extra Virgin Olive Oil",
                category = "Pantry",
                priceInCents = 1299,
                availableStock = 15,
                unit = "750 ml glass bottle",
                description = "Cold pressed single-origin Mediterranean olives with rich fruity notes.",
                badge = "Imported",
                rating = 4.9,
                imageEmoji = "🫒"
            ),
            Product(
                id = "P12",
                name = "Organic Basmati Rice",
                category = "Pantry",
                priceInCents = 849,
                availableStock = 22,
                unit = "5 lb cloth bag",
                description = "Aromatic long-grain aged rice perfect for curries, pilafs, and bowls.",
                badge = "Aged 1 Year",
                rating = 4.8,
                imageEmoji = "🍚"
            ),
            Product(
                id = "P13",
                name = "Italian Marinara Sauce",
                category = "Pantry",
                priceInCents = 379,
                availableStock = 18,
                unit = "24 oz glass jar",
                description = "Slow simmered San Marzano tomatoes, fresh basil, garlic, and sea salt.",
                badge = "No Sugar Added",
                rating = 4.7,
                imageEmoji = "🥫"
            ),

            // Beverages & Snacks
            Product(
                id = "S01",
                name = "Artisan Dark Roast Coffee",
                category = "Beverages & Snacks",
                priceInCents = 1199,
                availableStock = 12,
                unit = "12 oz whole bean",
                description = "Fair trade single origin beans with notes of dark chocolate and caramel.",
                badge = "Fair Trade",
                rating = 4.9,
                imageEmoji = "☕"
            ),
            Product(
                id = "S02",
                name = "Sparkling Spring Water",
                category = "Beverages & Snacks",
                priceInCents = 599,
                availableStock = 20,
                unit = "Pack of 8 (12 oz cans)",
                description = "Natural mountain spring water with effervescent bubbles and lime essence.",
                badge = "Zero Sugar",
                rating = 4.8,
                imageEmoji = "🥤"
            ),
            Product(
                id = "S03",
                name = "Roasted Sea Salt Almonds",
                category = "Beverages & Snacks",
                priceInCents = 649,
                availableStock = 14,
                unit = "16 oz resealable pouch",
                description = "Slow roasted California almonds lightly dusted with pink Himalayan salt.",
                badge = "Heart Healthy",
                rating = 4.8,
                imageEmoji = "🥜"
            )
        )

        authoritativeCatalog.clear()
        stockLevels.clear()
        for (item in initialItems) {
            authoritativeCatalog[item.id] = item
            stockLevels[item.id] = item.availableStock
        }
        publishProducts()
    }

    private fun publishProducts() {
        val list = authoritativeCatalog.values.map { p ->
            p.copy(availableStock = stockLevels[p.id] ?: 0)
        }.sortedBy { it.id }
        _productsFlow.value = list
    }

    fun getAllProducts(): List<Product> = _productsFlow.value

    fun getProduct(id: String): Product? {
        val p = authoritativeCatalog[id] ?: return null
        return p.copy(availableStock = stockLevels[id] ?: 0)
    }

    fun getStock(productId: String): Int {
        return stockLevels[productId] ?: 0
    }

    /**
     * Atomically check if quantity is available.
     */
    fun hasSufficientStock(productId: String, quantity: Int): Boolean {
        val current = stockLevels[productId] ?: 0
        return current >= quantity
    }

    /**
     * Atomically deduct stock. Returns true if successfully deducted, false otherwise.
     */
    @Synchronized
    fun deductStock(productId: String, quantity: Int): Boolean {
        val current = stockLevels[productId] ?: 0
        if (current < quantity) return false
        stockLevels[productId] = current - quantity
        publishProducts()
        return true
    }

    /**
     * Replenish stock (e.g. for cancellation or restock).
     */
    @Synchronized
    fun replenishStock(productId: String, quantity: Int) {
        val current = stockLevels[productId] ?: 0
        stockLevels[productId] = current + quantity
        publishProducts()
    }

    /**
     * Reset all stock to original catalog quantities (useful for testing and demo).
     */
    fun resetCatalog() {
        seedInitialCatalog()
    }

    /**
     * Explicitly adjust stock for concurrency testing scenarios.
     */
    @Synchronized
    fun setStock(productId: String, quantity: Int) {
        stockLevels[productId] = quantity.coerceAtLeast(0)
        publishProducts()
    }
}

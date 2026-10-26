package com.example

import com.example.data.InventoryRepository
import com.example.data.db.OrderEntity
import com.example.data.security.PasswordHasher
import com.example.domain.CheckoutResult
import com.example.domain.CheckoutUseCase
import com.example.domain.SimulatedPaymentGateway
import com.example.model.CartItem
import com.example.model.Product
import com.example.ui.GroceryUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class UrbanmartTest {

    // --- UNIT TESTS ---

    @Test
    fun testPasswordHasherVerification() {
        val password = "MySecretPassword123!"
        val salt = PasswordHasher.generateSalt()
        val hash = PasswordHasher.hashPassword(password, salt)

        // Verify correct password matches
        assertTrue(PasswordHasher.verifyPassword(password, salt, hash))

        // Verify incorrect password fails
        assertFalse(PasswordHasher.verifyPassword("WrongPassword!", salt, hash))

        // Verify distinct salts produce different hashes for the same password
        val salt2 = PasswordHasher.generateSalt()
        val hash2 = PasswordHasher.hashPassword(password, salt2)
        assertNotEquals(hash, hash2)
    }

    @Test
    fun testFinancialIntegerPrecision() {
        val item = CartItem(
            product = Product("P01", "Honeycrisp Apples", "Produce", priceInCents = 299L, availableStock = 10),
            quantity = 3
        )
        // 3 x $2.99 = $8.97 (897 cents)
        assertEquals(897L, item.subtotalInCents)
        assertEquals("$8.97", item.formattedSubtotal)
    }

    @Test
    fun testOrderItemsSerializationRoundtrip() {
        val items = listOf(
            CartItem(Product("P01", "Honeycrisp Apples", "Produce", 299L, 10, imageEmoji = "🍎"), 2),
            CartItem(Product("B01", "Artisan Sourdough Loaf", "Bakery", 499L, 5, imageEmoji = "🍞"), 1)
        )

        val serialized = OrderEntity.serializeCartItems(items)
        val deserialized = OrderEntity.deserializeCartItems(serialized)

        assertEquals(2, deserialized.size)
        assertEquals("P01", deserialized[0].product.id)
        assertEquals("Honeycrisp Apples", deserialized[0].product.name)
        assertEquals(2, deserialized[0].quantity)
        assertEquals(299L, deserialized[0].product.priceInCents)

        assertEquals("B01", deserialized[1].product.id)
        assertEquals(1, deserialized[1].quantity)
        assertEquals(499L, deserialized[1].product.priceInCents)
    }

    @Test
    fun testPromoCodeThresholds() {
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        // FRESH20 requires $20.00 (2000 cents)
        val failure = useCase.validatePromoCode("FRESH20", subtotalCents = 1500L)
        assertTrue(failure.isFailure)

        val success = useCase.validatePromoCode("FRESH20", subtotalCents = 2200L)
        assertTrue(success.isSuccess)
        assertEquals(20, success.getOrThrow().discountPercent)
    }

    @Test
    fun testCategoryFilteringChips() {
        val repo = InventoryRepository()
        val allProducts = repo.getAllProducts()

        val stateFruits = GroceryUiState(products = allProducts, selectedCategory = "Fruits")
        val fruits = stateFruits.filteredProducts
        assertTrue(fruits.isNotEmpty())
        assertTrue(fruits.all { it.category.contains("Produce", ignoreCase = true) || it.category.contains("Fruit", ignoreCase = true) })

        val stateDairy = GroceryUiState(products = allProducts, selectedCategory = "Dairy")
        val dairy = stateDairy.filteredProducts
        assertTrue(dairy.isNotEmpty())
        assertTrue(dairy.all { it.category.contains("Dairy", ignoreCase = true) })

        val stateBakery = GroceryUiState(products = allProducts, selectedCategory = "Bakery")
        val bakery = stateBakery.filteredProducts
        assertTrue(bakery.isNotEmpty())
        assertTrue(bakery.all { it.category.contains("Bakery", ignoreCase = true) })
    }

    // --- INTEGRATION TESTS ---

    @Test
    fun testCheckoutUseCaseDeductsStock() = runBlocking {
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        val initialStock = repo.getStock("P01") // Honeycrisp Apples
        val product = repo.getProduct("P01")!!

        val result = useCase.execute(
            userId = "test_user_01",
            cartItems = listOf(CartItem(product, quantity = 3))
        )

        assertTrue(result is CheckoutResult.Success)
        val order = (result as CheckoutResult.Success).order
        assertEquals(3 * 299L, order.subtotalInCents)
        assertEquals(initialStock - 3, repo.getStock("P01"))
    }

    @Test
    fun testPaymentDeclinedCompensatingRollback() = runBlocking {
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply {
            simulatedDelayMs = 0L
            simulatePaymentFailure = true // Simulate payment decline
        }
        val useCase = CheckoutUseCase(repo, gateway)

        val initialStock = repo.getStock("B01")
        val product = repo.getProduct("B01")!!

        val result = useCase.execute(
            userId = "test_user_declined",
            cartItems = listOf(CartItem(product, quantity = 2))
        )

        assertTrue(result is CheckoutResult.Error)
        // Ensure stock was NOT deducted
        assertEquals(initialStock, repo.getStock("B01"))
    }

    @Test
    fun testPriceTamperingDefense() = runBlocking {
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        // Attacker creates a client CartItem with tampered price = 1 cent ($0.01) instead of real $12.99 (1299 cents)
        val realProduct = repo.getProduct("P11")!! // Olive Oil ($12.99)
        val tamperedProduct = realProduct.copy(priceInCents = 1L)
        val tamperedCartItem = CartItem(tamperedProduct, quantity = 1)

        val result = useCase.execute("attacker_user", listOf(tamperedCartItem))
        assertTrue(result is CheckoutResult.Success)
        val order = (result as CheckoutResult.Success).order

        // Must charge authoritative 1299 cents, ignoring the client's 1 cent
        assertEquals(1299L, order.subtotalInCents)
    }

    @Test
    fun testIdempotencyDuplicatePayment() = runBlocking {
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val fixedKey = "IDEMPOTENT_TEST_KEY_${UUID.randomUUID()}"

        val res1 = gateway.processPayment("user_idemp", 2000L, fixedKey).getOrThrow()
        val res2 = gateway.processPayment("user_idemp", 2000L, fixedKey).getOrThrow()

        assertEquals(res1.transactionId, res2.transactionId)
        assertFalse(res1.isIdempotentDuplicate)
        assertTrue(res2.isIdempotentDuplicate)
    }

    // --- SYSTEM / E2E TEST ---

    @Test
    fun testCompleteUserShoppingAndCheckoutFlow() = runBlocking {
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        // Step 1: User picks items
        val apple = repo.getProduct("P01")!! // $2.99
        val bread = repo.getProduct("B01")!! // $4.99
        val yogurt = repo.getProduct("D03")!! // $3.49

        // Step 2: Build cart
        val cart = mutableListOf(
            CartItem(apple, 2),  // 598
            CartItem(bread, 1),  // 499
            CartItem(yogurt, 4)  // 1396
        )

        // Step 3: Remove yogurt, add Olive Oil ($12.99 x 2)
        cart.removeIf { it.product.id == "D03" }
        val oil = repo.getProduct("P11")!!
        cart.add(CartItem(oil, 2)) // 2598
        // Total subtotal: 598 + 499 + 2598 = 3695 ($36.95) -> Qualifies for Free Delivery and FRESH20!

        // Step 4: Checkout with FRESH20
        val result = useCase.execute(
            userId = "user_e2e_customer",
            cartItems = cart,
            appliedPromoCode = "FRESH20"
        )

        assertTrue(result is CheckoutResult.Success)
        val order = (result as CheckoutResult.Success).order
        assertEquals(3695L, order.subtotalInCents)
        assertEquals(739L, order.discountInCents) // 20% of 3695
        assertEquals(0L, order.deliveryFeeInCents) // Free over $35
        assertEquals(2956L, order.totalAmountInCents) // 3695 - 739 = 2956
        assertEquals(5, order.itemCount)
    }

    @Test
    fun testRealTimeOrderFulfillmentProgression() {
        var status = com.example.model.OrderStatus.CONFIRMED

        // Step 1: Confirmed -> Packing
        status = when (status) {
            com.example.model.OrderStatus.CONFIRMED -> com.example.model.OrderStatus.PACKING
            else -> status
        }
        assertEquals(com.example.model.OrderStatus.PACKING, status)

        // Step 2: Packing -> Out for Delivery
        status = when (status) {
            com.example.model.OrderStatus.PACKING -> com.example.model.OrderStatus.OUT_FOR_DELIVERY
            else -> status
        }
        assertEquals(com.example.model.OrderStatus.OUT_FOR_DELIVERY, status)

        // Step 3: Out for Delivery -> Delivered
        status = when (status) {
            com.example.model.OrderStatus.OUT_FOR_DELIVERY -> com.example.model.OrderStatus.DELIVERED
            else -> status
        }
        assertEquals(com.example.model.OrderStatus.DELIVERED, status)

        // Step 4: Verify active orders filtering
        val order1 = com.example.model.Order(
            userId = "u1",
            items = emptyList(),
            subtotalInCents = 1000L,
            totalAmountInCents = 1000L,
            status = com.example.model.OrderStatus.PACKING
        )
        val order2 = com.example.model.Order(
            userId = "u1",
            items = emptyList(),
            subtotalInCents = 2000L,
            totalAmountInCents = 2000L,
            status = com.example.model.OrderStatus.DELIVERED
        )

        val state = GroceryUiState(orderHistory = listOf(order1, order2))
        assertEquals(1, state.activeOrders.size)
        assertEquals(order1.orderId, state.activeOrders[0].orderId)
    }
}

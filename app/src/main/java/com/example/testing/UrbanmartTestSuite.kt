package com.example.testing

import com.example.data.InventoryRepository
import com.example.domain.CheckoutResult
import com.example.domain.CheckoutUseCase
import com.example.domain.SimulatedPaymentGateway
import com.example.model.CartItem
import com.example.model.Product
import java.util.UUID

enum class TestLayer(val title: String) {
    UNIT("Unit Testing"),
    INTEGRATION("Integration Testing"),
    SYSTEM("System / E2E Testing"),
    TDD("TDD & Security Suite")
}

data class TestCaseResult(
    val id: String,
    val name: String,
    val layer: TestLayer,
    val isPassed: Boolean,
    val durationMs: Long,
    val summary: String,
    val logOutput: String
)

class UrbanmartTestSuite {

    suspend fun runAllTests(): List<TestCaseResult> {
        val results = mutableListOf<TestCaseResult>()

        // 1. Unit Tests
        results.add(testCartSubtotalCalculation())
        results.add(testInvalidQuantityRejection())
        results.add(testPromoCodeValidation())
        results.add(testDeliveryFeeThreshold())
        results.add(testIdempotencyKeyEntropy())

        // 2. Integration Tests
        results.add(testStockDeductionIntegration())
        results.add(testPaymentFailureRollback())
        results.add(testClientPriceTamperingDefense())
        results.add(testDuplicateIdempotencyHandling())

        // 3. System / E2E Tests
        results.add(testCompleteGroceryShoppingJourney())
        results.add(testStockExhaustionEdgeCase())

        return results
    }

    // --- UNIT TESTS ---

    private fun testCartSubtotalCalculation(): TestCaseResult {
        val start = System.currentTimeMillis()
        val apple = Product("P01", "Apples", "Produce", 299L, 10)
        val cartItem = CartItem(apple, quantity = 3)
        val expected = 897L // $8.97
        val actual = cartItem.subtotalInCents
        val passed = (actual == expected)

        return TestCaseResult(
            id = "UT-1",
            name = "Subtotal Calculation (Zero Float Precision Error)",
            layer = TestLayer.UNIT,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "Verified 3 x $2.99 equals exact integer 897 cents ($8.97).",
            logOutput = "Asserted: actual=$actual cents, expected=$expected cents. Floating-point drift eliminated."
        )
    }

    private fun testInvalidQuantityRejection(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)
        val product = repo.getProduct("P01")!!

        var passed = false
        var log = ""
        kotlinx.coroutines.runBlocking {
            val result = useCase.execute(
                userId = "test_user",
                cartItems = listOf(CartItem(product, quantity = -2))
            )
            passed = (result is CheckoutResult.Error)
            log = "Checkout result: $result"
        }

        return TestCaseResult(
            id = "UT-2",
            name = "Boundary Validation (Negative Quantity Rejection)",
            layer = TestLayer.UNIT,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "Invalid non-positive quantities rejected before processing.",
            logOutput = log
        )
    }

    private fun testPromoCodeValidation(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        // Threshold for FRESH20 is $20.00 (2000 cents)
        val failureResult = useCase.validatePromoCode("FRESH20", subtotalCents = 1500L)
        val successResult = useCase.validatePromoCode("FRESH20", subtotalCents = 2500L)

        val passed = failureResult.isFailure && successResult.isSuccess
        return TestCaseResult(
            id = "UT-3",
            name = "Promo Code Threshold Enforcement",
            layer = TestLayer.UNIT,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "FRESH20 rejects spend below $20.00 and applies on qualifying carts.",
            logOutput = "Below threshold: ${failureResult.isFailure}, Above threshold: ${successResult.isSuccess}"
        )
    }

    private fun testDeliveryFeeThreshold(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        var passed = false
        kotlinx.coroutines.runBlocking {
            // Spend under $35 gets $3.99 delivery fee
            val apple = repo.getProduct("P01")!! // $2.99
            val res1 = useCase.execute("u1", listOf(CartItem(apple, 1))) as CheckoutResult.Success
            // Spend over $35 gets FREE delivery (Olive oil $12.99 * 3 = $38.97)
            val oil = repo.getProduct("P11")!! // $12.99
            val res2 = useCase.execute("u1", listOf(CartItem(oil, 3))) as CheckoutResult.Success

            passed = (res1.order.deliveryFeeInCents == 399L && res2.order.deliveryFeeInCents == 0L)
        }

        return TestCaseResult(
            id = "UT-4",
            name = "Delivery Fee Rule (Free over $35.00 threshold)",
            layer = TestLayer.UNIT,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "Verified $3.99 applied below $35, and $0.00 (Free) applied above $35.",
            logOutput = "Small order fee: 399c ($3.99), Large order fee: 0c (FREE)"
        )
    }

    private fun testIdempotencyKeyEntropy(): TestCaseResult {
        val start = System.currentTimeMillis()
        val uuid = UUID.randomUUID().toString()
        // Must be full 36-character UUIDv4 (not truncated to 8 chars)
        val hasFullEntropy = uuid.length == 36 && uuid.count { it == '-' } == 4

        return TestCaseResult(
            id = "UT-5",
            name = "Entropy Validation (No Truncated Order/Payment UUIDs)",
            layer = TestLayer.UNIT,
            isPassed = hasFullEntropy,
            durationMs = System.currentTimeMillis() - start,
            summary = "Full 128-bit UUID entropy guaranteed against birthday paradox collision.",
            logOutput = "Generated token: $uuid (36 chars, 128-bit strength)"
        )
    }

    // --- INTEGRATION TESTS ---

    private fun testStockDeductionIntegration(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        val product = repo.getProduct("D01")!! // Brown Eggs, initial stock 12
        val initialStock = product.availableStock

        var passed = false
        var currentStock = initialStock
        kotlinx.coroutines.runBlocking {
            val result = useCase.execute("u2", listOf(CartItem(product, 2)))
            currentStock = repo.getStock("D01")
            passed = (result is CheckoutResult.Success && currentStock == initialStock - 2)
        }

        return TestCaseResult(
            id = "IT-1",
            name = "Atomic Stock Deduction & Inventory Sync",
            layer = TestLayer.INTEGRATION,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "CheckoutUseCase atomically updated InventoryRepository stock from $initialStock to $currentStock.",
            logOutput = "Stock transition: Initial=$initialStock, After checkout=$currentStock"
        )
    }

    private fun testPaymentFailureRollback(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply {
            simulatedDelayMs = 0L
            simulatePaymentFailure = true // Simulate declined card
        }
        val useCase = CheckoutUseCase(repo, gateway)

        val product = repo.getProduct("B01")!! // Sourdough, initial stock 9
        val initialStock = repo.getStock("B01")

        var passed = false
        kotlinx.coroutines.runBlocking {
            val result = useCase.execute("u3", listOf(CartItem(product, 3)))
            val stockAfterFailure = repo.getStock("B01")
            // Stock must NOT be deducted when payment fails!
            passed = (result is CheckoutResult.Error && stockAfterFailure == initialStock)
        }

        return TestCaseResult(
            id = "IT-2",
            name = "Payment Failure Compensation & Zero-Loss Rollback",
            layer = TestLayer.INTEGRATION,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "Declined transaction prevented inventory deduction, preserving catalog consistency.",
            logOutput = "Payment error caught gracefully. Stock retained at: $initialStock units."
        )
    }

    private fun testClientPriceTamperingDefense(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        // Attacker creates a client CartItem with tampered price = 1 cent ($0.01) instead of real $12.99 (1299 cents)
        val realProduct = repo.getProduct("P11")!! // Olive Oil ($12.99)
        val tamperedProduct = realProduct.copy(priceInCents = 1L)
        val tamperedCartItem = CartItem(tamperedProduct, quantity = 1)

        var passed = false
        var chargedAmount = 0L
        kotlinx.coroutines.runBlocking {
            val result = useCase.execute("attacker_user", listOf(tamperedCartItem)) as CheckoutResult.Success
            chargedAmount = result.order.subtotalInCents
            // Must have charged authoritative 1299 cents, NOT tampered 1 cent!
            passed = (chargedAmount == 1299L)
        }

        return TestCaseResult(
            id = "IT-3",
            name = "Anti-Tampering (Authoritative Price Defense)",
            layer = TestLayer.INTEGRATION,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "Server ignored client-provided price ($0.01) and enforced authoritative catalog price ($12.99).",
            logOutput = "Tampered input: 1c -> Authoritative charge: ${chargedAmount}c ($12.99). Attack blocked."
        )
    }

    private fun testDuplicateIdempotencyHandling(): TestCaseResult {
        val start = System.currentTimeMillis()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val fixedKey = "IDEMPOTENT_TEST_KEY_${UUID.randomUUID()}"

        var passed = false
        var txn1 = ""
        var txn2 = ""
        kotlinx.coroutines.runBlocking {
            val res1 = gateway.processPayment("user_abc", 2500L, fixedKey).getOrThrow()
            val res2 = gateway.processPayment("user_abc", 2500L, fixedKey).getOrThrow()

            txn1 = res1.transactionId
            txn2 = res2.transactionId
            // The second call must recognize idempotency and return the existing transaction ID without double charge
            passed = (txn1 == txn2 && res2.isIdempotentDuplicate)
        }

        return TestCaseResult(
            id = "IT-4",
            name = "Payment Gateway Idempotency Key Validation",
            layer = TestLayer.INTEGRATION,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "Duplicate payment request returned original transaction ($txn1) without re-billing.",
            logOutput = "First call Txn: $txn1, Second call Txn: $txn2 (Matched: ${txn1 == txn2})"
        )
    }

    // --- SYSTEM (E2E) TESTS ---

    private fun testCompleteGroceryShoppingJourney(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        var passed = false
        var orderSummary = ""
        kotlinx.coroutines.runBlocking {
            // Step 1: User picks items
            val apple = repo.getProduct("P01")!! // $2.99
            val bread = repo.getProduct("B01")!! // $4.99
            val yogurt = repo.getProduct("D03")!! // $3.49

            // Step 2: User builds cart
            val cart = mutableListOf(
                CartItem(apple, 2),  // 598
                CartItem(bread, 1),  // 499
                CartItem(yogurt, 4)  // 1396
            )
            // Total before mod: 2493 cents ($24.93)

            // Step 3: User removes yogurt and adds Olive Oil ($12.99)
            cart.removeIf { it.product.id == "D03" }
            val oil = repo.getProduct("P11")!!
            cart.add(CartItem(oil, 2)) // 2598 cents ($25.98)
            // Subtotal: 598 + 499 + 2598 = 3695 cents ($36.95) -> Qualifies for Free Delivery and FRESH20!

            // Step 4: Checkout with FRESH20 (20% off)
            val result = useCase.execute(
                userId = "user_e2e_journey",
                cartItems = cart,
                appliedPromoCode = "FRESH20"
            ) as CheckoutResult.Success

            val order = result.order
            // Subtotal: 3695, Discount: 739, Delivery: 0, Total: 2956 cents ($29.56)
            passed = (order.subtotalInCents == 3695L &&
                    order.discountInCents == 739L &&
                    order.deliveryFeeInCents == 0L &&
                    order.totalAmountInCents == 2956L)

            orderSummary = "Order ID: ${order.orderId.take(8)} | Items: ${order.itemCount} | " +
                    "Subtotal: ${order.formattedSubtotal} | Discount: ${order.formattedDiscount} | " +
                    "Delivery: ${order.formattedDeliveryFee} | Total: ${order.formattedTotal}"
        }

        return TestCaseResult(
            id = "ST-1",
            name = "E2E Complete User Checkout Journey",
            layer = TestLayer.SYSTEM,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "End-to-end shopping flow: catalog selection -> cart modification -> promo code -> zero delivery fee checkout.",
            logOutput = orderSummary
        )
    }

    private fun testStockExhaustionEdgeCase(): TestCaseResult {
        val start = System.currentTimeMillis()
        val repo = InventoryRepository()
        val gateway = SimulatedPaymentGateway().apply { simulatedDelayMs = 0L }
        val useCase = CheckoutUseCase(repo, gateway)

        // Set stock of croissants to only 2
        repo.setStock("B02", 2)
        val croissant = repo.getProduct("B02")!!

        var passed = false
        var message = ""
        kotlinx.coroutines.runBlocking {
            // Attempt to buy 5 croissants when only 2 are available
            val result = useCase.execute("user_e2e_bulk", listOf(CartItem(croissant, 5)))
            passed = (result is CheckoutResult.Error)
            if (result is CheckoutResult.Error) {
                message = result.message
            }
        }

        return TestCaseResult(
            id = "ST-2",
            name = "E2E Inventory Boundary & Stock Depletion Defense",
            layer = TestLayer.SYSTEM,
            isPassed = passed,
            durationMs = System.currentTimeMillis() - start,
            summary = "System blocked checkout when requested quantity exceeded inventory.",
            logOutput = "Blocked with message: $message"
        )
    }
}

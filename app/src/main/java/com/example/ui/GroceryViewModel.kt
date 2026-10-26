package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.InventoryRepository
import com.example.data.repository.OrderRepository
import com.example.data.repository.UserRepository
import com.example.data.repository.UserSession
import com.example.domain.CheckoutResult
import com.example.domain.CheckoutUseCase
import com.example.domain.SimulatedPaymentGateway
import com.example.model.CartItem
import com.example.model.Order
import com.example.model.OrderStatus
import com.example.model.Product
import com.example.model.formatCents
import com.example.testing.TestCaseResult
import com.example.testing.UrbanmartTestSuite
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AuthMode {
    LOGIN,
    SIGNUP
}

data class GroceryUiState(
    val currentUser: UserSession? = null,
    val authMode: AuthMode = AuthMode.LOGIN,
    val isAuthLoading: Boolean = false,
    val authError: String? = null,
    val products: List<Product> = emptyList(),
    val selectedCategory: String = "All",
    val searchQuery: String = "",
    val cart: Map<String, CartItem> = emptyMap(),
    val appliedPromoCode: String? = null,
    val promoDiscountPercent: Int = 0,
    val isCheckingOut: Boolean = false,
    val lastOrder: Order? = null,
    val orderHistory: List<Order> = emptyList(),
    val selectedOrderForDetail: Order? = null,
    val userMessage: String? = null,
    val isErrorMessage: Boolean = false,
    val testSuiteResults: List<TestCaseResult>? = null,
    val isRunningTests: Boolean = false,
    val selectedPaymentMethod: String = "Visa •••• 4242",
    val deliveryAddress: String = "742 Evergreen Terr, Apt 4B",
    val deliverySlot: String = "Today, 15-30 min"
) {
    val isLoggedIn: Boolean
        get() = currentUser != null

    val cartSubtotalCents: Long
        get() = cart.values.sumOf { it.subtotalInCents }

    val cartDiscountCents: Long
        get() = if (appliedPromoCode != null && promoDiscountPercent > 0) {
            (cartSubtotalCents * promoDiscountPercent) / 100
        } else 0L

    val cartDeliveryFeeCents: Long
        get() = if (cartSubtotalCents >= 3500L || cart.isEmpty()) 0L else 399L

    val cartTotalCents: Long
        get() = (cartSubtotalCents - cartDiscountCents + cartDeliveryFeeCents).coerceAtLeast(0L)

    val cartItemCount: Int
        get() = cart.values.sumOf { it.quantity }

    val formattedSubtotal: String
        get() = formatCents(cartSubtotalCents)

    val formattedDiscount: String
        get() = formatCents(cartDiscountCents)

    val formattedDeliveryFee: String
        get() = if (cartDeliveryFeeCents == 0L) "FREE" else formatCents(cartDeliveryFeeCents)

    val formattedTotal: String
        get() = formatCents(cartTotalCents)

    val amountNeededForFreeDeliveryCents: Long
        get() = (3500L - cartSubtotalCents).coerceAtLeast(0L)

    val freeDeliveryProgress: Float
        get() = (cartSubtotalCents.toFloat() / 3500f).coerceIn(0f, 1f)

    val filteredProducts: List<Product>
        get() = products.filter { product ->
            val matchesCategory = when (selectedCategory) {
                "All" -> true
                "Fruits" -> product.category.equals("Fruits", ignoreCase = true) ||
                            product.category.equals("Produce", ignoreCase = true) ||
                            product.category.contains("Fruit", ignoreCase = true)
                "Dairy" -> product.category.contains("Dairy", ignoreCase = true)
                "Bakery" -> product.category.contains("Bakery", ignoreCase = true)
                "Pantry" -> product.category.contains("Pantry", ignoreCase = true)
                "Beverages & Snacks", "Beverages", "Snacks" ->
                    product.category.contains("Beverage", ignoreCase = true) ||
                    product.category.contains("Snack", ignoreCase = true)
                else -> product.category.equals(selectedCategory, ignoreCase = true) ||
                        product.category.contains(selectedCategory, ignoreCase = true)
            }
            val matchesQuery = searchQuery.isBlank() ||
                    product.name.contains(searchQuery, ignoreCase = true) ||
                    product.description.contains(searchQuery, ignoreCase = true)
            matchesCategory && matchesQuery
        }
    val activeOrders: List<Order>
        get() = orderHistory.filter { it.status != OrderStatus.DELIVERED && it.status != OrderStatus.CANCELLED }
}

class GroceryViewModel(
    val inventoryRepository: InventoryRepository = InventoryRepository(),
    val paymentGateway: SimulatedPaymentGateway = SimulatedPaymentGateway(),
    val checkoutUseCase: CheckoutUseCase = CheckoutUseCase(inventoryRepository, paymentGateway),
    val userRepository: UserRepository? = null,
    val orderRepository: OrderRepository? = null,
    val testSuite: UrbanmartTestSuite = UrbanmartTestSuite()
) : ViewModel() {

    private val _uiState = MutableStateFlow(GroceryUiState())
    val uiState: StateFlow<GroceryUiState> = _uiState.asStateFlow()

    private var ordersJob: Job? = null
    private var trackingTickerJob: Job? = null

    init {
        // Collect inventory updates
        viewModelScope.launch {
            inventoryRepository.productsFlow.collect { items ->
                _uiState.update { state ->
                    val updatedCart = state.cart.toMutableMap()
                    updatedCart.entries.removeIf { (id, item) ->
                        val latest = items.find { it.id == id }
                        if (latest == null || latest.availableStock <= 0) {
                            true
                        } else {
                            if (item.quantity > latest.availableStock) {
                                updatedCart[id] = item.copy(
                                    product = latest,
                                    quantity = latest.availableStock
                                )
                            }
                            false
                        }
                    }
                    state.copy(products = items, cart = updatedCart)
                }
            }
        }

        // Collect user session if userRepository is available
        userRepository?.let { repo ->
            viewModelScope.launch {
                repo.currentUser.collect { user ->
                    _uiState.update { it.copy(currentUser = user) }
                    observeOrdersForUser(user?.userId)
                }
            }
        }

        // Start background fulfillment ticker
        startFulfillmentPolling()
    }

    private fun observeOrdersForUser(userId: String?) {
        ordersJob?.cancel()
        if (userId == null || orderRepository == null) {
            _uiState.update { it.copy(orderHistory = emptyList()) }
            return
        }

        ordersJob = viewModelScope.launch {
            orderRepository.getOrdersForUser(userId).collect { orders ->
                _uiState.update { state ->
                    val updatedDetail = if (state.selectedOrderForDetail != null) {
                        orders.find { it.orderId == state.selectedOrderForDetail.orderId } ?: state.selectedOrderForDetail
                    } else null
                    state.copy(
                        orderHistory = orders,
                        selectedOrderForDetail = updatedDetail
                    )
                }
            }
        }
    }

    fun startFulfillmentPolling() {
        trackingTickerJob?.cancel()
        trackingTickerJob = viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(10000L) // Polls every 10 seconds to advance fulfillment status
                advanceNextActiveOrder()
            }
        }
    }

    var onOrderStatusChangedListener: ((orderId: String, newStatus: OrderStatus) -> Unit)? = null

    fun advanceOrderManually(orderId: String) {
        viewModelScope.launch {
            val newStatus = orderRepository?.advanceOrderStatus(orderId)
            if (newStatus != null) {
                val statusNotice = when (newStatus) {
                    OrderStatus.PACKING -> "Order #${orderId.take(8).uppercase()}: Store associate packing groceries 📦"
                    OrderStatus.OUT_FOR_DELIVERY -> "Order #${orderId.take(8).uppercase()}: Courier is out for delivery! 🛵"
                    OrderStatus.DELIVERED -> "Order #${orderId.take(8).uppercase()}: Delivered to your doorstep! 🎉"
                    else -> "Order status updated to ${newStatus.displayName}"
                }
                _uiState.update { it.copy(userMessage = statusNotice) }
                onOrderStatusChangedListener?.invoke(orderId, newStatus)
            }
        }
    }

    private suspend fun advanceNextActiveOrder() {
        val active = orderRepository?.getActiveOrders() ?: return
        if (active.isEmpty()) return

        val targetOrder = active.minByOrNull { it.timestamp } ?: return
        val newStatus = orderRepository.advanceOrderStatus(targetOrder.orderId)
        if (newStatus != null) {
            val statusNotice = when (newStatus) {
                OrderStatus.PACKING -> "Order #${targetOrder.orderId.take(8).uppercase()}: Store associate packing groceries 📦"
                OrderStatus.OUT_FOR_DELIVERY -> "Order #${targetOrder.orderId.take(8).uppercase()}: Out for delivery with courier! 🛵"
                OrderStatus.DELIVERED -> "Order #${targetOrder.orderId.take(8).uppercase()}: Delivered to doorstep! 🎉"
                else -> "Order #${targetOrder.orderId.take(8).uppercase()}: ${newStatus.displayName}"
            }
            _uiState.update { it.copy(userMessage = statusNotice) }
            onOrderStatusChangedListener?.invoke(targetOrder.orderId, newStatus)
        }
    }

    fun setAuthMode(mode: AuthMode) {
        _uiState.update { it.copy(authMode = mode, authError = null) }
    }

    fun signUp(name: String, email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authError = null) }
            if (userRepository != null) {
                val res = userRepository.signUp(name, email, pass)
                if (res.isSuccess) {
                    val user = res.getOrThrow()
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            currentUser = user,
                            userMessage = "Welcome to Urbanmart, ${user.name}!"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authError = res.exceptionOrNull()?.message ?: "Signup failed"
                        )
                    }
                }
            } else {
                // In-memory fallback if no DB passed (e.g. lightweight test)
                val session = UserSession("usr_demo", name, email)
                _uiState.update {
                    it.copy(isAuthLoading = false, currentUser = session, userMessage = "Welcome, $name!")
                }
            }
        }
    }

    fun logIn(email: String, pass: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authError = null) }
            if (userRepository != null) {
                val res = userRepository.logIn(email, pass)
                if (res.isSuccess) {
                    val user = res.getOrThrow()
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            currentUser = user,
                            userMessage = "Welcome back, ${user.name}!"
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authError = res.exceptionOrNull()?.message ?: "Invalid credentials"
                        )
                    }
                }
            } else {
                val session = UserSession("usr_demo", "Shopper", email)
                _uiState.update {
                    it.copy(isAuthLoading = false, currentUser = session, userMessage = "Logged in!")
                }
            }
        }
    }

    fun logOut() {
        userRepository?.logOut()
        _uiState.update {
            it.copy(
                currentUser = null,
                orderHistory = emptyList(),
                selectedOrderForDetail = null,
                userMessage = "You have been logged out."
            )
        }
    }

    fun selectOrderForDetail(order: Order?) {
        _uiState.update { it.copy(selectedOrderForDetail = order) }
    }

    fun setCategory(category: String) {
        _uiState.update { it.copy(selectedCategory = category) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun addToCart(product: Product) {
        val currentStock = inventoryRepository.getStock(product.id)
        val currentInCart = _uiState.value.cart[product.id]?.quantity ?: 0

        if (currentInCart + 1 > currentStock) {
            _uiState.update {
                it.copy(
                    userMessage = "Only $currentStock units of ${product.name} are available.",
                    isErrorMessage = true
                )
            }
            return
        }

        _uiState.update { state ->
            val updated = state.cart.toMutableMap()
            updated[product.id] = CartItem(product, currentInCart + 1)
            state.copy(
                cart = updated,
                userMessage = "Added ${product.name} to cart",
                isErrorMessage = false
            )
        }
    }

    fun removeFromCart(productId: String) {
        _uiState.update { state ->
            val updated = state.cart.toMutableMap()
            val existing = updated[productId] ?: return@update state
            if (existing.quantity > 1) {
                updated[productId] = existing.copy(quantity = existing.quantity - 1)
            } else {
                updated.remove(productId)
            }
            state.copy(cart = updated)
        }
    }

    fun clearCart() {
        _uiState.update { it.copy(cart = emptyMap(), appliedPromoCode = null, promoDiscountPercent = 0) }
    }

    fun applyPromoCode(code: String) {
        val subtotal = _uiState.value.cartSubtotalCents
        val result = checkoutUseCase.validatePromoCode(code, subtotal)
        if (result.isSuccess) {
            val promo = result.getOrThrow()
            _uiState.update {
                it.copy(
                    appliedPromoCode = promo.code,
                    promoDiscountPercent = promo.discountPercent,
                    userMessage = "Applied ${promo.code} (${promo.discountPercent}% OFF)!",
                    isErrorMessage = false
                )
            }
        } else {
            val msg = result.exceptionOrNull()?.message ?: "Invalid promo code."
            _uiState.update {
                it.copy(
                    userMessage = msg,
                    isErrorMessage = true
                )
            }
        }
    }

    fun removePromoCode() {
        _uiState.update {
            it.copy(
                appliedPromoCode = null,
                promoDiscountPercent = 0,
                userMessage = "Promo code removed.",
                isErrorMessage = false
            )
        }
    }

    fun setPaymentMethod(method: String) {
        _uiState.update { it.copy(selectedPaymentMethod = method) }
    }

    fun checkout() {
        val user = _uiState.value.currentUser
        val userId = user?.userId ?: "guest_user"

        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingOut = true, userMessage = null) }
            val currentState = _uiState.value
            val cartList = currentState.cart.values.toList()

            when (val res = checkoutUseCase.execute(
                userId = userId,
                cartItems = cartList,
                appliedPromoCode = currentState.appliedPromoCode,
                deliveryAddress = currentState.deliveryAddress,
                deliverySlot = currentState.deliverySlot
            )) {
                is CheckoutResult.Success -> {
                    val order = res.order
                    // Save to persistent database
                    orderRepository?.saveOrder(order)
                    onOrderStatusChangedListener?.invoke(order.orderId, OrderStatus.CONFIRMED)

                    _uiState.update { state ->
                        state.copy(
                            isCheckingOut = false,
                            cart = emptyMap(),
                            appliedPromoCode = null,
                            promoDiscountPercent = 0,
                            lastOrder = order,
                            orderHistory = if (orderRepository == null) listOf(order) + state.orderHistory else state.orderHistory,
                            selectedOrderForDetail = order, // automatically open order detail modal for the new order!
                            userMessage = "Order placed successfully! ID: ${order.orderId.take(8)}",
                            isErrorMessage = false
                        )
                    }
                }
                is CheckoutResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isCheckingOut = false,
                            userMessage = res.message,
                            isErrorMessage = true
                        )
                    }
                }
            }
        }
    }

    fun runTestSuite() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunningTests = true, userMessage = "Executing test suite...", isErrorMessage = false) }
            val results = testSuite.runAllTests()
            val allPassed = results.all { it.isPassed }
            _uiState.update {
                it.copy(
                    isRunningTests = false,
                    testSuiteResults = results,
                    userMessage = if (allPassed) "All 11 tests passed successfully!" else "Some tests failed.",
                    isErrorMessage = !allPassed
                )
            }
        }
    }

    fun resetInventory() {
        inventoryRepository.resetCatalog()
        paymentGateway.resetIdempotencyCache()
        paymentGateway.simulatePaymentFailure = false
        _uiState.update {
            it.copy(
                userMessage = "Catalog & test state reset to original factory inventory.",
                isErrorMessage = false
            )
        }
    }

    fun clearUserMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }
}

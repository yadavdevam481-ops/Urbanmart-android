package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.InventoryRepository
import com.example.data.db.AppDatabase
import com.example.data.repository.OrderRepository
import com.example.data.repository.UserRepository
import com.example.domain.CheckoutUseCase
import com.example.domain.SimulatedPaymentGateway
import com.example.notification.OrderNotificationManager
import com.example.ui.GroceryViewModel
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.CartCheckoutScreen
import com.example.ui.screens.GroceryCatalogScreen
import com.example.ui.screens.OrderDetailDialog
import com.example.ui.screens.OrdersScreen
import com.example.ui.screens.TestLabScreen
import com.example.ui.theme.MyApplicationTheme

enum class AppDestination(val label: String) {
    CATALOG("Groceries"),
    CART("Cart"),
    ORDERS("Orders"),
    AUTH_TESTS("Account")
}

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: GroceryViewModel

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize Android notification channel for order tracking
        OrderNotificationManager.createNotificationChannel(this)

        val database = AppDatabase.getDatabase(this)
        val userDao = database.userDao()
        val orderDao = database.orderDao()

        val userRepository = UserRepository(userDao)
        val orderRepository = OrderRepository(orderDao)
        val inventoryRepository = InventoryRepository()
        val paymentGateway = SimulatedPaymentGateway()
        val checkoutUseCase = CheckoutUseCase(inventoryRepository, paymentGateway)

        viewModel = GroceryViewModel(
            inventoryRepository = inventoryRepository,
            paymentGateway = paymentGateway,
            checkoutUseCase = checkoutUseCase,
            userRepository = userRepository,
            orderRepository = orderRepository
        )

        // Connect real-time order status updates to Android push notifications
        viewModel.onOrderStatusChangedListener = { orderId, newStatus ->
            OrderNotificationManager.showOrderStatusNotification(
                context = this,
                orderId = orderId,
                status = newStatus
            )
        }

        setContent {
            MyApplicationTheme {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                var currentDestination by remember { mutableStateOf(AppDestination.CATALOG) }
                val snackbarHostState = remember { SnackbarHostState() }

                // Request POST_NOTIFICATIONS permission on Android 13+
                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                // Trigger snackbar alerts on messages
                LaunchedEffect(state.userMessage) {
                    state.userMessage?.let { msg ->
                        snackbarHostState.showSnackbar(msg)
                        viewModel.clearUserMessage()
                    }
                }

                // Order detail modal dialog when an order is selected
                state.selectedOrderForDetail?.let { selectedOrder ->
                    OrderDetailDialog(
                        order = selectedOrder,
                        onDismiss = { viewModel.selectOrderForDetail(null) },
                        onAdvanceStatus = { orderId -> viewModel.advanceOrderManually(orderId) }
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        TopAppBar(
                            title = {
                                Text(
                                    text = "Urbanmart",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            },
                            actions = {
                                if (state.isLoggedIn) {
                                    Text(
                                        text = "Hi, ${state.currentUser?.name?.split(" ")?.firstOrNull() ?: "Shopper"}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(end = 12.dp)
                                    )
                                }
                            },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                    },
                    bottomBar = {
                        NavigationBar(
                            modifier = Modifier.testTag("bottom_nav_bar")
                        ) {
                            NavigationBarItem(
                                selected = currentDestination == AppDestination.CATALOG,
                                onClick = { currentDestination = AppDestination.CATALOG },
                                icon = { Icon(Icons.Default.Storefront, contentDescription = "Groceries") },
                                label = { Text(AppDestination.CATALOG.label) },
                                modifier = Modifier.testTag("nav_catalog")
                            )

                            NavigationBarItem(
                                selected = currentDestination == AppDestination.CART,
                                onClick = { currentDestination = AppDestination.CART },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (state.cartItemCount > 0) {
                                                Badge { Text("${state.cartItemCount}") }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.ShoppingCart, contentDescription = "Cart")
                                    }
                                },
                                label = { Text(AppDestination.CART.label) },
                                modifier = Modifier.testTag("nav_cart")
                            )

                            NavigationBarItem(
                                selected = currentDestination == AppDestination.ORDERS,
                                onClick = { currentDestination = AppDestination.ORDERS },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (state.orderHistory.isNotEmpty()) {
                                                Badge { Text("${state.orderHistory.size}") }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.ReceiptLong, contentDescription = "Orders")
                                    }
                                },
                                label = { Text(AppDestination.ORDERS.label) },
                                modifier = Modifier.testTag("nav_orders")
                            )

                            NavigationBarItem(
                                selected = currentDestination == AppDestination.AUTH_TESTS,
                                onClick = { currentDestination = AppDestination.AUTH_TESTS },
                                icon = { Icon(Icons.Default.Person, contentDescription = "Account") },
                                label = { Text(if (state.isLoggedIn) "Account" else "Sign In") },
                                modifier = Modifier.testTag("nav_account")
                            )
                        }
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        when (currentDestination) {
                            AppDestination.CATALOG -> {
                                GroceryCatalogScreen(
                                    state = state,
                                    viewModel = viewModel
                                )
                            }
                            AppDestination.CART -> {
                                CartCheckoutScreen(
                                    state = state,
                                    viewModel = viewModel,
                                    onNavigateToCatalog = { currentDestination = AppDestination.CATALOG },
                                    onNavigateToAuth = { currentDestination = AppDestination.AUTH_TESTS }
                                )
                            }
                            AppDestination.ORDERS -> {
                                OrdersScreen(
                                    state = state,
                                    onOrderClick = { order -> viewModel.selectOrderForDetail(order) },
                                    onNavigateToAuth = { currentDestination = AppDestination.AUTH_TESTS },
                                    onNavigateToCatalog = { currentDestination = AppDestination.CATALOG },
                                    onAdvanceStatus = { orderId -> viewModel.advanceOrderManually(orderId) }
                                )
                            }
                            AppDestination.AUTH_TESTS -> {
                                AuthScreen(
                                    state = state,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

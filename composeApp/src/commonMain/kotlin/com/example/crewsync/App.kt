package com.example.crewsync

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.crewsync.di.appModule
import com.example.crewsync.ui.screens.*
import com.example.crewsync.data.model.Broadcast
import com.example.crewsync.util.initializeFirebase
import com.example.crewsync.util.notifyTaskUpdate
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.datetime.Clock
import org.koin.compose.KoinApplication

@Composable
fun App() {
    var firebaseState by remember { mutableStateOf("loading") } 
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        try {
            initializeFirebase()
            kotlinx.coroutines.delay(300)
            firebaseState = "ready"
        } catch (e: Exception) {
            firebaseState = "error"
            errorMessage = e.message ?: "Connection Error"
        }
    }

    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            when (firebaseState) {
                "loading" -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                "error" -> {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Database Error", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error)
                        Text("Please check your internet or API key restrictions.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { firebaseState = "loading" }) {
                            Text("Retry")
                        }
                    }
                }
                "ready" -> {
                    KoinApplication(application = {
                        modules(appModule)
                    }) {
                        AppMainContent()
                    }
                }
            }
        }
    }
}

@Composable
fun AppMainContent() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val firestore = remember { Firebase.firestore }
    val auth = remember { Firebase.auth }
    
    LaunchedEffect(auth.currentUser?.uid) {
        val uid = auth.currentUser?.uid ?: return@LaunchedEffect
        try {
            val snapshot = firestore.collection("users").document(uid).get()
            if (snapshot.exists) {
                val userProfile = snapshot.data<com.example.crewsync.data.model.User>()
                firestore.collection("broadcasts").snapshots.collect { broadcastSnapshot ->
                    broadcastSnapshot.documents.mapNotNull { doc ->
                        try { doc.data<Broadcast>() } catch (_: Exception) { null }
                    }.forEach { broadcast ->
                        if (broadcast.timestamp > Clock.System.now().toEpochMilliseconds() - 10000) {
                            if (broadcast.projectId.isNotEmpty() || userProfile.role != "Member") {
                                notifyTaskUpdate(
                                    title = "CREWSYNC ALERT",
                                    message = "${broadcast.title}\n${broadcast.message}"
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}
    }

    val items = listOf(
        Screen.Dashboard,
        Screen.Contacts,
        Screen.Profile,
        Screen.About,
    )

    val showBottomBar = currentDestination?.route in items.map { it.route }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    items.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = null) },
                            label = { Text(screen.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().route!!) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars // Proper edge-to-edge support
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = "login"
            ) {
                composable("login") {
                    LoginScreen(
                        onLoginSuccess = {
                            navController.navigate(Screen.Dashboard.route) {
                                popUpTo("login") { inclusive = true }
                            }
                        }
                    )
                }
                composable(Screen.Dashboard.route) {
                    DashboardScreen(
                        onLogout = {
                            navController.navigate("login") {
                                popUpTo(Screen.Dashboard.route) { inclusive = true }
                            }
                        },
                        onProjectClick = { projectId ->
                            navController.navigate("projectDetails/$projectId")
                        }
                    )
                }
                composable("projectDetails/{projectId}") { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
                    ProjectDetailsScreen(
                        projectId = projectId,
                        onBack = { navController.popBackStack() },
                        onMemberClick = { memberEmail ->
                            navController.navigate("directChat/$memberEmail")
                        },
                        onMarkupClick = { url, name, fileId ->
                            navController.navigate("markup/$projectId/$fileId")
                        }
                    )
                }
                composable("markup/{projectId}/{fileId}") { backStackEntry ->
                    val projectId = backStackEntry.arguments?.getString("projectId") ?: ""
                    val fileId = backStackEntry.arguments?.getString("fileId") ?: ""
                    MarkupScreen(
                        projectId = projectId,
                        fileId = fileId,
                        onBack = { navController.popBackStack() },
                        onSaveSuccess = { navController.popBackStack() }
                    )
                }
                composable("directChat/{userEmail}") { backStackEntry ->
                    val userEmail = backStackEntry.arguments?.getString("userEmail") ?: ""
                    DirectChatScreen(
                        otherUserEmail = userEmail,
                        onBack = { navController.popBackStack() }
                    )
                }
                composable(Screen.Contacts.route) {
                    ContactsScreen()
                }
                composable(Screen.Profile.route) {
                    ProfileScreen()
                }
                composable(Screen.About.route) {
                    AboutScreen(
                        onNavigateToReports = { navController.navigate("bugReports") }
                    )
                }
                composable("bugReports") {
                    BugReportsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Screen("dashboard", "Home", Icons.Default.Home)
    object Contacts : Screen("contacts", "Contacts", Icons.AutoMirrored.Filled.List)
    object Profile : Screen("profile", "Profile", Icons.Default.Person)
    object About : Screen("about", "About", Icons.Default.DateRange)
}

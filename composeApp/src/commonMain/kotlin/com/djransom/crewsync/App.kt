package com.djransom.crewsync

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
import com.djransom.crewsync.di.appModule
import com.djransom.crewsync.ui.screens.*
import com.djransom.crewsync.data.model.Broadcast
import com.djransom.crewsync.data.model.ChatMessage
import com.djransom.crewsync.data.model.Project
import com.djransom.crewsync.util.initializeFirebase
import com.djransom.crewsync.util.notifyChatMessage
import com.djransom.crewsync.util.notifyTaskUpdate
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.auth.auth
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import com.djransom.crewsync.util.toProjectSafe
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

sealed class Screen(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Dashboard : Screen("dashboard", "Home", Icons.Default.Home)
    object Contacts : Screen("contacts", "Contacts", Icons.AutoMirrored.Filled.List)
    object Profile : Screen("profile", "Profile", Icons.Default.Person)
    object About : Screen("about", "About", Icons.Default.DateRange)
}

@Composable
fun App() {
    var firebaseState by remember { mutableStateOf("loading") } 

    LaunchedEffect(Unit) {
        try {
            initializeFirebase()
        } catch (_: Throwable) {
        } finally {
            firebaseState = "ready"
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
                        Text("Check connection and retry.", textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { firebaseState = "loading" }) {
                            Text("Retry")
                        }
                    }
                }
                "ready" -> {
                    remember {
                        if (GlobalContext.getOrNull() == null) {
                            startKoin {
                                modules(appModule)
                            }
                        }
                    }
                    AppMainContent()
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

    val firestore = remember {
        try { Firebase.firestore } catch (e: Throwable) {
            println("AppMainContent: Firestore note: ${e.message}")
            null
        }
    }
    val auth = remember {
        try { Firebase.auth } catch (e: Throwable) {
            println("AppMainContent: Auth note: ${e.message}")
            null
        }
    }
    val scope = rememberCoroutineScope()
    val authStateFlow = remember(auth) {
        try { auth?.authStateChanged ?: kotlinx.coroutines.flow.emptyFlow() } catch (_: Throwable) { kotlinx.coroutines.flow.emptyFlow() }
    }
    val currentUser by authStateFlow.collectAsState(initial = null)
    var isAuthChecked by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            authStateFlow.collect {
                isAuthChecked = true
            }
        } catch (_: Throwable) {
            isAuthChecked = true
        }
    }
    
    val safeHandler = remember {
        CoroutineExceptionHandler { _, throwable ->
            println("Caught background coroutine exception: ${throwable.message}")
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }

    // GLOBAL CROSS-PLATFORM NOTIFICATION ENGINE
    LaunchedEffect(currentUser?.uid, currentUser?.email) {
        val userEmail = currentUser?.email?.lowercase() ?: return@LaunchedEffect
        val fStore = firestore ?: return@LaunchedEffect
        
        // Listener 1: Site Alerts (Broadcasts)
        launch {
            try {
                fStore.collection("broadcasts").snapshots.collect { snapshot ->
                    snapshot.documents.mapNotNull { doc ->
                        try { doc.data<Broadcast>() } catch (_: Exception) { null }
                    }.forEach { broadcast ->
                        if (broadcast.timestamp > Clock.System.now().toEpochMilliseconds() - 10000) {
                            notifyTaskUpdate(
                                title = "SITE ALERT: ${broadcast.title}",
                                message = broadcast.message
                            )
                            snackbarHostState.showSnackbar("🚨 SITE ALERT: ${broadcast.title} - ${broadcast.message}")
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // Listener 2: Project Chat Messages Across User Projects
        launch {
            try {
                fStore.collection("projects").snapshots.collect { snapshot ->
                    val projectIds = snapshot.documents.map { it.id }
                    projectIds.forEach { projId ->
                        launch {
                            try {
                                fStore.collection("projects").document(projId).collection("messages").snapshots.collect { msgSnap ->
                                    val msgs = msgSnap.documents.mapNotNull { doc ->
                                        try { doc.data<ChatMessage>().copy(id = doc.id) } catch (_: Exception) { null }
                                    }
                                    if (msgs.isNotEmpty()) {
                                        val last = msgs.maxByOrNull { it.timestamp }
                                        if (last != null && last.senderEmail.lowercase() != userEmail && last.timestamp > Clock.System.now().toEpochMilliseconds() - 8000) {
                                            val sender = last.senderEmail.substringBefore("@")
                                            notifyChatMessage(projId, sender, last.text)
                                            snackbarHostState.showSnackbar("💬 Chat ($sender): ${last.text}")
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    val items = listOf(
        Screen.Dashboard,
        Screen.Contacts,
        Screen.Profile,
        Screen.About,
    )

    val showNav = currentDestination?.route in items.map { it.route }

    if (!isAuthChecked) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
    } else if (currentUser == null) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            LoginScreen(onLoginSuccess = { })
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWideScreen = maxWidth >= 720.dp

            Row(modifier = Modifier.fillMaxSize()) {
                if (showNav && isWideScreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        header = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 16.dp)
                            ) {
                                Text(
                                    "CREWSYNC",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                                )
                                Text(
                                    "WEB DASHBOARD",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    ) {
                        Spacer(modifier = Modifier.weight(1f))
                        items.forEach { screen ->
                            NavigationRailItem(
                                icon = { Icon(screen.icon, contentDescription = screen.label) },
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
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        NavHost(
                            navController = navController,
                            startDestination = Screen.Dashboard.route
                        ) {
                            composable(Screen.Dashboard.route) {
                                DashboardScreen(
                                    onLogout = {
                                        scope.launch {
                                            try {
                                                auth?.signOut()
                                            } catch (_: Exception) {}
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
                if (showNav && !isWideScreen) {
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
            }
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }
}
}
}

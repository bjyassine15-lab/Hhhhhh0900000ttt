package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.ui.screens.CaregiverSetupScreen
import com.example.ui.screens.MainDialerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.viewmodel.DialerViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                val navController = rememberNavController()
                val dialerViewModel: DialerViewModel = viewModel()

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = "main_dialer",
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("main_dialer") {
                            MainDialerScreen(
                                viewModel = dialerViewModel,
                                onNavigateToSetup = {
                                    navController.navigate("caregiver_setup")
                                }
                            )
                        }
                        composable("caregiver_setup") {
                            CaregiverSetupScreen(
                                viewModel = dialerViewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.simscheduler.ui

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.simscheduler.R
import com.simscheduler.databinding.ActivityMainBinding
import com.simscheduler.service.SimAccessibilityService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this,
            "⚠️ Notifications disabled — enable in Settings",
            Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupTabs()
        requestNotificationPermission()
    }

    private fun setupTabs() {
        val adapter = TabAdapter(this)
        binding.viewPager.adapter = adapter

        TabLayoutMediator(binding.tabLayout, binding.viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> "Home"
                1 -> "Schedule"
                2 -> "History"
                3 -> "Settings"
                else -> ""
            }
            tab.icon = ContextCompat.getDrawable(this, when (position) {
                0 -> android.R.drawable.ic_menu_myplaces
                1 -> android.R.drawable.ic_menu_recent_history
                2 -> android.R.drawable.ic_menu_sort_by_size
                3 -> android.R.drawable.ic_menu_preferences
                else -> android.R.drawable.ic_menu_myplaces
            })
        }.attach()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    class TabAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount() = 4
        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> SimControlFragment()
            1 -> ScheduleFragment()
            2 -> HistoryFragment()
            3 -> SettingsFragment()
            else -> SimControlFragment()
        }
    }
}

package com.itsluminous.hikviewer

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.itsluminous.hikviewer.databinding.ActivityMainBinding

/**
 * Entry point for both the TV (LEANBACK_LAUNCHER) and phone (LAUNCHER) launchers.
 *
 * Stage 1 placeholder: shows the application name. Later stages replace the content with the
 * login screen and the camera grid.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.appName.text = getString(R.string.app_name)
    }
}

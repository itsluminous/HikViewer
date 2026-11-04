package com.itsluminous.hikviewer.ui.login

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.databinding.ActivityLoginBinding
import com.itsluminous.hikviewer.di.AppGraph
import com.itsluminous.hikviewer.ui.common.LambdaViewModelFactory
import com.itsluminous.hikviewer.ui.grid.GridActivity
import kotlinx.coroutines.launch

/**
 * Launcher Activity for TV and phone. Skips itself when a session is stored; otherwise shows the
 * account / password / region form. IME "next"/"done" moves focus down the form so a remote
 * never needs to leave the keyboard to reach the button.
 */
class LoginActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val viewModel: LoginViewModel by viewModels {
        LambdaViewModelFactory { LoginViewModel(AppGraph.of(this).account) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (viewModel.hasSession) {
            openGrid()
            return
        }
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        bindForm()
        observe()
    }

    private fun bindForm() {
        val regions = Region.entries
        binding.region.adapter =
            ArrayAdapter(this, R.layout.item_spinner, regions.map { it.displayName }).also {
                it.setDropDownViewResource(R.layout.item_spinner_dropdown)
            }
        binding.region.setSelection(regions.indexOf(Region.DEFAULT))
        binding.account.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_NEXT) binding.password.requestFocus() else false
        }
        binding.password.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submit()
                true
            } else {
                false
            }
        }
        binding.account.doAfterTextChanged { viewModel.dismissError() }
        binding.password.doAfterTextChanged { viewModel.dismissError() }
        binding.login.setOnClickListener { submit() }
        binding.account.requestFocus()
    }

    private fun submit() {
        viewModel.login(
            binding.account.text.toString(),
            binding.password.text.toString(),
            Region.entries[binding.region.selectedItemPosition],
        )
    }

    private fun observe() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun render(state: LoginUiState) {
        val busy = state == LoginUiState.Busy
        binding.progress.visibility = if (busy) View.VISIBLE else View.INVISIBLE
        binding.login.isEnabled = !busy
        binding.account.isEnabled = !busy
        binding.password.isEnabled = !busy
        binding.region.isEnabled = !busy
        binding.error.text =
            when (state) {
                is LoginUiState.Failed ->
                    if (state.arg !=
                        null
                    ) {
                        getString(state.message, state.arg)
                    } else {
                        getString(state.message)
                    }
                else -> ""
            }
        binding.error.visibility = if (state is LoginUiState.Failed) View.VISIBLE else View.INVISIBLE
        if (state == LoginUiState.Done) openGrid()
    }

    private fun openGrid() {
        startActivity(Intent(this, GridActivity::class.java))
        finish()
    }
}

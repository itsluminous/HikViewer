package com.itsluminous.hikviewer.ui.login

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.LoginError
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.data.AccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the login screen shows. */
sealed class LoginUiState {
    /** Form enabled. */
    object Idle : LoginUiState()

    /** Request in flight; form disabled. */
    object Busy : LoginUiState()

    /** Logged in; the screen moves on to the grid. */
    object Done : LoginUiState()

    /** The attempt failed; [message] is a string resource, [arg] its optional formatting argument. */
    data class Failed(
        @StringRes val message: Int,
        val arg: String? = null,
    ) : LoginUiState()
}

/**
 * Drives the login form. The account field takes a bare mobile number and the chosen region adds
 * its country code ([AccountInput]); a rejected login reminds the user of that rule.
 */
class LoginViewModel(
    private val account: AccountRepository,
) : ViewModel() {
    private val stateFlow = MutableStateFlow<LoginUiState>(LoginUiState.Idle)

    /** Screen state. */
    val state: StateFlow<LoginUiState> = stateFlow.asStateFlow()

    /** True when a session is stored, so the screen can skip straight to the grid. */
    val hasSession: Boolean get() = account.isLoggedIn

    /** Submits the form. */
    fun login(
        username: String,
        password: String,
        region: Region,
    ) {
        if (stateFlow.value == LoginUiState.Busy) return
        val user = AccountInput.resolve(username, region)
        if (user == null || password.isEmpty()) {
            stateFlow.value = LoginUiState.Failed(R.string.login_error_empty)
            return
        }
        stateFlow.value = LoginUiState.Busy
        viewModelScope.launch {
            stateFlow.value =
                try {
                    account.login(user, password, region)
                    LoginUiState.Done
                } catch (e: LoginError) {
                    map(e)
                }
        }
    }

    /** Resets a failure to the idle form (after the user edits a field). */
    fun dismissError() {
        if (stateFlow.value is LoginUiState.Failed) stateFlow.value = LoginUiState.Idle
    }

    /** User-facing text for every `:core` login failure. */
    companion object {
        /** Maps [e] to a message resource (and optional argument). */
        fun map(e: LoginError): LoginUiState.Failed = when (e) {
            is LoginError.InvalidCredentials -> LoginUiState.Failed(R.string.login_error_credentials)
            is LoginError.NeedsVerification ->
                LoginUiState.Failed(
                    R.string.login_error_verification,
                    e.kind.name
                        .lowercase()
                        .replace('_', ' '),
                )
            is LoginError.Server -> LoginUiState.Failed(R.string.login_error_server, (e.metaCode ?: "?").toString())
            is LoginError.RedirectLoop -> LoginUiState.Failed(R.string.login_error_redirect)
            is LoginError.SessionExpired -> LoginUiState.Failed(R.string.login_error_expired)
            is LoginError.Network -> LoginUiState.Failed(R.string.login_error_network)
        }
    }
}

package com.itsluminous.hikviewer.ui.login

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.itsluminous.hikviewer.R
import com.itsluminous.hikviewer.core.auth.LoginError
import com.itsluminous.hikviewer.core.auth.Region
import com.itsluminous.hikviewer.core.auth.VerificationKind
import com.itsluminous.hikviewer.fakes.FakeCloudServices
import com.itsluminous.hikviewer.fakes.TestGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException

@RunWith(AndroidJUnit4::class)
class LoginViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val cloud = FakeCloudServices()
    private val graph = TestGraph.create(ApplicationProvider.getApplicationContext(), cloud)
    private val viewModel = LoginViewModel(graph.account)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `empty fields fail locally without a network call`() {
        viewModel.login("", "x", Region.INDIA)
        assertThat(viewModel.state.value).isEqualTo(LoginUiState.Failed(R.string.login_error_empty))
        viewModel.login("91123", " ", Region.INDIA)
        assertThat(cloud.logins).isEmpty()
    }

    @Test
    fun `successful login goes busy then done and sends the account as typed for Global`() = runTest(dispatcher) {
        assertThat(viewModel.hasSession).isFalse()
        viewModel.login(" 911234567890 ", "secret", Region.GLOBAL)
        assertThat(viewModel.state.value).isEqualTo(LoginUiState.Busy)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(LoginUiState.Done)
        assertThat(cloud.logins.single()).isEqualTo(Triple("911234567890", "secret", Region.GLOBAL))
        assertThat(viewModel.hasSession).isTrue()
    }

    @Test
    fun `the region's country code is prepended to a bare mobile number`() = runTest(dispatcher) {
        viewModel.login("98765 43210", "secret", Region.INDIA)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(cloud.logins.single()).isEqualTo(Triple("919876543210", "secret", Region.INDIA))
    }

    @Test
    fun `emails and plus-prefixed numbers are sent without the region code`() = runTest(dispatcher) {
        viewModel.login("me@example.com", "secret", Region.INDIA)
        dispatcher.scheduler.advanceUntilIdle()
        viewModel.login("+65 1234 5678", "secret", Region.INDIA)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(cloud.logins.map { it.first }).containsExactly("me@example.com", "6512345678").inOrder()
    }

    @Test
    fun `wrong password maps to the country-code reminder and clears on edit`() = runTest(dispatcher) {
        viewModel.login("1234567890", "nope", Region.INDIA)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(LoginUiState.Failed(R.string.login_error_credentials))
        viewModel.dismissError()
        assertThat(viewModel.state.value).isEqualTo(LoginUiState.Idle)
    }

    @Test
    fun `a second submit while busy is ignored`() = runTest(dispatcher) {
        viewModel.login("a", "secret", Region.INDIA)
        viewModel.login("b", "secret", Region.INDIA)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(cloud.logins).hasSize(1)
    }

    @Test
    fun `every core error has a message`() {
        assertThat(
            LoginViewModel.map(LoginError.InvalidCredentials()).message,
        ).isEqualTo(R.string.login_error_credentials)
        val mfa = LoginViewModel.map(LoginError.NeedsVerification(VerificationKind.SMS_CODE, 6002))
        assertThat(mfa.message).isEqualTo(R.string.login_error_verification)
        assertThat(mfa.arg).isEqualTo("sms code")
        assertThat(
            LoginViewModel.map(LoginError.Server(1234, "x")),
        ).isEqualTo(LoginUiState.Failed(R.string.login_error_server, "1234"))
        assertThat(LoginViewModel.map(LoginError.Server(null, null)).arg).isEqualTo("?")
        assertThat(
            LoginViewModel.map(LoginError.RedirectLoop(listOf("a", "b"))).message,
        ).isEqualTo(R.string.login_error_redirect)
        assertThat(LoginViewModel.map(LoginError.SessionExpired(401)).message).isEqualTo(R.string.login_error_expired)
        assertThat(
            LoginViewModel.map(LoginError.Network(IOException("x"))).message,
        ).isEqualTo(R.string.login_error_network)
    }

    @Test
    fun `server-side failures reach the screen`() = runTest(dispatcher) {
        cloud.nextLoginError = LoginError.Network(IOException("dns"))
        viewModel.login("a", "secret", Region.INDIA)
        dispatcher.scheduler.advanceUntilIdle()
        assertThat(viewModel.state.value).isEqualTo(LoginUiState.Failed(R.string.login_error_network))
    }
}

package com.itsluminous.hikviewer.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Builds any ViewModel from a lambda, which is all a hand-wired graph needs: the Activity passes
 * `{ GridViewModel(graph.account, ...) }` and the framework keeps the instance across rotations.
 */
class LambdaViewModelFactory<VM : ViewModel>(
    private val create: () -> VM,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = create() as T
}

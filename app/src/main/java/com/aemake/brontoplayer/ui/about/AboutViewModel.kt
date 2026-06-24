package com.aemake.brontoplayer.ui.about

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.aemake.brontoplayer.billing.BillingManager
import com.aemake.brontoplayer.billing.DonationEvent
import com.aemake.brontoplayer.billing.DonationState
import com.aemake.brontoplayer.billing.DonationTier
import com.aemake.brontoplayer.di.ServiceLocator
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Holds the About screen's donation state. Opens the billing connection for as long as the screen
 * is alive (via [BillingManager.start]/[BillingManager.stop]) and forwards purchase requests.
 */
class AboutViewModel(private val billing: BillingManager) : ViewModel() {

    val state: StateFlow<DonationState> = billing.state
    val events: SharedFlow<DonationEvent> = billing.events

    init {
        billing.start()
    }

    fun donate(activity: Activity, tier: DonationTier) = billing.purchase(activity, tier)

    override fun onCleared() {
        billing.stop()
    }

    companion object {
        fun factory() = viewModelFactory {
            initializer { AboutViewModel(ServiceLocator.billingManager) }
        }
    }
}

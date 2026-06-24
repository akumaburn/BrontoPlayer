package com.aemake.brontoplayer.billing

import androidx.annotation.StringRes
import com.aemake.brontoplayer.R

/**
 * The three donation tiers offered as one-time, *consumable* in-app products. They unlock nothing
 * — purchasing simply leaves a tip and is immediately consumed so it can be repeated.
 *
 * [productId] must match the in-app product IDs configured in the Google Play Console for the
 * release application id (`com.aemake.brontoplayer`). [fallbackPrice] is shown only until Google Play
 * returns the real, locale-formatted price (or when billing is unavailable, e.g. a sideloaded
 * build), so the buttons always read sensibly.
 */
enum class DonationTier(
    val productId: String,
    val fallbackPrice: String,
    @StringRes val descriptionRes: Int,
) {
    SMALL("donate_small", "$5", R.string.donate_tier_small),
    MEDIUM("donate_medium", "$15", R.string.donate_tier_medium),
    LARGE("donate_large", "$30", R.string.donate_tier_large),
}

/** Display model for a donation button: the tier, the price to show, and whether Play can sell it. */
data class DonationProductUi(
    val tier: DonationTier,
    val priceLabel: String,
    val available: Boolean,
)

/** Immutable snapshot of the donation/billing state surfaced to the About screen. */
data class DonationState(
    val connected: Boolean = false,
    val products: List<DonationProductUi> = DonationTier.entries.map {
        DonationProductUi(tier = it, priceLabel = it.fallbackPrice, available = false)
    },
) {
    /** True once Play has confirmed at least one purchasable tier. */
    val anyAvailable: Boolean get() = products.any { it.available }
}

/** One-shot outcomes of a donation attempt, surfaced to the user as a snackbar. */
sealed interface DonationEvent {
    /** A tip completed successfully. */
    data object ThankYou : DonationEvent

    /** A tip is awaiting payment (e.g. a slow/cash payment method). */
    data object Pending : DonationEvent

    /** Billing isn't usable (not installed from Play, product not configured, offline, etc.). */
    data object Unavailable : DonationEvent
}

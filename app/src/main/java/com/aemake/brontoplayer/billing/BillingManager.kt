package com.aemake.brontoplayer.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thin wrapper around the Google Play Billing client for the optional donation tips.
 *
 * Design notes:
 * - The three tiers are **consumable** one-time products. A completed purchase grants nothing; it is
 *   consumed immediately so the user can tip again, and so a tip never gets "stuck" as owned.
 * - Billing client callbacks are delivered on the main thread, so the [StateFlow]/[SharedFlow]
 *   mutations here are main-thread only — no extra synchronization is needed.
 * - Connection is reference counted via [start]/[stop] (tied to the About screen's lifecycle) so the
 *   service binding isn't held while that screen is gone. [enableAutoServiceReconnection] handles
 *   transient disconnects transparently while connected.
 * - Everything degrades gracefully: on a build that can't reach Play billing (e.g. sideloaded, or
 *   before the products are configured in the Play Console) the buttons keep their fallback prices
 *   and a tap surfaces [DonationEvent.Unavailable] instead of crashing.
 */
class BillingManager(context: Context) {

    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(DonationState())
    val state: StateFlow<DonationState> = _state.asStateFlow()

    // Replay 0, small buffer: one-shot, surfaced as a snackbar. tryEmit never suspends/drops here.
    private val _events = MutableSharedFlow<DonationEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<DonationEvent> = _events.asSharedFlow()

    /** Resolved Play product details, keyed by product id — needed to launch the purchase flow. */
    private val productDetailsById = mutableMapOf<String, ProductDetails>()

    /**
     * Purchase tokens we've already thanked the user for. A tip can be consumed via two paths (the
     * live [PurchasesUpdatedListener] callback and [consumeOutstanding] on the next connect); this
     * guarantees exactly one "thank you" per tip. Main-thread only, like every other field here.
     */
    private val thankedTokens = mutableSetOf<String>()

    private var billingClient: BillingClient? = null
    private var refCount = 0

    private val purchasesListener = PurchasesUpdatedListener { result, purchases ->
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK ->
                purchases?.forEach(::handlePurchase)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED ->
                // A previous consumable wasn't consumed yet; recover by consuming outstanding ones.
                consumeOutstanding()
            BillingClient.BillingResponseCode.USER_CANCELED ->
                Unit // Silent — the user simply backed out.
            else ->
                _events.tryEmit(DonationEvent.Unavailable)
        }
    }

    /** Opens (or reuses) the billing connection. Safe to call repeatedly; reference counted. */
    fun start() {
        refCount++
        val existing = billingClient
        if (existing != null) {
            if (existing.isReady) {
                queryProducts()
                consumeOutstanding()
            }
            return
        }
        val client = BillingClient.newBuilder(appContext)
            .setListener(purchasesListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
            )
            .enableAutoServiceReconnection()
            .build()
        billingClient = client
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                val ok = result.responseCode == BillingClient.BillingResponseCode.OK
                _state.update { it.copy(connected = ok) }
                if (ok) {
                    queryProducts()
                    consumeOutstanding()
                }
            }

            override fun onBillingServiceDisconnected() {
                _state.update { it.copy(connected = false) }
                // enableAutoServiceReconnection() retries the connection for us.
            }
        })
    }

    /** Releases the connection once the last [start] caller has [stop]ped. */
    fun stop() {
        refCount = (refCount - 1).coerceAtLeast(0)
        if (refCount == 0) {
            billingClient?.endConnection()
            billingClient = null
            productDetailsById.clear()
            thankedTokens.clear()
            _state.update { it.copy(connected = false) }
        }
    }

    private fun queryProducts() {
        val client = billingClient ?: return
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(
                DonationTier.entries.map { tier ->
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(tier.productId)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                },
            )
            .build()
        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            productDetailsById.clear()
            queryResult.productDetailsList.forEach { productDetailsById[it.productId] = it }
            _state.update { st ->
                st.copy(
                    products = DonationTier.entries.map { tier ->
                        val pd = productDetailsById[tier.productId]
                        val price = pd?.oneTimePurchaseOfferDetails?.formattedPrice
                        DonationProductUi(
                            tier = tier,
                            priceLabel = price ?: tier.fallbackPrice,
                            available = pd != null,
                        )
                    },
                )
            }
        }
    }

    /** Launches the Play purchase dialog for [tier]; reports [DonationEvent.Unavailable] if it can't. */
    fun purchase(activity: Activity, tier: DonationTier) {
        val client = billingClient
        val details = productDetailsById[tier.productId]
        if (client == null || !client.isReady || details == null) {
            _events.tryEmit(DonationEvent.Unavailable)
            return
        }
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .build()
        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .build()
        val result = client.launchBillingFlow(activity, flowParams)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            _events.tryEmit(DonationEvent.Unavailable)
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            // ThankYou is emitted from consume()'s success callback (see below) so it fires for BOTH
            // a live purchase and a tip that completed out-of-band and is found by consumeOutstanding.
            Purchase.PurchaseState.PURCHASED ->
                consume(purchase)
            Purchase.PurchaseState.PENDING ->
                _events.tryEmit(DonationEvent.Pending)
            else ->
                Unit
        }
    }

    /** Consume any already-owned (but not-yet-consumed) tips so they don't block future ones. */
    private fun consumeOutstanding() {
        val client = billingClient ?: return
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        client.queryPurchasesAsync(params) { result, purchases ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryPurchasesAsync
            purchases
                .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                .forEach(::consume)
        }
    }

    private fun consume(purchase: Purchase) {
        val client = billingClient ?: return
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.consumeAsync(params) { result, _ ->
            // Donations grant nothing, so there's nothing to unlock — but once the tip is actually
            // consumed we thank the user (exactly once per token).
            if (result.responseCode == BillingClient.BillingResponseCode.OK &&
                thankedTokens.add(purchase.purchaseToken)
            ) {
                _events.tryEmit(DonationEvent.ThankYou)
            }
        }
    }
}

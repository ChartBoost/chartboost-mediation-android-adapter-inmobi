/*
 * Copyright 2023-2024 Chartboost, Inc.
 *
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.inmobiadapter

import android.app.Activity
import android.content.Context
import com.chartboost.chartboostmediationsdk.domain.*
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.BIDDER_INFO_FETCH_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.CUSTOM
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_CLICK
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_DISMISS
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_REWARD
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.DID_TRACK_IMPRESSION
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_DENIED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_GRANTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.GDPR_CONSENT_UNKNOWN
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.INVALIDATE_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.LOAD_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SETUP_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_FAILED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_STARTED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.SHOW_SUCCEEDED
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_NOT_UNDERAGE
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController.PartnerAdapterEvents.USER_IS_UNDERAGE
import com.chartboost.core.consent.ConsentKey
import com.chartboost.core.consent.ConsentKeys
import com.chartboost.core.consent.ConsentValue
import com.chartboost.core.consent.ConsentValues
import com.inmobi.ads.AdMetaInfo
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiBanner
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.listeners.BannerAdEventListener
import com.inmobi.ads.listeners.InterstitialAdEventListener
import com.inmobi.sdk.InMobiSdk
import com.inmobi.sdk.SdkInitializationListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * The Chartboost Mediation InMobi SDK adapter.
 */
class InMobiAdapter : PartnerAdapter {
    companion object {
        /**
         * Key for parsing the InMobi SDK account ID.
         */
        private const val ACCOUNT_ID_KEY = "account_id"
    }

    /**
     * The InMobi adapter configuration.
     */
    override var configuration: PartnerAdapterConfiguration = InMobiAdapterConfiguration

    /**
     * A lambda to call for successful InMobi ad shows.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * A lambda to call for failed InMobi ad shows.
     */
    private var onShowError: () -> Unit = {}

    /**
     * Whether GDPR consent was given.
     */
    private var gdprConsentGiven: String? = null

    /**
     * The TCF String.
     */
    private var tcfString: String? = null

    /**
     * A map of InMobi interstitial ads keyed by a request identifier.
     */
    private val inMobiInterstitialAds = mutableMapOf<String, InMobiInterstitial>()

    /**
     * Initialize the InMobi SDK so that it is ready to request ads.
     *
     * @param context The current [Context].
     * @param partnerConfiguration Configuration object containing relevant data to initialize InMobi.
     *
     * @return Result.success(Unit) if InMobi successfully initialized, Result.failure(Exception) otherwise.
     */
    override suspend fun setUp(
        context: Context,
        partnerConfiguration: PartnerConfiguration,
    ): Result<Map<String, Any>> {
        PartnerLogController.log(SETUP_STARTED)

        Json.decodeFromJsonElement<String>(
            (partnerConfiguration.credentials as JsonObject).getValue(ACCOUNT_ID_KEY),
        ).trim()
            .takeIf { it.isNotEmpty() }
            ?.let { accountId ->
                setConsents(context, partnerConfiguration.consents, partnerConfiguration.consents.keys)
                val gdprConsent = buildGdprJsonObject()
                inMobiInterstitialAds.clear()

                return suspendCancellableCoroutine { continuation ->
                    fun resumeOnce(result: Result<Map<String, Any>>) {
                        if (continuation.isActive) {
                            continuation.resume(result)
                        }
                    }
                    InMobiSdk.init(
                        context = context.applicationContext,
                        accountId = accountId,
                        consentObject = gdprConsent,
                        sdkInitializationListener =
                            object : SdkInitializationListener {
                                override fun onInitializationComplete(error: Error?) {
                                    resumeOnce(
                                        error?.let {
                                            PartnerLogController.log(SETUP_FAILED, "${it.message}")
                                            Result.failure(
                                                ChartboostMediationAdException(ChartboostMediationError.InitializationError.Unknown),
                                            )
                                        } ?: run {
                                            PartnerLogController.log(SETUP_SUCCEEDED)
                                            Result.success(emptyMap())
                                        },
                                    )
                                }
                            },
                    )
                }
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing account ID.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.InitializationError.InvalidCredentials))
        }
    }

    /**
     * Notify InMobi of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isUserUnderage True if the user is subject to COPPA, false otherwise.
     */
    override fun setIsUserUnderage(
        context: Context,
        isUserUnderage: Boolean,
    ) {
        PartnerLogController.log(
            if (isUserUnderage) {
                USER_IS_UNDERAGE
            } else {
                USER_IS_NOT_UNDERAGE
            },
        )

        // NO-OP: InMobi does not have an API for setting COPPA.
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PartnerAdPreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PartnerAdPreBidRequest,
    ): Result<Map<String, String>> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return Result.success(emptyMap())
    }

    /**
     * Attempt to load an InMobi ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    override suspend fun load(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            PartnerAdFormats.BANNER -> {
                withContext(Main) {
                    loadBannerAd(context, request, partnerAdListener)
                }
            }
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED ->
                loadFullScreenAd(
                    context,
                    request,
                    partnerAdListener,
                )
            else -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Attempt to show the currently loaded InMobi ad.
     *
     * @param activity The current [Activity]
     * @param partnerAd The [PartnerAd] object containing the InMobi ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(
        activity: Activity,
        partnerAd: PartnerAd,
    ): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            PartnerAdFormats.INTERSTITIAL, PartnerAdFormats.REWARDED -> {
                (partnerAd.ad as? InMobiInterstitial)?.let { ad ->
                    if (ad.isReady()) {
                        suspendCancellableCoroutine { continuation ->
                            fun resumeOnce(result: Result<PartnerAd>) {
                                if (continuation.isActive) {
                                    continuation.resume(result)
                                }
                            }
                            onShowSuccess = {
                                PartnerLogController.log(SHOW_SUCCEEDED)
                                resumeOnce(Result.success(partnerAd))
                            }

                            onShowError = {
                                PartnerLogController.log(
                                    SHOW_FAILED,
                                    "Placement: ${partnerAd.request.partnerPlacement}",
                                )
                                resumeOnce(
                                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.Unknown)),
                                )
                            }
                            ad.show()
                        }
                    } else {
                        PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                        Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotReady))
                    }
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.AdNotFound))
                }
            }
            else -> {
                PartnerLogController.log(SHOW_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.ShowError.UnsupportedAdFormat))
            }
        }
    }

    /**
     * Discard unnecessary InMobi ad objects and release resources.
     *
     * @param partnerAd The [PartnerAd] object containing the InMobi ad to be discarded.
     *
     * @return Result.success(PartnerAd) if the ad was successfully discarded, Result.failure(Exception) otherwise.
     */
    override suspend fun invalidate(partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(INVALIDATE_STARTED)

        return when (partnerAd.request.format) {
            PartnerAdFormats.BANNER -> destroyBannerAd(partnerAd)
            else -> {
                // InMobi does not have destroy methods for their fullscreen ads.
                // Remove show result for this partner ad. No longer needed.
                inMobiInterstitialAds.remove(partnerAd.request.identifier)
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
        }
    }

    override fun setConsents(
        context: Context,
        consents: Map<ConsentKey, ConsentValue>,
        modifiedKeys: Set<ConsentKey>
    ) {
        gdprConsentGiven = consents[ConsentKeys.GDPR_CONSENT_GIVEN]
        PartnerLogController.log(
            when (gdprConsentGiven) {
                ConsentValues.GRANTED -> GDPR_CONSENT_GRANTED
                ConsentValues.DENIED -> GDPR_CONSENT_DENIED
                else -> GDPR_CONSENT_UNKNOWN
            }
        )

        tcfString = consents[ConsentKeys.TCF]
    }

    /**
     * Build a [JSONObject] that will be passed to the InMobi SDK for GDPR during [setUp].
     *
     * @return a [JSONObject] object as to whether GDPR consent is granted or not.
     */
    private fun buildGdprJsonObject(
    ): JSONObject {
        return JSONObject().apply {
            try {
                gdprConsentGiven?.let {
                    if (it != ConsentValues.DOES_NOT_APPLY) {
                        put(InMobiSdk.IM_GDPR_CONSENT_AVAILABLE, it == ConsentValues.GRANTED)
                    }
                }
                tcfString?.let {
                    put(InMobiSdk.IM_GDPR_CONSENT_IAB, it)
                } ?: PartnerLogController.log(CUSTOM, "TCFv2 String is empty or was not found.")
            } catch (error: JSONException) {
                PartnerLogController.log(
                    CUSTOM,
                    "Failed to build GDPR JSONObject with error: ${error.message}",
                )
            }
        }
    }

    /**
     * Attempt to load an InMobi banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadBannerAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        request.partnerPlacement.toLongOrNull()?.let { placement ->
            // There should be no placement with this value.
            if (placement == 0L) {
                PartnerLogController.log(LOAD_FAILED)
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidPartnerPlacement))
            }

            (context as? Activity)?.let { activity ->
                request.bannerSize?.size?.let { size ->
                    // InMobi silently fails and causes the coroutine from returning a result.
                    // We will check for the banner size and return a failure if the sizes are either 0.
                    if ((size.width == 0) or (size.height == 0)) {
                        PartnerLogController.log(LOAD_FAILED)
                        return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidBannerSize))
                    }

                    return suspendCancellableCoroutine { continuation ->
                        InMobiBanner(activity, placement).apply {
                            setEnableAutoRefresh(false)
                            setBannerSize(size.width, size.height)
                            setListener(
                                buildBannerAdListener(
                                    inMobiBanner = this,
                                    request = request,
                                    partnerAdListener = partnerAdListener,
                                    continuation = continuation,
                                ),
                            )
                            load()
                        }
                    }
                } ?: run {
                    PartnerLogController.log(LOAD_FAILED, "Size can't be null.")
                    return (Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidBannerSize)))
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Activity context is required.")
                return (Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.ActivityNotFound)))
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Placement is not valid.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidPartnerPlacement))
        }
    }

    /**
     * Build a [BannerAdEventListener] listener and return it.
     *
     * @param inMobiBanner An [InMobiBanner] instance that is passed down for Chartboost Mediation events.
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [Continuation] to notify Chartboost Mediation of load success or failure.
     *
     * @return A built [BannerAdEventListener] listener.
     */
    private fun buildBannerAdListener(
        inMobiBanner: InMobiBanner,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: CancellableContinuation<Result<PartnerAd>>,
    ): BannerAdEventListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        return object : BannerAdEventListener() {
            override fun onAdLoadSucceeded(
                ad: InMobiBanner,
                info: AdMetaInfo,
            ) {
                PartnerLogController.log(LOAD_SUCCEEDED)
                resumeOnce(
                    Result.success(
                        PartnerAd(
                            ad = inMobiBanner,
                            details = emptyMap(),
                            request = request,
                        ),
                    ),
                )
            }

            override fun onAdLoadFailed(
                ad: InMobiBanner,
                status: InMobiAdRequestStatus,
            ) {
                PartnerLogController.log(LOAD_FAILED, status.message ?: "")
                resumeOnce(Result.failure(ChartboostMediationAdException(getChartboostMediationError(status.statusCode))))
            }

            override fun onAdDisplayed(ad: InMobiBanner) {}

            override fun onAdDismissed(ad: InMobiBanner) {}

            override fun onAdClicked(
                ad: InMobiBanner,
                map: MutableMap<Any, Any>?,
            ) {
                PartnerLogController.log(DID_CLICK)
                partnerAdListener.onPartnerAdClicked(
                    PartnerAd(
                        ad = inMobiBanner,
                        details = emptyMap(),
                        request = request,
                    ),
                )
            }

            override fun onAdImpression(ad: InMobiBanner) {
                PartnerLogController.log(DID_TRACK_IMPRESSION)
                partnerAdListener.onPartnerAdImpression(
                    PartnerAd(
                        ad = inMobiBanner,
                        details = emptyMap(),
                        request = request,
                    ),
                )
            }
        }
    }

    /**
     * Attempt to load an InMobi fullscreen ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing data to load the ad with.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     *
     * @return Result.success(PartnerAd) if the ad was successfully loaded, Result.failure(Exception) otherwise.
     */
    private suspend fun loadFullScreenAd(
        context: Context,
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
    ): Result<PartnerAd> {
        request.partnerPlacement.toLongOrNull()?.let { placement ->
            // There should be no placement with this value.
            if (placement == 0L) {
                PartnerLogController.log(LOAD_FAILED)
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidPartnerPlacement))
            }

            return suspendCancellableCoroutine { continuation ->
                inMobiInterstitialAds[request.identifier] =
                    InMobiInterstitial(
                        context,
                        placement,
                        buildFullScreenAdListener(
                            request = request,
                            partnerAdListener = partnerAdListener,
                            continuation = continuation,
                        ),
                    ).apply {
                        load()
                    }
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Placement is not valid.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.LoadError.InvalidPartnerPlacement))
        }
    }

    /**
     * Build a [InterstitialAdEventListener] listener and return it.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [Continuation] to notify Chartboost Mediation of load success or failure.
     *
     * @return A built [InterstitialAdEventListener] listener.
     */
    private fun buildFullScreenAdListener(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: CancellableContinuation<Result<PartnerAd>>,
    ): InterstitialAdEventListener {
        fun resumeOnce(result: Result<PartnerAd>) {
            if (continuation.isActive) {
                continuation.resume(result)
            }
        }
        return object : InterstitialAdEventListener() {
            override fun onAdDisplayed(
                ad: InMobiInterstitial,
                info: AdMetaInfo,
            ) {
                onShowSuccess()
            }

            override fun onAdDisplayFailed(ad: InMobiInterstitial) {
                onShowError()
                inMobiInterstitialAds.remove(request.identifier)
            }

            override fun onAdDismissed(ad: InMobiInterstitial) {
                PartnerLogController.log(DID_DISMISS)
                partnerAdListener.onPartnerAdDismissed(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                    null,
                )
                inMobiInterstitialAds.remove(request.identifier)
            }

            override fun onAdClicked(
                ad: InMobiInterstitial,
                map: MutableMap<Any, Any>?,
            ) {
                PartnerLogController.log(DID_CLICK)
                partnerAdListener.onPartnerAdClicked(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                )
            }

            override fun onAdLoadSucceeded(
                ad: InMobiInterstitial,
                adMetaInfo: AdMetaInfo,
            ) {
                PartnerLogController.log(LOAD_SUCCEEDED)
                resumeOnce(
                    Result.success(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ),
                    ),
                )
            }

            override fun onAdLoadFailed(
                ad: InMobiInterstitial,
                status: InMobiAdRequestStatus,
            ) {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "Status code: ${status.statusCode}. Message: ${status.message}",
                )
                inMobiInterstitialAds.remove(request.identifier)
                resumeOnce(
                    Result.failure(ChartboostMediationAdException(getChartboostMediationError(status.statusCode))),
                )
            }

            override fun onRewardsUnlocked(
                ad: InMobiInterstitial,
                rewardMap: MutableMap<Any, Any>?,
            ) {
                rewardMap?.let {
                    PartnerLogController.log(DID_REWARD)
                    partnerAdListener.onPartnerAdRewarded(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request,
                        ),
                    )
                }
            }

            override fun onAdImpression(ad: InMobiInterstitial) {
                PartnerLogController.log(DID_TRACK_IMPRESSION)
                partnerAdListener.onPartnerAdImpression(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request,
                    ),
                )
            }
        }
    }

    /**
     * Destroy the current InMobi banner ad.
     *
     * @param partnerAd The [PartnerAd] object containing the InMobi ad to be destroyed.
     *
     * @return Result.success(PartnerAd) if the ad was successfully destroyed, Result.failure(Exception) otherwise.
     */
    private fun destroyBannerAd(partnerAd: PartnerAd): Result<PartnerAd> {
        return (partnerAd.ad as? InMobiBanner)?.let { bannerAd ->
            bannerAd.destroy()

            PartnerLogController.log(INVALIDATE_SUCCEEDED)
            Result.success(partnerAd)
        } ?: run {
            PartnerLogController.log(INVALIDATE_FAILED, "Ad is null.")
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.InvalidateError.AdNotFound))
        }
    }

    /**
     * Convert a given InMobi error code into a [ChartboostMediationError].
     *
     * @param error The InMobi error code.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: InMobiAdRequestStatus.StatusCode) =
        when (error) {
            InMobiAdRequestStatus.StatusCode.INTERNAL_ERROR -> ChartboostMediationError.OtherError.InternalError
            InMobiAdRequestStatus.StatusCode.NETWORK_UNREACHABLE -> ChartboostMediationError.OtherError.NoConnectivity
            InMobiAdRequestStatus.StatusCode.NO_FILL -> ChartboostMediationError.LoadError.NoFill
            InMobiAdRequestStatus.StatusCode.AD_NO_LONGER_AVAILABLE -> ChartboostMediationError.ShowError.AdNotFound
            InMobiAdRequestStatus.StatusCode.REQUEST_TIMED_OUT -> ChartboostMediationError.LoadError.AdRequestTimeout
            InMobiAdRequestStatus.StatusCode.SERVER_ERROR -> ChartboostMediationError.OtherError.AdServerError
            InMobiAdRequestStatus.StatusCode.INVALID_RESPONSE_IN_LOAD -> ChartboostMediationError.LoadError.InvalidBidResponse
            else -> ChartboostMediationError.OtherError.PartnerError
        }
}

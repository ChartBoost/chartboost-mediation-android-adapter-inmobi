/*
 * Copyright 2022-2023 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.inmobiadapter

import android.app.Activity
import android.content.Context
import com.chartboost.heliumsdk.domain.*
import com.chartboost.heliumsdk.utils.PartnerLogController
import com.chartboost.heliumsdk.utils.PartnerLogController.PartnerAdapterEvents.*
import com.inmobi.ads.AdMetaInfo
import com.inmobi.ads.InMobiAdRequestStatus
import com.inmobi.ads.InMobiBanner
import com.inmobi.ads.InMobiInterstitial
import com.inmobi.ads.listeners.BannerAdEventListener
import com.inmobi.ads.listeners.InterstitialAdEventListener
import com.inmobi.sdk.InMobiSdk
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
import kotlin.coroutines.suspendCoroutine

/**
 * The Chartboost Mediation InMobi SDK adapter.
 */
class InMobiAdapter : PartnerAdapter {
    companion object {
        /**
         * Log level option that can be set to alter the output verbosity of the InMobi SDK.
         */
        public var logLevel = InMobiSdk.LogLevel.NONE
            set(value) {
                field = value
                InMobiSdk.setLogLevel(value)
                PartnerLogController.log(CUSTOM, "InMobi log level set to $value.")
            }

        /**
         * Key for parsing the InMobi SDK account ID.
         */
        private const val ACCOUNT_ID_KEY = "account_id"
    }

    /**
     * A lambda to call for successful InMobi ad shows.
     */
    private var onShowSuccess: () -> Unit = {}

    /**
     * A lambda to call for failed InMobi ad shows.
     */
    private var onShowError: () -> Unit = {}

    /**
     * Indicate whether GDPR currently applies to the user.
     */
    private var gdprApplies: Boolean? = null

    /**
     * Get the InMobi SDK version.
     */
    override val partnerSdkVersion: String
        get() = InMobiSdk.getVersion()

    /**
     * Get the InMobi adapter version.
     *
     * You may version the adapter using any preferred convention, but it is recommended to apply the
     * following format if the adapter will be published by Chartboost Mediation:
     *
     * Chartboost Mediation.Partner.Adapter
     *
     * "Chartboost Mediation" represents the Chartboost Mediation SDK’s major version that is compatible with this adapter. This must be 1 digit.
     * "Partner" represents the partner SDK’s major.minor.patch.x (where x is optional) version that is compatible with this adapter. This can be 3-4 digits.
     * "Adapter" represents this adapter’s version (starting with 0), which resets to 0 when the partner SDK’s version changes. This must be 1 digit.
     */
    override val adapterVersion: String
        get() = BuildConfig.CHARTBOOST_MEDIATION_INMOBI_ADAPTER_VERSION

    /**
     * Get the partner name for internal uses.
     */
    override val partnerId: String
        get() = "inmobi"

    /**
     * Get the partner name for external uses.
     */
    override val partnerDisplayName: String
        get() = "InMobi"

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
        partnerConfiguration: PartnerConfiguration
    ): Result<Unit> {
        PartnerLogController.log(SETUP_STARTED)

        Json.decodeFromJsonElement<String>(
            (partnerConfiguration.credentials as JsonObject).getValue(ACCOUNT_ID_KEY)
        ).trim()
            .takeIf { it.isNotEmpty() }
            ?.let { accountId ->
                val gdprConsent = gdprApplies?.let { buildGdprJsonObject(it) }

                return suspendCoroutine { continuation ->
                    InMobiSdk.init(context.applicationContext, accountId, gdprConsent) { error ->
                        continuation.resume(
                            error?.let {
                                PartnerLogController.log(SETUP_FAILED, "${it.message}")
                                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_UNKNOWN))
                            } ?: run {
                                Result.success(
                                    PartnerLogController.log(SETUP_SUCCEEDED)
                                )
                            }
                        )
                    }
                }
            } ?: run {
            PartnerLogController.log(SETUP_FAILED, "Missing account ID.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INITIALIZATION_FAILURE_INVALID_CREDENTIALS))
        }
    }

    /**
     * Build a [JSONObject] that will be passed to the InMobi SDK for GDPR during [setUp].
     *
     * @param gdprConsent A Boolean indicating whether GDPR consent is granted or not.
     *
     * @return a [JSONObject] object as to whether GDPR  .
     */
    private fun buildGdprJsonObject(gdprConsent: Boolean): JSONObject {
        return JSONObject().apply {
            try {
                put(InMobiSdk.IM_GDPR_CONSENT_AVAILABLE, gdprConsent)
                put("gdpr", if (gdprApplies == true) "1" else "0")
            } catch (error: JSONException) {
                PartnerLogController.log(
                    CUSTOM,
                    "Failed to build GDPR JSONObject with error: ${error.message}"
                )
            }
        }
    }

    /**
     * Notify the InMobi SDK of the GDPR applicability and consent status.
     *
     * @param context The current [Context].
     * @param applies True if GDPR applies, false otherwise.
     * @param gdprConsentStatus The user's GDPR consent status.
     */
    override fun setGdpr(
        context: Context,
        applies: Boolean?,
        gdprConsentStatus: GdprConsentStatus
    ) {
        PartnerLogController.log(
            when (applies) {
                true -> GDPR_APPLICABLE
                false -> GDPR_NOT_APPLICABLE
                else -> GDPR_UNKNOWN
            }
        )

        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        this.gdprApplies = applies

        if (applies == true) {
            InMobiSdk.setPartnerGDPRConsent(
                buildGdprJsonObject(GdprConsentStatus.GDPR_CONSENT_GRANTED == gdprConsentStatus)
            )
        }
    }

    /**
     * Notify InMobi of the CCPA compliance.
     *
     * @param context The current [Context].
     * @param hasGrantedCcpaConsent True if the user has granted CCPA consent, false otherwise.
     * @param privacyString The CCPA privacy String.
     */
    override fun setCcpaConsent(
        context: Context,
        hasGrantedCcpaConsent: Boolean,
        privacyString: String
    ) {
        PartnerLogController.log(
            if (hasGrantedCcpaConsent) CCPA_CONSENT_GRANTED
            else CCPA_CONSENT_DENIED
        )

        // NO-OP: InMobi handles CCPA on their dashboard.
    }

    /**
     * Notify InMobi of the COPPA subjectivity.
     *
     * @param context The current [Context].
     * @param isSubjectToCoppa True if the user is subject to COPPA, false otherwise.
     */
    override fun setUserSubjectToCoppa(context: Context, isSubjectToCoppa: Boolean) {
        PartnerLogController.log(
            if (isSubjectToCoppa) COPPA_SUBJECT
            else COPPA_NOT_SUBJECT
        )

        // NO-OP: InMobi does not have an API for setting COPPA.
    }

    /**
     * Get a bid token if network bidding is supported.
     *
     * @param context The current [Context].
     * @param request The [PreBidRequest] instance containing relevant data for the current bid request.
     *
     * @return A Map of biddable token Strings.
     */
    override suspend fun fetchBidderInformation(
        context: Context,
        request: PreBidRequest
    ): Map<String, String> {
        PartnerLogController.log(BIDDER_INFO_FETCH_STARTED)
        PartnerLogController.log(BIDDER_INFO_FETCH_SUCCEEDED)
        return emptyMap()
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
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        PartnerLogController.log(LOAD_STARTED)

        return when (request.format) {
            AdFormat.BANNER -> {
                withContext(Main) {
                    loadBannerAd(context, request, partnerAdListener)
                }
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> loadFullScreenAd(
                context,
                request,
                partnerAdListener
            )
            AdFormat.REWARDED_INTERSTITIAL -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_UNSUPPORTED_AD_FORMAT))
            }
        }
    }

    /**
     * Attempt to show the currently loaded InMobi ad.
     *
     * @param context The current [Context]
     * @param partnerAd The [PartnerAd] object containing the InMobi ad to be shown.
     *
     * @return Result.success(PartnerAd) if the ad was successfully shown, Result.failure(Exception) otherwise.
     */
    override suspend fun show(context: Context, partnerAd: PartnerAd): Result<PartnerAd> {
        PartnerLogController.log(SHOW_STARTED)

        return when (partnerAd.request.format) {
            AdFormat.BANNER -> {
                // Banner ads do not have a separate "show" mechanism.
                PartnerLogController.log(SHOW_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                (partnerAd.ad as? InMobiInterstitial)?.let { ad ->
                    if (ad.isReady) {
                        suspendCancellableCoroutine { continuation ->
                            onShowSuccess = {
                                PartnerLogController.log(SHOW_SUCCEEDED)
                                continuation.resume(Result.success(partnerAd))
                            }

                            onShowError = {
                                PartnerLogController.log(
                                    SHOW_FAILED,
                                    "Placement: ${partnerAd.request.partnerPlacement}"
                                )
                                continuation.resume(
                                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNKNOWN))
                                )
                            }
                            ad.show()
                        }
                    } else {
                        PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                        Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_READY))
                    }
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                    Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND))
                }
            }
            AdFormat.REWARDED_INTERSTITIAL -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_SHOW_FAILURE_UNSUPPORTED_AD_FORMAT))
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
            AdFormat.BANNER -> destroyBannerAd(partnerAd)
            AdFormat.INTERSTITIAL, AdFormat.REWARDED -> {
                // InMobi does not have destroy methods for their fullscreen ads.
                // Remove show result for this partner ad. No longer needed.
                PartnerLogController.log(INVALIDATE_SUCCEEDED)
                Result.success(partnerAd)
            }
            AdFormat.REWARDED_INTERSTITIAL -> {
                PartnerLogController.log(LOAD_FAILED)
                Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_UNSUPPORTED_AD_FORMAT))
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
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        request.partnerPlacement.toLongOrNull()?.let { placement ->
            // There should be no placement with this value.
            if (placement == 0L) {
                PartnerLogController.log(LOAD_FAILED)
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT))
            }

            (context as? Activity)?.let { activity ->
                request.size?.let { size ->
                    // InMobi silently fails and causes the coroutine from returning a result.
                    // We will check for the banner size and return a failure if the sizes are either 0.
                    if ((size.width == 0) or (size.height == 0)) {
                        PartnerLogController.log(LOAD_FAILED)
                        return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_BANNER_SIZE))
                    }

                    return suspendCoroutine { continuation ->
                        // Load an InMobiBanner
                        InMobiBanner(activity, placement).apply {
                            setEnableAutoRefresh(false)
                            setBannerSize(size.width, size.height)
                            setListener(
                                buildBannerAdListener(
                                    request = request,
                                    partnerAdListener = partnerAdListener,
                                    continuation = continuation
                                )
                            )
                            load()
                        }
                    }
                } ?: run {
                    PartnerLogController.log(LOAD_FAILED, "Size can't be null.")
                    return (Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_BANNER_SIZE)))
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Activity context is required.")
                return (Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_ACTIVITY_NOT_FOUND)))
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Placement is not valid.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT))
        }
    }

    /**
     * Build a [BannerAdEventListener] listener and return it.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Chartboost Mediation of ad events.
     * @param continuation A [Continuation] to notify Chartboost Mediation of load success or failure.
     *
     * @return A built [BannerAdEventListener] listener.
     */
    private fun buildBannerAdListener(
        request: PartnerAdLoadRequest,
        partnerAdListener: PartnerAdListener,
        continuation: Continuation<Result<PartnerAd>>
    ): BannerAdEventListener {
        return object : BannerAdEventListener() {
            override fun onAdLoadSucceeded(ad: InMobiBanner, info: AdMetaInfo) {
                PartnerLogController.log(LOAD_SUCCEEDED)
                continuation.resume(
                    Result.success(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                )
            }

            override fun onAdLoadFailed(
                ad: InMobiBanner,
                status: InMobiAdRequestStatus
            ) {
                PartnerLogController.log(LOAD_FAILED, status.message)
                continuation.resume(Result.failure(ChartboostMediationAdException(getChartboostMediationError(status.statusCode))))
            }

            override fun onAdDisplayed(ad: InMobiBanner) {}

            override fun onAdDismissed(ad: InMobiBanner) {}

            override fun onAdClicked(ad: InMobiBanner, map: MutableMap<Any, Any>?) {
                PartnerLogController.log(DID_CLICK)
                partnerAdListener.onPartnerAdClicked(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request
                    )
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
        partnerAdListener: PartnerAdListener
    ): Result<PartnerAd> {
        request.partnerPlacement.toLongOrNull()?.let { placement ->
            // There should be no placement with this value.
            if (placement == 0L) {
                PartnerLogController.log(LOAD_FAILED)
                return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT))
            }

            return suspendCoroutine { continuation ->
                InMobiInterstitial(
                    context,
                    placement,
                    buildFullScreenAdListener(
                        request = request,
                        partnerAdListener = partnerAdListener,
                        continuation = continuation
                    )
                ).load()
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Placement is not valid.")
            return Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_LOAD_FAILURE_INVALID_PARTNER_PLACEMENT))
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
        continuation: Continuation<Result<PartnerAd>>
    ): InterstitialAdEventListener {
        return object : InterstitialAdEventListener() {
            override fun onAdDisplayed(ad: InMobiInterstitial, info: AdMetaInfo) {
                onShowSuccess()
            }

            override fun onAdDisplayFailed(ad: InMobiInterstitial) {
                onShowError()
            }

            override fun onAdDismissed(ad: InMobiInterstitial) {
                PartnerLogController.log(DID_DISMISS)
                partnerAdListener.onPartnerAdDismissed(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request
                    ), null
                )
            }

            override fun onAdClicked(ad: InMobiInterstitial, map: MutableMap<Any, Any>?) {
                PartnerLogController.log(DID_CLICK)
                partnerAdListener.onPartnerAdClicked(
                    PartnerAd(
                        ad = ad,
                        details = emptyMap(),
                        request = request
                    )
                )
            }

            override fun onAdLoadSucceeded(ad: InMobiInterstitial, adMetaInfo: AdMetaInfo) {
                PartnerLogController.log(LOAD_SUCCEEDED)
                continuation.resume(
                    Result.success(
                        PartnerAd(
                            ad = ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                )
            }

            override fun onAdLoadFailed(
                ad: InMobiInterstitial,
                status: InMobiAdRequestStatus
            ) {
                PartnerLogController.log(
                    LOAD_FAILED,
                    "Status code: ${status.statusCode}. Message: ${status.message}"
                )
                continuation.resume(
                    Result.failure(ChartboostMediationAdException(getChartboostMediationError(status.statusCode)))
                )
            }

            override fun onRewardsUnlocked(
                ad: InMobiInterstitial,
                rewardMap: MutableMap<Any, Any>?
            ) {
                rewardMap?.let {
                    PartnerLogController.log(DID_REWARD)
                    partnerAdListener.onPartnerAdRewarded(
                        PartnerAd(
                            ad,
                            details = emptyMap(),
                            request = request
                        )
                    )
                }
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
            Result.failure(ChartboostMediationAdException(ChartboostMediationError.CM_INVALIDATE_FAILURE_AD_NOT_FOUND))
        }
    }

    /**
     * Convert a given InMobi error code into a [ChartboostMediationError].
     *
     * @param error The InMobi error code.
     *
     * @return The corresponding [ChartboostMediationError].
     */
    private fun getChartboostMediationError(error: InMobiAdRequestStatus.StatusCode) = when (error) {
        InMobiAdRequestStatus.StatusCode.INTERNAL_ERROR -> ChartboostMediationError.CM_INTERNAL_ERROR
        InMobiAdRequestStatus.StatusCode.NETWORK_UNREACHABLE -> ChartboostMediationError.CM_NO_CONNECTIVITY
        InMobiAdRequestStatus.StatusCode.NO_FILL -> ChartboostMediationError.CM_LOAD_FAILURE_NO_FILL
        InMobiAdRequestStatus.StatusCode.AD_NO_LONGER_AVAILABLE -> ChartboostMediationError.CM_SHOW_FAILURE_AD_NOT_FOUND
        InMobiAdRequestStatus.StatusCode.REQUEST_TIMED_OUT -> ChartboostMediationError.CM_LOAD_FAILURE_TIMEOUT
        InMobiAdRequestStatus.StatusCode.SERVER_ERROR -> ChartboostMediationError.CM_AD_SERVER_ERROR
        InMobiAdRequestStatus.StatusCode.INVALID_RESPONSE_IN_LOAD -> ChartboostMediationError.CM_LOAD_FAILURE_INVALID_BID_RESPONSE
        else -> ChartboostMediationError.CM_PARTNER_ERROR
    }
}

package com.chartboost.helium.inmobiadapter

import android.app.Activity
import android.content.Context
import com.chartboost.helium.inmobiadapter.BuildConfig.HELIUM_INMOBI_ADAPTER_VERSION
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
import org.json.JSONException
import org.json.JSONObject
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * The Helium InMobi SDK adapter.
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
     * Note that the version string will be in the format of `Helium.Partner.Partner.Partner.Adapter`,
     * in which `Helium` is the version of the Helium SDK, `Partner` is the major.minor.patch version
     * of the partner SDK, and `Adapter` is the version of the adapter.
     */
    override val adapterVersion: String
        get() = HELIUM_INMOBI_ADAPTER_VERSION

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

        partnerConfiguration.credentials.optString(ACCOUNT_ID_KEY).trim().takeIf { it.isNotEmpty() }
            ?.let { accountId ->
                val gdprConsent = gdprApplies?.let { buildGdprJsonObject(it) }

                return suspendCoroutine { continuation ->
                    InMobiSdk.init(context.applicationContext, accountId, gdprConsent) { error ->
                        continuation.resume(
                            error?.let {
                                PartnerLogController.log(SETUP_FAILED, "${it.message}")
                                Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
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
            return Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_SDK_NOT_INITIALIZED))
        }
    }

    /**
     * Build a [JSONObject] that will be passed to the InMobi SDK for GDPR during [setUp].
     *
     * @param gdprConsent A Boolean indicating whether GDPR consent is granted or not.
     * @return a [JSONObject] object as to whether GDPR  .
     */
    private fun buildGdprJsonObject(gdprConsent: Boolean): JSONObject {
        return JSONObject().apply {
            try {
                // Provide correct consent value to sdk which is obtained by User
                put(InMobiSdk.IM_GDPR_CONSENT_AVAILABLE, gdprConsent)
                // Provide 0 if GDPR is not applicable and 1 if applicable
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
     * Save the current GDPR applicability state for later use.
     *
     * @param context The current [Context].
     * @param gdprApplies True if GDPR applies, false otherwise.
     */
    override fun setGdprApplies(context: Context, gdprApplies: Boolean) {
        PartnerLogController.log(if (gdprApplies) GDPR_APPLICABLE else GDPR_NOT_APPLICABLE)
        this.gdprApplies = gdprApplies
    }

    /**
     * Notify InMobi of user GDPR consent.
     *
     * @param context The current [Context].
     * @param gdprConsentStatus The user's current GDPR consent status.
     */
    override fun setGdprConsentStatus(context: Context, gdprConsentStatus: GdprConsentStatus) {
        PartnerLogController.log(
            when (gdprConsentStatus) {
                GdprConsentStatus.GDPR_CONSENT_UNKNOWN -> GDPR_CONSENT_UNKNOWN
                GdprConsentStatus.GDPR_CONSENT_GRANTED -> GDPR_CONSENT_GRANTED
                GdprConsentStatus.GDPR_CONSENT_DENIED -> GDPR_CONSENT_DENIED
            }
        )

        if (gdprApplies == true) {
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
        privacyString: String?
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
     * Attempt to load a InMobi ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
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
                                    Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
                                )
                            }
                            ad.show()
                        }
                    } else {
                        PartnerLogController.log(SHOW_FAILED, "Ad is not ready.")
                        Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
                    }
                } ?: run {
                    PartnerLogController.log(SHOW_FAILED, "Ad is null.")
                    Result.failure(HeliumAdException(HeliumErrorCode.PARTNER_ERROR))
                }
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
        }
    }

    /**
     * Attempt to load a InMobi banner ad.
     *
     * @param context The current [Context].
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
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
                return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
            }

            (context as? Activity)?.let { activity ->
                request.size?.let { size ->
                    // InMobi silently fails and causes the coroutine from returning a result.
                    // We will check for the banner size and return a failure if the sizes are either 0.
                    if ((size.width == 0) or (size.height == 0)) {
                        PartnerLogController.log(LOAD_FAILED)
                        return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
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
                    return (Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
                }
            } ?: run {
                PartnerLogController.log(LOAD_FAILED, "Activity context is required.")
                return (Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
            }
        } ?: run {
            PartnerLogController.log(LOAD_FAILED, "Placement is not valid.")
            return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }
    }

    /**
     * Build a [BannerAdEventListener] listener and return it.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     * @param continuation A [Continuation] to notify Helium of load success or failure.
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
                continuation.resume(Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL)))
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
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
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
                return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
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
            return Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
        }
    }

    /**
     * Build a [InterstitialAdEventListener] listener and return it.
     *
     * @param request An [PartnerAdLoadRequest] instance containing relevant data for the current ad load call.
     * @param partnerAdListener A [PartnerAdListener] to notify Helium of ad events.
     * @param continuation A [Continuation] to notify Helium of load success or failure.
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
                    Result.failure(HeliumAdException(HeliumErrorCode.NO_FILL))
                )
            }

            override fun onRewardsUnlocked(
                ad: InMobiInterstitial,
                rewardMap: MutableMap<Any, Any>?
            ) {
                rewardMap?.let {
                    val reward = rewardMap.keys.iterator().next().let { rewardKey ->
                        rewardMap[rewardKey]
                    } ?: 0

                    PartnerLogController.log(DID_REWARD)
                    partnerAdListener.onPartnerAdRewarded(
                        PartnerAd(
                            ad,
                            details = emptyMap(),
                            request = request
                        ),
                        Reward(
                            reward as Int,
                            request.partnerPlacement
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
            Result.failure(HeliumAdException(HeliumErrorCode.INTERNAL))
        }
    }
}

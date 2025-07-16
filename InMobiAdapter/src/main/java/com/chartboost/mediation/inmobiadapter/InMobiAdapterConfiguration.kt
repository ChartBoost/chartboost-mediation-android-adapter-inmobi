/*
 * Copyright 2024-2025 Chartboost, Inc.
 * 
 * Use of this source code is governed by an MIT-style
 * license that can be found in the LICENSE file.
 */

package com.chartboost.mediation.inmobiadapter

import com.chartboost.chartboostmediationsdk.domain.PartnerAdapterConfiguration
import com.chartboost.chartboostmediationsdk.utils.PartnerLogController
import com.inmobi.sdk.InMobiSdk

object InMobiAdapterConfiguration : PartnerAdapterConfiguration {
    /**
     * The partner name for internal uses.
     */
    override val partnerId = "inmobi"

    /**
     * The partner name for external uses.
     */
    override val partnerDisplayName = "InMobi"

    /**
     * The partner SDK version.
     */
    override val partnerSdkVersion = InMobiSdk.getVersion()

    /**
     * The partner adapter version.
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
    override val adapterVersion = BuildConfig.CHARTBOOST_MEDIATION_INMOBI_ADAPTER_VERSION

    /**
     * Log level option that can be set to alter the output verbosity of the InMobi SDK.
     */
    var logLevel = InMobiSdk.LogLevel.NONE
        set(value) {
            field = value
            InMobiSdk.setLogLevel(value)
            PartnerLogController.log(PartnerLogController.PartnerAdapterEvents.CUSTOM, "InMobi log level set to $value.")
        }
}

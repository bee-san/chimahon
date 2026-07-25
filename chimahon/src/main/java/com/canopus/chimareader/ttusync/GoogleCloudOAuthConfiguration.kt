package com.canopus.chimareader.ttusync

object GoogleCloudOAuthConfiguration {
    const val TTU_SETUP_URL: String =
        "https://github.com/ttu-ttu/ebook-reader?tab=readme-ov-file#storage-sources"
    const val TTU_SETUP_LINK_LABEL: String = "\u30C3\u30C4 Google Cloud setup"
    const val GOOGLE_CLOUD_CONSOLE_URL: String = "https://console.cloud.google.com/auth/clients"
    const val GOOGLE_CLOUD_CONSOLE_LINK_LABEL: String = "Google Cloud Console"
    const val GOOGLE_DEVICE_URL: String = "https://www.google.com/device"
    const val GOOGLE_DEVICE_LINK_LABEL: String = "Google device page"

    const val INTRODUCTION: String =
        "Google Drive sync uses Device Code flow so this Android app can use the same user-owned Google Cloud project as iOS/\u30C3\u30C4."

    val instructions: List<String> = listOf(
        "Open the same Google Cloud project used by iOS/\u30C3\u30C4 sync and make sure the Google Drive API is enabled.",
        "Open Google Auth Platform -> Clients in the $GOOGLE_CLOUD_CONSOLE_LINK_LABEL, click CREATE CLIENT, and select application type TVs and Limited Input devices. If your console still shows the older navigation, use APIs & Services -> Credentials -> Create Credentials -> OAuth client ID.",
        "Paste that client ID and client secret here. Do not create an Android OAuth client for this flow.",
        "Press Connect Google Drive, open the verification URL, and enter the displayed device code.",
        "If authorization has trouble while app is in the background, open the $GOOGLE_DEVICE_LINK_LABEL on another device and enter the device code shown here.",
        "Authorize the same Google Account whose Drive contains the \u30C3\u30C4 sync folder.",
    )

    val instructionLinks: Map<String, String> = mapOf(
        GOOGLE_CLOUD_CONSOLE_LINK_LABEL to GOOGLE_CLOUD_CONSOLE_URL,
        GOOGLE_DEVICE_LINK_LABEL to GOOGLE_DEVICE_URL,
    )
}

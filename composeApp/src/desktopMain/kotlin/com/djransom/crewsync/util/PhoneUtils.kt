package com.djransom.crewsync.util

actual fun makePhoneCall(phoneNumber: String) {
    // Desktop doesn't usually make calls directly, could open a URL tel scheme
    openUrl("tel:$phoneNumber")
}

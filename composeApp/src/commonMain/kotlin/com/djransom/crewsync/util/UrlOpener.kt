package com.djransom.crewsync.util

expect fun openUrl(url: String)

// Shares a file by its download link (not by downloading and re-attaching bytes) - both
// platforms below hand the URL to a native share/mail surface, and the recipient's own
// email client or share target handles fetching it from there.
expect fun shareFile(url: String, name: String)

// Actually pulls the file's bytes onto local storage (a real "Save As"/download), unlike
// openUrl (streams it into an in-app or external viewer) or shareFile (hands off a link) -
// this is the one that gets a file the user can attach, move, or open outside the app entirely.
expect fun downloadFile(url: String, suggestedName: String)

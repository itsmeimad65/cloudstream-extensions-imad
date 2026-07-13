version = 1

cloudstream {
    description = "Watch the latest OnlyFans leaks and studio releases"
    language = "en"
    authors = listOf("imad")
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://rentry.org/favicon.ico"
    isCrossPlatform = false
}

dependencies {
    val cloudstream by configurations
    cloudstream("com.lagradost:cloudstream3:pre-release")
}

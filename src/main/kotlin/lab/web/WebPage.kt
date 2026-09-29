package lab.web

object WebPage {
    fun render(): String =
        WebPage::class.java.getResourceAsStream("/index.html")!!
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
}

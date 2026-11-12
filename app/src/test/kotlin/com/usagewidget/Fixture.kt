package com.usagewidget

fun loadFixture(name: String): String {
    val stream = Thread.currentThread().contextClassLoader?.getResourceAsStream("fixtures/$name")
        ?: ClassLoader.getSystemResourceAsStream("fixtures/$name")
        ?: error("Missing fixture fixtures/$name")
    return stream.bufferedReader().use { it.readText() }
}

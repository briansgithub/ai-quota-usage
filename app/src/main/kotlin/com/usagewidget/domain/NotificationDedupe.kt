package com.usagewidget.domain

object NotificationDedupe {
    const val MAX_KEYS = 80

    fun shouldNotify(seen: Set<String>, key: String): Boolean = key !in seen

    fun remember(seen: Set<String>, key: String): LinkedHashSet<String> {
        val next = LinkedHashSet(seen.filter { it.isNotBlank() })
        next.remove(key)
        next.add(key)
        while (next.size > MAX_KEYS) {
            val first = next.first()
            next.remove(first)
        }
        return next
    }

    fun rememberAll(seen: Set<String>, keys: Collection<String>): LinkedHashSet<String> {
        var acc = LinkedHashSet(seen.filter { it.isNotBlank() })
        for (key in keys) {
            acc = remember(acc, key)
        }
        return acc
    }
}

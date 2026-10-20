package com.fullautoclicker.service

import java.lang.ref.WeakReference

/**
 * Same-process link between the running accessibility service and the UI.
 * Main thread only.
 */
object ClickerHub {
    private var serviceRef: WeakReference<ClickerService>? = null
    private val listeners = LinkedHashSet<() -> Unit>()

    /** The connected service, or null when accessibility access is off or the service is stopping. */
    val service: ClickerService? get() = serviceRef?.get()

    internal fun attach(service: ClickerService) {
        serviceRef = WeakReference(service)
        notifyChanged()
    }

    internal fun detach(service: ClickerService) {
        if (serviceRef?.get() === service) serviceRef = null
        notifyChanged()
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun notifyChanged() {
        for (listener in listeners.toList()) listener()
    }
}

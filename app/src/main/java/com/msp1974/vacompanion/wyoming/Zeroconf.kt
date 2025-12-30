package com.msp1974.vacompanion.wyoming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.msp1974.vacompanion.settings.APPConfig
import timber.log.Timber

internal class Zeroconf(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private val config = APPConfig.Companion.getInstance(context)
    private var isRegistered: Boolean = false
    var serviceName: String? = null

    fun registerService(port: Int) {
        if (!isRegistered) {
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "vaca-${config.uuid}"
                serviceType = "_vaca._tcp."
                setPort(port)
            }

            try {
                nsdManager = (context.getSystemService(Context.NSD_SERVICE) as NsdManager).apply {
                    registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
                }
            } catch (e: Exception) {
                Timber.e("Error registering NSD service: $e")
            }
        }
    }

    fun unregisterService() {
        if (isRegistered && nsdManager != null) {
            nsdManager!!.unregisterService(registrationListener)
        }
    }

    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
            // Save the service name. Android may have changed it in order to
            // resolve a conflict, so update the name you initially requested
            // with the name Android actually used.
            serviceName = nsdServiceInfo.serviceName
            isRegistered = true
            Timber.d("Registered NSD service: $serviceName")
        }

        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Registration failed! Put debugging code here to determine why.
            Timber.e("Failed to register NSD service: $errorCode")
        }

        override fun onServiceUnregistered(arg0: NsdServiceInfo) {
            // Service has been unregistered. This only happens when you call
            // NsdManager.unregisterService() and pass in this listener.
            Timber.d("Unregistered NSD service")
            isRegistered = false
        }

        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // De-registration failed. Put debugging code here to determine why.
            Timber.e("Failed to unregister NSD service: $errorCode")
        }
    }
}
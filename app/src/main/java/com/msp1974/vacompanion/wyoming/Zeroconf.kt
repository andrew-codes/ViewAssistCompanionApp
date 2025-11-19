package com.msp1974.vacompanion.wyoming

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.msp1974.vacompanion.settings.APPConfig

internal class Zeroconf(private val context: Context) {
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private val config = APPConfig.Companion.getInstance(context)
    private var isRegistered: Boolean = false
    var serviceName: String? = null

    init {
        initializeRegistrationListener()
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    fun registerService(port: Int) {
        if (!isRegistered) {
            val serviceInfo = NsdServiceInfo()
            serviceInfo.serviceName = "vaca-${config.uuid}"
            serviceInfo.serviceType = "_vaca._tcp."
            serviceInfo.port = port

            nsdManager!!.registerService(
                serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener
            )
        }
    }

    fun unregisterService() {
        if (isRegistered) {
            nsdManager!!.unregisterService(registrationListener)
        }
    }

    fun initializeRegistrationListener() {
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                // Save the service name. Android may have changed it in order to
                // resolve a conflict, so update the name you initially requested
                // with the name Android actually used.
                serviceName = nsdServiceInfo.serviceName
                isRegistered = true
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Registration failed! Put debugging code here to determine why.
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                // Service has been unregistered. This only happens when you call
                // NsdManager.unregisterService() and pass in this listener.
                isRegistered = false
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // De-registration failed. Put debugging code here to determine why.
            }
        }
    }
}
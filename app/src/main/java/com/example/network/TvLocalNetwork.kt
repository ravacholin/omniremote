package com.example.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket

/** Uses the LAN even when Android chooses mobile data as its default internet route. */
class TvLocalNetwork(context: Context?) {
    private val connectivity = context?.applicationContext
        ?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun current(): Network? = connectivity?.let { manager ->
        manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network)
            caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    fun localIpv4(): String? = current()?.let { network ->
        connectivity?.getLinkProperties(network)?.linkAddresses
            ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
            ?.address?.hostAddress
    }

    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(): Socket = current()?.socketFactory?.createSocket() ?: Socket()
        override fun createSocket(host: String, port: Int): Socket = createSocket().apply {
            connect(InetSocketAddress(host, port))
        }
        override fun createSocket(host: InetAddress, port: Int): Socket = createSocket().apply {
            connect(InetSocketAddress(host, port))
        }
        override fun createSocket(host: String, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
        override fun createSocket(host: InetAddress, port: Int, local: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(local, localPort)); connect(InetSocketAddress(host, port)) }
    }

    fun bind(socket: DatagramSocket) { current()?.bindSocket(socket) }

    fun tlsSocket(ssl: SSLContext, host: String, port: Int, timeout: Int): SSLSocket {
        val transport = socketFactory.createSocket()
        try {
            transport.connect(InetSocketAddress(host, port), timeout)
            return ssl.socketFactory.createSocket(transport, host, port, true) as SSLSocket
        } catch (error: Exception) {
            transport.close()
            throw error
        }
    }
}

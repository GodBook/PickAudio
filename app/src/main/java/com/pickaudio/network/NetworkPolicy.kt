package com.pickaudio.network

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

/** Validate the addresses actually returned to the connection, including every redirect. */
class NetworkPolicy(private val allowedTestHosts: Set<String> = emptySet()) {
    private val insecureDomains = setOf("music.126.net", "music.163.com", "stream.qqmusic.qq.com")

    fun validate(url: String): HttpUrl = validate(url.toHttpUrl())
    fun validate(url: HttpUrl): HttpUrl {
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) throw IOException("链接不能包含账号或密码")
        if (!url.isHttps && url.host !in allowedTestHosts && insecureDomains.none {
                url.host == it || url.host.endsWith(".$it")
            }) throw IOException("此音源使用未支持的明文链接，请使用 HTTPS 音源")
        if (url.host.contains(':') || url.host.matches(Regex("[0-9.]+"))) {
            checkAddresses(url.host, listOf(InetAddress.getByName(url.host)))
        }
        return url
    }

    private fun checkAddresses(host: String, addresses: List<InetAddress>): List<InetAddress> {
        if (addresses.isEmpty() || (host !in allowedTestHosts && addresses.any { !isPublicAddress(it) }))
            throw IOException("禁止访问本地、局域网或保留地址")
        return addresses
    }

    fun client(base: OkHttpClient = OkHttpClient(), dns: Dns = base.dns, httpsOnly: Boolean = false): OkHttpClient =
        base.newBuilder().proxy(Proxy.NO_PROXY)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    checkAddresses(hostname, dns.lookup(hostname))
            })
            .followRedirects(false).followSslRedirects(false)
            .addInterceptor(Interceptor { chain ->
                var request = chain.request()
                var redirects = 0
                while (true) {
                    if (httpsOnly && !request.url.isHttps) throw IOException("此请求仅允许 HTTPS 链接")
                    validate(request.url)
                    val response = chain.proceed(request)
                    if (response.code !in setOf(301, 302, 303, 307, 308)) return@Interceptor response
                    val next = response.header("Location")?.let { request.url.resolve(it) }
                        ?: return@Interceptor response
                    response.close()
                    if (++redirects > 8) throw IOException("链接重定向次数过多")
                    validate(next)
                    val builder = request.newBuilder().url(next)
                    if (request.url.host != next.host || request.url.port != next.port || request.url.scheme != next.scheme) {
                        builder.removeHeader("Authorization").removeHeader("Cookie").removeHeader("Host")
                    }
                    if (response.code == 303 || (response.code in setOf(301, 302) && request.method == "POST")) {
                        builder.method("GET", null).removeHeader("Content-Length").removeHeader("Content-Type")
                    }
                    request = builder.build()
                }
                @Suppress("UNREACHABLE_CODE") error("redirect loop")
            }).build()

    companion object {
        val Default = NetworkPolicy()
        fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val b = address.address.map { it.toInt() and 255 }
            if (b.size == 4) return !(b[0] == 0 || b[0] == 10 || b[0] == 127 || b[0] >= 224 ||
                (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
                (b[0] == 172 && b[1] in 16..31) || (b[0] == 192 && b[1] == 168) ||
                (b[0] == 192 && b[1] == 0 && b[2] in setOf(0, 2)) ||
                (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
                (b[0] == 203 && b[1] == 0 && b[2] == 113))
            // Global unicast only; reject transition and documentation networks.
            return b.size == 16 && b[0] in 0x20..0x3f &&
                !(b[0] == 0x20 && b[1] == 0x02) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0 && b[3] == 0) &&
                !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
        }
    }
}

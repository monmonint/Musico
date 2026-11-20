package fr.musico

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    data class Candidate(val iface: String, val ip: String, val score: Int)

    /**
     * Adresses IPv4 locales, la plus probable pour le partage de connexion en premier.
     * Les noms d'interface varient selon les constructeurs (ap0, swlan0, wlan1, softap0…).
     */
    fun localAddresses(): List<Candidate> {
        val out = ArrayList<Candidate>()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces() ?: return out) {
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy") ||
                    name.startsWith("tun") || name.startsWith("p2p")) continue
                for (addr in ni.inetAddresses) {
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    val score = when {
                        name.startsWith("ap") || name.contains("softap") || name.startsWith("swlan") -> 100
                        name == "wlan1" || name == "wlan2" -> 90
                        name.startsWith("rndis") || name.startsWith("usb") -> 50
                        name.startsWith("wlan") -> 70
                        name.startsWith("eth") -> 60
                        else -> 10
                    } + if (ip.startsWith("192.168.")) 5 else 0
                    out.add(Candidate(ni.name, ip, score))
                }
            }
        } catch (_: Exception) {}
        return out.sortedByDescending { it.score }
    }
}

package com.nexus.offline

import org.json.JSONObject

const val BROADCAST = "*"

/**
 * One mesh packet. Header fields are plaintext so relays can route; `body` is
 * AES-GCM encrypted for chat/file packets (end-to-end), plaintext for hello/ack.
 *
 * id   unique packet id (file chunks: "<fileId>-<index>")
 * src  originating node id
 * dst  destination node id or "*" (everyone)
 * type hello | chat | file | ack
 * ttl  remaining hops
 * a    retransmission attempt (relays dedupe on id+attempt so a retry can take a new route)
 */
data class Packet(
    val id: String,
    val src: String,
    val dst: String,
    val type: String,
    val ttl: Int,
    val ts: Long,
    val body: String,
    val a: Int = 0
) {
    fun toBytes(): ByteArray = JSONObject()
        .put("id", id).put("s", src).put("d", dst).put("t", type)
        .put("l", ttl).put("ts", ts).put("b", body).put("a", a)
        .toString().toByteArray(Charsets.UTF_8)

    companion object {
        fun parse(bytes: ByteArray): Packet? = try {
            val j = JSONObject(String(bytes, Charsets.UTF_8))
            Packet(
                j.getString("id"), j.getString("s"), j.getString("d"), j.getString("t"),
                j.getInt("l"), j.getLong("ts"), j.getString("b"), j.optInt("a", 0)
            )
        } catch (e: Exception) {
            null
        }
    }
}

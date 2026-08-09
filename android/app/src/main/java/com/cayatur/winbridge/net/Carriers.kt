package com.cayatur.winbridge.net

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

const val TAG = "WinBridge"

enum class CarrierKind { BLUETOOTH, LAN }

/** A connected link. Closing it releases whatever the carrier allocated. */
class Link(
    val kind: CarrierKind,
    val input: InputStream,
    val output: OutputStream,
    val describe: String,
    private val resource: Closeable,
) : Closeable {
    override fun close() {
        runCatching { resource.close() }
    }
}

interface Carrier {
    val kind: CarrierKind
    /** Opens a link, or throws. Blocking. */
    fun open(): Link
    /** Whether this carrier has enough configuration to be worth attempting. */
    fun isConfigured(): Boolean
}

/**
 * RFCOMM over classic Bluetooth.
 *
 * The address must be the PC's classic BR/EDR address, taken from the pairing
 * payload. Looking the PC up by name in `bondedDevices` finds it under its LE
 * identity address on many setups, and RFCOMM to an LE address fails every time
 * with an unhelpful "read failed, socket might closed or timeout".
 */
class BluetoothCarrier(
    private val context: Context,
    private val macProvider: () -> String?,
    private val serviceUuid: UUID = SERVICE_UUID,
) : Carrier {

    override val kind = CarrierKind.BLUETOOTH

    override fun isConfigured(): Boolean = macProvider() != null && adapter()?.isEnabled == true

    private fun adapter(): BluetoothAdapter? =
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter

    @SuppressLint("MissingPermission")
    override fun open(): Link {
        val mac = macProvider() ?: throw IllegalStateException("no Bluetooth address for the host")
        val adapter = adapter() ?: throw IllegalStateException("no Bluetooth adapter")
        if (!adapter.isEnabled) throw IllegalStateException("Bluetooth is off")

        // Discovery starves RFCOMM connects; the docs are explicit about this.
        runCatching { adapter.cancelDiscovery() }

        val device = adapter.getRemoteDevice(mac)
        val socket: BluetoothSocket = device.createRfcommSocketToServiceRecord(serviceUuid)
        socket.connect()

        Log.i(TAG, "bluetooth link up to $mac")
        return Link(kind, socket.inputStream, socket.outputStream, "bluetooth $mac", socket)
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b6b3a8f1-6f1a-4a5e-9c2d-7e4f1a2b3c4d")
    }
}

/**
 * TCP over Wi-Fi, or over the internet if the user forwarded the port.
 *
 * Every candidate address is tried in turn: a laptop moving between networks
 * changes address, and the pairing payload records all of them.
 */
class TcpCarrier(
    private val hostsProvider: () -> List<String>,
    private val portProvider: () -> Int,
    private val connectTimeoutMs: Int = 3000,
) : Carrier {

    override val kind = CarrierKind.LAN

    override fun isConfigured(): Boolean = hostsProvider().isNotEmpty()

    override fun open(): Link {
        val port = portProvider()
        var lastError: Exception? = null

        for (host in hostsProvider()) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                // Our traffic is small control frames; Nagle would batch them
                // into 40 ms clumps and make everything feel sluggish.
                socket.tcpNoDelay = true
                socket.keepAlive = true

                Log.i(TAG, "lan link up to $host:$port")
                return Link(kind, socket.getInputStream(), socket.getOutputStream(), "$host:$port", socket)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("no LAN address configured for the host")
    }
}

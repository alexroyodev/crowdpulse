package com.alexroyodev.crowdpulse.processor;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.Collections;
import java.util.Optional;

/** Test helper for spec 005, CA1: which addresses of this machine the processor answers on. */
public final class Addresses {

	private Addresses() {
	}

	/** An IPv4 address of this machine that is not loopback (127.x), if it has one. */
	public static Optional<InetAddress> nonLoopbackIpv4() throws SocketException {
		for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
			if (!network.isUp() || network.isLoopback()) {
				continue;
			}
			for (InetAddress address : Collections.list(network.getInetAddresses())) {
				if (address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) {
					return Optional.of(address);
				}
			}
		}
		return Optional.empty();
	}

	/** Whether something accepts TCP connections on that address and port (2 s at most). */
	public static boolean accepts(InetAddress address, int port) {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, port), 2_000);
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

}

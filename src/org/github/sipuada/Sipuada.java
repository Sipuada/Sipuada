package org.github.sipuada;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.Set;

import org.github.sipuada.Constants.RequestMethod;
import org.github.sipuada.Constants.Transport;
import org.github.sipuada.exceptions.SipuadaException;
import org.github.sipuada.plugins.SipuadaPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.javax.sip.InvalidArgumentException;
import android.javax.sip.ListeningPoint;
import android.javax.sip.ObjectInUseException;
import android.javax.sip.PeerUnavailableException;
import android.javax.sip.SipFactory;
import android.javax.sip.SipProvider;
import android.javax.sip.SipStack;
import android.javax.sip.TransportNotSupportedException;

public class Sipuada implements SipuadaApi {

	private final Logger logger = LoggerFactory.getLogger(Sipuada.class);

	private final Map<Transport, Set<UserAgent>> transportToUserAgents = new HashMap<>();
	private final String defaultTransport;

	private final Map<RequestMethod, SipuadaPlugin> registeredPlugins = new HashMap<>();

	public Sipuada(SipuadaListener sipuadaListener, String sipUsername, String sipPrimaryHost,
			String sipPassword, String... localAddresses) throws SipuadaException {

		Properties properties = new Properties();
		properties.setProperty("android.javax.sip.STACK_NAME", "SipuadaUserAgentv0");
		SipFactory factory = SipFactory.getInstance();
		SipStack stack = null;
		try {
			stack = factory.createSipStack(properties);
		} catch (PeerUnavailableException unexpectedException) {
			logger.error("Unexpected problem: {}.", unexpectedException.getMessage(),
					unexpectedException.getCause());
			throw new SipuadaException("Unexpected problem: "
					+ unexpectedException.getMessage(), unexpectedException);
		}
		List<ListeningPoint> listeningPoints = new LinkedList<>();
		for (String localAddress : localAddresses) {
			String localIp = null, localPort = null, transport = null;
			try {
				localIp = localAddress.split(":")[0];
				localPort = localAddress.split(":")[1].split("/")[0];
				transport = localAddress.split("/")[1];
				ListeningPoint listeningPoint = stack.createListeningPoint(localIp,
						Integer.parseInt(localPort), transport);
				listeningPoints.add(listeningPoint);
			} catch (IndexOutOfBoundsException malformedAddress) {
				logger.error("Malformed address: {}.", localAddress);
				throw new SipuadaException("Malformed address provided: " + localAddress,
						malformedAddress);
			} catch (NumberFormatException invalidPort) {
				logger.error("Invalid port for address {}: {}.", localAddress, localPort);
				throw new SipuadaException("Invalid port provided for address "
						+ localAddress + ": " + localPort, invalidPort);
			} catch (TransportNotSupportedException invalidTransport) {
				logger.error("Invalid transport for address {}: {}.", localAddress, transport);
				throw new SipuadaException("Invalid transport provided for address "
						+ localAddress + ": " + transport, invalidTransport);
			} catch (InvalidArgumentException invalidAddress) {
				logger.error("Invalid address provided: {}.", localAddress);
				throw new SipuadaException("Invalid address provided: " + localAddress,
						invalidAddress);
			}
		}
		if (listeningPoints.isEmpty()) {
			logger.error("No local address provided.");
			throw new SipuadaException("No local address provided.", null);
		}

		String mostVotedTransport = Transport.UNKNOWN.toString();
		int mostVotesToATransport = 0;
		Map<Transport, Integer> transportVotes = new HashMap<>();
		for (Transport transport : Transport.values()) {
			transportVotes.put(transport, 0);
		}
		List<Transport> winners = new LinkedList<>();
		for (ListeningPoint listeningPoint : listeningPoints) {
			String rawTransport = listeningPoint.getTransport().toUpperCase();
			Transport transport = Transport.UNKNOWN;
			try {
				transport = Transport.valueOf(rawTransport);
			} catch (IllegalArgumentException ignore) {}
			if (!transportToUserAgents.containsKey(transport)) {
				transportToUserAgents.put(transport, new HashSet<UserAgent>());
			}
			Set<UserAgent> userAgents = transportToUserAgents.get(transport);
			SipProvider sipProvider = null;
			try {
				sipProvider = stack.createSipProvider(listeningPoint);
			} catch (ObjectInUseException unexpectedException) {
				logger.error("Unexpected problem: {}.", unexpectedException.getMessage(),
						unexpectedException.getCause());
				throw new SipuadaException("Unexpected problem: "
						+ unexpectedException.getMessage(), unexpectedException);
			}
			userAgents.add(new UserAgent(sipProvider, sipuadaListener,
					registeredPlugins, sipUsername, sipPrimaryHost, sipPassword,
					listeningPoint.getIPAddress(),
					Integer.toString(listeningPoint.getPort()),
					rawTransport));
			transportVotes.put(transport, transportVotes.get(transport) + 1);
			int votesToThisTransport = transportVotes.get(transport);
			if (votesToThisTransport > mostVotesToATransport) {
				mostVotesToATransport = votesToThisTransport;
				mostVotedTransport = rawTransport;
			}
		}
		for (Transport transport : transportVotes.keySet()) {
			if (transportVotes.get(transport) == mostVotesToATransport) {
				winners.add(transport);
			}
		}
		if (winners.size() <= 1) {
			defaultTransport = mostVotedTransport;
		}
		else {
			defaultTransport = winners.get((new Random())
					.nextInt(winners.size())).toString();
		}

		StringBuilder userAgentsDump = new StringBuilder();
		userAgentsDump.append("{ ");
		int transportGroupIndex = 0;
		for (Transport transport : transportToUserAgents.keySet()) {
			if (transportGroupIndex != 0) {
				userAgentsDump.append(", ");
			}
			userAgentsDump.append(String.format("'%s' : { ", transport));
			Iterator<UserAgent> iterator = transportToUserAgents.get(transport).iterator();
			int userAgentIndex = 0;
			while (iterator.hasNext()) {
				if (userAgentIndex != 0) {
					userAgentsDump.append(", ");
				}
				UserAgent userAgent = iterator.next();
				userAgentsDump.append(String.format("'%s'", userAgent.getRawAddress()));
				userAgentIndex++;
			}
			userAgentsDump.append(" } ");
			transportGroupIndex++;
		}
		userAgentsDump.append(" }");
		logger.info("Sipuada created. Default transport: {}. UA: {}",
				defaultTransport, userAgentsDump.toString());
	}

	@Override
	public boolean registerCaller(RegistrationCallback callback) {
		return fetchBestAgent(defaultTransport).sendRegisterRequest(callback);
	}

	@Override
	public boolean inviteToCall(String remoteUser, String remoteDomain,
			CallInvitationCallback callback) {
		return fetchBestAgent(defaultTransport).sendInviteRequest(remoteUser,
				remoteDomain, callback);
	}

	@Override
	public boolean cancelCallInvitation(String callId) {
		return fetchBestAgent(defaultTransport).cancelInviteRequest(callId);
	}

	@Override
	public boolean acceptCallInvitation(String callId) {
		return fetchBestAgent(defaultTransport).answerInviteRequest(callId, true);
	}

	@Override
	public boolean declineCallInvitation(String callId) {
		return fetchBestAgent(defaultTransport).answerInviteRequest(callId, false);
	}

	@Override
	public boolean finishCall(String callId) {
		return fetchBestAgent(defaultTransport).finishCall(callId);
	}

	private UserAgent fetchBestAgent(String rawTransport) {
		Transport transport = Transport.UNKNOWN;
		try {
			transport = Transport.valueOf(rawTransport);
		} catch (IllegalArgumentException ignore) {}
		Set<UserAgent> userAgentCandidates = transportToUserAgents.get(transport);
		int randomNumber = (new Random()).nextInt(userAgentCandidates.size());
		Iterator<UserAgent> iterator = userAgentCandidates.iterator();
		UserAgent bestUserAgent = iterator.next();
		while (iterator.hasNext() && randomNumber > 0) {
			bestUserAgent = iterator.next();
			randomNumber--;
		}
		return bestUserAgent;
	}

	@Override
	public boolean registerPlugin(SipuadaPlugin plugin) {
		if (registeredPlugins.containsKey(RequestMethod.INVITE)) {
			return false;
		}
		registeredPlugins.put(RequestMethod.INVITE, plugin);
		return true;
	}

	@Override
	public boolean queryOptions(String remoteUser, String remoteDomain, OptionsQueryingCallback callback) {
		return fetchBestAgent(defaultTransport).sendOptionsRequest(remoteUser,
				remoteDomain, callback);
	}

	public void destroy() {
		for (Transport transport : transportToUserAgents.keySet()) {
			Set<UserAgent> userAgents = transportToUserAgents.get(transport);
			for (UserAgent userAgent : userAgents) {
				SipProvider provider = userAgent.getProvider();
				SipStack stack = provider.getSipStack();
				stack.stop();
				for (ListeningPoint listeningPoint : provider.getListeningPoints()) {
					try {
						stack.deleteListeningPoint(listeningPoint);
					} catch (ObjectInUseException ignore) {}
				}
				try {
					stack.deleteSipProvider(provider);
				} catch (ObjectInUseException ignore) {}
			}
		}
	}

}

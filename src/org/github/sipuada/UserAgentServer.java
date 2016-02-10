package org.github.sipuada;

import java.text.ParseException;
import java.util.LinkedList;
import java.util.List;

import org.github.sipuada.Constants.RequestMethod;
import org.github.sipuada.Constants.ResponseClass;
import org.github.sipuada.events.CallInvitationArrived;
import org.github.sipuada.events.CallInvitationCanceled;
import org.github.sipuada.events.RequestCouldNotBeAddressed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.eventbus.EventBus;

import android.javax.sip.Dialog;
import android.javax.sip.InvalidArgumentException;
import android.javax.sip.RequestEvent;
import android.javax.sip.ServerTransaction;
import android.javax.sip.SipException;
import android.javax.sip.SipProvider;
import android.javax.sip.TimeoutEvent;
import android.javax.sip.TransactionAlreadyExistsException;
import android.javax.sip.TransactionUnavailableException;
import android.javax.sip.address.Address;
import android.javax.sip.address.AddressFactory;
import android.javax.sip.address.URI;
import android.javax.sip.header.AllowHeader;
import android.javax.sip.header.CallIdHeader;
import android.javax.sip.header.Header;
import android.javax.sip.header.HeaderFactory;
import android.javax.sip.header.ToHeader;
import android.javax.sip.message.MessageFactory;
import android.javax.sip.message.Request;
import android.javax.sip.message.Response;

public class UserAgentServer {

	private final Logger logger = LoggerFactory.getLogger(UserAgentServer.class);

	private final EventBus bus;
	private final SipProvider provider;
	private final MessageFactory messenger;
	private final HeaderFactory headerMaker;
	private final AddressFactory addressMaker;

	private final String username;
	private final String localDomain;

	public UserAgentServer(EventBus eventBus, SipProvider sipProvider,
			MessageFactory messageFactory, HeaderFactory headerFactory,
			AddressFactory addressFactory, String... identity) {
		bus = eventBus;
		provider = sipProvider;
		messenger = messageFactory;
		headerMaker = headerFactory;
		addressMaker = addressFactory;
		username = identity.length > 0 && identity[0] != null ? identity[0] : "";
		localDomain = identity.length > 1 && identity[1] != null ? identity[1] : "";
	}

	public void processRequest(RequestEvent requestEvent) {
		ServerTransaction serverTransaction = requestEvent.getServerTransaction();
		Request request = requestEvent.getRequest();
		RequestMethod method = RequestMethod.UNKNOWN;
		try {
			method = RequestMethod.valueOf(request.getMethod());
		} catch (IllegalArgumentException ignore) {}
		logger.debug("Request arrived to UAS with method {}.", method);
		handleRequest(method, request, serverTransaction);
	}
	
	private void handleRequest(RequestMethod method, Request request,
			ServerTransaction serverTransaction) {
		try {
			if (tryHandlingRequestGenerically(method, request, serverTransaction)) {
				switch (method) {
					case CANCEL:
						handleCancelRequest(request, serverTransaction);
						break;
					case OPTIONS:
						break;
					case INVITE:
						handleInviteRequest(request, serverTransaction);
						break;
					case BYE:
						break;
					case UNKNOWN:
					default:
						break;
				}
			}
		} catch (RequestCouldNotBeAddressed requestCouldNotBeAddressed) {
			//This means that some internal error happened during UAS processing
			//of this request. Probably it couldn't send a response back.
			//TODO do something about this problem, what?
			logger.error("{} request could not be addressed.", method);
		}
	}

	private boolean tryHandlingRequestGenerically(RequestMethod method, Request request,
			ServerTransaction serverTransaction) {
		if (!methodIsAllowed(method)) {
			logger.warn("{} request is not allowed.", method);
			//TODO add Allow header with supported methods.
			List<Header> allowedMethods = new LinkedList<>();
			try {
				AllowHeader allowHeader = headerMaker
						.createAllowHeader(RequestMethod.CANCEL.toString());
				allowedMethods.add(allowHeader);
				allowHeader = headerMaker
						.createAllowHeader(RequestMethod.OPTIONS.toString());
				allowedMethods.add(allowHeader);
				allowHeader = headerMaker
						.createAllowHeader(RequestMethod.INVITE.toString());
				allowedMethods.add(allowHeader);
				allowHeader = headerMaker
						.createAllowHeader(RequestMethod.BYE.toString());
				allowedMethods.add(allowHeader);
			} catch (ParseException ignore) {}
			if (doSendResponse(Response.METHOD_NOT_ALLOWED, method,
					request, serverTransaction, allowedMethods
					.toArray(new Header[allowedMethods.size()]))) {
				logger.info("{} response sent.",
						Response.METHOD_NOT_ALLOWED);
				return false;
			}
			throw new RequestCouldNotBeAddressed();
		}
		if (!requestShouldBeAddressed(method, request, serverTransaction)) {
			logger.info("{} request should not be addressed by this UAS.", method);
			return false;
		}
		//TODO examine Require header field and check whether a
		//(420 BAD EXTENSION) response is appropriate.
		//TODO perform content processing, responding with
		//(415 UNSUPPORTED MEDIA TYPE) when appropriate.
		return true;
	}

	private boolean methodIsAllowed(RequestMethod method) {
		switch (method) {
			case UNKNOWN:
			default:
				return false;
			case CANCEL:
			case OPTIONS:
			case INVITE:
			case BYE:
				return true;
			}
	}
	
	private boolean requestShouldBeAddressed(RequestMethod method, Request request,
			ServerTransaction serverTransaction) {
		boolean shouldForbid = true;
		ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
		String identityUser = username.toLowerCase();
		String identityDomain = localDomain.toLowerCase().split(":")[0];
		if (toHeader != null) {
			Address toAddress = toHeader.getAddress();
			URI toUri = toAddress.getURI();
			String[] toUriParts = toUri.toString().split("@");
			if (toUriParts.length > 1) {
				String toUriUser = toUriParts[0].split(":")[1].trim().toLowerCase();
				String toUriDomain = toUriParts[1].split(":")[0].trim().toLowerCase();
				if (toUriUser.equals(identityUser) &&
						toUriDomain.equals(identityDomain)) {
					shouldForbid = false;
				}
			}
		}
		else {
			logger.error("Incoming request {} contains no 'To' header.", method);
		}
		if (shouldForbid) {
			if (doSendResponse(Response.FORBIDDEN, method,
					request, serverTransaction)) {
				logger.info("{} response sent.", Response.FORBIDDEN);
				return false;
			}
			else {
				throw new RequestCouldNotBeAddressed();
			}
		}
		URI requestUri = request.getRequestURI();
		boolean shouldNotFound = true;
		String[] requestUriParts = requestUri.toString().split("@");
		if (requestUriParts.length > 1) {
			String requestUriUser = requestUriParts[0].split(":")[1].trim().toLowerCase();
			String requestUriDomain = requestUriParts[1].split(":")[0].trim().toLowerCase();
			if (requestUriUser.equals(identityUser) &&
					requestUriDomain.equals(identityDomain)) {
				shouldNotFound = false;
			}
		}
		if (shouldNotFound) {
			if (doSendResponse(Response.NOT_FOUND, method,
					request, serverTransaction)) {
				logger.info("{} response sent.", Response.NOT_FOUND);
				return false;
			}
			else {
				throw new RequestCouldNotBeAddressed();
			}
		}
		return true;
	}

	private void handleCancelRequest(Request request, ServerTransaction serverTransaction) {
		CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
		String callId = callIdHeader.getCallId();
		if (doSendResponse(Response.OK, RequestMethod.CANCEL, request, serverTransaction)) {
			logger.info("{} response sent.", Response.OK);
			bus.post(new CallInvitationCanceled("Call invitation canceled by the caller " +
					"or callee took longer than roughly 30 seconds to answer.", callId));
			return;
		}
		throw new RequestCouldNotBeAddressed();
	}

	private void handleInviteRequest(Request request, ServerTransaction serverTransaction) {
		boolean withinDialog = serverTransaction != null;
		if (withinDialog) {
			handleReinviteRequest(request, serverTransaction);
			return;
		}
		//TODO take into consideration session offer/answer negotiation procedures.
		//TODO also consider supporting multicast conferences, by sending silent 2xx responses
		//when appropriate, by using identifiers within the SDP session description.
		CallIdHeader callIdHeader = (CallIdHeader) request.getHeader(CallIdHeader.NAME);
		String callId = callIdHeader.getCallId();
		if (doSendResponse(Response.RINGING, RequestMethod.INVITE, request,
				serverTransaction)) {
			logger.info("{} response sent.", Response.RINGING);
			bus.post(new CallInvitationArrived(callId, request));
			return;
		}
		throw new RequestCouldNotBeAddressed();
	}

	private void handleReinviteRequest(Request request, ServerTransaction serverTransaction) {}

	public void processRetransmission(TimeoutEvent retransmissionEvent) {
		if (retransmissionEvent.isServerTransaction()) {
			ServerTransaction serverTransaction = retransmissionEvent.getServerTransaction();
			//TODO Dialog layer says we should retransmit a response. how?
			logger.warn("<RETRANSMISSION event>: " + retransmissionEvent.getServerTransaction());
		}
	}

	public void sendResponse() {
		
	}

	private boolean doSendResponse(int statusCode, RequestMethod method, Request request,
			ServerTransaction serverTransaction, Header... additionalHeaders) {
		boolean withinDialog = true;
		ServerTransaction newServerTransaction = serverTransaction;
		if (newServerTransaction == null) {
			withinDialog = false;
			try {
				newServerTransaction = provider.getNewServerTransaction(request);
			} catch (TransactionAlreadyExistsException requestIsRetransmit) {
				//This may happen if UAS got a retransmit of already pending request.
				logger.debug("{} response could not be sent to {} request: {}.",
						statusCode, request.getMethod(),
						requestIsRetransmit.getMessage());
				return false;
			} catch (TransactionUnavailableException invalidTransaction) {
				//A invalid (maybe null) server transaction
				//can't be used to send this response.
				logger.debug("{} response could not be sent to {} request: {}.",
						statusCode, request.getMethod(),
						invalidTransaction.getMessage());
				return false;
			}
		}
		Dialog dialog = newServerTransaction.getDialog();
		withinDialog &= dialog != null;
		if (Constants.getResponseClass(statusCode) == ResponseClass.PROVISIONAL &&
				method == RequestMethod.INVITE && withinDialog) {
			try {
				Response response = dialog.createReliableProvisionalResponse(statusCode);
				for (Header header : additionalHeaders) {
					response.addHeader(header);
				}
				logger.info("Sending {} response to {} request...", statusCode, method);
				dialog.sendReliableProvisionalResponse(response);
				return true;
			} catch (InvalidArgumentException ignore) {
			} catch (SipException invalidResponse) {
				//A final response to this request was already sent, so this
				//provisional response shall not be sent, or another reliable
				//provisional response is still pending.
				//In either case, we won't send this new response.
				logger.debug("{} response could not be sent to {} request: {} ({}).",
						statusCode, method, invalidResponse.getMessage(),
						invalidResponse.getCause().getMessage());
				return false;
			}
		}
		try {
			Response response = messenger.createResponse(statusCode, request);
			for (Header header : additionalHeaders) {
				response.addHeader(header);
			}
			logger.info("Sending {} response to {} request...", statusCode, method);
			newServerTransaction.sendResponse(response);
			return true;
		} catch (ParseException ignore) {
		} catch (InvalidArgumentException ignore) {
		} catch (SipException responseCouldNotBeSent) {
			logger.debug("{} response could not be sent to {} request: {} ({}).",
					statusCode, method, responseCouldNotBeSent.getMessage(),
					responseCouldNotBeSent.getCause().getMessage());
		}
		return false;
	}

}

package org.github.sipuada;

import java.text.ParseException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

import org.github.sipuada.Constants.RequestMethod;
import org.github.sipuada.Constants.ResponseClass;

import android.gov.nist.gnjvx.sip.Utils;
import android.gov.nist.gnjvx.sip.address.SipUri;
import android.javax.sip.ClientTransaction;
import android.javax.sip.Dialog;
import android.javax.sip.IOExceptionEvent;
import android.javax.sip.InvalidArgumentException;
import android.javax.sip.ResponseEvent;
import android.javax.sip.SipException;
import android.javax.sip.SipProvider;
import android.javax.sip.TimeoutEvent;
import android.javax.sip.TransactionDoesNotExistException;
import android.javax.sip.TransactionState;
import android.javax.sip.TransactionUnavailableException;
import android.javax.sip.address.Address;
import android.javax.sip.address.AddressFactory;
import android.javax.sip.address.SipURI;
import android.javax.sip.address.URI;
import android.javax.sip.header.AcceptEncodingHeader;
import android.javax.sip.header.AcceptHeader;
import android.javax.sip.header.AuthorizationHeader;
import android.javax.sip.header.CSeqHeader;
import android.javax.sip.header.CallIdHeader;
import android.javax.sip.header.ContactHeader;
import android.javax.sip.header.ContentEncodingHeader;
import android.javax.sip.header.ContentTypeHeader;
import android.javax.sip.header.Header;
import android.javax.sip.header.HeaderFactory;
import android.javax.sip.header.ProxyAuthenticateHeader;
import android.javax.sip.header.ProxyAuthorizationHeader;
import android.javax.sip.header.ProxyRequireHeader;
import android.javax.sip.header.RequireHeader;
import android.javax.sip.header.RetryAfterHeader;
import android.javax.sip.header.RouteHeader;
import android.javax.sip.header.ToHeader;
import android.javax.sip.header.UnsupportedHeader;
import android.javax.sip.header.ViaHeader;
import android.javax.sip.header.WWWAuthenticateHeader;
import android.javax.sip.message.MessageFactory;
import android.javax.sip.message.Request;
import android.javax.sip.message.Response;

public class UserAgentClient {

	private final SipProvider provider;
	private final MessageFactory messenger;
	private final HeaderFactory headerMaker;
	private final AddressFactory addressMaker;

	private final String username;
	private final String localDomain;
	private final String password;
	private final String localIp;
	private final int localPort;
	private final String transport;
	private final Map<String, Map<String, String>> authNoncesCache = new HashMap<>();
	private final Map<String, Map<String, String>> proxyAuthNoncesCache = new HashMap<>();
	private final Map<String, Map<String, String>> proxyAuthCallIdCache = new HashMap<>();
	private long localCSeq = 0;
	private final List<Address> configuredRouteSet = new LinkedList<>();
	private Map<URI, CallIdHeader> registerCallIds = new HashMap<>();
	private Map<URI, Long> registerCSeqs = new HashMap<>();

	public UserAgentClient(SipProvider sipProvider, MessageFactory messageFactory,
			HeaderFactory headerFactory, AddressFactory addressFactory,
			String... credentials) {
		provider = sipProvider;
		messenger = messageFactory;
		headerMaker = headerFactory;
		addressMaker = addressFactory;
		username = credentials.length > 0 && credentials[0] != null ? credentials[0] : "";
		localDomain = credentials.length > 1 && credentials[1] != null ? credentials[1] : "";
		password = credentials.length > 2 && credentials[2] != null ? credentials[2] : "";
		localIp = credentials.length > 3 && credentials[3] != null ? credentials[3] : "";
		localPort = credentials.length > 4 && credentials[4] != null ?
				Integer.parseInt(credentials[4]) : 5060;
		transport = credentials.length > 5 && credentials[5] != null ? credentials[5] : "";
	}
	
	public void sendRegisterRequest() {
		URI requestUri;
		try {
			requestUri = addressMaker.createSipURI(null, localDomain);
		} catch (ParseException parseException) {
			//Could not properly parse the request URI for this request.
			//Must be a valid URI.
			//TODO report error condition back to the application layer.
			return;
		}
		if (!registerCallIds.containsKey(requestUri)) {
			registerCallIds.put(requestUri, provider.getNewCallId());
			registerCSeqs.put(requestUri, ++localCSeq);
		}
		CallIdHeader callIdHeader = registerCallIds.get(requestUri);
		long cseq = registerCSeqs.get(requestUri);
		registerCSeqs.put(requestUri, cseq + 1);

		SipURI contactUri;
		try {
			contactUri = addressMaker.createSipURI(username, localIp);
		} catch (ParseException parseException) {
			//Could not properly parse the contact URI for this request.
			//Must be a valid URI.
			//TODO report error condition back to the application layer.
			return;
		}
		contactUri.setPort(localPort);
		Address contactAddress = addressMaker.createAddress(contactUri);
		ContactHeader contactHeader = headerMaker.createContactHeader(contactAddress);
		try {
			contactHeader.setExpires(3600);
		} catch (InvalidArgumentException ignore) {}

		Header[] additionalHeaders = ((List<ContactHeader>)(Collections
				.singletonList(contactHeader))).toArray(new ContactHeader[1]);

		//TODO *IF* request is a REGISTER, keep in mind that:
		/*
		 * UAs MUST NOT send a new registration (that is, containing new Contact
		 * header field values, as opposed to a retransmission) until they have
		 * received a final response from the registrar for the previous one or
		 * the previous REGISTER request has timed out.
		 *
		 * FIXME for now we only allow REGISTER requests passing a single hardcoded
		 * Contact header so this doesn't apply to us yet.
		 */

		sendRequest(RequestMethod.REGISTER, username, localDomain,
				requestUri, callIdHeader, cseq, additionalHeaders);
	}

	//TODO later implement the OPTIONS method.
	//	public void sendOptionsRequest(String remoteUser, String remoteDomain) {
	//		sendRequest(RequestMethod.OPTIONS, remoteUser, remoteDomain,
	//				...);
	//	}
	//TODO when we do it, make sure that no dialog and session state is
	//messed up with by the flow of incoming responses to this OPTIONS request.

	public void sendInviteRequest(String remoteUser, String remoteDomain) {
		URI requestUri;
		try {
			requestUri = addressMaker.createSipURI(remoteUser, remoteDomain);
		} catch (ParseException parseException) {
			//Could not properly parse the request URI for this request.
			//Must be a valid URI.
			//TODO report error condition back to the application layer.
			return;
		}
		CallIdHeader callIdHeader = provider.getNewCallId();
		long cseq = ++localCSeq;

		//TODO *IF* request is a INVITE, make sure to add the following headers:
		/*
		 * (according to section 13.2.1)
		 *
		 * Allow
		 * Supported
		 * (later support also: Accept, Expires, adding body and body-related headers)
		 * (later support also: the offer/answer model
		 */

		sendRequest(RequestMethod.INVITE, remoteUser, remoteDomain,
				requestUri, callIdHeader, cseq);
	}

	private void sendRequest(RequestMethod method, String remoteUser, String remoteDomain,
			URI requestUri, CallIdHeader callIdHeader, long cseq,
			Header... additionalHeaders) {
		try {
			URI addresserUri = addressMaker.createSipURI(username, localDomain);
			URI addresseeUri = addressMaker.createSipURI(remoteUser, remoteDomain);
			sendRequest(method, requestUri, addresserUri, addresseeUri, null,
					callIdHeader, cseq, additionalHeaders);
		} catch (ParseException parseException) {
			//Could not properly parse the addresser or addressee URI.
			//Must be a valid URI.
			//TODO report error condition back to the application layer.
			return;
		}
	}

	public void sendReinviteRequest(Dialog dialog) {
		//TODO everything related to sending RE-INVITE requests, such as
		//making sure they are sent only when they should (it MUST NOT be sent
		//while another INVITE transaction is in progress in either direction within the
		//context of this dialog).
		//TODO also it's important to make sure that failures to this RE-INVITE
		//don't cause the current dialog and session to cease to exist, unless the received
		//response is either a 481 (Call Does Not Exist) or a 408 (Request Timeout), in
		//which case the dialog and session shall be terminated.
		//This will probably need a review of almost all of this source code.
		//TODO finally, also make sure this RE-INVITE is handled properly in the case
		//a 491 response is received (given that the transaction layer doesn't already
		//do this transparently for us, that is).
		sendRequest(RequestMethod.INVITE, dialog);
	}

	public void sendByeRequest(Dialog dialog) {
		//TODO make sure this is only sent by the callee if the dialog is NOT early and
		//it has already received an ACK for it's 2xx response or until the server
		//transaction timed out. The callee MUST NOT send it if the conditions
		//above are not met.
		//(The caller is allowed to send for either confirmed or early dialogs).
		sendRequest(RequestMethod.BYE, dialog);
	}

	private void sendRequest(RequestMethod method, Dialog dialog) {
		URI addresserUri = dialog.getLocalParty().getURI();
		URI addresseeUri = dialog.getRemoteParty().getURI();
		URI requestUri = (URI) addresseeUri.clone();
		CallIdHeader callIdHeader = dialog.getCallId();
		long cseq = dialog.getLocalSeqNumber();
		if (cseq == 0) {
			cseq = localCSeq + 1;
		}
		if (localCSeq < cseq) {
			localCSeq = cseq;
		}
		sendRequest(method, requestUri, addresserUri, addresseeUri, dialog,
				callIdHeader, cseq);
	}

	private void sendRequest(RequestMethod method, URI requestUri, URI addresserUri,
			URI addresseeUri, Dialog dialog, CallIdHeader callIdHeader, long cseq,
			Header... additionalHeaders) {
		if (method == RequestMethod.CANCEL || method == RequestMethod.ACK
				|| method == RequestMethod.UNKNOWN) {
			//This method is meant for the INVITE request and
			//the following NON-INVITE requests: REGISTER, OPTIONS and BYE.
			//(In the future, INFO and MESSAGE as well.)
			//TODO log this wrong usage condition back to the application layer.
			return;
		}

		Address from, to;
		String fromTag, toTag;
		List<Address> canonRouteSet = new LinkedList<>();
		if (dialog != null) {
			from = addressMaker.createAddress(dialog.getLocalParty().getURI());
			fromTag = dialog.getLocalTag();
			to = addressMaker.createAddress(dialog.getRemoteParty().getURI());
			toTag = dialog.getRemoteTag();

			Iterator<?> routeHeaders = dialog.getRouteSet();
			while (routeHeaders.hasNext()) {
				RouteHeader routeHeader = (RouteHeader) routeHeaders.next();
				canonRouteSet.add(routeHeader.getAddress());
			}
		}
		else {
			from = addressMaker.createAddress(addresserUri);
			fromTag = Utils.getInstance().generateTag();
			to = addressMaker.createAddress(addresseeUri);
			toTag = null;
			
			canonRouteSet.addAll(configuredRouteSet);
		}

		List<Address> normalizedRouteSet = new LinkedList<>();
		URI remoteTargetUri = (URI) requestUri.clone();
		if (!canonRouteSet.isEmpty()) {
			if (((SipURI)canonRouteSet.get(0).getURI()).hasLrParam()) {
				for (Address address : canonRouteSet) {
					normalizedRouteSet.add(address);
				}
			}
			else {
				requestUri = canonRouteSet.get(0).getURI();
				for (int i=1; i<canonRouteSet.size(); i++) {
					normalizedRouteSet.add(canonRouteSet.get(i));
				}
				Address remoteTargetAddress = addressMaker.createAddress(remoteTargetUri);
				normalizedRouteSet.add(remoteTargetAddress);
			}
		}

		try {
			ViaHeader viaHeader = headerMaker
					.createViaHeader(localIp, localPort, transport, null);
			viaHeader.setRPort();
			Request request = messenger.createRequest(requestUri, method.toString(),
					callIdHeader, headerMaker.createCSeqHeader(cseq, method.toString()),
					headerMaker.createFromHeader(from, fromTag),
					headerMaker.createToHeader(to, toTag),
					Collections.singletonList(viaHeader),
					headerMaker.createMaxForwardsHeader(70));
			if (!normalizedRouteSet.isEmpty()) {
				for (Address routeAddress : normalizedRouteSet) {
					RouteHeader routeHeader = headerMaker.createRouteHeader(routeAddress);
					request.addHeader(routeHeader);
				}
			}
			else {
				request.removeHeader(RouteHeader.NAME);
			}

			for (int i=0; i<additionalHeaders.length; i++) {
				request.addHeader(additionalHeaders[i]);
			}
			
			ClientTransaction clientTransaction = provider
					.getNewClientTransaction(request);
			viaHeader.setBranch(clientTransaction.getBranchId());
			doSendRequest(request, clientTransaction, dialog);
			//TODO *IF* this request is a INVITE, then please propagate
			//this clientTransaction back to the application layer
			//so that it can later cancel this request if desired.
			//TODO *IF* this request is a BYE, please let the application layer
			//know it should consider the session associated with this request
			//terminated.
		} catch (ParseException requestCouldNotBeBuilt) {
			//Could not properly build this request.
			//TODO report this error condition back to the application layer.
		} catch (SipException requestCouldNotBeSent) {
			//This request could not be sent.
			//TODO report this error condition back to the application layer.
		} catch (InvalidArgumentException ignore) {}
	}

	public void sendCancelRequest(final ClientTransaction clientTransaction) {
		if (!clientTransaction.getRequest().getMethod().equals(RequestMethod.INVITE)) {
			//This method is meant for canceling INVITE requests only.
			//TODO log this wrong usage condition back to the application layer.
			return;
		}
		final Request cancelRequest;
		try {
			cancelRequest = clientTransaction.createCancel();
		} catch (SipException ignore) {
			return;
		}
		final Timer timer = new Timer();
		timer.schedule(new TimerTask() {

			@Override
			public void run() {
				if (clientTransaction.getState() == null) {
					return;
				}
				switch (clientTransaction.getState().getValue()) {
				case TransactionState._PROCEEDING:
					try {
						doSendRequest(cancelRequest, null, null);
					} catch (SipException requestCouldNotBeSent) {
						//This request could not be sent.
						//TODO report this error condition back to the application layer.
					}
					break;
				case TransactionState._COMPLETED:
				case TransactionState._TERMINATED:
					timer.cancel();
					//The request was accepted before we could cancel it.
					//TODO decide whether we will send a BYE request here to
					//terminate the established session, or just inform the application
					//layer that it was too late to cancel.
				}
			}
		}, 180, 180);
	}

	public void processResponse(ResponseEvent responseEvent) {
		ClientTransaction clientTransaction = responseEvent.getClientTransaction();
		if (clientTransaction != null) {
			Response response = responseEvent.getResponse();
			int statusCode = response.getStatusCode();
			handleResponse(statusCode, response, clientTransaction);
		}
		else {
			//No request made by this UAC is associated with this response.
			//The response then is considered stray, so it simply discards it.
		}
	}

	public void processTimeout(TimeoutEvent timeoutEvent) {
		if (!timeoutEvent.isServerTransaction()) {
			ClientTransaction clientTransaction = timeoutEvent.getClientTransaction();
			handleResponse(Response.REQUEST_TIMEOUT, null, clientTransaction);
		}
	}

	public void processFatalTransportError(IOExceptionEvent exceptionEvent) {
		handleResponse(Response.SERVICE_UNAVAILABLE, null, null);
	}
	
	private void handleResponse(int statusCode, Response response,
			ClientTransaction clientTransaction) {
		if (tryHandlingResponseGenerically(statusCode, response, clientTransaction)) {
			if (response == null || clientTransaction == null) {
				return;
			}
			switch (Constants.getRequestMethod(clientTransaction.getRequest().getMethod())) {
				case INVITE:
					handleInviteResponse(statusCode, clientTransaction);
					break;
				case BYE:
					handleByeResponse(statusCode, clientTransaction);
				case UNKNOWN:
				default:
					break;
			}
		}
	}
	
	private boolean tryHandlingResponseGenerically(int statusCode, Response response,
			ClientTransaction clientTransaction) {
		if (response != null) {
			ListIterator<?> iterator = response.getHeaders(ViaHeader.NAME);
			int viaHeaderCount = 0;
			while (iterator.hasNext()) {
				iterator.next();
				viaHeaderCount++;
			}
			if (viaHeaderCount > 1) {
				//Handle by simply discarding the response, it's corrupted.
				return false;
			}
		}
		switch (statusCode) {
			case Response.PROXY_AUTHENTICATION_REQUIRED:
			case Response.UNAUTHORIZED:
				//Handle this by performing authorization procedures.
				handleAuthorizationRequired(response, clientTransaction);
				//No method-specific handling is required.
				return false;
			case Response.REQUEST_ENTITY_TOO_LARGE:
				//TODO handle this by retrying omitting the body or using one of
				//smaller length.
				//If the condition is temporary, the server SHOULD include a
				//Retry-After header field to indicate that it is temporary and after
				//what time the client MAY try again.
				//For now just checking whether simple rescheduling is applicable.
				handleUnsupportedExtension(response, clientTransaction);
				//No method-specific handling is required.
				return false;
			case Response.UNSUPPORTED_MEDIA_TYPE:
				//TODO handle this by retrying after filtering any media types not listed in
				//the Accept header field in the response, with encodings listed in the
				//Accept-Encoding header field in the response, and with languages listed in
				//the Accept-Language in the response.
				handleUnsupportedMediaTypes(response, clientTransaction);
				//No method-specific handling is required.
				return false;
			case Response.BAD_EXTENSION:
				//TODO handle this by retrying, this time omitting any extensions listed in
				//the Unsupported header field in the response.
				handleUnsupportedExtension(response, clientTransaction);
				//No method-specific handling is required.
				return false;
			case Response.NOT_FOUND:
			case Response.BUSY_HERE:
			case Response.BUSY_EVERYWHERE:
			case Response.DECLINE:
				//TODO handle this by retrying if a better time to call is indicated in
				//the Retry-After header field.
				handleByReschedulingIfApplicable(response, clientTransaction, false);
				//No method-specific handling is required.
				return false;
			case Response.SERVER_INTERNAL_ERROR:
			case Response.SERVICE_UNAVAILABLE:
				//TODO handle this by retrying if expected available time is indicated in
				//the Retry-After header field.
				handleByReschedulingIfApplicable(response, clientTransaction, false);
				//No method-specific handling is required.
				return false;
			case Response.REQUEST_TIMEOUT:
			case Response.SERVER_TIMEOUT:
				//TODO handle this by retrying the same request after a while if it is
				//outside of a dialog, otherwise consider the dialog and session terminated.
				handleByReschedulingIfApplicable(response, clientTransaction, true);
				//No method-specific handling is required.
				return false;
			/*
			 * case Response.ADDRESS_INCOMPLETE:
			 */
				//TODO figure out how to handle this by doing overlapped dialing(?)
				//until the response no longer is a 484 (Address Incomplete).
				//No method-specific handling is required.
				//return false;
			/*
			 * case Response.UNSUPPORTED_URI_SCHEME: 
			 */
				//TODO handle this by retrying, this time using a SIP(S) URI.
				//No method-specific handling is required.
				//return false;
			/*
			 * case Response.AMBIGUOUS:
			 */
				//FIXME I think no method-specific handling is required.
				//TODO figure out how to handle this by prompting for user intervention
				//for deciding which of the choices provided is to be used in the retry.
				//return false;
			case Response.CALL_OR_TRANSACTION_DOES_NOT_EXIST:
				handleByTerminatingIfWithinDialog(clientTransaction);
				return false;
			case Response.REQUEST_TERMINATED:
				handleThisRequestTerminated(clientTransaction);
				return false;
		}
		switch (Constants.getResponseClass(statusCode)) {
			case PROVISIONAL:
				//TODO give the application layer feedback on this event.
				return true;
			case SUCCESS:
				return true;
			case REDIRECT:
				//TODO perform redirect request(s) transparently.
				//TODO remember to, if no redirect is sent, remove any early dialogs
				//associated with this request and tell the application layer about it.
				//FIXME for now we are always doing the task above for obvious reasons.
				handleThisByRemovingEarlyDialogs(clientTransaction);
				//TODO also remember to implement the AMBIGUOUS case above as it's similar.
				return false;
			case CLIENT_ERROR:
			case SERVER_ERROR:
			case GLOBAL_ERROR:
				//TODO remove any early dialogs associated with this request
				//and tell the application layer about it.
				//TODO report this response error back to the application layer.
				handleThisByRemovingEarlyDialogs(clientTransaction);
				return false;
			case UNKNOWN:
				//Handle this by simply discarding this unknown response.
				return false;
		}
		return true;
	}
	
	private void handleAuthorizationRequired(Response response,
			ClientTransaction clientTransaction) {
		Request request = createFromRequest(clientTransaction.getRequest());
		incrementCSeq(request);

		SipUri domainUri = new SipUri();
		try {
			domainUri.setHost(localDomain);
		} catch (ParseException parseException) {
			//Could not properly parse domainUri.
			handleThisByRemovingEarlyDialogs(clientTransaction);
			//TODO report 401/407 error back to application layer.
			//TODO also log this error condition back to the application layer.
			return;
		}

		String username = this.username, password = this.password;
		if (username.trim().isEmpty()) {
			username = "anonymous";
			password = "";
		}
		ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
		String toHeaderValue = toHeader.getAddress().getURI().toString();

		boolean worthAuthenticating = false;
		ListIterator<?> wwwAuthenticateHeaders = response
				.getHeaders(WWWAuthenticateHeader.NAME);
		while (wwwAuthenticateHeaders.hasNext()) {
			WWWAuthenticateHeader wwwAuthenticateHeader =
					(WWWAuthenticateHeader) wwwAuthenticateHeaders.next();
			String realm = wwwAuthenticateHeader.getRealm();
			String nonce = wwwAuthenticateHeader.getNonce();
			if (authNoncesCache.containsKey(toHeaderValue)
					&& authNoncesCache.get(toHeaderValue).containsKey(realm)) {
				String oldNonce = authNoncesCache.get(toHeaderValue).get(realm);
				if (oldNonce.equals(nonce)) {
					authNoncesCache.get(toHeaderValue).remove(realm);
					continue;
				}
			}
			worthAuthenticating = addAuthorizationHeader(request, domainUri,
					toHeaderValue, username, password, realm, nonce);
			if (!authNoncesCache.containsKey(toHeaderValue)) {
				authNoncesCache.put(toHeaderValue, new HashMap<String, String>());
			}
			authNoncesCache.get(toHeaderValue).put(realm, nonce);
		}
		ListIterator<?> proxyAuthenticateHeaders = response
				.getHeaders(ProxyAuthenticateHeader.NAME);
		while (proxyAuthenticateHeaders.hasNext()) {
			ProxyAuthenticateHeader proxyAuthenticateHeader =
					(ProxyAuthenticateHeader) proxyAuthenticateHeaders.next();
			String realm = proxyAuthenticateHeader.getRealm();
			String nonce = proxyAuthenticateHeader.getNonce();
			if (proxyAuthNoncesCache.containsKey(toHeaderValue)
					&& proxyAuthNoncesCache.get(toHeaderValue).containsKey(realm)) {
				String oldNonce = proxyAuthNoncesCache.get(toHeaderValue).get(realm);
				if (oldNonce.equals(nonce)) {
					proxyAuthNoncesCache.get(toHeaderValue).remove(realm);
					proxyAuthCallIdCache.get(toHeaderValue).remove(realm);
					continue;
				}
			}
			worthAuthenticating = addProxyAuthorizationHeader(request, domainUri,
					toHeaderValue, username, password, realm, nonce);
			if (!proxyAuthNoncesCache.containsKey(toHeaderValue)) {
				proxyAuthNoncesCache.put(toHeaderValue, new HashMap<String, String>());
				proxyAuthCallIdCache.put(toHeaderValue, new HashMap<String, String>());
			}
			proxyAuthNoncesCache.get(toHeaderValue).put(realm, nonce);
			Dialog dialog = clientTransaction.getDialog();
			String callId = dialog.getCallId().getCallId();
			if (dialog != null) {
				proxyAuthCallIdCache.get(toHeaderValue).put(realm, callId);
			}
		}

		if (worthAuthenticating) {
			try {
				doSendRequest(request, null, clientTransaction.getDialog(), false);
			} catch (SipException requestCouldNotBeSent) {
				//Request that would authenticate could not be sent.
				handleThisByRemovingEarlyDialogs(clientTransaction);
				//TODO report 401/407 error back to application layer.
			}
		}
		else {
			//Not worth authenticating because server already denied
			//this exact request.
			handleThisByRemovingEarlyDialogs(clientTransaction);
			//TODO report 401/407 error back to application layer.
		}
	}
	
	private void handleUnsupportedMediaTypes(Response response,
			ClientTransaction clientTransaction) {
		Request request = createFromRequest(clientTransaction.getRequest());
		incrementCSeq(request);

		ListIterator<?> acceptEncodingHeaders =
				response.getHeaders(AcceptEncodingHeader.NAME);
		List<String> acceptedEncodings = new LinkedList<>();
		while (acceptEncodingHeaders.hasNext()) {
			AcceptEncodingHeader acceptEncodingHeader =
					(AcceptEncodingHeader) acceptEncodingHeaders.next();
			StringBuilder encoding =
					new StringBuilder(acceptEncodingHeader.getEncoding());
			float qValue = acceptEncodingHeader.getQValue();
			if (qValue != -1) {
				encoding.insert(0, String.format("q=%.2f, ", qValue));
			}
			acceptedEncodings.add(encoding.toString());
		}
		ContentEncodingHeader contentEncodingHeader =
				request.getContentEncoding();
		String definedEncoding = contentEncodingHeader.getEncoding();
		StringBuilder overlappingEncodings = new StringBuilder();
		for (String acceptedEncoding : acceptedEncodings) {
			String encodingWithoutQValue = acceptedEncoding;
			if (encodingWithoutQValue.contains(",")) {
				encodingWithoutQValue = acceptedEncoding
						.split(",")[1].trim();
			}
			if (definedEncoding.contains(encodingWithoutQValue)) {
				if (overlappingEncodings.length() != 0) {
					overlappingEncodings.append("; ");
				}
				overlappingEncodings.append(acceptedEncoding);
			}
		}
		boolean overlappingEncodingsFound = false;
		if (overlappingEncodings.length() > 0) {
			try {
				contentEncodingHeader
					.setEncoding(overlappingEncodings.toString());
				request.setContentEncoding(contentEncodingHeader);
				overlappingEncodingsFound = true;
			} catch (ParseException ignore) {}
		}
		
		boolean shouldBypassContentTypesCheck = false;
		ListIterator<?> acceptHeaders = response.getHeaders(AcceptHeader.NAME);
		Map<String, String> typeSubtypeToQValue = new HashMap<>();
		Map<String, Set<String>> typeToSubTypes = new HashMap<>();
		while (acceptHeaders.hasNext()) {
			AcceptHeader acceptHeader =
					(AcceptHeader) acceptHeaders.next();
			String contentType = acceptHeader.getContentType();
			String contentSubType = acceptHeader.getContentSubType();
			if (acceptHeader.allowsAllContentTypes()) {
				shouldBypassContentTypesCheck = true;
				break;
			}
			else if (acceptHeader.allowsAllContentSubTypes()) {
				typeToSubTypes.put(contentType, new HashSet<String>());
			}
			else {
				if (!typeToSubTypes.containsKey(contentType)) {
					typeToSubTypes.put(contentType, new HashSet<String>());
				}
				typeToSubTypes.get(contentType).add(contentSubType);
			}
			float qValue = acceptHeader.getQValue();
			if (qValue != -1) {
				typeSubtypeToQValue.put(String.format("%s/%s", contentType,
						contentSubType), Float.toString(qValue));
			}
		}
		boolean overlappingContentTypesFound = false;
		if (!shouldBypassContentTypesCheck) {
			ListIterator<?> definedContentTypeHeaders =
					request.getHeaders(ContentTypeHeader.NAME);
			List<ContentTypeHeader> overlappingContentTypes
					= new LinkedList<>();
			while (definedContentTypeHeaders.hasNext()) {
				ContentTypeHeader contentTypeHeader = (ContentTypeHeader)
						definedContentTypeHeaders.next();
				boolean addThisContentType = false;
				String contentType = contentTypeHeader
						.getContentType();
				String contentSubType = contentTypeHeader
						.getContentSubType();
				if (typeToSubTypes.containsKey(contentType)) {
					Set<String> acceptedSubTypes =
							typeToSubTypes.get(contentType);
					if (acceptedSubTypes.isEmpty()) {
						addThisContentType = true;
					}
					else {
						for (String acceptedSubType : acceptedSubTypes) {
							if (acceptedSubType.equals(contentSubType)) {
								addThisContentType = true;
								break;
							}
						}
					}
				}
				if (addThisContentType) {
					String typeSubtype = String.format("%s/%s",
							contentType, contentSubType);
					if (typeSubtypeToQValue.containsKey(typeSubtype)) {
						String qValue = typeSubtypeToQValue
								.get(typeSubtype);
						try {
							contentTypeHeader.setParameter("q", qValue);
						} catch (ParseException ignore) {}
					}
					overlappingContentTypes.add(contentTypeHeader);
				}
			}
			if (overlappingContentTypes.size() > 0) {
				for (ContentTypeHeader header
						: overlappingContentTypes) {
					request.addHeader(header);
				}
				overlappingContentTypesFound = true;
			}
		}
		if (overlappingEncodingsFound && overlappingContentTypesFound) {
			try {
				doSendRequest(request, null, clientTransaction.getDialog());
			} catch (SipException requestCouldNotBeSent) {
				//Request that would amend this situation could not be sent.
				handleThisByRemovingEarlyDialogs(clientTransaction);
				//TODO report 415 error back to application layer.
			}
		}
		else {
			//Cannot satisfy the media type requirements since this UAC doesn't
			//support any that are accepted by the UAS that sent this response.
			handleThisByRemovingEarlyDialogs(clientTransaction);
			//TODO report 415 error back to application layer.
		}
	}
	
	private void handleUnsupportedExtension(Response response,
			ClientTransaction clientTransaction) {
		Request request = createFromRequest(clientTransaction.getRequest());
		incrementCSeq(request);
		
		UnsupportedHeader unsupportedHeader =
				(UnsupportedHeader) response.getHeader(UnsupportedHeader.NAME);
		String unsupportedOptionTags = unsupportedHeader.getOptionTag();
		
		RequireHeader requireHeader =
				(RequireHeader) request.getHeader(RequireHeader.NAME);
		ProxyRequireHeader proxyRequireHeader =
				(ProxyRequireHeader) request.getHeader(ProxyRequireHeader.NAME);
		StringBuilder allowedOptionTags = new StringBuilder();
		if (requireHeader != null) {
			String[] requiredOptionTags = requireHeader.getOptionTag().split(",");
			for (String requiredOptionTag : requiredOptionTags) {
				requiredOptionTag = requiredOptionTag.trim();
				if (!unsupportedOptionTags.contains(requiredOptionTag)) {
					if (allowedOptionTags.length() > 0) {
						allowedOptionTags.append(", ");
					}
					allowedOptionTags.append(requiredOptionTag);
				}
			}
			if (allowedOptionTags.length() > 0) {
				try {
					requireHeader.setOptionTag(allowedOptionTags.toString());
				} catch (ParseException ignore) {}
				request.setHeader(requireHeader);
			}
			else {
				request.removeHeader(RequireHeader.NAME);
			}
		}
		else if (proxyRequireHeader != null) {
			String[] proxyRequiredOptionTags = proxyRequireHeader.getOptionTag().split(",");
			for (String proxyRequiredOptionTag : proxyRequiredOptionTags) {
				proxyRequiredOptionTag = proxyRequiredOptionTag.trim();
				if (!unsupportedOptionTags.contains(proxyRequiredOptionTag)) {
					if (allowedOptionTags.length() > 0) {
						allowedOptionTags.append(", ");
					}
					allowedOptionTags.append(proxyRequiredOptionTag);
				}
			}
			if (allowedOptionTags.length() > 0) {
				try {
					proxyRequireHeader.setOptionTag(allowedOptionTags.toString());
				} catch (ParseException ignore) {}
				request.setHeader(proxyRequireHeader);
			}
			else {
				request.removeHeader(ProxyRequireHeader.NAME);
			}
		}
		try {
			doSendRequest(request, null, clientTransaction.getDialog());
		} catch (SipException requestCouldNotBeSent) {
			//Request that would amend this situation could not be sent.
			handleThisByRemovingEarlyDialogs(clientTransaction);
			//TODO report 420 error back to application layer.
		}
	}

	private void handleByReschedulingIfApplicable(Response response,
			final ClientTransaction clientTransaction, boolean isTimeout) {
		if (isTimeout) {
			boolean isError = true;
			if (response == null) {
				isError = !handleThisRequestTerminated(clientTransaction);
			}
			Dialog dialog = clientTransaction.getDialog();
			if (dialog != null) {
				dialog.delete();
				if (isError) {
					handleThisByRemovingEarlyDialogs(clientTransaction);
					//TODO report response error back to application layer.
				}
				return;
			}
		}

		final Request request = createFromRequest(clientTransaction.getRequest());
		incrementCSeq(request);

		RetryAfterHeader retryAfterHeader =
				(RetryAfterHeader) response.getHeader(RetryAfterHeader.NAME);
		if (retryAfterHeader != null) {
			int retryAfterSeconds = retryAfterHeader.getRetryAfter();
			int durationSeconds = retryAfterHeader.getDuration();
			if (durationSeconds == 0 || durationSeconds > 300) {
				Timer timer = new Timer();
				timer.schedule(new TimerTask() {

					@Override
					public void run() {
						try {
							doSendRequest(request, null, clientTransaction.getDialog());
						} catch (SipException requestCouldNotBeSent) {
							//Could not reschedule request.
							handleThisByRemovingEarlyDialogs(clientTransaction);
							//TODO report response error back to application layer.
						}
					}

				}, retryAfterSeconds * 1000);
				return;
			}
		}
		//Request is not allowed to reschedule or it would be pointless to do so
		//because availability frame is too short.
		handleThisByRemovingEarlyDialogs(clientTransaction);
		//TODO report response error back to application layer.
	}

	private void handleByTerminatingIfWithinDialog(ClientTransaction clientTransaction) {
		Dialog dialog = clientTransaction.getDialog();
		if (dialog != null) {
			//TODO handle this by sending a BYE request within this dialog immediately.
		}
		else {
			//TODO report 481 error back to the application layer.
		}
	}
	
	private void handleInviteResponse(int statusCode, ClientTransaction clientTransaction) {
		boolean shouldSaveDialog = false;
		Dialog dialog = clientTransaction.getDialog();
		if (ResponseClass.SUCCESS == Constants.getResponseClass(statusCode)) {
			if (dialog != null) {
				try {
					CSeqHeader cseqHeader = (CSeqHeader) clientTransaction
							.getRequest().getHeader(CSeqHeader.NAME);
					Request ackRequest = dialog.createAck(cseqHeader.getSeqNumber());
					//TODO *IF* the INVITE request contained a offer, this ACK
					//MUST carry an answer to that offer, given that the offer is acceptable!
					//TODO *HOWEVER* if the offer is not acceptable, after sending the ACK,
					//a BYE request MUST be sent immediately.
					dialog.sendAck(ackRequest);
				} catch (InvalidArgumentException ignore) {
				} catch (SipException ignore) {}
				shouldSaveDialog = true;
			}
		}
		else if (ResponseClass.PROVISIONAL == Constants.getResponseClass(statusCode)) {
			if (statusCode == Response.RINGING) {
				//TODO report response status code back to the application layer.
			}
			shouldSaveDialog = true;
		}
		if (shouldSaveDialog) {
			//TODO propagate this dialog back to the application layer so that it can
			//pass it back when desiring to perform subsequent dialog operations.
			//TODO remember that this dialog may be early. It may be useful for us
			//to keep track of the allowed methods while early by reading the Allow header
			//in the provisional response, or allowed methods afterwards by reading the
			//aforementioned header in the final 2xx response.
			//TODO also make sure to tell the application layer to get rid of the
			//Client transaction associated with this request as it just became useless.
		}
	}

	private void handleByeResponse(int statusCode, ClientTransaction clientTransaction) {
		if (ResponseClass.SUCCESS == Constants.getResponseClass(statusCode)) {
			handleThisRequestTerminated(clientTransaction);
			//TODO make sure to tell the application layer to get rid of the
			//Dialog associated with this request as it just became invalid.
		}
	}

	private boolean handleThisRequestTerminated(ClientTransaction clientTransaction) {
		switch (Constants.getRequestMethod(clientTransaction.getRequest().getMethod())) {
			case INVITE:
				//This means a CANCEL succeeded in canceling the original INVITE request.
				//TODO make sure to tell the application layer to get rid of the
				//Client transaction associated with this request as it just became useless.
				handleThisByRemovingEarlyDialogs(clientTransaction);
				//TODO report this condition back to the application layer.
				return true;
			case BYE:
				//This means a BYE succeeded in terminating a INVITE request within a Dialog.
				//TODO make sure to tell the application layer to get rid of the
				//Dialog associated with this request as it just became invalid.
				//TODO report this condition back to the application layer.
				return true;
			default:
				//This should never happen.
				return false;
		}
	}

	private void handleThisByRemovingEarlyDialogs(ClientTransaction clientTransaction) {
		Dialog dialog = clientTransaction.getDialog();
		if (dialog != null) {
			//TODO tell the application layer to get rid of the early dialog associated
			//with this request as it just became invalid.
			//TODO According to "15 Terminating a Session", all sessions and dialogs
			//that were created through responses to this request shall be terminated too,
			//if this was a INVITE request.
		}
	}

	private Request createFromRequest(Request original) {
		Request derived = (Request) original.clone();
		ListIterator<?> iterator = original.getHeaders(ViaHeader.NAME);
		String newBranchId = Utils.getInstance().generateBranchId();
		derived.removeHeader(ViaHeader.NAME);
		while (iterator.hasNext()) {
			ViaHeader viaHeader = (ViaHeader) iterator.next();
			if (viaHeader.getBranch() != null) {
				try {
					viaHeader.setBranch(newBranchId);
				} catch (ParseException ignore) {}
			}
			derived.addHeader(viaHeader);
		}
		return derived;
	}

	private void incrementCSeq(Request request) {
		CSeqHeader cseq = (CSeqHeader) request.getHeader(CSeqHeader.NAME);
		try {
			long newCSeq = cseq.getSeqNumber() + 1;
			cseq.setSeqNumber(newCSeq);
			if (localCSeq < newCSeq) {
				localCSeq = newCSeq;
			}
		} catch (InvalidArgumentException ignore) {}
		request.setHeader(cseq);
	}

	private void doSendRequest(Request request,
			ClientTransaction clientTransaction, Dialog dialog)
			throws TransactionUnavailableException, SipException {
		doSendRequest(request, clientTransaction, dialog, true);
	}

	private void doSendRequest(Request request, ClientTransaction clientTransaction,
			Dialog dialog, boolean tryAddingAuthorizationHeaders)
			throws TransactionUnavailableException, SipException {
		ToHeader toHeader = (ToHeader) request.getHeader(ToHeader.NAME);
		String toHeaderValue = toHeader.getAddress().getURI().toString();

		SipUri domainUri = new SipUri();
		try {
			domainUri.setHost(localDomain);
		} catch (ParseException parseException) {
			//Could not properly parse domainUri.
			//TODO also log this error condition back to the application layer.
			return;
		}

		if (tryAddingAuthorizationHeaders) {
			if (authNoncesCache.containsKey(toHeaderValue)) {
				Map<String, String> probableRealms = new HashMap<>();
				for (Entry<String, String> entry :
					authNoncesCache.get(toHeaderValue).entrySet()) {
					String realm = entry.getKey();
					String remoteDomain = toHeaderValue.split("@")[1];
					if (realm.contains(remoteDomain)) {
						String nonce = entry.getValue();
						probableRealms.put(realm, nonce);
					}
				}
				for (String realm : probableRealms.keySet()) {
					addAuthorizationHeader(request, domainUri, toHeaderValue,
							toHeaderValue, toHeaderValue, realm, probableRealms.get(realm));
				}
			}
			if (proxyAuthNoncesCache.containsKey(toHeaderValue)) {
				Map<String, String> probableRealms = new HashMap<>();
				for (Entry<String, String> entry :
					proxyAuthNoncesCache.get(toHeaderValue).entrySet()) {
					String realm = entry.getKey();
					String remoteDomain = toHeaderValue.split("@")[1];
					if (realm.contains(remoteDomain)) {
						String nonce = entry.getValue();
						probableRealms.put(realm, nonce);
					}
				}
				for (String realm : probableRealms.keySet()) {
					String thisCallId = dialog.getCallId().getCallId();
					String savedCallId = proxyAuthCallIdCache.get(toHeaderValue).get(realm);
					if (thisCallId.equals(savedCallId)) {
						addProxyAuthorizationHeader(request, domainUri, toHeaderValue,
								toHeaderValue, toHeaderValue, realm,
								probableRealms.get(realm));
					}
				}
			}
		}

		ClientTransaction newClientTransaction = clientTransaction;
		if (clientTransaction == null) {
			newClientTransaction = provider.getNewClientTransaction(request);
			ViaHeader viaHeader = (ViaHeader) request.getHeader(ViaHeader.NAME);
			try {
				viaHeader.setBranch(newClientTransaction.getBranchId());
				viaHeader.setRPort();
			} catch (ParseException ignore) {
			} catch (InvalidArgumentException ignore) {}
		}
		if (dialog != null) {
			try {
				dialog.sendRequest(newClientTransaction);
			}
			catch (TransactionDoesNotExistException invalidTransaction) {
				//A invalid (probably null) client transaction
				//can't be used to send this request.
				//TODO log this error condition back to the application layer.
			}
		}
		else {
			newClientTransaction.sendRequest();
		}
	}

	private boolean addAuthorizationHeader(Request request, URI domainUri,
			String toHeaderValue, String username, String password,
			String realm, String nonce) {
		String responseDigest = AuthorizationDigest.getDigest(username, realm,
				password, request.getMethod(), domainUri.toString(), nonce);
		AuthorizationHeader authorizationHeader;
		try {
			authorizationHeader = headerMaker
					.createAuthorizationHeader("Digest");
			authorizationHeader.setAlgorithm("MD5");
			authorizationHeader.setURI(domainUri);
			authorizationHeader.setUsername(username);
			authorizationHeader.setRealm(realm);
			authorizationHeader.setNonce(nonce);
			authorizationHeader.setResponse(responseDigest);
		} catch (ParseException parseException) {
			//TODO log this error condition back to the application layer.
			return false;
		}
		request.addHeader(authorizationHeader);
		return true;
	}

	private boolean addProxyAuthorizationHeader(Request request, URI domainUri,
			String toHeaderValue, String username, String password,
			String realm, String nonce) {
		String responseDigest = AuthorizationDigest.getDigest(username, realm,
				password, request.getMethod(), domainUri.toString(), nonce);
		ProxyAuthorizationHeader proxyAuthorizationHeader;
		try {
			proxyAuthorizationHeader = headerMaker
					.createProxyAuthorizationHeader("Digest");
			proxyAuthorizationHeader.setAlgorithm("MD5");
			if (domainUri != null) {
				proxyAuthorizationHeader.setURI(domainUri);
			}
			proxyAuthorizationHeader.setUsername(username);
			proxyAuthorizationHeader.setRealm(realm);
			proxyAuthorizationHeader.setNonce(nonce);
			proxyAuthorizationHeader.setResponse(responseDigest);
		} catch (ParseException parseException) {
			//TODO log this error condition back to the application layer.
			return false;
		}
		request.addHeader(proxyAuthorizationHeader);
		return true;
	}

}
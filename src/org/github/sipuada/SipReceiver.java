package org.github.sipuada;

import org.github.sipuada.state.SipStateMachine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.javax.sip.DialogTerminatedEvent;
import android.javax.sip.IOExceptionEvent;
import android.javax.sip.RequestEvent;
import android.javax.sip.ResponseEvent;
import android.javax.sip.SipListener;
import android.javax.sip.TimeoutEvent;
import android.javax.sip.TransactionTerminatedEvent;
import android.javax.sip.message.Request;
import android.javax.sip.message.Response;

public class SipReceiver implements SipListener {
	
	private static final Logger LOGGER = LoggerFactory.getLogger(SipReceiver.class);
	private SipStateMachine stateMachine;
	
	public SipReceiver(SipStateMachine machine) {
		stateMachine = machine;
	}

	@Override
	public void processRequest(RequestEvent requestEvent) {
		Request request = requestEvent.getRequest();
		RequestVerb requestVerb = RequestVerb.valueOf(request.getMethod().toUpperCase());
		stateMachine.requestHasBeenReceived(requestVerb, request);
	}

	@Override
	public void processResponse(ResponseEvent responseEvent) {
		Response response = responseEvent.getResponse();
		stateMachine.responseHasBeenReceived(response.getStatusCode(), response);
	}

	@Override
	public void processTimeout(TimeoutEvent timeoutEvent) {
		stateMachine.responseHasBeenReceived(ResponseCode.REQUEST_TIMEOUT, null);
	}

	@Override
	public void processIOException(IOExceptionEvent exceptionEvent) {
		LOGGER.error("SipReceiver: IO exception: " + exceptionEvent.getTransport()
		+ " " + exceptionEvent.getHost() + ":" + exceptionEvent.getPort());
	}

	@Override
	public void processTransactionTerminated(TransactionTerminatedEvent transactionTerminatedEvent) {}

	@Override
	public void processDialogTerminated(DialogTerminatedEvent dialogTerminatedEvent) {}

}
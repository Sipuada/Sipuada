package org.github.sipuada.events;

public class SendingInformationFailed {

	private final String reason;
	private final String callId;

	public SendingInformationFailed(String reason, String callId) {
		this.reason = reason;
		this.callId = callId;
	}

	public String getReason() {
		return reason;
	}

	public String getCallId() {
		return callId;
	}

}
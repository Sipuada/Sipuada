package org.github.sipuada.requester;

import android.javax.sip.ClientTransaction;
import android.javax.sip.Dialog;
import android.javax.sip.ListeningPoint;
import android.javax.sip.ServerTransaction;
import android.javax.sip.SipProvider;
import android.javax.sip.SipStack;
import android.javax.sip.header.CallIdHeader;

public class SipConnection {
	
	private SipStack sipStack;
	private String localIpAddress;
	private int localSipPort;
	private SipProvider provider;
	private ListeningPoint listeningPoint;
	private ServerTransaction serverTransaction;
	private ClientTransaction clientTransaction;
	private CallIdHeader currentCallId;
	private Dialog currentDialog;
	private long callSequence = 1L;
	
	
	public SipStack getSipStack() {
		return sipStack;
	}

	public void setSipStack(SipStack sipStack) {
		this.sipStack = sipStack;
	}
	
	public String getLocalIpAddress() {
		return localIpAddress;
	}

	public void setLocalIpAddress(String localIpAddress) {
		this.localIpAddress = localIpAddress;
	}

	public int getLocalSipPort() {
		return localSipPort;
	}

	public void setLocalSipPort(int localSipPort) {
		this.localSipPort = localSipPort;
	}

	public SipProvider getProvider() {
		return provider;
	}

	public void setProvider(SipProvider provider) {
		this.provider = provider;
	}

	public ListeningPoint getListeningPoint() {
		return listeningPoint;
	}

	public void setListeningPoint(ListeningPoint listeningPoint) {
		this.listeningPoint = listeningPoint;
	}

	public ServerTransaction getServerTransaction() {
		return serverTransaction;
	}

	public void setServerTransaction(ServerTransaction serverTransaction) {
		this.serverTransaction = serverTransaction;
	}

	public ClientTransaction getClientTransaction() {
		return clientTransaction;
	}

	public void setClientTransaction(ClientTransaction clientTransaction) {
		this.clientTransaction = clientTransaction;
	}

	public CallIdHeader getCurrentCallId() {
		return currentCallId;
	}

	public void setCurrentCallId(CallIdHeader currentCallId) {
		this.currentCallId = currentCallId;
	}

	public Dialog getCurrentDialog() {
		return currentDialog;
	}

	public void setCurrentDialog(Dialog currentDialog) {
		this.currentDialog = currentDialog;
	}

	public long getCallSequence() {
		return callSequence;
	}

	public void setCallSequence(long callSequence) {
		this.callSequence = callSequence;
	}

	public long incrementCallSequence() {
		return callSequence++;
	}
	

}
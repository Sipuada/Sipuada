package org.github.sipuada.state;

import java.util.HashMap;
import java.util.Map;

import org.github.sipuada.requester.SipRequestVerb;
import org.github.sipuada.state.AbstractSipStateMachine.MessageDirection;

public class SipStateMachineBehavior {

	private static Map<State, Map<MessageDirection, Map<SipRequestVerb, Step>>> resetRequestBehavior;
	private static Map<State, Map<MessageDirection, Map<Integer, Step>>> resetResponseBehavior;
	{
		resetRequestBehavior = new HashMap<>();
		for (State state : State.values()) {
			Map<MessageDirection, Map<SipRequestVerb, Step>> partialBehavior = new HashMap<>();
			partialBehavior.put(MessageDirection.INCOMING, new HashMap<SipRequestVerb, Step>());
			partialBehavior.put(MessageDirection.OUTGOING, new HashMap<SipRequestVerb, Step>());
			resetRequestBehavior.put(state, partialBehavior);
		}
		resetResponseBehavior = new HashMap<>();
		for (State state : State.values()) {
			Map<MessageDirection, Map<Integer, Step>> partialBehavior = new HashMap<>();
			partialBehavior.put(MessageDirection.INCOMING, new HashMap<Integer, Step>());
			partialBehavior.put(MessageDirection.OUTGOING, new HashMap<Integer, Step>());
			resetResponseBehavior.put(state, partialBehavior);
		}
	}
	
	protected class During {
		
		private final State currentState;
		
		public During(State current) {
			currentState = current;
		}
		
		public WhenRequest whenRequest(MessageDirection direction) {
			return new WhenRequest(currentState, direction);
		}
		
		public WhenResponse whenResponse(MessageDirection direction) {
			return new WhenResponse(currentState, direction);
		}
		
	}

	protected class WhenRequest {

		private final State currentState;
		private final MessageDirection requestDirection;

		public WhenRequest(State current, MessageDirection direction) {
			currentState = current;
			requestDirection = direction;
		}
		
		public WithVerb with(SipRequestVerb verb) {
			return new WithVerb(currentState, requestDirection, verb);
		}

	}
	
	protected class WhenResponse {

		private final State currentState;
		private final MessageDirection responseDirection;

		public WhenResponse(State current, MessageDirection direction) {
			currentState = current;
			responseDirection = direction;
		}
		
		public WithCode with(int code) {
			return new WithCode(currentState, responseDirection, code);
		}

	}
	
	protected class WithVerb {

		private final State currentState;
		private final MessageDirection requestDirection;
		private final SipRequestVerb requestVerb;

		public WithVerb(State current, MessageDirection direction, SipRequestVerb verb) {
			currentState = current;
			requestDirection = direction;
			requestVerb = verb;
		}
		
		public Step goTo(State newState) {
			return new Step(currentState, requestDirection, requestVerb, newState);
		}

	}

	protected class WithCode {

		private final State currentState;
		private final MessageDirection responseDirection;
		private final int responseCode;

		public WithCode(State current, MessageDirection direction, int code) {
			currentState = current;
			responseDirection = direction;
			responseCode = code;
		}
		
		public Step goTo(State newState) {
			return new Step(currentState, responseDirection, responseCode, newState);
		}

	}
	
	protected class Step {

		private final State currentState;
		private final MessageDirection actionDirection;
		private final State newState;
		private SipRequestVerb requestVerb;
		private Integer responseCode;
		private boolean allowAction;
		private SipRequestVerb followUpRequestVerb;
		private Integer followUpResponseCode;
		
		public Step(State current, MessageDirection direction, SipRequestVerb verb, State brandnew) {
			this(current, direction, brandnew, verb, null, true, null,  null);
		}
		
		public Step(State current, MessageDirection direction, int code, State brandnew) {
			this(current, direction, brandnew, null, code, true, null,  null);
		}
		
		private Step(State current, MessageDirection direction, State brandnew, SipRequestVerb verb,
				Integer code, boolean allow, SipRequestVerb followUpRequest, Integer followUpResponse) {
			currentState = current;
			actionDirection = direction;
			newState = brandnew;
			requestVerb = verb;
			responseCode = code;
			allowAction = allow;
			followUpRequestVerb = followUpRequest;
			followUpResponseCode = followUpResponse;
			updateBehavior();
		}
		
		public Step andAllowAction() {
			allowAction = true;
			updateBehavior();
			return this;
		}
		
		public Step andDontAllowAction() {
			allowAction = false;
			updateBehavior();
			return this;
		}
		
		public Step thenSendRequest(SipRequestVerb followUpRequest) {
			followUpRequestVerb = followUpRequest;
			updateBehavior();
			return this;
		}

		public Step thenSendResponse(int followUpResponse) {
			followUpResponseCode = followUpResponse;
			updateBehavior();
			return this;
		}
		
		private void updateBehavior() {
			if (requestVerb != null) {
				SipStateMachineBehavior.this.record(currentState, actionDirection, requestVerb, this);
			}
			else if (responseCode != null) {
				SipStateMachineBehavior.this.record(currentState, actionDirection, responseCode, this);
			}
		}
		
		public boolean actionIsAllowed() {
			return allowAction;
		}
		
		public boolean hasFollowUpRequest() {
			return followUpRequestVerb != null;
		}
		
		public SipRequestVerb getFollowUpRequestVerb() {
			return followUpRequestVerb;
		}
		
		public boolean hasFollowUpResponse() {
			return followUpResponseCode != null;
		}
		
		public Integer getFollowUpResponseCode() {
			return followUpResponseCode;
		}

		public State getNextState() {
			return newState;
		}

	}
	
	private Map<State, Map<MessageDirection, Map<SipRequestVerb, Step>>> requestBehavior;
	private Map<State, Map<MessageDirection, Map<Integer, Step>>> responseBehavior;
	
	public SipStateMachineBehavior(){
		requestBehavior = new HashMap<>();
		requestBehavior.putAll(resetRequestBehavior);
		responseBehavior = new HashMap<>();
		responseBehavior.putAll(resetResponseBehavior);
	}
	
	public During during(State currentState) {
		return new During(currentState);
	}
	
	public void record(State currentState, MessageDirection actionDirection, SipRequestVerb requestVerb, Step nextStep) {
		requestBehavior.get(currentState).get(actionDirection).put(requestVerb, nextStep);
	}

	public void record(State currentState, MessageDirection actionDirection, int responseCode, Step nextStep) {
		responseBehavior.get(currentState).get(actionDirection).put(responseCode, nextStep);
	}
	
	public Step computeNextStepAfterRequest(State currentState, MessageDirection direction, SipRequestVerb requestVerb) {
		return requestBehavior.get(currentState).get(direction).get(requestVerb);
	}
	
	public Step computeNextStepAfterResponse(State currentState, MessageDirection direction, int responseCode) {
		return requestBehavior.get(currentState).get(direction).get(responseCode);
	}

}
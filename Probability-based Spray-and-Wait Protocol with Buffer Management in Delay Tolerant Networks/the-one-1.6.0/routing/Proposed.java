/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;
//按概率分配副本数
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import util.Tuple;

/**
 * Implementation of Spray and wait router as depicted in 
 * <I>Spray and Wait: An Efficient Routing Scheme for Intermittently
 * Connected Mobile Networks</I> by Thrasyvoulos Spyropoulus et al.
 *
 */
public class Proposed extends ActiveRouter {
	
	public static final double P_INIT = 0.95;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.95;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.8;
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
    public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
    /**
	 * Transitivity scaling constant (beta) -setting id ({@value}).
	 * Default value for setting is {@link #DEFAULT_BETA}.
	 */
	public static final String BETA_S = "beta";
	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;
	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String Proposed_NS = "Proposed";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = Proposed_NS + "." +
		"copies";
	public static final String UTILITY = Proposed_NS + "." +
			"utility";
	
	
	protected int initialNrofCopies;
	protected boolean isBinary;

	public Proposed(Settings s) {
		super(s);
		Settings sawSettings = new Settings(Proposed_NS);
		
		initialNrofCopies = sawSettings.getInt(NROF_COPIES);
		isBinary = sawSettings.getBoolean( BINARY_MODE);
		secondsInTimeUnit=sawSettings.getInt(SECONDS_IN_UNIT_S);
		if (sawSettings.contains(BETA_S)) {
			beta = sawSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected Proposed(Proposed r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		this.secondsInTimeUnit=r.secondsInTimeUnit;
		this.beta=r.beta;
		this.preds=new HashMap<DTNHost,Double>();
	}
	
	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
		}
	}
	

	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		preds.put(host, newValue);
	}
	/**
	 * Returns the current prediction (P) value for a host or 0 if entry for
	 * the host doesn't exist.
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(DTNHost host) {
		ageDeliveryPreds(); // make sure preds are updated before getting
		if (preds.containsKey(host)) {
			return preds.get(host);
		}
		else {
			return 0;
		}
	}
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host) {
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof ProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((Proposed)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue() * beta;
			preds.put(e.getKey(), pNew);
		}
	}
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds() {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 
			secondsInTimeUnit;
		
		if (timeDiff == 0) {
			return;
		}
		
		double mult = Math.pow(GAMMA, timeDiff);
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			e.setValue(e.getValue()*mult);
		}
		
		this.lastAgeUpdate = SimClock.getTime();
	}
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {//接收方
		Message msg = super.messageTransferred(id, from);
		Proposed othRouter = (Proposed)from.getRouter();
		//此时是发送端没有分配前的总副本数
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		assert nrofCopies != null : "Not a SnW message: " + msg;
		if (isBinary) {
				Message msg1 = othRouter.getMessage(id);
				if(msg1!=null){
					Integer remaining = (Integer)msg1.getProperty(MSG_COUNT_PROPERTY);
					nrofCopies=nrofCopies-remaining;//发送的副本数
				}
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		//System.out.println("!!!!消息："+msg+"副本数："+nrofCopies);
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		msg.addProperty(UTILITY, new Double(0));
		addToMessages(msg, true);
		return true;
	}
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() || isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}

		/* try messages that could be delivered to final recipient */
		if (exchangeDeliverableMessages() != null) {
			return;
			
		}
		/* create a list of SAWMessages that have copies left to distribute */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		//@SuppressWarnings(value = "unchecked")
		//List<Message> copiesOne = sortByQueueMode(getMessagesWithCopiesOne());//喷射阶段
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
		//if(copiesOne.size()>0) {
		//	this.tryMessagesToConnectionsWait(copiesOne, getConnections());
		//}
		
	}
	
	/**
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();
		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
		}
		return list;
	}
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies == 1) {
				list.add(m);
			}
		}
		return list;
	}
	
	protected Connection tryMessagesToConnections(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started = tryAllMessages(con, messages); 
			if (started != null) { 
				return con;
			}
		}
		
		return null;
	}
	
	protected Message tryAllMessages(Connection con, List<Message> messages) {
		for (Message m : messages) {
			DTNHost other = con.getOtherNode(getHost());
			Proposed otherRouter = (Proposed)other.getRouter();
			if (otherRouter.hasMessage(m.getId())) {
				continue; // skip messages that the other one has
			}
				double p1=getPredFor(m.getTo());
				double p2=otherRouter.getPredFor(m.getTo());
				if(p1<p2) {
					int retVal = startTransfer(m, con); 
					if (retVal == RCV_OK) {
						m.updateProperty(UTILITY, p2/(p1+p2));
						return m;	// accepted a message, don't try others
					}
				else if (retVal > 0) { 
					return null; // should try later -> don't bother trying others
				}
				}
			}
			return null; // no message was accepted		
	}
	
	
	protected Connection tryMessagesToConnectionsWait(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started = tryAllMessagesWait(con, messages); 
			if (started != null) { 
				return con;
			}
		}
		
		return null;
	}
	
	protected Message tryAllMessagesWait(Connection con, List<Message> messages) {
		for (Message m : messages) {
			DTNHost other = con.getOtherNode(getHost());
			Proposed otherRouter = (Proposed)other.getRouter();
			double p1=getPredFor(m.getTo());
			double p2=otherRouter.getPredFor(m.getTo());
			if(p1<p2) {
				int retVal = startTransfer(m, con); 
				if (retVal == RCV_OK) {
					return m;	// accepted a message, don't try others
				}
			else if (retVal > 0) { 
				return null; // should try later -> don't bother trying others
			}
			}
		}
		
		return null; // no message was accepted		
	}
	/**
	 * Called just before a transfer is finalized (by 
	 * {@link ActiveRouter#update()}).
	 * Reduces the number of copies we have left for a message. 
	 * In binary Spray and Wait, sending host is left with floor(n/2) copies,
	 * but in standard mode, nrof copies left is reduced by one. 
	 */
	@Override
	protected void transferDone(Connection con) {
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);
		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		double utility=(Double)msg.getProperty(UTILITY);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
			if (isBinary) { 
				if(msg.getTo()==con.getOtherNode(getHost())){
					nrofCopies =0;
				}
				else {
					nrofCopies =nrofCopies-(int)Math.floor((utility)*nrofCopies);	
				}
			}	
		if(nrofCopies==0) {
			this.deleteMessage(msgId, false);
		}
		else {
			msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		}	
	}
	
	@Override
	public Proposed replicate() {
		return new Proposed(this);
	}
}

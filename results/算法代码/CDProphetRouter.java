/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;
/**DTN中考虑连接时间的概率路由算法*/
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import routing.util.RoutingInfo;

import util.Tuple;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;
import java.util.List;
/**
 * Implementation of PRoPHET router as described in 
 * <I>Probabilistic routing in intermittently connected networks</I> by
 * Anders Lindgren et al.
 */
public class CDProphetRouter extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	public static final double c = 1.1;
	
	/** Prophet router's setting namespace ({@value})*/ 
	public static final String PROPHET_NS = "ProphetRouter";
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
	
	/**节点相遇持续时间=当前连接断开时间-连接开始时间*/
	private Map<DTNHost, Double> encounterDuration;
	/**节点连接开始时间*/
	private Map<DTNHost, Double> startTime;
	/**节点连接断开时间*/
	private Map<DTNHost, Double> endTime;
	/**节点与其他所有节点相遇持续时间*/
	private Map<DTNHost, Double> encounterAllDuration;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public CDProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}

		initParameters();
	}

	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected CDProphetRouter(CDProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		initParameters();
	}
	


	private void initParameters() {
		this.encounterDuration = new HashMap<DTNHost, Double>();
		this.startTime = new HashMap<DTNHost, Double>();
		this.endTime = new HashMap<DTNHost, Double>();
		this.encounterAllDuration = new HashMap<DTNHost, Double>();
	}
	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {/**连接建立(连接状态改变)*/
			DTNHost otherHost = con.getOtherNode(getHost());
			/**记录当前连接开始时间和相遇节点*/
			startTime.put(otherHost, SimClock.getTime());
			Collection<Message> msgCollection = getMessageCollection();
			for(Message m:msgCollection) {
				updateDeliveryPredFor(getHost(),m);
				updateTransitivePreds(getHost(),otherHost,m);
			}
		
		}
		else {/**连接断开(连接状态改变)*/
			DTNHost otherHost = con.getOtherNode(getHost());
			/**记录当前连接断开时间和相遇节点*/
			endTime.put(otherHost, SimClock.getTime());
			/**节点相遇持续时间*/
			encounterDuration.put(otherHost, 
					this.getDurationFor(otherHost) + 
					endTime.get(otherHost) - startTime.get(otherHost));
			encounterAllDuration.put(getHost(),this.getAllDurationFor(getHost())
					+endTime.get(otherHost)-startTime.get(otherHost));
		}
	}
	
	/** 返回当前节点与所有其它节点的相遇持续时间*/
	private double getAllDurationFor(DTNHost host) {
		// TODO Auto-generated method stub
		if (encounterAllDuration.containsKey(host)) {
			return encounterAllDuration.get(host);
		}
		else {
			return 0;
		}
	}

	/** 返回当前节点与某个节点的相遇持续时间*/
	private double getDurationFor(DTNHost host) {
		// TODO Auto-generated method stub
		if (encounterDuration.containsKey(host)) {
			return encounterDuration.get(host);
		}
		else {
			return 0;
		}
	}


	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * P_INIT</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host,Message m) {
		double oldValue=getPredFor(m);
		double newValue = oldValue + (1 - oldValue) * P_INIT 
		* Math.pow(c, getDurationFor(m.getTo())
		/((getAllDurationFor(host)+getAllDurationFor(m.getTo()))/2));
		m.setMessageDeliveryProb(newValue);
	}
	
	/**
	 * Returns the current prediction (P) value for a host or 0 if entry for
	 * the host doesn't exist.
	 * @param host The host to look the P for
	 * @return the current P value
	 */
	public double getPredFor(Message m) {
		ageDeliveryPreds(m); // make sure preds are updated before getting
		return m.getMessageDeliveryProb();
	}
	
	/**
	 * Updates transitive (A->B->C) delivery predictions.
	 * <CODE>P(a,c) = P(a,c)_old + (1 - P(a,c)_old) * P(a,b) * P(b,c) * BETA
	 * </CODE>
	 * @param host The B host who we just met
	 */
	private void updateTransitivePreds(DTNHost host,DTNHost otherHost,Message m) {
		MessageRouter otherRouter = host.getRouter();
		assert otherRouter instanceof CDProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double prd = getPredFor(m); // P(r,d)
		double prc=0;
		Collection<Message> msgCollection = host.getMessageCollection();
		for(Message m1:msgCollection) {
			if(m1.getTo().equals(otherHost)) {
				prc=getPredFor(m1);
			}
		}
		double pcd=0;
		Collection<Message> msgCollection1 = otherHost.getMessageCollection();
		for(Message m2:msgCollection) {
			if(m2.getTo().equals(m.getTo())) {
				pcd=getPredFor(m2);
			}
		}
			double pNew = prd + ( 1 - prd) * prc * pcd * beta;
			m.setMessageDeliveryProb(pNew);
		}

	/**
	 * Ages all entries in the delivery predictions.
	 * <CODE>P(a,b) = P(a,b)_old * (GAMMA ^ k)</CODE>, where k is number of
	 * time units that have elapsed since the last time the metric was aged.
	 * @see #SECONDS_IN_UNIT_S
	 */
	private void ageDeliveryPreds(Message m) {
		double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 
			secondsInTimeUnit;
		
		if (timeDiff == 0) {
			return;
		}
		
		double mult = Math.pow(GAMMA, timeDiff);
		m.setMessageDeliveryProb(m.getMessageDeliveryProb()*mult);
		this.lastAgeUpdate = SimClock.getTime();
	}
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	
	@Override
	public void update() {
		super.update();
		if (!canStartTransfer() ||isTransferring()) {
			return; // nothing to transfer or is currently transferring 
		}
		
		// try messages that could be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return;
		}
		
		tryOtherMessages();		
	}
	
	/**
	 * Tries to send all other messages to all connected hosts ordered by
	 * their delivery probability
	 * @return The return value of {@link #tryMessagesForConnected(List)}
	 */
	private Tuple<Message, Connection> tryOtherMessages() {
		List<Tuple<Message, Connection>> messages = 
			new ArrayList<Tuple<Message, Connection>>(); 
		Collection<Message> msgCollection = getMessageCollection();
		double pcd=0;
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			Collection<Message> msgCollection1 = other.getMessageCollection();
			CDProphetRouter othRouter = (CDProphetRouter)other.getRouter();
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}
				for(Message m1:msgCollection1) {
					if(m1.getTo().equals(m.getTo())) {
						pcd=getPredFor(m1);
					}
				}
				if (getPredFor(m) < pcd) {
					// the other node has higher probability of delivery
					messages.add(new Tuple<Message, Connection>(m,con));
				}
			}			
		}
		
		if (messages.size() == 0) {
			return null;
		}
		
		// sort the message-connection tuples
		Collections.sort(messages, new TupleComparator());
		return tryMessagesForConnected(messages);	// try to send messages
	}
	
	/**
	 * Comparator for Message-Connection-Tuples that orders the tuples by
	 * their delivery probability by the host on the other side of the 
	 * connection (GRTRMax)
	 */
	private class TupleComparator implements Comparator 
		<Tuple<Message, Connection>> {

		public int compare(Tuple<Message, Connection> tuple1,
				Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = getPredFor(tuple1.getKey());
			// -"- tuple2...
			double p2 = getPredFor(tuple2.getKey());
			// bigger probability should come first
			if (p2-p1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			}
			else if (p2-p1 < 0) {
				return 1;
			}
			else {
				return -1;
			}
		}
	}
	
	@Override
	public MessageRouter replicate() {
		CDProphetRouter r = new CDProphetRouter(this);
		return r;
	}

}

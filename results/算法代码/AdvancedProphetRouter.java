/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;
/**Advanced PROPHET Routing in Delay Tolerant Network*/
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
public class AdvancedProphetRouter extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	
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

	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/**节点相遇持续时间=当前连接断开时间-连接开始时间*/
	private Map<DTNHost, Double> encounterDuration;
	/**节点连接开始时间*/
	private Map<DTNHost, Double> startTime;
	/**节点连接断开时间*/
	private Map<DTNHost, Double> endTime;
	/**节点相遇集合及相遇次数*/
	private Map<DTNHost, Integer> encounterArray;
	/**节点相遇时间间隔=当前连接开始时间-上一次连接开始时间*/
	private Map<DTNHost, Double> encounterIntervalTime;
	/**节点上一次连接开始时间(即节点上次相遇时间)*/
	private double lastEncounterTime;
	/**存放相遇时间间隔的有序数组*/
	private List<Double> list;
	/**节点第几次与前一次相遇的时间间隔*/
	private Map<DTNHost,List<Double> > allEncounterTime;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public AdvancedProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}

		initPreds();
		initParameters();
	}

	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected AdvancedProphetRouter(AdvancedProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		initPreds();
		initParameters();
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}
	private void initParameters() {
		this.encounterDuration = new HashMap<DTNHost, Double>();
		this.startTime = new HashMap<DTNHost, Double>();
		this.endTime = new HashMap<DTNHost, Double>();
		this.encounterArray = new HashMap<DTNHost, Integer>();
		this.encounterIntervalTime = new HashMap<DTNHost, Double>();
		this.list=new ArrayList<Double>();
		this.allEncounterTime=new HashMap<DTNHost, List<Double>>();
		this.lastEncounterTime= SimClock.getTime();
	}
	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {/**连接建立(连接状态改变)*/
			DTNHost otherHost = con.getOtherNode(getHost());
			/** 更新当前节点与其他节点的相遇次数 */
			updateEncounterArray(otherHost);
			/**记录当前连接开始时间和相遇节点*/
			startTime.put(otherHost, SimClock.getTime());
			/**更新节点相遇间隔时间*/
			updateEncounterIntervalTime(otherHost);	
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
		}
		else {/**连接断开(连接状态改变)*/
			DTNHost otherHost = con.getOtherNode(getHost());
			/**记录当前连接断开时间和相遇节点*/
			endTime.put(otherHost, SimClock.getTime());
			/**节点相遇持续时间*/
			encounterDuration.put(otherHost, 
					this.getDurationFor(otherHost) + 
					endTime.get(otherHost) - startTime.get(otherHost));
		}
	}
	
	private void updateEncounterIntervalTime(DTNHost otherHost) {
		// TODO Auto-generated method stub
		double interval = SimClock.getTime() - this.lastEncounterTime;
		this.encounterIntervalTime.put(otherHost, interval);
		this.lastEncounterTime = SimClock.getTime();
		this.list.add(interval);
		allEncounterTime.put(otherHost, this.list);
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
	
/**更新节点相遇次数*/
	private void updateEncounterArray(DTNHost host) {
		// TODO Auto-generated method stub
		int oldValue = getEncounterNum(host);
		encounterArray.put(host, oldValue+1);
	}

/**返回节点相遇次数*/
	private int getEncounterNum(DTNHost host) {
	// TODO Auto-generated method stub
		if (encounterArray.containsKey(host)) {
			return encounterArray.get(host);
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
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);
		double newValue = oldValue + (1 - oldValue) * P_INIT;
		double avgValue;
		if(encounterArray.get(host)==1) {
			avgValue=newValue;
		}
		else {
			int num=encounterArray.get(host);
			double t1=allEncounterTime.get(host).get(num-2);
			double t2=allEncounterTime.get(host).get(num-1);
			avgValue=(oldValue*t1+newValue*t2)/(t1+t2);
		}
		preds.put(host, avgValue);
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
		assert otherRouter instanceof AdvancedProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((AdvancedProphetRouter)otherRouter).getDeliveryPreds();
		
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
	
	/**
	 * Returns a map of this router's delivery predictions
	 * @return a map of this router's delivery predictions
	 */
	private Map<DTNHost, Double> getDeliveryPreds() {
		ageDeliveryPreds(); // make sure the aging is done
		return this.preds;
	}
	
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
		
		/* for all connected hosts collect all messages that have a higher
		   probability of delivery by the other host */
		for (Connection con : getConnections()) {
			DTNHost other = con.getOtherNode(getHost());
			AdvancedProphetRouter othRouter = (AdvancedProphetRouter)other.getRouter();
			
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			
			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}
				if (othRouter.getPredFor(m.getTo()) > getPredFor(m.getTo())) {
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
			double p1 = ((AdvancedProphetRouter)tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((AdvancedProphetRouter)tuple2.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple2.getKey().getTo());

			// bigger probability should come first
			if (p2-p1 == 0) {
				/* equal probabilities -> let queue mode decide */
				return compareByQueueMode(tuple1.getKey(), tuple2.getKey());
			}
			else if (p2-p1 < 0) {
				return -1;
			}
			else {
				return 1;
			}
		}
	}
	
	@Override
	public RoutingInfo getRoutingInfo() {
		ageDeliveryPreds();
		RoutingInfo top = super.getRoutingInfo();
		RoutingInfo ri = new RoutingInfo(preds.size() + 
				" delivery prediction(s)");
		
		for (Map.Entry<DTNHost, Double> e : preds.entrySet()) {
			DTNHost host = e.getKey();
			Double value = e.getValue();
			
			ri.addMoreInfo(new RoutingInfo(String.format("%s : %.6f", 
					host, value)));
		}
		
		top.addMoreInfo(ri);
		return top;
	}
	
	@Override
	public MessageRouter replicate() {
		AdvancedProphetRouter r = new AdvancedProphetRouter(this);
		return r;
	}

}

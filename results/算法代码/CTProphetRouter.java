
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import routing.util.RoutingInfo;

import util.Tuple;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * 2019-10-30 基于Prophet算法，使用消息大小，消息跳数，节点缓存，节点相遇持续时间对公式进行改变
 * 使用节点相遇持续时间，对公式进行变化
 * 2019-11-03 添加ACK机制删除冗余消息
 * 2019-11-12 根据文献：时延容忍网络中基于效用转发的自适应机会路由算法进行改变Prophet算法的公式
 * 主要引入节点频繁度、节点关联度、节点相似度、节点中心度等
 */
public class CTProphetRouter extends ActiveRouter {
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
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	
	/** 节点相遇持续时间=结束时间-开始时间*/
	private Map<DTNHost, Double> meetTime;
	/** 节点连接开始时间*/
	private Map<DTNHost, Double> startTime;
	/** 节点连接结束时间*/
	private Map<DTNHost, Double> endTime;
	
	/** message acknowledge ID set */
	private Set<String> ackedMessageIds; 
	
	/**2019-11-12 添加*/
	/** 记录节点相遇集合与相遇次数*/
	private Map<DTNHost, Integer> freqs;
	/** 节点相遇时间间隔=当前时间 - 上一次相遇的时间 */
	private Map<DTNHost, Double> meetIntervalTime;
	/** 节点上一次相遇的时间 */
	private double lastMeetTime;
	
	private double ALPHA = 0.75;
	private Map<DTNHost, Double> utilities;
	
	private Set<DTNHost> sims;
	
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SPRAYANDWAIT_NS = "SprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." +
		"copies";
	protected int initialNrofCopies;

	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public CTProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);		
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		
		initPreds();
		initTimes();
		initFreqs();
		initUtilities();
	}

	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected CTProphetRouter(CTProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		this.initialNrofCopies = r.initialNrofCopies;
		
		initPreds();
		initTimes();
		initFreqs();
		initAck();
		initUtilities();
	}
	
	private void initAck() {
		this.ackedMessageIds = new HashSet<String>();
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}

	private void initTimes() {
		this.meetTime = new HashMap<DTNHost, Double>();
		this.startTime = new HashMap<DTNHost, Double>();
		this.endTime = new HashMap<DTNHost, Double>();
		this.meetIntervalTime = new HashMap<DTNHost, Double>();
	}
	
	private void initUtilities() {
		this.utilities = new HashMap<DTNHost, Double>();
	}
	
	private void initFreqs() {
		this.freqs = new HashMap<DTNHost, Integer>();
	}
	
	
	
	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			if(con.isInitiator(getHost())) {
				CTProphetRouter otherRouter = (CTProphetRouter)otherHost.getRouter();
				/** exchange ACK message data */
				this.ackedMessageIds.addAll(otherRouter.ackedMessageIds);
				otherRouter.ackedMessageIds.addAll(this.ackedMessageIds);
				deleteAckedMessage();
				this.deleteAckedMessage();
			}
			/** 更新当前节点与其他节点的相遇次数 */
			updateFreqsFor(otherHost);
			//updateUtility(otherHost);
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
			startTime.put(otherHost, SimClock.getTime());
			
			/**2019-11-13更新当前节点与其他节点的相遇时间间隔*/
			updateMeetIntervalTime(otherHost);
		}
		else {
			DTNHost otherHost = con.getOtherNode(getHost());
			endTime.put(otherHost, SimClock.getTime());
			meetTime.put(otherHost, 
					this.getTimeFor(otherHost) + 
					endTime.get(otherHost) - startTime.get(otherHost));
		}
	}
	
	/**
	 * @param otherHost
	 */
	private void updateMeetIntervalTime(DTNHost otherHost) {
		// TODO Auto-generated method stub
		double interval = SimClock.getTime() - this.lastMeetTime;
		if (interval > 7200) {
			// 清空相遇列表,当设置为7200时，缓存为2-10时候运行结果与没有清空相同
			//freqs.clear();
			java.util.Iterator<DTNHost> iterator = freqs.keySet().iterator();
			while (iterator.hasNext()) {
				DTNHost key = (DTNHost) iterator.next();
				if (key == otherHost) {
					iterator.remove();
				}
			}			
		} else {
			this.meetIntervalTime.put(otherHost, interval);
		}
		//this.meetIntervalTime.put(otherHost, interval);
		this.lastMeetTime = SimClock.getTime();
	}

	/** Delete the messages from the message buffer that are known to be acknowledged */
	private void deleteAckedMessage() {
		for(String msgId : this.ackedMessageIds) {
			if (this.hasMessage(msgId) && !isSending(msgId)) {
				this.deleteMessage(msgId, false);
			}
		}
	}
	
	/**2019-11-15 
	 * 返回节点相似度，
	 * 这里定义节点相似度为，两个节点相遇集合中相交的节点个数 / 两个节点的总数
	 * 即 交集的个数 / 并集的个数
	 * 遍历两个集合，找出两个集合相交的节点的个数
	 * */
	private double getSimilarFor(DTNHost host) {
		int simNumber = 0;
		MessageRouter otherRouter = host.getRouter();
		Map<DTNHost, Integer> othersFreqs = 
				((CTProphetRouter)otherRouter).getFrequency();
		for (Map.Entry<DTNHost, Integer> e : othersFreqs.entrySet()) {
			for (Map.Entry<DTNHost, Integer> fMap : freqs.entrySet()) {
				if (e.getKey() == fMap.getKey()) {
					simNumber++;
				}
			}
		}
		int count = othersFreqs.size() + freqs.size() - simNumber;
		return simNumber * 1.0 / count;
	}
	
	/**2019-11-12 添加*/
	/**Returns the number of encounters between the current node and other nodes*/
	public int getFreqFor(DTNHost host) {
		if (freqs.containsKey(host)) {
			return freqs.get(host);
		}
		else {
			return 0;
		}
	}
	
	/**Returns the number of encounters between the current node and All other nodes sum */
	public int getAllFreqFor() {
		int sumCount = 0;
		for(int dValue: freqs.values()) {
			sumCount += dValue;
		}
		return sumCount;
	}
	
	private Map<DTNHost, Integer> getFrequency() {
		return this.freqs;
	}
	
	/** @param host The host we just met */
	private void updateFreqsFor(DTNHost host) {
		int oldValue = getFreqFor(host);
		freqs.put(host, oldValue+1);
	}
	
	/**节点相遇次数的平均值*/
	private double avgFreqsFor(DTNHost host) {
		double average = 0;
		if (freqs.size() != 0) {
			average = getAllFreqFor() / freqs.size();
		}
		return average;
		
	}
	
	/** 返回当前节点与某个节点的相遇持续时间*/
	public double getTimeFor(DTNHost host) {
		if (meetTime.containsKey(host)) {
			return meetTime.get(host);
		}
		else {
			return 0;
		}
	}
	
	/** 返回当前节点与所有节点的相遇持续时间*/
	public double getTimeAllFor() {
		double sum = 0;
		for(double dValue : meetTime.values()) {
			sum += dValue;
		}
		return sum;
	}
	
	/** 返回节点平均相遇持续时间*/
	private double getAvgMeetTime() {
		double averageMeetTime = 0;
		if (getTimeAllFor() != 0) {
			averageMeetTime = getTimeAllFor() / meetTime.size();
		}
		return averageMeetTime;		
	}
	
	/** 返回当前节点与某个节点的相遇时间间隔*/
	public double getIntervalTimeFor(DTNHost host) {
		if (meetIntervalTime.containsKey(host)) {
			return meetIntervalTime.get(host);
		}
		else {
			return 0;
		}
	}
	
	private void updateUtility(DTNHost host) {
		if (getFreqFor(host) != 0 || getPredFor(host) != 0) {
			double fValue = getFreqFor(host) / getAllFreqFor();
			double uValue = ALPHA * getPredFor(host) + (1-ALPHA) * fValue;
			utilities.put(host, uValue);
		}
	}
	
	public double getUtilityFor(DTNHost host) {
		if (utilities.containsKey(host)) {
			return utilities.get(host);
		}
		else {
			return 0;
		}
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
		addToMessages(msg, true);
		return true;
	}
	
	
	/**
	 * Called just before a transfer is finalized (by
	 * {@link ActiveRouter#update()}). 
	 * if the message delivered to the final recipient 
	 * then add to ackedMessageIds and delete it from buffer
	 */
	@Override
	protected void transferDone(Connection con) {
		DTNHost recipient = con.getOtherNode(getHost());
		String msgId = con.getMessage().getId();
		Message msg = getMessage(msgId);
		if (msg == null) {
			return;
		}
		if (msg.getTo() == recipient) {
			this.ackedMessageIds.add(msgId);
			this.deleteMessage(msgId, false); 
		}		
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
	
		if (isDeliveredMessage(msg)) {
			this.ackedMessageIds.add(id);
		}
		return msg;
	}
	
	/**
	 * Updates delivery predictions for a host.
	 * <CODE>P(a,b) = P(a,b)_old + (1 - P(a,b)_old) * sim</CODE>
	 * @param host The host we just met
	 */
	private void updateDeliveryPredFor(DTNHost host) {
		double oldValue = getPredFor(host);		
		//double newValue = oldValue + (1 - oldValue) * getSimilarFor(host);
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
		assert otherRouter instanceof CTProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((CTProphetRouter)otherRouter).getDeliveryPreds();
		
		for (Map.Entry<DTNHost, Double> e : othersPreds.entrySet()) {
			if (e.getKey() == getHost()) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + (1 - pOld) * pForHost * e.getValue() * beta;
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
		
		//double timeDiff = getTimeFor(getHost()) / secondsInTimeUnit;
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
			CTProphetRouter othRouter = (CTProphetRouter)other.getRouter();
					
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
				else if (othRouter.getAvgMeetTime() > getAvgMeetTime()) {
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
		
		@Override
		public int compare(Tuple<Message, Connection> tuple1,
				Tuple<Message, Connection> tuple2) {
			// delivery probability of tuple1's message with tuple1's connection
			double p1 = ((CTProphetRouter)tuple1.getValue().
					getOtherNode(getHost()).getRouter()).getPredFor(
					tuple1.getKey().getTo());
			// -"- tuple2...
			double p2 = ((CTProphetRouter)tuple2.getValue().
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
		CTProphetRouter r = new CTProphetRouter(this);
		return r;
	}

	
	
	
	
}

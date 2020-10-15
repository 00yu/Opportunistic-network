/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimClock;

/**
 * Implementation of Spray and wait router as depicted in 
 * <I>Spray and Wait: An Efficient Routing Scheme for Intermittently
 * Connected Mobile Networks</I> by Thrasyvoulos Spyropoulus et al.
 *
 */
public class AMSW extends ActiveRouter {
	
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	/**
	 * Number of seconds in time unit -setting id ({@value}).
	 * How many seconds one time unit is when calculating aging of 
	 * delivery predictions. Should be tweaked for the scenario.*/
    public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
    private Set<String> ackedMessageIds=new HashSet<String>();
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
	public static final String SPRAY_TIMES = "spray_times";//再次喷射次数(控制喷射次数防止喷射过多)
	
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SAW_NS = "AMSW";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SAW_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;

	public AMSW(Settings s) {
		super(s);
		Settings sawSettings = new Settings(SAW_NS);
		
		initialNrofCopies = sawSettings.getInt(NROF_COPIES);
		isBinary = sawSettings.getBoolean( BINARY_MODE);
		secondsInTimeUnit=sawSettings.getInt(SECONDS_IN_UNIT_S);
		if (sawSettings.contains(BETA_S)) {
			beta = sawSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}
		this.ackedMessageIds = new HashSet<String>();
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected AMSW(AMSW r) {
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
			if(!getHost().getE().contains(otherHost)){
				getHost().getE().add(otherHost);//相遇节点集合的更新
			}
			
			//节点的社交群的更新
			if(!getHost().getEG().contains(otherHost) && (getHost().getSimilar(otherHost)>1/2*Math.min(otherHost.getE().size(), getHost().getE().size()))) {
				getHost().getEG().add(otherHost);
			}
			checkAck(otherHost);
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
			((AMSW)otherRouter).getDeliveryPreds();
		
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
	
	// 更新ACK表并删除多余副本
		private void checkAck(DTNHost otherHost) {

			MessageRouter mRouter = otherHost.getRouter();

			assert mRouter instanceof MaxPropRouter : "MaxProp only works "
					+ " with other routers of same type";
			AMSW otherRouter = (AMSW) mRouter;

			/* exchange ACKed message data */
			if(otherRouter.ackedMessageIds!=null){
				this.ackedMessageIds.addAll(otherRouter.ackedMessageIds);
				otherRouter.ackedMessageIds.addAll(this.ackedMessageIds);

			}
			// otherRouter.ackedMessageIds.addAll(this.ackedMessageIds);

			if(this.ackedMessageIds!=null){
				deleteAckedMessages();
				otherRouter.deleteAckedMessages();
			}
			// otherRouter.deleteAckedMessages();
		}
		private void deleteAckedMessages() {
			for (String id : this.ackedMessageIds) {
				if (this.hasMessage(id) && !isSending(id)) {
					this.deleteMessage(id, false);
				}
			}
		}
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		
		assert nrofCopies != null : "Not a SnW message: " + msg;
		
		if (isBinary) {
			/* in binary S'n'W the receiving node gets ceil(n/2) copies */
			nrofCopies = (int)Math.ceil(nrofCopies/2.0);
		}
		else {
			/* in standard S'n'W the receiving node gets only single copy */
			nrofCopies = 1;
		}
		
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		if (isDeliveredMessage(msg)) {
			this.ackedMessageIds.add(id);
		}
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.addProperty(SPRAY_TIMES,0);//添加喷发启始次数
		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
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
		/**
		 * 1.获得当前的副本数为一的消息
		 * 因为saw是通过消息的副本数量来区分是该消息处于等待阶段
		 * 或是喷发阶段。
		 * 所以这里的方法主要是来筛选出各个阶段的消息，方便后面做不同的处理。
		 * 比如上面的copiesLeft就是喷发阶段的消息,而copiesOne是等待阶段的消息
		 * sortByQueueMode随机排序或FIFO排序(消息接收时间从小到大排序)
		 * MessageRouter中定义消息排序，sortByQueueNewMode按(消息接收时间/跳数)从大到小进行排序
		 */
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesOne = sortByQueueNewMode(getMessagesWithCopiesOne());//等待阶段
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnectionsSpray(copiesLeft, getConnections());
		}
		if(copiesOne.size() > 0){//自写代码	
			this.tryMessagesToConnectionsWait(copiesOne, getConnections());	//可以再次喷射
		}
	}
	//ActiveRouter中代码
	/**
	 * Tries to send all given messages to all given connections. Connections
	 * are first iterated in the order they are in the list and for every
	 * connection, the messages are tried in the order they are in the list.
	 * Once an accepting connection is found, no other connections or messages
	 * are tried.
	 * @param messages The list of Messages to try
	 * @param connections The list of Connections to try
	 * @return The connections that started a transfer or null if no connection
	 * accepted a message.
	 */
	protected Connection tryMessagesToConnections(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=connections.size(); i<n; i++) {//遍历所有连接
			Connection con = connections.get(i);
			Message started = tryAllMessages(con, messages); 
			if (started != null) { 
				return con;
			}
		}
		
		return null;
	}
	/**
	 * Tries to send all messages that this router is carrying to all
	 * connections this node has. Messages are ordered using the 
	 * {@link MessageRouter#sortByQueueMode(List)}. See 
	 * {@link #tryMessagesToConnections(List, List)} for sending details.
	 * @return The connections that started a transfer or null if no connection
	 * accepted a message.
	 */
	protected Connection tryAllMessagesToAllConnections(){
		List<Connection> connections = getConnections();
		if (connections.size() == 0 || this.getNrofMessages() == 0) {
			return null;
		}

		List<Message> messages = 
			new ArrayList<Message>(this.getMessageCollection());
		this.sortByQueueMode(messages);

		return tryMessagesToConnections(messages, connections);
	}
	//自己改代码
	protected Message tryMessagesToConnectionsSpray(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=messages.size(); i<n; i++) {//遍历所有消息
			Message msg = messages.get(i);
			Connection started = tryAllConnection(msg, connections); 
			if (started != null) { 
				return msg;
			}
		}
		return null;
	}
	//等待阶段 需要改写
		protected Connection tryMessagesToConnectionsWait(List<Message> messages,
				List<Connection> connections)  {
			for (int i=0, n=connections.size(); i<n; i++) {
				Connection con = connections.get(i);
				Message started;
				try {
					started = tryAllConnectionWait(con, messages);
					if (started != null) { 
						return con;
					}
				} catch (Exception e) {
					e.printStackTrace();
				} 
				
			}
			
			return null;
		}
	//筛选中继节点
		protected Connection tryAllConnection(Message msg, List<Connection> connections) {
			List<Connection> finalconnections=new ArrayList<Connection> ();
			if(connections.size()<=1)//只有一个连接，消息直接投递给这个连接的对方节点
				finalconnections=connections;
			else//多个连接采用不同的策略筛选出较好的几个连接
				finalconnections=getConnectionByStrategy(msg,connections);
			//给每个连接传输该消息	
	   	     for (Connection con : finalconnections) {  	    	
				int retVal = startTransfer(msg, con); 			
				if (retVal == RCV_OK) {
					return con;	// accepted a message, don't try others
				}else if (retVal > 0) { 
					return null; // should try later -> don't bother trying others
				}			
			}
			return null; // no message was accepted		
		}
		/**
		 * FIN
		 * 根据不同的策略去选择不同的方法选择合适的连接
		 */
		protected List<Connection>  getConnectionByStrategy(Message msg, List<Connection> connections) {
			List<Connection> newConnections=new ArrayList<Connection>();
			DTNHost d=msg.getTo();//消息目的节点信息
			for (Connection con : connections) {
				DTNHost other= con.getOtherNode(getHost());
				if(getHost().getEG().contains(msg.getTo())){//当前节点和目的节点在一个社交群(Prophet)
					AMSW othRouter = (AMSW)other.getRouter();
					//目的节点和相遇节点也在一个社区，若相遇节点到目的节点的可能性越大，则转发
					if(other.getEG().contains(msg.getTo())&&(othRouter.getPredFor(msg.getTo()) > ((AMSW) getHost().getRouter()).getPredFor(msg.getTo()))){
					newConnections.add(con);
					}
				}
				else{//当前节点和目的节点不在一个社交群(地理位置)
					double x1=getHost().getDestination().getX()-getHost().getLocation().getX();
					 double x2=other.getDestination().getX()-other.getLocation().getX();
					 double y1=getHost().getDestination().getY()-getHost().getLocation().getY();
					 double y2=other.getDestination().getY()-other.getLocation().getY();
					 double xd=d.getDestination().getX()-d.getLocation().getX();
					 double yd=d.getDestination().getY()-d.getLocation().getY();
					 double cosa1=(x1*xd+y1*yd)/(Math.sqrt(x1*x1+y1*y1)*Math.sqrt(xd*xd+yd*yd));
					 double cosa2=(x2*xd+y2*yd)/(Math.sqrt(x2*x2+y2*y2)*Math.sqrt(xd*xd+yd*yd));			
					 double d1=Math.sqrt((getHost().getLocation().getX()-d.getLocation().getX())*(getHost().getLocation().getX()-d.getLocation().getX())
									 +(getHost().getLocation().getY()-d.getLocation().getY())*(getHost().getLocation().getY()-d.getLocation().getY()));	
					 double d2=Math.sqrt((other.getLocation().getX()-d.getLocation().getX())*(other.getLocation().getX()-d.getLocation().getX())
									 +(other.getLocation().getY()-d.getLocation().getY())*(other.getLocation().getY()-d.getLocation().getY()));		  
					if(other.getEG().contains(msg.getTo())||(getHost().getSpeed()/(d1*Math.acos(cosa1)))<(other.getSpeed()/(d2*Math.acos(cosa2)))){//相遇的节点和目的节点在一个社交群，或者相遇的节点速度更快

					 newConnections.add(con);
					}
				}
			}
					return newConnections;
			
		}
		protected Message tryAllConnectionWait(Connection con, List<Message> messages) throws Exception
		{//根据效用复制
			DTNHost otherNode =con.getOtherNode(getHost());//获得对方节点
			for (Message m : messages) {
				Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
				Integer copies=(Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到当前节点携带消息副本数
				if( getPredFor(m.getTo())<0.7){//消息投递概率小于阈值
					copies=(int)Math.floor(32*(1-getPredFor(m.getTo())));//获得再次喷射副本数
				    if(copies*m.getSize()>=getFreeBufferSize()){
				    	copies=getFreeBufferSize()/(m.getSize()*2);
				    }
				if(copies>=1){	//只向更好的邻居节点喷发消息						
						if(((AMSW) otherNode.getRouter()).getPredFor(m.getTo()) > getPredFor(m.getTo())){
							m.updateProperty(MSG_COUNT_PROPERTY,copies);//更新再次喷射副本数
							int retVal = startTransfer(m, con); 
							if (retVal == RCV_OK) {
								m.updateProperty(SPRAY_TIMES,sprayTimes+1);//开始持续喷发,喷发次数加1
								return m;	
							}else if (retVal > 0) { 
								return null; 
							}
						}else{//当前节点消息投递概率大于邻居节点消息投递概率
							return null;
						}
				}
				}
			}
			
			return null; // no message was accepted		
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
	/**
	 *节点副本数为一的消息副本的消息列表
	 */
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();
		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";	
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
				if (nrofCopies == 1 &&sprayTimes<2) {//开销大（解决）限制喷射次数为2次
					list.add(m);				
				}
		}	
		return list;
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
		Integer nrofCopies;
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		
		/* reduce the amount of copies left */
		nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		if (isBinary) { 
			nrofCopies /= 2;
		}
		else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}
	
	@Override
	public AMSW replicate() {
		return new AMSW(this);
	}
}

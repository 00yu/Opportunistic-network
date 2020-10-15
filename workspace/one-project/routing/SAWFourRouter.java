/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
public class SAWFourRouter extends ActiveRouter {
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SPRAYANDWAIT_NS = "SprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." +
		"copies";
	public static final String SPRAY_TIMES = "spray_times";
	
	protected int initialNrofCopies;
	protected boolean isBinary;
	
	private Set<String> ackedMessageIds;

	public SAWFourRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
		
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWFourRouter(SAWFourRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		
		this.isBinary = r.isBinary;
		this.ackedMessageIds = new HashSet<String>();
	}
	
	//DTNHOS中调用
	public void changedConnection(Connection con) { 
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
			checkAck(otherHost);
			countUtility( otherHost);
		
	}}
	
	//更新ACK表并删除多余副本
	private void checkAck(DTNHost otherHost) {
	
		MessageRouter mRouter = otherHost.getRouter();

		assert mRouter instanceof MaxPropRouter : "MaxProp only works "+ 
		" with other routers of same type";
		SAWFourRouter otherRouter = (SAWFourRouter)mRouter;
		
		/* exchange ACKed message data */
		this.ackedMessageIds.addAll(otherRouter.ackedMessageIds);
//		otherRouter.ackedMessageIds.addAll(this.ackedMessageIds);
		
		for (String id : this.ackedMessageIds) {
			if (this.hasMessage(id) &&!isSending(id) ) {
				this.deleteMessage(id, false);
			}
		}
		
//		otherRouter.deleteAckedMessages();
	}
		
		
		//更新历史效用值
		private void countUtility(DTNHost otherHost ) {
			
			double timeDiff = (SimClock.getTime()/super.secondsInTimeUnit);
			getHost().updatehu(otherHost,(int)timeDiff);//更新历史效用表
			
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
		/* was this node the final recipient of the message? */
		if (isDeliveredMessage(msg)) {
			this.ackedMessageIds.add(id);
		}
		return msg;
	}

	
	/*重写父类，增加消息副本数量字段*/
	@Override 
	public boolean createNewMessage(Message msg) {
	
		makeRoomForNewMessage(msg.getSize());
	
		msg.setTtl(this.msgTtl);
		msg.addProperty(SPRAY_TIMES,1);//添加喷发启始次数
		msg.addProperty(MSG_COUNT_PROPERTY, initialNrofCopies);
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
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());//需要转发的消息
		@SuppressWarnings(value ="unchecked")
		List<Message> copiesOne = sortByQueueMode(getMessagesWithCopiesOne());//副本数为为一的消息
		
		if(copiesOne.size() > 0)//自写代码
		{
			/* try to send those messages */
			/*向邻居节点分发这些消息*/
				this.tryMessagesToConnectionsSomeCopy(copiesOne, getConnections());
			
		}
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			/*向邻居节点分发这些消息*/
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
	}
	
	/**
	 * 创建并返回当前路由携带和需要转发的消息副本的消息列表（节点副本数>1）
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
			
		}
		
		return list;
	}
	
	/**
	 * 创建并返回当前路由携带的节点副本数为一的消息副本的消息列表
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies = 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();


		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			double buffertime = SimClock.getTime()-m.getReceiveTime();//得到消息副本数量的属性
		
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
		
				if (nrofCopies == 1 && sprayTimes<=2) {
					
					m.updateProperty(SPRAY_TIMES,sprayTimes+1);
					list.add(m);					
				}
		}
		
		return list;
	}
	
	
	/**
	 * 更新节点消息副本数
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
		/*得到该路由的消息副本*/
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
	public SAWFourRouter replicate() {
		return new SAWFourRouter(this);
	}
}

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
public class SAWFinRouter extends ActiveRouter {
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SPRAYANDWAIT_NS = "SprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;

	/** IDs of the messages that are known to have reached the final dst */
	private Set<String> ackedMessageIds;
	
	public SAWFinRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
		//自定义实例化
		
		
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWFinRouter(SAWFinRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		this.ackedMessageIds = new HashSet<String>();
		
	}
	/*我添加的代码*/
	/*
	 * 相遇节点连接建立时更新效用值
	 * 4.这里是当本节点和其他节点刚建立连接时的操作
	 * 比较适合用来交换历史相遇信息，更新效用值，删除一些不需要的消息等等
	 */
	  public void changedConnection(Connection con) { 
		 if (con.isUp()) {
		 DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
		 /**
		  * ACK表，也就是每个节点都有一张ACK表记录投递成功的消息的id
		  * 当两个节点建立连接时，交换ack表做并集，然后各自删除自己缓存中和
		  * ack表中对应的消息
		  */
		 checkAck(otherHost);
		 /**
		  * 更新效用值，主要是节点交换和更新历史相遇信息表
		  */
		 countUtility(otherHost);
	     }
	}
	
	// 更新ACK表并删除多余副本
	private void checkAck(DTNHost otherHost) {

		MessageRouter mRouter = otherHost.getRouter();

		assert mRouter instanceof MaxPropRouter : "MaxProp only works "
				+ " with other routers of same type";
		SAWFinRouter otherRouter = (SAWFinRouter) mRouter;

		/* exchange ACKed message data */
		this.ackedMessageIds.addAll(otherRouter.ackedMessageIds);
		// otherRouter.ackedMessageIds.addAll(this.ackedMessageIds);

		deleteAckedMessages();

		// otherRouter.deleteAckedMessages();
	}

	/**
	 * Deletes the messages from the message buffer that are known to be ACKed
	 */
	private void deleteAckedMessages() {
		for (String id : this.ackedMessageIds) {
			if (this.hasMessage(id) && !isSending(id)) {
				this.deleteMessage(id, false);
			}
		}
	}
	/*
	 * 更新效用值
	 * (连接的两个节点同时被处理)
	 */
	private void countUtility(DTNHost otherHost) {
		DTNHost sourceHost = getHost();// 本地主机

		double timeDiff = (SimClock.getTime() / super.secondsInTimeUnit);
		getHost().updatehu(otherHost, (int) timeDiff);// 主机更新等待历史效用表

		sourceHost.updateFinUtility(sourceHost, otherHost);// 主机更新效用表
	}
	
		
	/***********/
		
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	//更新接收端
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		
		assert nrofCopies != null : "Not a SnW message: " + msg;
		

		if (isBinary) {
			/* in binary S'n'W the receiving node gets ceil(n/2) copies */
			nrofCopies = (int)Math.ceil(nrofCopies/2.0);
		}else {
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
		
		msg.addProperty(SPRAY_TIMES,0);//添加喷发启始次数
		msg.addProperty(SPRAY_FLAG,0);//添加喷发标识0-喷发中 1-启动喷发

//		msg.addProperty(OTHER_COPY,0);//邻居节点副本数
		
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
		//需要转发的消息
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		@SuppressWarnings(value ="unchecked")
		/**
		 * 1.获得未喷发过且当前的副本数为一的消息
		 * 因为saw是通过消息的副本数量来区分是该消息处于等待阶段
		 * 或是喷发阶段。
		 * 所以这里的方法主要是来筛选出各个阶段的消息，方便后面做不同的处理。
		 * 比如上面的copiesLeft就是喷发阶段的消息,而copiesOne是等待阶段的消息
		 */
		List<Message> copiesOne = sortByQueueMode(getMessagesWithCopiesOne());
		//已喷发副本数为为一的消息
		@SuppressWarnings(value ="unchecked")
		List<Message> copiesBin = sortByQueueMode(getMessagesWithCopiesBin());
		
		/**
		 * 2.处理喷发阶段的消息
		 * 下面的三个if是针对上面不同阶段的消息（copiesLeft，copiesOne，copiesBin）
		 * 应用不同的策略去处理,比如下面第一个策略
		 */
		if (copiesLeft.size() > 0) {
			/*向邻居节点分发这些消息*/
			this.tryMessagesToConnectionsFin(copiesLeft, getConnections());
		}
		//等待阶段副本为一再次喷发
		if(copiesOne.size() > 0){//自写代码	

			this.tryMessagesToConnectionsWait(copiesOne, getConnections());	
		}
		//等待阶段副本大于一再次喷发
		if (copiesBin.size() > 0) {
			/*向邻居节点二分分发这些消息*/
			this.tryMessagesToConnectionsWait(copiesBin, getConnections());
		}
		
		//this.tryMessagesToConnectionsSomeCopy(copiesOne, getConnections());
//		this.tryMessagesToConnectionsSomeCopy(copiesBin, getConnections());	
//		this.tryMessagesToConnections(copiesBin, getConnections());
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
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);
			if (nrofCopies > 1 && sprayTimes==0 ) {//
				list.add(m);
			}
		}
		
		return list;
	}
	
   /********我写的代码******/
	
	/**
	 * 创建并返回当前路由携带和需要转发的消息副本的消息列表（节点副本数>1）二进制
	 */
	protected List<Message> getMessagesWithCopiesBin() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);	
			if (nrofCopies > 1 && sprayTimes>0) {
				list.add(m);
			}
		}
		return list;
	}
	
	/**
	 * 创建并返回当前路由携带的节点副本数为一的消息副本的消息列表
	 */
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			double buffertime = SimClock.getTime()-m.getReceiveTime();//得到消息副本数量的属性
		
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
		
				if (nrofCopies == 1 &&sprayTimes<2) {//开销大（解决）
					m.updateProperty(SPRAY_TIMES,sprayTimes+1);
					m.updateProperty(SPRAY_FLAG,1);
					list.add(m);				
				}
		}	
		return list;
	}
	/***********/
	
	/**
	 * 更新节点消息副本数
	 * 传输完成前被调用
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
		
		//自定义分配副本
		
		if (isBinary) { 
			nrofCopies /= 2;
		}else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	
	}
	
	@Override
	public SAWFinRouter replicate() {
		return new SAWFinRouter(this);
	}
}

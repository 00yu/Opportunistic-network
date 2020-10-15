/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.List;

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
public class SAWTwoRouter extends ActiveRouter {
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

	

	public SAWTwoRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
		
		
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWTwoRouter(SAWTwoRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		
	}
	//DTNHOS中调用
	public void changedConnection(Connection con) { 
		if (con.isUp()) {
		
		checkAck(con);
		countUtility( con);
		
	}}
	
	//更新ack表并删除多余副本
	private void checkAck(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
		List<String> hostAck=getHost().getAck();
		List<String> otherAck=otherHost.getAck();
		//更新方法需要优化
		hostAck.removeAll(otherAck);
		hostAck.addAll(otherAck);
		otherAck.clear();
		otherAck.addAll(hostAck);
		//对方节点删除
		for(Message m :otherHost.getMessageCollection()){
			if(m !=null){
				if(otherAck.contains(m.getId())){
//					System.out.println(m.getId()+"需要丢包");
					otherHost.deleteMessage(m.getId(), false);
				}
			}
			
		}
		//我方节点删除
		for(Message m :getHost().getMessageCollection()){
			if(m !=null){
				if(hostAck.contains(m.getId())){
//					System.out.println(m.getId()+"需要丢包");
					getHost().deleteMessage(m.getId(), false);
				}
			}
			
		}
	}
	
	
	//更新历史效用值
	private void countUtility(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
		double timeDiff = (SimClock.getTime()/super.secondsInTimeUnit);
		getHost().updatehu(otherHost,(int)timeDiff);//主机更新历史效用表
		otherHost.updatehu(getHost(),(int)timeDiff);//对方更新历史效用表
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
		return msg;
	}

	
	/*重写父类，增加消息副本数量字段*/
	@Override 
	public boolean createNewMessage(Message msg) {
		
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
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
			//System.out.println(copiesOne);
			this.tryMessagesToConnectionsSome(copiesOne, getConnections());
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
			Integer ttlover = m.getTtl();//得到消息副本数量的生成时间
			//System.out.println(ttlover);
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies == 1) {
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
	public SAWTwoRouter replicate() {
		return new SAWTwoRouter(this);
	}
}

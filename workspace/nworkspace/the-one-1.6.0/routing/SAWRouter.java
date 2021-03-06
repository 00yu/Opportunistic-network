/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

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
public class SAWRouter extends ActiveRouter {
	
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SPRAYANDWAIT_NS = "SAWRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;

	public SAWRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWRouter(SAWRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
	}
	/**
	 * Initializes predictability hash
	 */
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	
	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {/**连接建立(连接状态改变)*/
			DTNHost otherHost = con.getOtherNode(getHost());
			/**记录当前连接开始时间和相遇节点*/
			getHost().setstartTime(otherHost, SimClock.getTime());
			otherHost.setstartTime(getHost(), SimClock.getTime());
		}
		else {/**连接断开(连接状态改变)*/
			DTNHost otherHost = con.getOtherNode(getHost());
			/**记录当前连接断开时间和相遇节点*/
			getHost().setendTime(otherHost, SimClock.getTime());
			otherHost.setendTime(getHost(), SimClock.getTime());
		}
	}

	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
		SAWRouter othRouter = (SAWRouter)from.getRouter();
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
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

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
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			tryOtherMessages();
		}
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
	private Tuple<Message, Connection> tryOtherMessages() {
		// TODO Auto-generated method stub
		List<Tuple<Message, Connection>> messages = 
				new ArrayList<Tuple<Message, Connection>>(); 
		Collection<Message> msgCollection = getMessageCollection();
			/* for all connected hosts collect all messages that have a higher
			   probability of delivery by the other host */
			for (Connection con : getConnections()) {
				DTNHost other = con.getOtherNode(getHost());
				SAWRouter othRouter = (SAWRouter)other.getRouter();
				if (othRouter.isTransferring()) {
					continue; // skip hosts that are transferring
				}
				for (Message m : msgCollection) {
					DTNHost otherHost=con.getOtherNode(getHost());
					SAWRouter otherRouter = (SAWRouter)otherHost.getRouter();
					double p1=getHost().getpropertion(m.getTo());
					double p2=otherHost.getpropertion(m.getTo());
					int copies1 = (Integer)m.getProperty(MSG_COUNT_PROPERTY);
					if(copies1>1) {
						if(p1+p2>0) {
								//可添加动态改变La
								if(!otherRouter.hasMessage(m.getId())) {
									int copies1new=(int)(p1/(p1+p2)*(copies1));
									int copies2new=copies1-copies1new;
									if(copies2new>0) {
										messages.add(new Tuple<Message, Connection>(m,con));
									}
								}
							}
					}
				}
			}
			
			if (messages.size() == 0) {
				return null;
			}
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
			int p1 = (Integer)tuple1.getKey().getTtl();
			// -"- tuple2...
			int p2 = (Integer)tuple2.getKey().getTtl();

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
		Integer nrofCopies = (Integer)msg.getProperty(MSG_COUNT_PROPERTY);
		DTNHost otherHost=con.getOtherNode(getHost());
		double p1=getHost().getpropertion(msg.getTo());
		double p2=otherHost.getpropertion(msg.getTo());
		
		if (isBinary) { 
			nrofCopies =(int)(p1/(p1+p2)*nrofCopies);
		}
		if(nrofCopies==0) {
			this.deleteMessage(msg.getId(), false);
		}
		else{
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
		}
	}
	
	@Override
	public SAWRouter replicate() {
		return new SAWRouter(this);
	}
}

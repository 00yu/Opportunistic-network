/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import core.SimError;
import util.Tuple;

/**
 * Implementation of Spray and wait router as depicted in 
 * <I>Spray and Wait: An Efficient Routing Scheme for Intermittently
 * Connected Mobile Networks</I> by Thrasyvoulos Spyropoulus et al.
 *
 */
public class Proposed extends ActiveRouter {
	private Set<String> ackedMessageIds=new HashSet<String>();
	public static final double DEFAULT_DEERTA=0.5;
	public static final String DEERTA_S = "deerta";
	private double δ;
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String PROPOSED_NS = "Proposed";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = PROPOSED_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;

	public Proposed(Settings s) {
		super(s);
		Settings snwSettings = new Settings(PROPOSED_NS);
		
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
		if (snwSettings.contains(DEERTA_S)) {
			δ = snwSettings.getDouble(DEERTA_S);
		}
		else {
			δ = DEFAULT_DEERTA;
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
		this.δ=r.δ;
		this.ackedMessageIds = new HashSet<String>();
	}
	
	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			checkAck(otherHost);
		}
	}
	
	// 更新ACK表并删除多余副本
	private void checkAck(DTNHost otherHost) {

		MessageRouter mRouter = otherHost.getRouter();

		assert mRouter instanceof MaxPropRouter : "MaxProp only works "
				+ " with other routers of same type";
		Proposed otherRouter = (Proposed) mRouter;

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
		List<Message> copiesLeft = sortByNewQueueMode(getMessagesWithCopiesLeft());
		
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnections(copiesLeft, getConnections());
		}
	}
	
	@SuppressWarnings(value = "unchecked") /* ugly way to make this generic */
	protected List sortByNewQueueMode(List list) {
			Collections.sort(list, 
					new Comparator() {
				/** Compares two tuples by their messages' receiving time */
				public int compare(Object o1, Object o2) {
					double diff;
					Message m1, m2;
					if (o1 instanceof Tuple) {
						m1 = ((Tuple<Message, Connection>)o1).getKey();
						m2 = ((Tuple<Message, Connection>)o2).getKey();
					}
					else if (o1 instanceof Message) {
						m1 = (Message)o1;
						m2 = (Message)o2;
					}
					else {
						throw new SimError("Invalid type of objects in " + 
								"the list");
					}
					//double u1=δ*(Math.log(1+(Integer)m1.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((m1.getTtl()*1.0)/(m1.getInitiTtl()*1.0));
					//double u2=δ*(Math.log(1+(Integer)m2.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((m2.getTtl()*1.0)/(m2.getInitiTtl()*1.0));
					//System.out.println(Math.log(1+(Integer)m1.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0));
					//System.out.println("副本占比："+(Integer)m1.getProperty(MSG_COUNT_PROPERTY)*1.0/((initialNrofCopies)*1.0));
					//System.out.println("TTL占比："+(m1.getTtl()*1.0)/(m1.getInitiTtl()*1.0));
//					double u1,u2;
//					if(Math.abs((Integer)m1.getProperty(MSG_COUNT_PROPERTY)*1.0-(Integer)m2.getProperty(MSG_COUNT_PROPERTY)*1.0)>δ) {
//						u1=(Integer)m1.getProperty(MSG_COUNT_PROPERTY)*1.0;
//						u2=(Integer)m2.getProperty(MSG_COUNT_PROPERTY)*1.0;
//					}
//					else {
//						u1=m1.getTtl()*1.0;
//						u2=m2.getTtl()*1.0;
//					}
					double u1,u2;
					if((m1.getTtl()*1.0)/(m1.getInitiTtl()*1.0)>δ && (m2.getTtl()*1.0)/(m2.getInitiTtl()*1.0)>δ) {
						u1=(Integer)m1.getProperty(MSG_COUNT_PROPERTY)*1.0;
						u2=(Integer)m2.getProperty(MSG_COUNT_PROPERTY)*1.0;
					}
					else {
						u1=m1.getTtl()*1.0;
						u2=m2.getTtl()*1.0;
					}
					diff = u1-u2;
					if (diff == 0) {
						diff=m1.getSize()-m2.getSize();
						if(diff==0) {
							diff=m1.getReceiveTime()-m2.getReceiveTime();
							return (diff < 0 ? -1 : 1);
						}
						return (diff < 0 ? -1 : 1);
					}
					return (diff > 0 ? -1 : 1);
				}
			});

		return list;
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
		/* was the message delivered to the final recipient? */
		if (msg.getTo() == con.getOtherNode(getHost())) { 
			this.ackedMessageIds.add(msg.getId()); // yes, add to ACKed messages
			this.deleteMessage(msg.getId(), false); // delete from buffer
		}
		if (isBinary) { 
			nrofCopies /= 2;
		}
		else {
			nrofCopies--;
		}
		msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
	}
	
	@Override
	public Proposed replicate() {
		return new Proposed(this);
	}
	
	protected boolean makeRoomForMessage(int size){
		if (size > this.getBufferSize()) {
			return false; // message too big for the buffer
		}
		int freeBuffer = this.getFreeBufferSize();
		/* delete messages from the buffer until there's enough space */
		Collection<Message> messages = this.getMessageCollection();
		while(freeBuffer<size) {
			List<Message> selected=new ArrayList<Message>();
			int threshold=size-freeBuffer;
			for(Message m:messages) {
				if(m.getSize()>=threshold) {
					selected.add(m);
				}
			}
			if(!selected.isEmpty()) {
				Message oldest = null;
				for (Message m : selected) {
					
					if (isSending(m.getId())) {
						continue; // skip the message(s) that router is sending
					}
					
					if (oldest == null ) {
						oldest = m;
					}
					else {
						//double u1=δ*((Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0)/((initialNrofCopies)*1.0)+(1-δ)*((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0));
						//double u2=δ*((Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)/((initialNrofCopies)*1.0)+(1-δ)*((m.getTtl()*1.0)/(m.getInitiTtl()*1.0));
						//double u1=δ*(Math.log(1+(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0));
						//double u2=δ*(Math.log(1+(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((m.getTtl()*1.0)/(m.getInitiTtl()*1.0));
//						double u1,u2;
//						if(Math.abs((Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0-(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)>δ) {
//							u1=(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0;
//							u2=(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0;
//						}
//						else {
//							u1=oldest.getTtl()*1.0;
//							u2=m.getTtl()*1.0;
//						}
						double u1,u2;
						if((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0)>δ && (m.getTtl()*1.0)/(m.getInitiTtl()*1.0)>δ) {
							u1=(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0;
							u2=(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0;
						}
						else {
							u1=oldest.getTtl()*1.0;
							u2=m.getTtl()*1.0;
						}
						if(u1>u2) {
							oldest=m;
						}
						else if(u1==u2) {
							if(oldest.getSize()<m.getSize()) {
								oldest=m;
							}
							else if(oldest.getSize()==m.getSize()) {
								if(oldest.getReceiveTime() > m.getReceiveTime()) {
									oldest=m;
								}
							}
						}
					}
				}
				if (oldest == null) {
					for (Message m : messages) {
						
						if (isSending(m.getId())) {
							continue; // skip the message(s) that router is sending
						}
						
						if (oldest == null ) {
							oldest = m;
						}
						else {
							//double u1=δ*((Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0)/((initialNrofCopies)*1.0)+(1-δ)*((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0));
							//double u2=δ*((Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)/((initialNrofCopies)*1.0)+(1-δ)*((m.getTtl()*1.0)/(m.getInitiTtl()*1.0));
							//double u1=δ*(Math.log(1+(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0));
							//double u2=δ*(Math.log(1+(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((m.getTtl()*1.0)/(m.getInitiTtl()*1.0));
//							double u1,u2;
//							if(Math.abs((Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0-(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)>δ) {
//								u1=(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0;
//								u2=(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0;
//							}
//							else {
//								u1=oldest.getTtl()*1.0;
//								u2=m.getTtl()*1.0;
//							}
							double u1,u2;
							if((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0)>δ && (m.getTtl()*1.0)/(m.getInitiTtl()*1.0)>δ) {
								u1=(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0;
								u2=(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0;
							}
							else {
								u1=oldest.getTtl()*1.0;
								u2=m.getTtl()*1.0;
							}
							if(u1>u2) {
								oldest=m;
							}
							else if(u1==u2) {
								if(oldest.getSize()<m.getSize()) {
									oldest=m;
								}
								else if(oldest.getSize()==m.getSize()) {
									if(oldest.getReceiveTime() > m.getReceiveTime()) {
										oldest=m;
									}
								}
							}
						}
					}
					if (oldest == null) {
						return false; // couldn't remove any more messages
					}			
					
					/* delete message from the buffer as "drop" */
					deleteMessage(oldest.getId(), true);
					freeBuffer += oldest.getSize();
					continue;
				}			
				
				/* delete message from the buffer as "drop" */
				deleteMessage(oldest.getId(), true);
				freeBuffer += oldest.getSize();
			}
			else {
				Message oldest = null;
				for (Message m : messages) {
					
					if (isSending(m.getId())) {
						continue; // skip the message(s) that router is sending
					}
					
					if (oldest == null ) {
						oldest = m;
					}
					else {
						//double u1=δ*((Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0)/((initialNrofCopies)*1.0)+(1-δ)*((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0));
						//double u2=δ*((Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)/((initialNrofCopies)*1.0)+(1-δ)*((m.getTtl()*1.0)/(m.getInitiTtl()*1.0));
						//double u1=δ*(Math.log(1+(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0));
						//double u2=δ*(Math.log(1+(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)/Math.log(1+initialNrofCopies*1.0))+(1-δ)*((m.getTtl()*1.0)/(m.getInitiTtl()*1.0));
//						double u1,u2;
//						if(Math.abs((Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0-(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0)>δ) {
//							u1=(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0;
//							u2=(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0;
//						}
//						else {
//							u1=oldest.getTtl()*1.0;
//							u2=m.getTtl()*1.0;
//						}
						double u1,u2;
						if((oldest.getTtl()*1.0)/(oldest.getInitiTtl()*1.0)>δ && (m.getTtl()*1.0)/(m.getInitiTtl()*1.0)>δ) {
							u1=(Integer)oldest.getProperty(MSG_COUNT_PROPERTY)*1.0;
							u2=(Integer)m.getProperty(MSG_COUNT_PROPERTY)*1.0;
						}
						else {
							u1=oldest.getTtl()*1.0;
							u2=m.getTtl()*1.0;
						}
						if(u1>u2) {
							oldest=m;
						}
						else if(u1==u2) {
							if(oldest.getSize()<m.getSize()) {
								oldest=m;
							}
							else if(oldest.getSize()==m.getSize()) {
								if(oldest.getReceiveTime() > m.getReceiveTime()) {
									oldest=m;
								}
							}
						}
					}
				}
				if (oldest == null) {
					return false; // couldn't remove any more messages
				}			
				
				/* delete message from the buffer as "drop" */
				deleteMessage(oldest.getId(), true);
				freeBuffer += oldest.getSize();
			}
		}
		return true;
	}
}

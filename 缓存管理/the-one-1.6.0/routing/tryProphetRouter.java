/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
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
 * Implementation of PRoPHET router as described in 
 * <I>Probabilistic routing in intermittently connected networks</I> by
 * Anders Lindgren et al.
 */
public class tryProphetRouter extends ActiveRouter {
	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	
	/** Prophet router's setting namespace ({@value})*/ 
	public static final String PROPHET_NS = "tryProphetRouter";
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
	public static final double DEFAULT_W = 0.5;
	public static final String W_S = "w";
	/** the value of nrof seconds in time unit -setting */
	private int secondsInTimeUnit;
	/** value of beta setting */
	private double beta;
	private double w;
    private Map<Message,Double> msguu;
    private Map<Message,Double> msgsize;
    private Map<String,Integer> trans;
    private Map<String,Integer> drop;
	/** delivery predictabilities */
	private Map<DTNHost, Double> preds;
	/** last delivery predictability update (sim)time */
	private double lastAgeUpdate;
	public static final String MSG_SPREAD_PROPERTY ="spreadcopies";
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public tryProphetRouter(Settings s) {
		super(s);
		Settings prophetSettings = new Settings(PROPHET_NS);
		secondsInTimeUnit = prophetSettings.getInt(SECONDS_IN_UNIT_S);
		if (prophetSettings.contains(BETA_S)) {
			beta = prophetSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}
		if (prophetSettings.contains(W_S)) {
			w = prophetSettings.getDouble(W_S);
		}
		else {
			w = DEFAULT_W;
		}
		initPreds();
		initmsguu();
		initmsgsize();
		inittrans();
		initdrop();
	}

	/**
	 * Copyconstructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected tryProphetRouter(tryProphetRouter r) {
		super(r);
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		this.w=r.w;
		initPreds();
		initmsguu();
		initmsgsize();
		inittrans();
		initdrop();
	}
	
	/**
	 * Initializes predictability hash
	 */
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}
	private void initmsguu() {
		this.msguu = new HashMap<Message, Double>();
	}
	private void initmsgsize() {
		this.msgsize = new HashMap<Message,Double>();
	}
	private void inittrans(){
		this.trans=new HashMap<String,Integer>();
	}
	private void initdrop(){
		this.drop=new HashMap<String,Integer>();
	}

	@Override
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
			updatemsgcopies(otherHost);
		}
	}
	public void updatemsgcopies(DTNHost otherhost){
		tryProphetRouter othRouter = (tryProphetRouter)otherhost.getRouter();
		int max=0;
		for(Message m1:this.getMessageCollection())
			if (othRouter.hasMessage(m1.getId())){
		                   Message m2=othRouter.getMessage(m1.getId());
		            	   int count1=(int)m1.getProperty(MSG_SPREAD_PROPERTY);
		            	   int count2=(int)m2.getProperty(MSG_SPREAD_PROPERTY);
		            	
		            	   if(count1<=count2){
		            		   max=count2;
		            		   
		            		   }
		            	   else{
		            		   max=count1;
		            		   
		            	   }
		            	
		            	   m1.updateProperty(MSG_SPREAD_PROPERTY,max);
		            	   m2.updateProperty(MSG_SPREAD_PROPERTY,max);}
		               
	}
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForOriMessage(msg);
		//makeRoomForMessage(msg.getSize());
		msg.setTtl(this.msgTtl);
		
		msg.addProperty(MSG_SPREAD_PROPERTY, 0);
		addToMessages(msg, true);
		
		return true;
	}
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message msg = super.messageTransferred(id, from);
	    int count=(int)msg.getProperty(MSG_SPREAD_PROPERTY);
		msg.updateProperty(MSG_SPREAD_PROPERTY, count+1);
		
		return msg;
	}
	@Override
	protected void transferDone(Connection con) {
		
		DTNHost other = con.getOtherNode(getHost());
		String msgId = con.getMessage().getId();
		/* get this router's copy of the message */
		Message msg = getMessage(msgId);
		

		if (msg == null) { // message has been dropped from the buffer after..
			return; // ..start of transfer -> no need to reduce amount of copies
		}
		int count1=(int)msg.getProperty(MSG_SPREAD_PROPERTY);
		msg.updateProperty(MSG_SPREAD_PROPERTY, count1+1);
		int i=getTransFor(msgId);
		i=i+1;
		trans.put(msgId,i);
	}
	public int getTransFor(String id){
		if(trans.containsKey(id))
			return trans.get(id);
		else{
			return 0;
		}
	}
	public int getDropFor(String id){
		if(drop.containsKey(id)){
			return drop.get(id);
		}
		else{
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
		assert otherRouter instanceof tryProphetRouter : "PRoPHET only works " + 
			" with other routers of same type";
		
		double pForHost = getPredFor(host); // P(a,b)
		Map<DTNHost, Double> othersPreds = 
			((tryProphetRouter)otherRouter).getDeliveryPreds();
		
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
			tryProphetRouter othRouter = (tryProphetRouter)other.getRouter();
			
			if (othRouter.isTransferring()) {
				continue; // skip hosts that are transferring
			}
			
			for (Message m : msgCollection) {
				if (othRouter.hasMessage(m.getId())) {
					continue; // skip messages that the other one has
				}
				double p1=getPredFor(m.getTo());
				
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
		double p1 = ((tryProphetRouter)tuple1.getValue().
				getOtherNode(getHost()).getRouter()).getPredFor(
				tuple1.getKey().getTo());
		// -"- tuple2...
		double p2 = ((tryProphetRouter)tuple2.getValue().
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
	protected int checkReceiving(Message m, DTNHost from) {
		if (isTransferring()) {
			return TRY_LATER_BUSY; // only one connection at a time
		}
	
		if ( hasMessage(m.getId()) || isDeliveredMessage(m) ||
				super.isBlacklistedMessage(m.getId())) {
			return DENIED_OLD; // already seen this message -> reject it
		}
		
		if (m.getTtl() <= 0 && m.getTo() != getHost()) {
			/* TTL has expired and this host is not the final recipient */
			return DENIED_TTL; 
		}

		if (energy != null && energy.getEnergy() <= 0) {
			return MessageRouter.DENIED_LOW_RESOURCES;
		}
		
		if (!policy.acceptReceiving(from, getHost(), m)) {
			return MessageRouter.DENIED_POLICY;
		}
		
		/* remove oldest messages but not the ones being sent */
		if (!makeRoomForRelayMessage(m)) {
			return DENIED_NO_SPACE; // couldn't fit into buffer -> reject
		}
		
		
		return RCV_OK;
	}
	
	protected boolean makeRoomForOriMessage(Message mm){
		int size=mm.getSize();
	    String transid=mm.getId();
		if (size > this.getBufferSize()) {
			return false; // message too big for the buffer
		}
			
		int freeBuffer = this.getFreeBufferSize();
		/* delete messages from the buffer until there's enough space */
		Collection<Message> messages1 = this.getMessageCollection();
		double avgcount=0;
		 for(Message m:messages1){
			 int count1=(int)m.getProperty(MSG_SPREAD_PROPERTY);
			 avgcount=avgcount+count1;
			 }
		 avgcount=avgcount*1.0/messages1.size();
		
		while (freeBuffer < size) {
		
			Collection<Message> messages = this.getMessageCollection();
		    msguu=new HashMap<Message,Double>();
		    for(Message m:messages){
					 int count1=(int)m.getProperty(MSG_SPREAD_PROPERTY);
					double count=avgcount/(count1+1);
				    double remainttl=m.getTtl()*1.0/m.gettotalTtl();
				    double p=this.getPredFor(m.getTo());
					 double msgulity=w*p+(1-w)*remainttl*count;
					 msguu.put(m,msgulity);
					
				 }
				 Message m = getNextMessageToDes(true); // don't remove msgs being sent

					if (m == null) {
						
						return false; // couldn't remove any more messages
					}			
					
					/* delete message from the buffer as "drop" */
					deleteMessage(m.getId(), true);
					freeBuffer += m.getSize();
					
					
					}
			return true;
		}

	protected boolean makeRoomForRelayMessage(Message mm){
		int size=mm.getSize();
		String transid=mm.getId();
		if (size > this.getBufferSize()) {
			return false; // message too big for the buffer
		}
			
		int freeBuffer = this.getFreeBufferSize();
		/* delete messages from the buffer until there's enough space */
		int i=0;
		double avgcount=0;
		Collection<Message> messages1 = this.getMessageCollection();
		 for(Message m:messages1){
			 int count1=(int)m.getProperty(MSG_SPREAD_PROPERTY);
			 avgcount=avgcount+count1;
			 }
		 avgcount=avgcount*1.0/messages1.size();
		while (freeBuffer < size) {
			i++;
			Collection<Message> messages = this.getMessageCollection();
		    msguu=new HashMap<Message,Double>();
		    msgsize=new HashMap<Message,Double>();
			if(mm.getTo()==getHost()){
				
				 for(Message m:messages){
					 int count1=(int)m.getProperty(MSG_SPREAD_PROPERTY);
					 double count=avgcount/(count1+1);
					 double remainttl=m.getTtl()*1.0/m.gettotalTtl();
					 double p=this.getPredFor(m.getTo());
				     double msgulity=w*p+(1-w)*remainttl*count;
					 msguu.put(m,msgulity);
					
				 }
				 Message m = getNextMessageToDes(true); // don't remove msgs being sent

					if (m == null) {
						
						return false; // couldn't remove any more messages
					}			
					
					/* delete message from the buffer as "drop" */
					deleteMessage(m.getId(), true);
					freeBuffer += m.getSize();
					
					}
				 
			
			else{
				 
				 int count1=(int)mm.getProperty(MSG_SPREAD_PROPERTY);
				 double count=avgcount/(count1+1);
				 double remainttl=mm.getTtl()*1.0/mm.gettotalTtl();
				 double p=this.getPredFor(mm.getTo());
			     double msgulity=w*p+(1-w)*remainttl*count;
			    Set<Message> lowqueue=getlowqueue(messages,msgulity,avgcount);
		        int needspace=size-freeBuffer;
		       
			    Message m = getNextMessageToRelay(true,lowqueue,needspace,i); // don't remove msgs being sent
                if (m == null) {
				
				return false; // couldn't remove any more messages
			}			
			
			/* delete message from the buffer as "drop" */
			deleteMessage(m.getId(), true);
			freeBuffer += m.getSize();
			
			}
			
		}
		
		return true;
	}
	
	public Set<Message> getlowqueue(Collection<Message> messages,double msgulity,double avgcount){
		
		Set<Message> lowqueue=new HashSet<Message>();
		 for(Message m:messages){
			 int count1=(int)m.getProperty(MSG_SPREAD_PROPERTY);
			 double count=avgcount/(count1+1);
			 double remainttl=m.getTtl()*1.0/m.gettotalTtl();
			 double p=this.getPredFor(m.getTo());
		     double mu=w*p+(1-w)*remainttl*count;
		
			 if(msgulity>mu){
				
				 lowqueue.add(m);
				}
		 }
		
		 return lowqueue;
	}
protected Message getNextMessageToDes(boolean excludeMsgBeingSent) {
	Collection<Message> messages = this.getMessageCollection();
		List<Message> validMessages = new ArrayList<Message>();
	   
		for (Message m : messages) {	
			if (excludeMsgBeingSent && isSending(m.getId())) {
				continue; // skip the message(s) that router is sending
			}
			
			validMessages.add(m);
		}
		
		Collections.sort(validMessages, new Prophet3Comparator()); 
		
		return validMessages.get(validMessages.size()-1); // return last message
	}
	
	protected Message getNextMessageToRelay(boolean excludeMsgBeingSent,Set<Message> lowqueue,int needspace,int i) {
		Collection<Message> messages = this.getMessageCollection();
		List<Message> validMessages = new ArrayList<Message>();
		for(Message m:lowqueue){
			if (excludeMsgBeingSent && isSending(m.getId())) {
				continue; 
			}
			validMessages.add(m);
			double ratio=m.getTtl()*1.0/m.gettotalTtl();
			msgsize.put(m,ratio);
			
		   
		}
	
		
		if(i==1){
		    int total=0;
		    for(Map.Entry<Message,Double> mapping:msgsize.entrySet()){ 
		        	   Message m=mapping.getKey();
		               total=total+m.getSize();
		          } 
		    
	        if(total<needspace){
		    	   
					return null;
				}
		        
			}
		 Collections.sort(validMessages, new Prophet4Comparator()); 
			
			return validMessages.get(validMessages.size()-1); 
			 
		
		
	}
	
	private class Prophet4Comparator implements Comparator<Message> {

		public Prophet4Comparator() {
			
		}

		public int compare(Message msg1, Message msg2) {
			double diff = msgsize.get(msg1)-msgsize.get(msg2);
		    
			if (diff == 0) {
				return 0;
			}
			return (diff < 0 ? 1 : -1);
		}
		}
	
	private class Prophet3Comparator implements Comparator<Message> {

		public Prophet3Comparator() {
			
		}

		public int compare(Message msg1, Message msg2) {
			double diff = msguu.get(msg1)-msguu.get(msg2);
		
			if (diff == 0) {
				return 0;
			}
			return (diff < 0 ? 1 : -1);
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
		tryProphetRouter r = new tryProphetRouter(this);
		return r;
	}

}

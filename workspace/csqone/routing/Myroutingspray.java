/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
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
import core.Tuple;

/**
 * Implementation of Spray and wait router as depicted in 
 * <I>Spray and Wait: An Efficient Routing Scheme for Intermittently
 * Connected Mobile Networks</I> by Thrasyvoulos Spyropoulus et al.
 *
 */
public class Myroutingspray extends ActiveRouter {
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String MY_NS = "Myroutingspray";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = MY_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
    public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
    //private Set<String> ackedMessageIds=new HashSet<String>();
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
	public Myroutingspray(Settings s) {
		super(s);
		Settings snwSettings = new Settings(MY_NS);
		
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
		secondsInTimeUnit = snwSettings.getInt(SECONDS_IN_UNIT_S);
		if (snwSettings.contains(BETA_S)) {
			beta = snwSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}
		//this.ackedMessageIds = new HashSet<String>();

		initPreds();
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected Myroutingspray(Myroutingspray r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		this.secondsInTimeUnit = r.secondsInTimeUnit;
		this.beta = r.beta;
		initPreds();
	}
	private void initPreds() {
		this.preds = new HashMap<DTNHost, Double>();
	}

	@Override
	public void changedConnection(Connection con) {
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			updateDeliveryPredFor(otherHost);
			updateTransitivePreds(otherHost);
			if(!getHost().getE().contains(otherHost)){
				getHost().getE().add(otherHost);//�����ڵ㼯�ϵĸ���
			}
			
			//�ڵ���罻Ⱥ�ĸ���
			if(!getHost().getEG().contains(otherHost) && (getHost().getSimilar(otherHost)>1/2*Math.min(otherHost.getE().size(), getHost().getE().size()))) {
				getHost().getEG().add(otherHost);
			}
			//checkAck(otherHost);
		}
	}
	// ����ACK��ɾ�����ั��
	/*private void checkAck(DTNHost otherHost) {

		MessageRouter mRouter = otherHost.getRouter();

		assert mRouter instanceof MaxPropRouter : "MaxProp only works "
				+ " with other routers of same type";
		Myroutingspray otherRouter = (Myroutingspray) mRouter;


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
	*/
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
			((Myroutingspray)otherRouter).getDeliveryPreds();
		
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
			//this.ackedMessageIds.add(id);
		}
		return msg;
	}
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());
		msg.addProperty(SPRAY_TIMES,0);//����緢��ʼ����
		msg.addProperty(SPRAY_FLAG,0);//����緢��ʶ0-�緢�� 1-�����緢
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
		List<Message> copiesLeft = sortByQueueNewMode(getMessagesWithCopiesLeft());//��������1,���Ҳ����ٴ������
		
		
		/**
		 * 1.��õ�ǰ�ĸ�����Ϊһ����Ϣ
		 * ��Ϊsaw��ͨ����Ϣ�ĸ��������������Ǹ���Ϣ���ڵȴ��׶�
		 * �����緢�׶Ρ�
		 * ��������ķ�����Ҫ����ɸѡ�������׶ε���Ϣ�������������ͬ�Ĵ���
		 * ���������copiesLeft�����緢�׶ε���Ϣ,��copiesOne�ǵȴ��׶ε���Ϣ
		 */
		List<Message> copiesOne = sortByQueueNewMode(getMessagesWithCopiesOne());//����׶�
		//�緢�׶�
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			this.tryMessagesToConnectionsSpray(copiesLeft, getConnections());
		}
		
	
	
	
	}
	private class TupleComparator implements Comparator 
	<Tuple<Message, Connection>> {

	public int compare(Tuple<Message, Connection> tuple1,
			Tuple<Message, Connection> tuple2) {
		// delivery probability of tuple1's message with tuple1's connection
		double p1 = ((Myroutingspray)tuple1.getValue().
				getOtherNode(getHost()).getRouter()).getPredFor(
				tuple1.getKey().getTo());
		// -"- tuple2...
		double p2 = ((Myroutingspray)tuple2.getValue().
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
	protected List<Message> getMessagesWithCopiesBin() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//�õ���Ϣ��������������
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);	
			if (nrofCopies > 1 && sprayTimes>0) {//�ȴ��׶��ٴ�����ĸ���
				list.add(m);
			}
		}
		return list;
	}
	
	//�ȴ��׶� ��Ҫ��д
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
				// TODO �Զ����ɵ� catch ��
				e.printStackTrace();
			} 
			
		}
		
		return null;
	}
	
	
	
	
	
	protected Message tryAllConnectionWait(Connection con, List<Message> messages) throws Exception
	{//����Ч�ø���
		DTNHost otherNode =con.getOtherNode(getHost());//��öԷ��ڵ�
		
		for (Message m : messages) {
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//�õ���Ϣת������
			Integer copies=(Integer)m.getProperty(MSG_COUNT_PROPERTY);
			if( getPredFor(m.getTo())<0.7){//�ȴ��׶��ٴ�����,����Ϊ1�ĲŻ�������������Ϊ˧ѡ������Ϊ1��ʱ�����˽�sprayflag=1
				copies=(int)Math.floor(32*(1-getPredFor(m.getTo())));//����ٴ����丱����
			    if(copies*m.getSize()>=getFreeBufferSize()){
			    	copies=getFreeBufferSize()/(m.getSize()*2);
			    }
			
			if(copies>=1){	//ֻ����õ��ھӽڵ��緢��Ϣ						
					if(((Myroutingspray) otherNode.getRouter()).getPredFor(m.getTo()) > getPredFor(m.getTo())){
						m.updateProperty(MSG_COUNT_PROPERTY,copies);//�ս��������ʱcopiesӦ�õ���1
						int retVal = startTransfer(m, con); 
						if (retVal == RCV_OK) {
							m.updateProperty(SPRAY_TIMES,sprayTimes+1);//��ʼ�����緢
							//m.updateProperty(SPRAY_FLAG,0);
							m.setZongcopies(m.getZongcopies()+copies-1);
							return m;	
						}else if (retVal > 0) { 
							return null; 
						}
						
					}else{//��������
//						System.out.println("����ת��");
						return null;
					}
			
			
			}
			}
		}
		
		return null; // no message was accepted		
	}
	
	
	
	
	/**
	 *�ڵ㸱����Ϊһ����Ϣ��������Ϣ�б�
	 */
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();
		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//�õ���Ϣ��������������
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";	
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//�õ���Ϣת������
			Integer sprayFlag = (Integer)m.getProperty(SPRAY_FLAG);//�õ���Ϣת������
				if (nrofCopies == 1 &&sprayTimes<2) {//�����󣨽����
					//m.updateProperty(SPRAY_TIMES,sprayTimes+1);
					//m.updateProperty(SPRAY_FLAG,1);
					list.add(m);				
				}
		}	
		return list;
	}
	protected List<Message> getMessagesWithCopiesOneForward() {
		List<Message> list = new ArrayList<Message>();
		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//�õ���Ϣ��������������
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";	
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//�õ���Ϣת������
			
				if (nrofCopies == 1 &&sprayTimes>=2) {//�����󣨽����
					//m.updateProperty(SPRAY_TIMES,sprayTimes+1);
					//m.updateProperty(SPRAY_FLAG,1);
					list.add(m);				
				}
		}	
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
			if (nrofCopies > 1 ) {
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
	public Myroutingspray replicate() {
		return new Myroutingspray(this);
	}
}

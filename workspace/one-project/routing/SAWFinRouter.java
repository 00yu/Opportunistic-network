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
		//�Զ���ʵ����
		
		
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
	/*����ӵĴ���*/
	/*
	 * �����ڵ����ӽ���ʱ����Ч��ֵ
	 * 4.�����ǵ����ڵ�������ڵ�ս�������ʱ�Ĳ���
	 * �Ƚ��ʺ�����������ʷ������Ϣ������Ч��ֵ��ɾ��һЩ����Ҫ����Ϣ�ȵ�
	 */
	  public void changedConnection(Connection con) { 
		 if (con.isUp()) {
		 DTNHost otherHost = con.getOtherNode(getHost());//������ӵĶԷ�����
		 /**
		  * ACK��Ҳ����ÿ���ڵ㶼��һ��ACK���¼Ͷ�ݳɹ�����Ϣ��id
		  * �������ڵ㽨������ʱ������ack����������Ȼ�����ɾ���Լ������к�
		  * ack���ж�Ӧ����Ϣ
		  */
		 checkAck(otherHost);
		 /**
		  * ����Ч��ֵ����Ҫ�ǽڵ㽻���͸�����ʷ������Ϣ��
		  */
		 countUtility(otherHost);
	     }
	}
	
	// ����ACK��ɾ�����ั��
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
	 * ����Ч��ֵ
	 * (���ӵ������ڵ�ͬʱ������)
	 */
	private void countUtility(DTNHost otherHost) {
		DTNHost sourceHost = getHost();// ��������

		double timeDiff = (SimClock.getTime() / super.secondsInTimeUnit);
		getHost().updatehu(otherHost, (int) timeDiff);// �������µȴ���ʷЧ�ñ�

		sourceHost.updateFinUtility(sourceHost, otherHost);// ��������Ч�ñ�
	}
	
		
	/***********/
		
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	//���½��ն�
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

	
	/*��д���࣬������Ϣ���������ֶ�*/
	@Override 
	public boolean createNewMessage(Message msg) {

		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		
		msg.addProperty(SPRAY_TIMES,0);//����緢��ʼ����
		msg.addProperty(SPRAY_FLAG,0);//����緢��ʶ0-�緢�� 1-�����緢

//		msg.addProperty(OTHER_COPY,0);//�ھӽڵ㸱����
		
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
		//��Ҫת������Ϣ
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		@SuppressWarnings(value ="unchecked")
		/**
		 * 1.���δ�緢���ҵ�ǰ�ĸ�����Ϊһ����Ϣ
		 * ��Ϊsaw��ͨ����Ϣ�ĸ��������������Ǹ���Ϣ���ڵȴ��׶�
		 * �����緢�׶Ρ�
		 * ��������ķ�����Ҫ����ɸѡ�������׶ε���Ϣ�������������ͬ�Ĵ���
		 * ���������copiesLeft�����緢�׶ε���Ϣ,��copiesOne�ǵȴ��׶ε���Ϣ
		 */
		List<Message> copiesOne = sortByQueueMode(getMessagesWithCopiesOne());
		//���緢������ΪΪһ����Ϣ
		@SuppressWarnings(value ="unchecked")
		List<Message> copiesBin = sortByQueueMode(getMessagesWithCopiesBin());
		
		/**
		 * 2.�����緢�׶ε���Ϣ
		 * ���������if��������治ͬ�׶ε���Ϣ��copiesLeft��copiesOne��copiesBin��
		 * Ӧ�ò�ͬ�Ĳ���ȥ����,���������һ������
		 */
		if (copiesLeft.size() > 0) {
			/*���ھӽڵ�ַ���Щ��Ϣ*/
			this.tryMessagesToConnectionsFin(copiesLeft, getConnections());
		}
		//�ȴ��׶θ���Ϊһ�ٴ��緢
		if(copiesOne.size() > 0){//��д����	

			this.tryMessagesToConnectionsWait(copiesOne, getConnections());	
		}
		//�ȴ��׶θ�������һ�ٴ��緢
		if (copiesBin.size() > 0) {
			/*���ھӽڵ���ַַ���Щ��Ϣ*/
			this.tryMessagesToConnectionsWait(copiesBin, getConnections());
		}
		
		//this.tryMessagesToConnectionsSomeCopy(copiesOne, getConnections());
//		this.tryMessagesToConnectionsSomeCopy(copiesBin, getConnections());	
//		this.tryMessagesToConnections(copiesBin, getConnections());
	}
	
	/**
	 * ���������ص�ǰ·��Я������Ҫת������Ϣ��������Ϣ�б��ڵ㸱����>1��
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//�õ���Ϣ��������������
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);
			if (nrofCopies > 1 && sprayTimes==0 ) {//
				list.add(m);
			}
		}
		
		return list;
	}
	
   /********��д�Ĵ���******/
	
	/**
	 * ���������ص�ǰ·��Я������Ҫת������Ϣ��������Ϣ�б��ڵ㸱����>1��������
	 */
	protected List<Message> getMessagesWithCopiesBin() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//�õ���Ϣ��������������
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
	 * ���������ص�ǰ·��Я���Ľڵ㸱����Ϊһ����Ϣ��������Ϣ�б�
	 */
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//�õ���Ϣ��������������
			double buffertime = SimClock.getTime()-m.getReceiveTime();//�õ���Ϣ��������������
		
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//�õ���Ϣת������
		
				if (nrofCopies == 1 &&sprayTimes<2) {//�����󣨽����
					m.updateProperty(SPRAY_TIMES,sprayTimes+1);
					m.updateProperty(SPRAY_FLAG,1);
					list.add(m);				
				}
		}	
		return list;
	}
	/***********/
	
	/**
	 * ���½ڵ���Ϣ������
	 * �������ǰ������
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
		
		//�Զ�����丱��
		
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

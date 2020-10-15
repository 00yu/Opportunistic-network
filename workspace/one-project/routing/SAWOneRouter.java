/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;

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
public class SAWOneRouter extends ActiveRouter {
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
	protected int initialL;
	protected boolean isBinary;
	//��д������
	
	public SAWOneRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWOneRouter(SAWOneRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.initialL=r.initialNrofCopies;
		this.isBinary = r.isBinary;
		
	}
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		return super.receiveMessage(m, from);
	}
	
	/*��Ϣ������ɺ������ڵ�����Լ�����Ϣ����*/
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
	
	//���ӵ���
	public void changedConnection(Connection con) { 
		if (con.isUp()) {
		DTNHost otherHost = con.getOtherNode(getHost());//������ӵĶԷ�����
		int timeDiff = (int)(SimClock.getTime()/super.secondsInTimeUnit);
		getHost().updatehu(otherHost,timeDiff);//������ʷ����Ч�ñ�

		
	}}
	
	/*��д���࣬������Ϣ���������ֶ�*/
	@Override 
	public boolean createNewMessage(Message msg) {
	
		reset_copies();
		System.out.println("��󸱱�����"+initialL);
		makeRoomForNewMessage(msg.getSize());
		msg.setTtl(this.msgTtl);
		msg.addProperty(MSG_COUNT_PROPERTY, initialL);
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
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());//��Ҫת������Ϣ
	
		if (copiesLeft.size() > 0) {
			/* try to send those messages */
			/*���ھӽڵ�ַ���Щ��Ϣ*/
			
				this.tryMessagesToConnections(copiesLeft, getConnections());
			
			
		}
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
			if (nrofCopies > 1) {
				list.add(m);
			}
		}
		
		return list;
	}
	
	/**
	 * ���½ڵ���Ϣ������
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
	public SAWOneRouter replicate() {
		return new SAWOneRouter(this);
	}
	
	//��д������
	//�����ھӵĶ�����
	protected boolean is_good_host(List<Connection> connections)
	{
		boolean check=true;
		int totaldrop = 0;
		double item_prob=0.0;
		List<DTNHost> hosts =new ArrayList<DTNHost>() ;
		
		for(DTNHost othernode:hosts)
		{
			totaldrop+=othernode.getdrop();
			
		}
			
		if(totaldrop>30){
		HashMap<DTNHost,Double> droprob=new HashMap<DTNHost,Double>();
		
		for(DTNHost othernode:hosts)
		{
			if(totaldrop !=0)
			{
				item_prob=(othernode.getdrop()/totaldrop)/2+othernode.getBufferOccupancy()/2;	
			}
			else
			{
				item_prob=othernode.getBufferOccupancy()/2;	
			}
			
			droprob.put(othernode,item_prob);
			
		}
	
		for(Connection c:connections)
		{
			
			DTNHost othernode=c.getOtherNode(this.getHost());
			for (Entry<DTNHost, Double> entry : droprob.entrySet()) {
				if(entry.getValue()<droprob.get(othernode))
				{
					
					check=false;
				}
			}
				
		}
		}
		return check;
		
	}
	
	
	
	
	//����ӵ������ø�����
	protected void reset_copies()
	{
		System.out.println("ӵ�����㿪ʼ��");
		double totalsize = 0.0;
		double 	itemsize =0.0;
		
		List<DTNHost> otherhost=get_otherHost();
		System.out.println("�ھӽڵ㼯���ǣ�"+otherhost);
		if (otherhost.size() == 0 ) {
			System.out.println("�ձ�ǣ�");
		}
		else
		{
			for (int i=0, n=otherhost.size(); i<n; i++) {
				DTNHost othernode = otherhost.get(i);
				totalsize+=othernode.getRouter().getBufferSize();
				itemsize+=(othernode.getRouter().getBufferSize()-othernode.getRouter().getFreeBufferSize());
			}
			
			float prob= (float)(itemsize/totalsize);
			System.out.println("�ôر���Ϊ��"+prob);
			
			if(prob>=0.5){
				initialL=(int)Math.ceil(initialNrofCopies*(1-prob));
			}else if(prob>=0.3)
			{
				initialL=initialL++;
				System.out.println("��Ϣ����ֵ2Ϊ��"+initialL);	
			}
			else
			{
				initialL=initialNrofCopies;
				System.out.println("��Ϣ����ֵ2Ϊ��"+initialL);	
			}

		}	
	}
	
	//�õ��ھӽڵ㼯��
	private  List<DTNHost>  get_otherHost() {
		
		List<DTNHost> hosts =new ArrayList<DTNHost>() ;
		int timenum=(int)(SimClock.getTime()/super.secondsInTimeUnit);//ת��
		HashMap<DTNHost, HashMap<Integer,Integer>> hun_hash=getHost().gethu();//����
	
		Iterator<DTNHost> iter = hun_hash.keySet().iterator();
		while (iter.hasNext()) {
			DTNHost key = iter.next();
			HashMap<Integer,Integer> val = hun_hash.get(key);
			
//			if(timenum-4>0 &&val.containsKey(timenum-4)){
//				hosts.add(key);
//				
//			}else 
			if(timenum-3>0 && val.containsKey(timenum-3)){
				hosts.add(key);
			
			}else if(timenum-2>0 &&val.containsKey(timenum-2)){
				hosts.add(key);
			
			}else if(timenum-1>0 &&val.containsKey(timenum-1)){
				hosts.add(key);
			
			}	
	}
		return hosts;
	}
	
}

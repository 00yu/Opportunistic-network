/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Arrays;
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
public class SAWRouter2 extends ActiveRouter {
	//�Ҷ���ı���
	/** IDs of the messages that are known to have reached the final dst */
	private Set<String> ackedMessageIds;
	/** delivery predictability initialization constant*/
	public static final double P_INIT = 0.75;
	/** delivery predictability transitivity scaling constant default value */
	public static final double DEFAULT_BETA = 0.25;
	public static final String BETA_S = "beta";
	/** value of beta setting */
	private double beta;
	/** delivery predictability aging constant */
	public static final double GAMMA = 0.98;
	/** the value of nrof seconds in time unit -setting */
	 int secondsInTimeUnit;
	 /**
	  * Number of seconds in time unit -setting id ({@value}).
	  * How many seconds one time unit is when calculating aging of 
	  * delivery predictions. Should be tweaked for the scenario.*/
	public static final String SECONDS_IN_UNIT_S ="secondsInTimeUnit";
	public static final String SPRAY_TIMES = "spray_times";
	public static final String SPRAY_FLAG = "spray_flag";
	/** �������� */
	public int msgttl;
	
	
	
	/** identifier for the initial number of copies setting ({@value})*/ 
	public static final String NROF_COPIES = "nrofCopies";
	/** identifier for the binary-mode setting ({@value})*/ 
	public static final String BINARY_MODE = "binaryMode";
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SAW2_NS = "SAWRouter2";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SAW2_NS + "." +
		"copies";
	
	protected int initialNrofCopies;
	protected boolean isBinary;

	public SAWRouter2(Settings s) {//��ʼ����Ҫ�����ļ�����Ϣ�ı��� 
		super(s);
		Settings nSAWSettings = new Settings(SAW2_NS);
		if (nSAWSettings.contains(BETA_S)) {
			beta = nSAWSettings.getDouble(BETA_S);
		}
		else {
			beta = DEFAULT_BETA;
		}
		initialNrofCopies = nSAWSettings.getInt(NROF_COPIES);
		isBinary = nSAWSettings.getBoolean( BINARY_MODE);
		secondsInTimeUnit = nSAWSettings.getInt(SECONDS_IN_UNIT_S);
		msgttl=s.getInt("msgTtl");
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWRouter2(SAWRouter2 r) {
		super(r);
		this.beta=r.beta;
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		this.secondsInTimeUnit=r.secondsInTimeUnit;
		this.ackedMessageIds=new HashSet<String>();
		this.msgttl=r.msgttl;
	}
	
	/*�����ӵĴ���*/
	/*
	 * �����ڵ����ӽ���ʱ����Ч��ֵ
	 * 4.�����ǵ����ڵ�������ڵ�ս�������ʱ�Ĳ���
	 * �Ƚ��ʺ�����������ʷ������Ϣ������Ч��ֵ��ɾ��һЩ����Ҫ����Ϣ�ȵ�
	 */
	  public void changedConnection(Connection con) { 
		 if (con.isUp()) {
		 DTNHost otherHost = con.getOtherNode(getHost());//������ӵĶԷ�����
		 /**
		  * ACK����Ҳ����ÿ���ڵ㶼��һ��ACK����¼Ͷ�ݳɹ�����Ϣ��id
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
	// ����ACK����ɾ�����ั��
		private void checkAck(DTNHost otherHost) {

			MessageRouter mRouter = otherHost.getRouter();

			assert mRouter instanceof MaxPropRouter : "MaxProp only works "
					+ " with other routers of same type";
			SAWRouter2 otherRouter = (SAWRouter2) mRouter;

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
			updateFinUtility(sourceHost, otherHost);// ��������Ч�ñ�
		}
		
		/**
		 * ����FINЧ�ñ���ÿ���ڵ�ά��һ��Ч�ñ��������ڵ�����ʱ���Ի����Լ�Ч�ñ��и��¶Է��ڵ��Ч��ֵ		
		 */
		public void updateFinUtility(DTNHost host,DTNHost other) {			
			//���±��ؽڵ����Ϣ
			updateFinUtilityItem(host,other);
			//�����ھӽڵ����Ϣ
			updateFinUtilityItem(other,host);
		}

		
		//���½ڵ�Ч�õķ���
		public void updateFinUtilityItem(DTNHost host,DTNHost other) {
			//���±��ڵ��Ч����Ϣ,���꣬ʱ�䣬ƽ���ٶȣ�ƽ�����򣬸���ʱ�䣬ͨ�ŷ�Χ��
			HashMap<String, Double> dtnhash=host.getFinUtility().get(other);//��ȡ���ڵ�Ч�ñ����ھӽڵ���Ϣ
			double hostX =other.getLocation().getX();
			double hostY =other.getLocation().getY();//��ȡ�Է�����
			double hostSpeed=other.getSpeed();//��ȡ�Է��ٶȴ�С
			double avgDirection=0;//Ԥ�ⷽ��
			double count=updateDeliveryPredFor(host,other);//Ͷ��Ԥ��ֵ
			updateTransitivePreds(host,other);//���´���Ԥ��ֵ
			if(dtnhash!=null){
				if((double)dtnhash.get("updateTime")<SimClock.getTime()){
					double lastX=dtnhash.get("x");
					double lastY=dtnhash.get("y");
					hostSpeed=(hostSpeed+dtnhash.get("avgSpeed"))/2;
					avgDirection=Math.atan2(hostY-lastY,hostX-lastX)*(180/Math.PI);
				}else{
					avgDirection=(double)dtnhash.get("avgDirection");
					hostSpeed=(double)dtnhash.get("avgSpeed");
				}				
			}
			
			HashMap<String,Double> newData=new HashMap<String,Double>();
			newData.put("avgSpeed", hostSpeed);
			newData.put("x", hostX);
			newData.put("y", hostY);
//			System.out.println("�ڵ�"+other+"�Ƕ�"+avgDirection);
			newData.put("avgDirection", avgDirection);
			//����ʱ��
			newData.put("updateTime", SimClock.getTime());
			newData.put("count",count);
			host.setFinUtility(other, newData);//����Ч�ñ�		
		}
		
		
		//������ʷͶ��Ч��
		private double updateDeliveryPredFor(DTNHost host,DTNHost other) {
			double oldValue = getPredFor(host,other);//�õ��ɵ�Ͷ��ֵ
			double newValue = oldValue + (1 - oldValue) * 0.75;
			return newValue;
		}
		//���㴫��Ч��
		public double getPredFor(DTNHost host,DTNHost other) {					
			ageDeliveryPreds(host); // ��������ʱ����˥��Ͷ��Ԥ��ֵ
			HashMap<String, Double> tmpHost=host.getFinUtility().get(other);
			if (tmpHost !=null) {
				return tmpHost.get("count");
			}else {
				return 0;
			}
		}
		//���´���Ч��
		private void ageDeliveryPreds(DTNHost host) {
			double timeDiff = (SimClock.getTime() - host.getlastAgeUpdate()) / secondsInTimeUnit;
//	       	System.out.println("���Ը���"+timeDiff);]
			if (timeDiff == 0) {
			return;
			}
			double mult = Math.pow(0.98, timeDiff);
			for (HashMap.Entry<DTNHost, HashMap<String,Double>>e : host.getFinUtility().entrySet()) {
			e.getValue().put("count",e.getValue().get("count")*mult);
			}//���µ�ǰ�ڵ�Ч�ñ���Ͷ�ݸ���ֵ
			host.setlastAgeUpdate(SimClock.getTime());
		}
		//���´���Ͷ��ֵ
		private void updateTransitivePreds(DTNHost host,DTNHost other) {			
			double pForHost = getPredFor(host,other); // P(a,b)
			HashMap<DTNHost, HashMap<String,Double>>  othersPreds = other.getFinUtility();//��öԷ�·�ɵ�Ͷ��Ԥ��ֵ����
			for (Map.Entry<DTNHost, HashMap<String,Double>> e : othersPreds.entrySet()) {
				if (e.getKey() == host) {
					continue; // don't add yourself
				}		
				double pOld = getPredFor(host,e.getKey()); // P(a,c)_old
				double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue().get("count") * beta;//beta
//				System.out.println("old"+e.getValue().get("count")+"::new"+pNew);
				e.getValue().put("count",pNew);
			}
		}
		//��ʷͶ��Ч�ý���	
		/***********/
	
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
	
	@Override 
	public boolean createNewMessage(Message msg) {
		makeRoomForNewMessage(msg.getSize());

		msg.setTtl(this.msgTtl);
		msg.addProperty(SPRAY_TIMES,0);//�����緢��ʼ����
		msg.addProperty(SPRAY_FLAG,0);//�����緢��ʶ0-�緢�� 1-�����緢
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
		/**
		 * 1.���δ�緢���ҵ�ǰ�ĸ�����Ϊһ����Ϣ
		 * ��Ϊsaw��ͨ����Ϣ�ĸ��������������Ǹ���Ϣ���ڵȴ��׶�
		 * �����緢�׶Ρ�
		 * ��������ķ�����Ҫ����ɸѡ�������׶ε���Ϣ�������������ͬ�Ĵ�����
		 * ���������copiesLeft�����緢�׶ε���Ϣ,��copiesOne�ǵȴ��׶ε���Ϣ
		 */
		/*�緢�׶���Ϣ*/
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());
		
		/**
		 * 2.�����緢�׶ε���Ϣ
		 * ���������if��������治ͬ�׶ε���Ϣ��copiesLeft��copiesOne��copiesBin��
		 * Ӧ�ò�ͬ�Ĳ���ȥ����,���������һ������
		 */
		if (copiesLeft.size() > 0) {
			/*�緢�׶Σ����ھӽڵ�ַ���Щ��Ϣ*/
			this.tryMessagesToConnectionsFin(copiesLeft, getConnections());
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
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);
			if (nrofCopies > 1 && sprayTimes==0) {//sprayTimes==0��˼��û���趨�ٴ��緢���������ڵ�һ���緢ʱ��
				list.add(m);
			}
		}
		
		return list;
	}
	
	 /********��д�Ĵ���******/
	
		/**
		 * ���������ص�ǰ·��Я������Ҫת������Ϣ��������Ϣ�б����ڵ㸱����>1��������
		 * �ٴ��緢��Ϣ��������Ϣ�б�
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
				assert nrofCopies != null : "SnW message " + m + " didn't have " + 
					"nrof copies property!";
				
				Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//�õ���Ϣת������
			
					if (nrofCopies == 1 &&sprayTimes<2) {//�����󣨽���������緢����
						m.updateProperty(SPRAY_TIMES,sprayTimes+1);
						m.updateProperty(SPRAY_FLAG,1);
						list.add(m);				
					}
			}	
			return list;
		}
		/***********/
	
		
		
		/**��д�Ĵ���***/
		
		/***
		 *FIN
		 *��ÿ����Ϣ�ַ����Ӽ���,����ActiveRouter��tryMessagesToConnections��д
		 */
		protected Message tryMessagesToConnectionsFin(List<Message> messages,
				List<Connection> connections) {
			for (int i=0, n=messages.size(); i<n; i++) {
				Message msg = messages.get(i);
				Connection started = tryAllConnection(msg, connections); 
				if (started != null) { 
					return msg;
				}
			}
			return null;
		}
		//�ȴ��׶� ��Ҫ��д
		protected Connection tryMessagesToConnectionsWait(List<Message> messages,
				List<Connection> connections)  {
			for (int i=0, n=connections.size(); i<n; i++) {
				Connection con = connections.get(i);
				Message started;
				try {
					started = trySprayAgainMessages(con, messages);
					if (started != null) { 
						return con;
					}
				} catch (Exception e) {
					e.printStackTrace();
				} 
				
			}
			
			return null;
		}
		
		/**
		 *FIN
		 *3.���ｫÿ����Ϣ���͸��������Ӽ���
		 *��������Ĳ�����һ����Ϣ������ڵ���������ӣ�
		 *��ô����ͱȽ��ʺ�ɸѡ��ÿ����Ϣ���ʵ��м̽ڵ㡣
		 *
		 * ����˵ѡ�������ھӽڵ�������Ϣ��Ŀ�Ľڵ�����ģ�
		 * �Ϳ��Ա����������ӣ��õ�ÿ�����ӵ��ھӽڵ��λ�ã�
		 * Ȼ��õ���Ϣ��Ŀ�Ľڵ��λ�ã������Ƚϣ�ѡ������ʵ�����
		 */
		protected Connection tryAllConnection(Message msg, List<Connection> connections) {
			 
			List<Connection> finalconnections=new ArrayList<Connection> ();
			if(connections.size()<=1)//ֻ��һ�����ӣ���Ϣֱ��Ͷ�ݸ�������ӵĶԷ��ڵ�
				finalconnections=connections;
			else//������Ӳ��ò�ͬ�Ĳ���ɸѡ���Ϻõļ�������
				finalconnections=getConnectionByStrategy(msg,connections);
			//��ÿ�����Ӵ������Ϣ
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
		 * ���ݲ�ͬ�Ĳ���ȥѡ��ͬ�ķ���ѡ����ʵ�����
		 */
		protected List<Connection>  getConnectionByStrategy(Message msg, List<Connection> connections) {
			List<Connection> newConnections=new ArrayList<Connection>();
			DTNHost destination=msg.getTo();//��ϢĿ�Ľڵ�	
			HashMap<Double,Connection> history= new HashMap<Double,Connection >();//�ھӽڵ��Ŀ�Ľڵ���ʷ��������
			HashMap<String,Double> destinationInfo=new HashMap<String,Double>();//��ϢĿ�Ľڵ���Ϣ
			double maxupdateTime=0;//�ھӽڵ���Ŀ�Ľڵ����һ������ʱ������ʱ��(�ھӽڵ������Ŀ�Ľڵ�������ʱ��)
			for (Connection con : connections) {
				DTNHost other= con.getOtherNode(getHost());//��ȡ�ھӽڵ�
				HashMap<DTNHost, HashMap<String,Double>> otherFinUtility=other.getFinUtility();//��ȡ�ھӽڵ���Ϣ��
				//���Ŀ�Ľڵ���ھӽڵ���ʷ������Ϣ
				if(otherFinUtility.containsKey(destination)){
					if(otherFinUtility.get(destination).get("updateTime")>maxupdateTime){
						destinationInfo=otherFinUtility.get(destination);
						maxupdateTime=destinationInfo.get("updateTime");
					}
					history.put(otherFinUtility.get(destination).get("count"),con);
				}
			}
			
			if(history.isEmpty()){
				newConnections=getConnectionByAngle(msg,connections);//û����ʷ������Ϣ�����ݽǶ�
			}else{//����ʷ������Ϣ
				//����Ŀ�Ľڵ�Ļ��Χ���ҳ��ܹ����Ͷ�ݵ�Ŀ�Ľڵ�Ļ��Χ�Ľڵ�
				newConnections=getConnectionByRange(destinationInfo,connections);
				if(newConnections.isEmpty()){//�����������Ľڵ�,�͸�����ʷ��������
			    	newConnections=getConnectionByHistory(destination,history,connections);
			    }
			}
			return newConnections;
		}
		/**
		 * FIN
		 * �����ھӽڵ�Ƕȣ��ҳ��ǶȲ�ͬ�Ľڵ㼯��
		 */
		protected List<Connection>  getConnectionByAngle(Message msg, List<Connection> connections) {
			List<Connection> newConnections=new ArrayList<Connection>();
			Connection ul=null;//����90 - 180
			Connection ur=null;//����0 - 90
			Connection dl=null;//����-90 - -180
			Connection dr=null;//����0 - -90
			double ulmin=46,urmin = 46,dlmin=46,drmin=46;
				for (Connection con : connections) {
					DTNHost other= con.getOtherNode(getHost());
					double otherAngle=getHost().getFinUtility().get(other).get("avgDirection");
					if(otherAngle>0&&otherAngle<=90){//����
							if(Math.abs(otherAngle-45)<urmin){
								urmin=Math.abs(otherAngle-45);
								ur=con;
							}		
					}else if(90<otherAngle&&otherAngle<=180){//����
						
							if(Math.abs(otherAngle-135)<ulmin){
								ulmin=Math.abs(otherAngle-135);
								ul=con;			
						}
					}else if(0>otherAngle&&otherAngle>=-90){//����
						
							if(Math.abs(otherAngle+45)<drmin){
								drmin=Math.abs(otherAngle+45);
								dr=con;		
						}
					}else if(-90>otherAngle&&otherAngle>=-180){//����	
							if(Math.abs(otherAngle+135)<dlmin){
								dlmin=Math.abs(otherAngle+135);
								dl=con;
							}
					}	
				}
				if(ul!=null)
					newConnections.add(ul);
				if(ur!=null)
					newConnections.add(ur);
				if(dr!=null)
					newConnections.add(dr);
			    if(dl!=null)
					newConnections.add(dl);
				return newConnections;
		}
		/**
		 * FIN
		 * �����ھӽڵ���Ŀ�Ľڵ����ʷ�����������ҳ�Ч����õĵĽڵ㼯��
		 */
		protected List<Connection>  getConnectionByHistory(DTNHost destination, HashMap<Double,Connection> history,List<Connection> connections) {
			List<Connection> newConnections=new ArrayList<Connection>();
			//Դ�ڵ㱾����Ŀ�Ľڵ����������
			HashMap<DTNHost, HashMap<String,Double>> hostFinUtility=getHost().getFinUtility();
			if(hostFinUtility.containsKey(destination)){
				history.put(hostFinUtility.get(destination).get("count"),null);
			}
			Object[] key_arr = history.keySet().toArray();   
			Arrays.sort(key_arr);
			for(int i=key_arr.length-1;i>=0;i--){
				if(history.get(key_arr[i])!=null){
					newConnections.add(history.get(key_arr[i]));
				}else{
					break;
				}
					
			}	
			return newConnections;	
		}
		/**
		 * FIN
		 * ����Ŀ�Ľڵ��ƶ��ķ�Χѡ����ʵ��м̽ڵ�
		 */
		protected List<Connection>  getConnectionByRange(HashMap<String,Double>  destinationInfo, List<Connection> connections) {
			List<Connection> newConnections=new ArrayList<Connection>();
			//����Ŀ�Ľڵ����ڵķ�Χ
			double  desavgSpeed=(double)destinationInfo.get("avgSpeed");
			double  desx=(double)destinationInfo.get("x");
			double  desy=(double)destinationInfo.get("y");
			double  desupdateTime=(double)destinationInfo.get("updateTime");
			double  desavgDirection=(double)destinationInfo.get("avgDirection");
			double  timediff=SimClock.getTime()-desupdateTime;
			double  radius=timediff*desavgSpeed;//ֱ��
			//�ж�Դ�ڵ��Ƿ���Ŀ�Ľڵ�Ļ��Χ��
			DTNHost source=getHost();
			double sourcex=source.getLocation().getX();
			double sourcey=source.getLocation().getY();
			double sourceDistance= Math.sqrt(Math.pow(sourcex - desx,2)+Math.pow(sourcey - desy,2));//б��
			//Դ�ڵ㲻�ڻ��Χ��(��Ч)
			if(sourceDistance>radius){
			HashMap<Double,Connection> upperBound=new HashMap<Double,Connection>();//�Ͻ缯��
			HashMap<Double,Connection> lowerBound=new HashMap<Double,Connection>();//�½缯��
			for (Connection con : connections) {
				DTNHost other= con.getOtherNode(source);
				HashMap<String,Double> otherinfo=source.getFinUtility().get(other);
				double otherx=(double)otherinfo.get("x");
				double othery=(double)otherinfo.get("y");
				double otheravgSpeed=(double)otherinfo.get("avgSpeed");
				double otheravgDirection=(double)otherinfo.get("avgDirection");
				double centerAngle=Math.atan2(othery-desy,otherx-desx)*(180/Math.PI);//���ĽǶ�
				double hypotenuse= Math.sqrt(Math.pow(otherx - desx,2)+Math.pow(othery - desy,2));//б��
				double offsetAngle=Math.asin(radius/hypotenuse)*(180/Math.PI);//�Ƕȷ�Χ
				double distanceTmp=Math.pow(hypotenuse,2)-Math.pow(radius,2);
				double distance=Math.sqrt(Math.abs(distanceTmp));//���е�ľ���
				//�����������ϣ����ϸ����ƶ������ٶȣ�����
				double upperAngle=centerAngle+offsetAngle;//�ϱ߽�Ƕ�
				double lowerAngle=centerAngle-offsetAngle;//�±߽�Ƕ�
				double commonUtility=1/otheravgSpeed+distance;
				if(distanceTmp<0){//�ھӲ���Ŀ�Ľڵ���Χ��		
				  if(otheravgDirection<=upperAngle&&otheravgDirection>=lowerAngle){
				     double utility=Math.abs(upperAngle-otheravgDirection)+commonUtility;
				     upperBound.put(utility, con);
				     utility=Math.abs(lowerAngle-otheravgDirection)+commonUtility;
				     lowerBound.put(utility, con);
				  }  
				}else{
					  upperBound.put(commonUtility, con);
					  lowerBound.put(commonUtility, con);
				}
			}
			//ѡ���������ϵ�һ��
			Connection tmpCon=null;
			if(desavgDirection>0){
				//�Ͻ缯�ϲ�Ϊ��
				if(!upperBound.isEmpty()){
					Object[] key_upper = upperBound.keySet().toArray();   
					Arrays.sort(key_upper); 
					 tmpCon=upperBound.get(key_upper[0]);
					if(tmpCon!=null ){
						 newConnections.add(tmpCon);
					}
				}
				//�½缯�ϲ�Ϊ��
				if(!lowerBound.isEmpty()){
					Object[] key_lower = lowerBound.keySet().toArray();   
					Arrays.sort(key_lower); 
					if(lowerBound.get(key_lower[0])!=null ){
					   if(tmpCon !=lowerBound.get(key_lower[0])){
						   newConnections.add(lowerBound.get(key_lower[0]));  //�Ͻ���½�ѡ������Ӳ�ͬ��ֱ������
					   }else if(key_lower.length>1){
						   newConnections.add(lowerBound.get(key_lower[1]));//ѡ����½��������Ͻ�������ͬ�����½����Ӵ���1���½�ĵڶ������Ӽ���	  
					   }
					}
				}	
			}else{
				//�½缯�ϲ�Ϊ��
				if(!lowerBound.isEmpty()){
					Object[] key_lower = lowerBound.keySet().toArray();   
					Arrays.sort(key_lower); 
					 tmpCon=lowerBound.get(key_lower[0]);
					if(tmpCon!=null ){
						
						 newConnections.add(tmpCon);
					}
				}
				//�Ͻ缯�ϲ�Ϊ��
				if(!upperBound.isEmpty()){
					Object[] key_upper = upperBound.keySet().toArray();   
					Arrays.sort(key_upper); 
					if(upperBound.get(key_upper[0])!=null ){
					   if(tmpCon !=upperBound.get(key_upper[0])){
						   newConnections.add(upperBound.get(key_upper[0]));  
					   }else if(key_upper.length>1){
						   newConnections.add(upperBound.get(key_upper[1]));
					   }
					}
				}	
			}
		}
			return newConnections;
		}
		/**
		 * FIN
		 *�ȴ��׶��ٴ��緢���� 
		 */
		protected Message trySprayAgainMessages(Connection con, List<Message> messages) throws Exception
		{//����Ч�ø���
			DTNHost otherNode =con.getOtherNode(getHost());//��öԷ��ڵ�
			
			for (Message m : messages) {
				Integer sprayFlag = (Integer)m.getProperty(SPRAY_FLAG);//�õ���Ϣת����־
				Integer copies=(Integer)m.getProperty(MSG_COUNT_PROPERTY);
				if(sprayFlag==1){
					copies=resetCopies((2),m.getSize(),otherNode);//����ӵ���������ٴ��緢�ĸ�����
				}
				if(copies != 0){		
				if(otherNode.getFinUtility().containsKey(m.getTo()))//�ж��ھӽڵ���Ŀ�Ľڵ��Ч���Ƿ����
				{
					if(getHost().getFinUtility().containsKey(m.getTo()))//�жϵ�ǰ�ڵ���Ŀ�Ľڵ��Ч���Ƿ����
					{
						double otherCount=otherNode.getFinUtility().get(m.getTo()).get("count");
						double hostCount=getHost().getFinUtility().get(m.getTo()).get("count");
						boolean utility_check=hostCount < otherCount;
						if(utility_check){//��ǰ�ڵ���Ŀ�Ľڵ���������С���ھӽڵ���Ŀ�Ľڵ���������
							m.updateProperty(MSG_COUNT_PROPERTY,copies);
							int retVal = startTransfer(m, con); 
							if (retVal == RCV_OK) {
								m.updateProperty(SPRAY_FLAG,0);//��ʼ�����緢
								return m;	
							}else if (retVal > 0) { 
								return null; 
							}
							
						}else{//����ת��
							return null;
						}
					}else{//��ǰ�ڵ���û��Ŀ�Ľڵ���Ϣ��ֱ��ת��
							m.updateProperty(MSG_COUNT_PROPERTY,copies);
							int retVal = startTransfer(m, con); 
							if (retVal == RCV_OK) {
								m.updateProperty(SPRAY_FLAG,0);//��ʼ�����緢
								return m;	// accepted a message, don't try others
							}else if (retVal > 0) { 
								return null; // should try later -> don't bother trying others
							}
					}
				}else{//�ھӽڵ�û��Ŀ�Ľڵ���Ϣ��ת��
					return null;
				}
				}
			}
			return null; // no message was accepted		
		}
		
		//����ӵ������ø�����
	protected int resetCopies(int num,int size,DTNHost otherNode){
				double totalsize = 0.0;
				double freesize = 0.0;
				for (int i=0, n=getConnections().size(); i<n; i++) {
					Connection con = getConnections().get(i);
					DTNHost othernode=con.getOtherNode(getHost());
					totalsize+=othernode.getRouter().getBufferSize();
					freesize+=othernode.getRouter().getFreeBufferSize();
				}
					double prob=(totalsize-freesize)/totalsize;
					if(prob>=0.7){
						num=0;
					}else if(prob>=0.5){
						num=(int)Math.ceil(num*(1-prob));//����ȡ��
					}else if(prob>=0.2){
						num++;
					}else{
						num*=2;	  
					}
				if(num*size>freesize){
					num=(int)Math.ceil(freesize/size)/2;
				}
				if(num%2!=0)//�߼��ϲ���Ϊһ
					num++;
				return num;	
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
	public SAWRouter2 replicate() {
		return new SAWRouter2(this);
	}
}
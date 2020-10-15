/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Random;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.MessageListener;
import core.Settings;
import core.SimClock;
import core.Tuple;

/**
 * Superclass of active routers. Contains convenience methods (e.g. 
 * {@link #getOldestMessage(boolean)}) and watching of sending connections (see
 * {@link #update()}).
 */
public abstract class ActiveRouter extends MessageRouter {
	/** Delete delivered messages -setting id ({@value}). Boolean valued.
	 * If set to true and final recipient of a message rejects it because it
	 * already has it, the message is deleted from buffer. Default=false. */
	public static final String DELETE_DELIVERED_S = "deleteDelivered";
	/** should messages that final recipient marks as delivered be deleted
	 * from message buffer */
	protected boolean deleteDelivered;
	
	/** prefix of all response message IDs */
	public static final String RESPONSE_PREFIX = "R_";
	/** how often TTL check (discarding old messages) is performed */
	/**多久一次TTL检查*/
	public static int TTL_CHECK_INTERVAL = 60;
	/** 当前用于发送的连接 */
	/** connection(s) that are currently used for sending */
	protected ArrayList<Connection> sendingConnections;
	/** sim time when the last TTL check was done */
	/** sim时间是最后一次TTL检查完成的时间*/
	private double lastTtlCheck;
	
	/** SprayAndWait router's settings name space ({@value})*/ 
	public static final String SPRAYANDWAIT_NS = "SprayAndWaitRouter";
	/** Message property key */
	public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "." +
		"copies";
	
	//自定义区
	public int initialL=5;
	public static final String SPRAY_TIMES = "spray_times";
	public static final String SPRAY_FLAG = "spray_flag";
	public static final String FORM_COPY = "form_copy";
	public static final String OTHER_COPY = "other_copy";
	public  double copyNumber = 0;
	public  boolean copyFlag = false;
	/** 生存周期 */
	public int msgttl;
	public double endtime;
	/** 历史效用的时间间隔，应该比单个副本bufftime时间长*/
	public double secondsInTimeUnit;//分的细，保证最开始的丢包不被忽略
	/** 比较历史效用的多个时间间隔*/
	private int comparetimes;
	/** 单个副本停留时间*/
	public int f_buffertime;
	/** 单个副本停留时间*/
	public int t_buffertime;
	
	/** 投递选择*/
	public HashMap<Message,Connection>toTrans;
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public ActiveRouter(Settings s) {
		super(s);
		if (s.contains(DELETE_DELIVERED_S)) {
			this.deleteDelivered = s.getBoolean(DELETE_DELIVERED_S);
		}
		else {
			this.deleteDelivered = false;
		}
		
		Settings ms = new Settings("Scenario");
		this.endtime=ms.getDouble("endTime");
		this.msgttl=s.getInt("msgTtl");
		this.toTrans=new HashMap<Message,Connection>();
		
	
	
//		System.out.println(scen.getEndTime());		
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected ActiveRouter(ActiveRouter r) {
		super(r);
		this.deleteDelivered = r.deleteDelivered;
		this.msgttl=r.msgttl;
		this.endtime=r.endtime;
		this.f_buffertime=(int)(this.msgttl*60*0.5);
		this.t_buffertime=(int)(this.msgttl*60*0.3);
		this.secondsInTimeUnit=this.msgttl*60*0.1;
		//存在的问题
		this.comparetimes=(int)(f_buffertime/secondsInTimeUnit);
		
		System.out.println("比较次数"+comparetimes);
		System.out.println("时间"+this.f_buffertime);
		
		this.toTrans=r.toTrans;
	}
	
	@Override
	public void init(DTNHost host, List<MessageListener> mListeners) {
		super.init(host, mListeners);
		this.sendingConnections = new ArrayList<Connection>(1);
		this.lastTtlCheck = 0;
	}
	
	/**
	 * Called when a connection's state changes. This version doesn't do 
	 * anything but subclasses may want to override this.
	 */
	@Override
	public void changedConnection(Connection con) { }
	
	@Override
	public boolean requestDeliverableMessages(Connection con) {
		if (isTransferring()) {
			return false;
		}
		
		DTNHost other = con.getOtherNode(getHost());//即得到本节点
		/* do a copy to avoid concurrent modification exceptions 
		 * (startTransfer may remove messages) */
		ArrayList<Message> temp = 
			new ArrayList<Message>(this.getMessageCollection());
		for (Message m : temp) {
			if (other == m.getTo()) {
				if (startTransfer(m, con) == RCV_OK) {
					return true;
				}
			}
		}
		return false;
	}
	
	@Override 
	public boolean createNewMessage(Message m) {
		makeRoomForNewMessage(m.getSize());
		return super.createNewMessage(m);	
	}
	
	@Override
	public int receiveMessage(Message m, DTNHost from) {
		int recvCheck = checkReceiving(m); 
		if (recvCheck != RCV_OK) {
			return recvCheck;
		}

		// seems OK, start receiving the message
		return super.receiveMessage(m, from);
	}
	
	@Override
	public Message messageTransferred(String id, DTNHost from) {
		Message m = super.messageTransferred(id, from);

		/**
		 *  N.B. With application support the following if-block
		 *  becomes obsolete, and the response size should be configured 
		 *  to zero.
		 */
		// check if msg was for this host and a response was requested
		//消息是到这个主机且需要应答
		if (m.getTo() == getHost() && m.getResponseSize() > 0) {
			// generate a response message
			Message res = new Message(this.getHost(),m.getFrom(), 
					RESPONSE_PREFIX+m.getId(), m.getResponseSize());
			this.createNewMessage(res);
			this.getMessage(RESPONSE_PREFIX+m.getId()).setRequest(m);
		}
		
		return m;
	}
	
	/**
	 * Returns a list of connections this host currently has with other hosts.
	 * @return a list of connections this host currently has with other hosts
	 */
	protected List<Connection> getConnections() {
		return getHost().getConnections();
	}
	
	/**
	 * Tries to start a transfer of message using a connection. Is starting
	 * succeeds, the connection is added to the watch list of active connections
	 * @param m The message to transfer
	 * @param con The connection to use
	 * @return the value returned by 
	 * {@link Connection#startTransfer(DTNHost, Message)}
	 */
	protected int startTransfer(Message m, Connection con) {
		int retVal;
		
		if (!con.isReadyForTransfer()) {
			return TRY_LATER_BUSY;
		}
		
		retVal = con.startTransfer(getHost(), m);//开始传输
		
		if (retVal == RCV_OK) { // started transfer
			addToSendingConnections(con);//传输成功中，将链接添加传输链接中
		}else if (deleteDelivered && retVal == DENIED_OLD && 
				m.getTo() == con.getOtherNode(this.getHost())) {
			/* final recipient has already received the msg -> delete it */
			/* 目的节点已经接收到消息，删除*/
			this.deleteMessage(m.getId(), false);//删除消息
		}
		
		return retVal;
	}
	
	/**
	 * Makes rudimentary checks (that we have at least one message and one
	 * connection) about can this router start transfer.
	 * @return True if router can start transfer, false if not
	 */
	protected boolean canStartTransfer() {
		if (this.getNrofMessages() == 0) {//缓冲区空
			return false;
		}
		if (this.getConnections().size() == 0) {//邻居节点空
			return false;
		}
		
		return true;
	}
	
	/**
	 * Checks if router "wants" to start receiving message (i.e. router 
	 * isn't transferring, doesn't have the message and has room for it).
	 * @param m The message to check
	 * @return A return code similar to 
	 * {@link MessageRouter#receiveMessage(Message, DTNHost)}, i.e. 
	 * {@link MessageRouter#RCV_OK} if receiving seems to be OK, 
	 * TRY_LATER_BUSY if router is transferring, DENIED_OLD if the router
	 * is already carrying the message or it has been delivered to
	 * this router (as final recipient), or DENIED_NO_SPACE if the message
	 * does not fit into buffer
	 */
	protected int checkReceiving(Message m) {
		if (isTransferring()) {
			return TRY_LATER_BUSY; // only one connection at a time
		}
	
		if ( hasMessage(m.getId()) || isDeliveredMessage(m) ){
			return DENIED_OLD; // already seen this message -> reject it
		}
		
		if (m.getTtl() <= 0 && m.getTo() != getHost()) {
			/* TTL has expired and this host is not the final recipient */
			return DENIED_TTL; 
		}
		
		/*删除最旧的消息，但不是发送的新的*/
		/* remove oldest messages but not the ones being sent */
		if (!makeRoomForMessage(m.getSize())) {
			return DENIED_NO_SPACE; // couldn't fit into buffer -> reject
		}
		
		return RCV_OK;
	}
	
	/** 
	 * 从缓冲区中删除消息（最早的），直到新消息有足够的空间。
	 * Removes messages from the buffer (oldest first) until
	 * there's enough space for the new message.
	 * @param size Size of the new message 
	 * transferred, the transfer is aborted before message is removed
	 * @return True if enough space could be freed, false if not
	 */
	protected boolean makeRoomForMessage(int size){
		if (size > this.getBufferSize()) {
			return false; // message too big for the buffer
		}
			
		int freeBuffer = this.getFreeBufferSize();
		/* delete messages from the buffer until there's enough space */
		while (freeBuffer < size) {
			Message m = getOldestMessage(true); // don't remove msgs being sent

			if (m == null) {
				return false; // couldn't remove any more messages
			}			
			
			/* delete message from the buffer as "drop" */
			deleteMessage(m.getId(), true);
			freeBuffer += m.getSize();
		}
	
		return true;
	}
	
	/**
	 * 删除TTL到期的消息
	 * Drops messages whose TTL is less than zero.
	 */
	protected void dropExpiredMessages() {
		Message[] messages = getMessageCollection().toArray(new Message[0]);
		for (int i=0; i<messages.length; i++) {
			int ttl = messages[i].getTtl(); 
			if (ttl <= 0) {
				deleteMessage(messages[i].getId(), true);
			}
		}
	}
	
	/**
	 * Tries to make room for a new message. Current implementation simply
	 * calls {@link #makeRoomForMessage(int)} and ignores the return value.
	 * Therefore, if the message can't fit into buffer, the buffer is only 
	 * cleared from messages that are not being sent.
	 * @param size Size of the new message
	 */
	protected void makeRoomForNewMessage(int size) {
		makeRoomForMessage(size);
	}

	
	/**
	 * Returns the oldest (by receive time) message in the message buffer 
	 * (that is not being sent if excludeMsgBeingSent is true).
	 * @param excludeMsgBeingSent If true, excludes message(s) that are
	 * being sent from the oldest message check (i.e. if oldest message is
	 * being sent, the second oldest message is returned)
	 * @return The oldest message or null if no message could be returned
	 * (no messages in buffer or all messages in buffer are being sent and
	 * exludeMsgBeingSent is true)
	 */
	protected Message getOldestMessage(boolean excludeMsgBeingSent) {
		Collection<Message> messages = this.getMessageCollection();
		Message oldest = null;
		for (Message m : messages) {
			
			if (excludeMsgBeingSent && isSending(m.getId())) {
				continue; // skip the message(s) that router is sending
			}
			
			if (oldest == null ) {
				oldest = m;
			}
			else if (oldest.getReceiveTime() > m.getReceiveTime()) {
				oldest = m;
			}
		}
		
		return oldest;
	}
	
	/**
	 * Returns a list of message-connections tuples of the messages whose
	 * recipient is some host that we're connected to at the moment.
	 * @return a list of message-connections tuples
	 */
	protected List<Tuple<Message, Connection>> getMessagesForConnected() {
		if (getNrofMessages() == 0 || getConnections().size() == 0) {
			/* no messages -> empty list */
			return new ArrayList<Tuple<Message, Connection>>(0); 
		}

		List<Tuple<Message, Connection>> forTuples = 
			new ArrayList<Tuple<Message, Connection>>();
		for (Message m : getMessageCollection()) {
			for (Connection con : getConnections()) {
				DTNHost to = con.getOtherNode(getHost());
				if (m.getTo() == to) {
					forTuples.add(new Tuple<Message, Connection>(m,con));
				}
			}
		}
		
		return forTuples;
	}
	
	/**
	 * Tries to send messages for the connections that are mentioned
	 * in the Tuples in the order they are in the list until one of
	 * the connections starts transferring or all tuples have been tried.
	 * @param tuples The tuples to try
	 * @return The tuple whose connection accepted the message or null if
	 * none of the connections accepted the message that was meant for them.
	 */
	protected Tuple<Message, Connection> tryMessagesForConnected(
			List<Tuple<Message, Connection>> tuples) {
		if (tuples.size() == 0) {
			return null;
		}
		
		for (Tuple<Message, Connection> t : tuples) {
			Message m = t.getKey();//消息
			Connection con = t.getValue();//连接
			if (startTransfer(m, con) == RCV_OK) {//开始传输
				return t;
			}
		}
		
		return null;
	}
	
	 /**
	  * Goes trough the messages until the other node accepts one
	  * for receiving (or doesn't accept any). If a transfer is started, the
	  * connection is included in the list of sending connections.
	  * @param con Connection trough which the messages are sent
	  * @param messages A list of messages to try
	  * @return The message whose transfer was started or null if no 
	  * transfer was started. 
	  */
	protected Message tryAllMessages(Connection con, List<Message> messages) {
		for (Message m : messages) {
			int retVal = startTransfer(m, con); 
			if (retVal == RCV_OK) {
				return m;	// accepted a message, don't try others
			}
			else if (retVal > 0) { 
				return null; // should try later -> don't bother trying others
			}
		
		}
		
		return null; // no message was accepted		
	}
	
   /**我写的代码***/
	//sw5
	protected Message tryAllMessagesUn(Connection con, List<Message> messages) {
		for (Message m : messages) {
			if(!this.toTrans.containsKey(m) ||this.toTrans.get(m)==con){
						
			int retVal = startTransfer(m, con); 
			if (retVal == RCV_OK) {
				return m;	// accepted a message, don't try others
			}
			else if (retVal > 0) { 
				return null; // should try later -> don't bother trying others
			}
		}
		}
		
		return null; // no message was accepted		
	}
	
	//SAW2专用
	protected Message trySomeMessages(Connection con, List<Message> messages) {//根据效用转发
		DTNHost otherNode =con.getOtherNode(getHost());//获得对方节点
		
		for (Message m : messages) {
		
			if(otherNode.gethu().containsKey(m.getTo()))
			{
				if(getHost().gethu().containsKey(m.getTo()))//判断本的效用是否存在
				{
					boolean utility_check=getUtility(getHost().gethu(),otherNode.gethu(),m.getTo());
				
					if(utility_check)
					{
						int retVal = startTransfer(m, con); 
						if (retVal == RCV_OK) {
//							System.out.println("邻大转");
							return m;	// accepted a message, don't try others
						}
						else if (retVal > 0) { 
							return null; // should try later -> don't bother trying others
						}
						
					}
					else//本大于邻
					{
						
//						System.out.println(utility_check+"本大不转");
						return null;
					}
				}
				else//本为空
				{
					int retVal = startTransfer(m, con); 
					if (retVal == RCV_OK) {
//						System.out.println("本为空转");
						//getHost().deleteMessage(m.getId(), false);
						return m;	// accepted a message, don't try others
					}
					else if (retVal > 0) { 
						return null; // should try later -> don't bother trying others
					}
				}
			}
			else//邻为空
			{
//				System.out.println("邻为空不转");
				return null;
			}
			
		}
		
		return null; // no message was accepted		
	}
	
	
	//SAW3专用
	protected Message tryCopyMessages(Connection con, List<Message> messages) {//有限喷发
		for (Message m : messages) {
			//System.out.println(m.getProperty(SPRAY_TIMES));
			
//			if((Integer)m.getProperty("spray_times")==0)
//			{
//				//System.out.println(getHost().getBufferOccupancy());
//				if(getHost().getBufferOccupancy()>90){
//				if(!getHost().gethu().containsKey(m.getTo())&& m.getTtl()<this.msgTtl/8)//到目的效用不存在
//				{
//					if(this.hasMessage(m.getId()))//存在该消息
//					{
//						deleteMessage(m.getId(), false);
//						return null;
//					}
//				}
//				}
//				
//			}else{
			
			m.setCreationTime(SimClock.getTime());//重置生存周期
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
			m.updateProperty(MSG_COUNT_PROPERTY, sprayTimes);

			int retVal = startTransfer(m, con); 
			if (retVal == RCV_OK) {
				return m;	// accepted a message, don't try others
			}else if (retVal > 0) { 
				return null; // should try later -> don't bother trying others
			}
		}
		return null; // no message was accepted		
	}
	

			//计算拥塞，获得副本数
	protected int resetCopies(int num,int size,DTNHost otherNode){
//				System.out.println("拥塞计算开始：");
				double totalsize = 0.0;
				double freesize = 0.0;
				
		
				for (int i=0, n=getConnections().size(); i<n; i++) {
					Connection con = getConnections().get(i);
					DTNHost othernode=con.getOtherNode(getHost());
					totalsize+=othernode.getRouter().getBufferSize();
					freesize+=othernode.getRouter().getFreeBufferSize();
					}

//					totalsize=otherNode.getRouter().getBufferSize();
//					freesize=otherNode.getRouter().getFreeBufferSize();
					double prob=(totalsize-freesize)/totalsize;
//					System.out.println("该簇比例为："+prob);
					
					if(prob>=0.7){
						num=0;
					}else if(prob>=0.5){
						num=(int)Math.ceil(num*(1-prob));//向上取整
					}else if(prob>=0.2){
						num++;
					}else{
						num*=2;	  
					}
				
				if(num*size>freesize){
					num=(int)Math.ceil(freesize/size)/2;
//					
				}
				if(num%2!=0)//逻辑上不能为一
					num++;
		
				return num;	
			}
			
			
	//SAW4专用
	protected Message trySomeCopyMessages(Connection con, List<Message> messages) throws Exception
	{//根据效用复制
		DTNHost otherNode =con.getOtherNode(getHost());//获得对方节点
		for (Message m : messages) {
			int reSparyTime=resetCopies(2,m.getSize(),otherNode);//获得副本数
			if(reSparyTime !=0){//(有效)
		
			if(otherNode.gethu().containsKey(m.getTo()))//判断邻的效用是否存在
			{
				if(getHost().gethu().containsKey(m.getTo()))//判断本的效用是否存在
				{
				
					boolean Utility_check=getUtility(getHost().gethu(),otherNode.gethu(),m.getTo());
					if(Utility_check){
						m.setCreationTime(SimClock.getTime());//重置生存周期
//						Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
						m.updateProperty(MSG_COUNT_PROPERTY,reSparyTime);
					
						int retVal = startTransfer(m, con); 
						if (retVal == RCV_OK) {
							m.updateProperty(SPRAY_FLAG,0);//开始持续喷发
//							System.out.println("邻大转发");
							return m;	
						}else if (retVal > 0) {
							return null; 
						}
						
					}else{//本大于邻
//						System.out.println("本大不转发");
						return null;
					}
				}else{//本为空
						m.setCreationTime(SimClock.getTime());//重置生存周期
						m.updateProperty(MSG_COUNT_PROPERTY,reSparyTime);
						int retVal = startTransfer(m, con); 
						if (retVal == RCV_OK) {
							m.updateProperty(SPRAY_FLAG,0);//开始持续喷发
							return m;	// accepted a message, don't try others
						}else if (retVal > 0) { 
							return null; // should try later -> don't bother trying others
						}
				}
			}else{//邻为空
				//System.out.println("邻为空不转");
				return null;
			}
			}	
		}
		
		return null; // no message was accepted		
	}
	
	//判断效用值
	private boolean getUtility(HashMap<DTNHost,HashMap<Integer,Integer>> hun,HashMap<DTNHost,HashMap<Integer,Integer>> oun,DTNHost to) {
			
		int timenum=(int)(SimClock.getTime()/secondsInTimeUnit);//转换
		
		boolean check=false;
		if (timenum < comparetimes) {
			return check;
		}
		
		int hun_total=0;
		int oun_total=0;
		
		HashMap<Integer,Integer> hun_hash=hun.get(to);//本机
		HashMap<Integer,Integer> oun_hash=oun.get(to);//其他
		//System.out.println("sda"+dtnhash);
		//比较到0次间隔时候
		for(int i=0; i<=comparetimes;i++)
		{
			if(hun_hash.containsKey(timenum-i))
				hun_total+=hun_hash.get(timenum-i);
			
			if(oun_hash.containsKey(timenum-i))
				
				oun_total+=oun_hash.get(timenum-i);
			
			
		}
		
		if((double)hun_total<(double)oun_total)
	          check=true;
		
//		System.out.println("主机平均"+(double)hun_total/comparetimes);
//		System.out.println("邻居平均"+(double)oun_total/times);
			
		return check;	
	}
	/****************************/
	
	/**
	 * Tries to send all given messages to all given connections. Connections
	 * are first iterated in the order they are in the list and for every
	 * connection, the messages are tried in the order they are in the list.
	 * Once an accepting connection is found, no other connections or messages
	 * are tried.
	 * @param messages The list of Messages to try
	 * @param connections The list of Connections to try
	 * @return The connections that started a transfer or null if no connection
	 * accepted a message.
	 */
	protected Connection tryMessagesToConnections(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started = tryAllMessages(con, messages); 
			if (started != null) { 
				return con;
			}
		}
		return null;
	}
	
	/**我写的代码***/
	
	/***
	 *FIN
	 *给每个消息分发连接集合
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
	//等待阶段 需要改写
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
				// TODO 自动生成的 catch 块
				e.printStackTrace();
			} 
			
		}
		
		return null;
	}
	
	/**
	 *FIN
	 *3.这里将每个消息发送给合适连接集合
	 *这个方法的参数是一个消息和这个节点的所有连接，
	 *那么这里就比较适合筛选出每个消息合适的中继节点。
	 *
	 * 比如说选出所有邻居节点中离消息的目的节点最近的，
	 * 就可以遍历所有连接，得到每个连接的邻居节点的位置，
	 * 然后得到消息的目的节点的位置，再做比较，选出最合适的连接
	 */
	protected Connection tryAllConnection(Message msg, List<Connection> connections) {
		 
		List<Connection> finalconnections=new ArrayList<Connection> ();
		if(connections.size()<=1)//只有一个连接，消息直接投递给这个连接的对方节点
			finalconnections=connections;
		else//多个连接采用不同的策略筛选出较好的几个连接
			finalconnections=getConnectionByStrategy(msg,connections);
		//给每个连接传输该消息
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
	 * 根据不同的策略去选择不同的方法选择合适的连接
	 */
	protected List<Connection>  getConnectionByStrategy(Message msg, List<Connection> connections) {
		List<Connection> newConnections=new ArrayList<Connection>();
		DTNHost destination=msg.getTo();//消息目的节点信息
	
		HashMap<Double,Connection> history= new HashMap<Double,Connection >();//历史相遇次数
		HashMap<String,Double> destinationInfo=new HashMap<String,Double>();
		double maxupdateTime=0;//
		
		for (Connection con : connections) {
			DTNHost other= con.getOtherNode(getHost());
			HashMap<DTNHost, HashMap<String,Double>> otherFinUtility=other.getFinUtility();
			//获得目的节点和该连接的节点历史相遇信息
			if(otherFinUtility.containsKey(destination)){
				if(otherFinUtility.get(destination).get("updateTime")>maxupdateTime){
					destinationInfo=otherFinUtility.get(destination);
					maxupdateTime=destinationInfo.get("updateTime");
				}
				history.put(otherFinUtility.get(destination).get("count"),con);
			}
		}
		
		if(history.isEmpty()){
			newConnections=getConnectionByAngle(msg,connections);//没有历史相遇信息，根据角度
		}else{//有历史相遇信息
			//根据目的节点的活动范围，找出能够最快投递到目的节点的活动范围的节点
			newConnections=getConnectionByRange(destinationInfo,connections);
			
			if(newConnections.isEmpty()){//不存在这样的节点,就根据历史相遇次数
				
		    	newConnections=getConnectionByHistory(destination,history,connections);
		    }
		}
		
		return newConnections;
		
	}
	/**
	 * FIN
	 * 根据邻居节点角度，找出角度不同的节点集合
	 */
	protected List<Connection>  getConnectionByAngle(Message msg, List<Connection> connections) {
		List<Connection> newConnections=new ArrayList<Connection>();
		
		Connection ul=null;//上左90 - 180
		Connection ur=null;//上右0 - 90
		Connection dl=null;//下左-90 - -180
		Connection dr=null;//下右0 - -90
		
		double ulmin=46,urmin = 46,dlmin=46,drmin=46;
		
			for (Connection con : connections) {
				
				DTNHost other= con.getOtherNode(getHost());
				double otherAngle=getHost().getFinUtility().get(other).get("avgDirection");
				
				if(otherAngle>0&&otherAngle<=90){//上右
						if(Math.abs(otherAngle-45)<urmin){
							urmin=Math.abs(otherAngle-45);
							ur=con;
						}		
				}else if(90<otherAngle&&otherAngle<=180){//上左
					
						if(Math.abs(otherAngle-135)<ulmin){
							ulmin=Math.abs(otherAngle-135);
							ul=con;			
					}
				}else if(0>otherAngle&&otherAngle>=-90){//下右
					
						if(Math.abs(otherAngle+45)<drmin){
							drmin=Math.abs(otherAngle+45);
							dr=con;		
					}
				}else if(-90>otherAngle&&otherAngle>=-180){//下左	
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
	 * 根据邻居节点与目的节点的历史相遇次数，找出效用最好的的节点集合
	 */
	protected List<Connection>  getConnectionByHistory(DTNHost destination, HashMap<Double,Connection> history,List<Connection> connections) {
		List<Connection> newConnections=new ArrayList<Connection>();
		
		//源节点本身与目的节点的相遇次数
		HashMap<DTNHost, HashMap<String,Double>> hostFinUtility=getHost().getFinUtility();
		if(hostFinUtility.containsKey(destination)){
			history.put(hostFinUtility.get(destination).get("count"),null);
		}
//		HashMap<Double,Connection> historys= new HashMap<Double,Connection >();//历史相遇次数
//
//		for (Connection con : connections) {
//			DTNHost other= con.getOtherNode(getHost());
//			
//			//历史相遇次数
//			if(other.gethu().containsKey(destination)){
//				
//				historys.put(returnUtility(other.gethu(),destination),con);
//			}
//		}
		
		Object[] key_arr = history.keySet().toArray();   
		Arrays.sort(key_arr);
		for(int i=key_arr.length-1;i>=0;i--){
			if(history.get(key_arr[i])!=null){
				newConnections.add(history.get(key_arr[i]));
			}else{
				break;
			}
				
		}
		
//		if(history.get(key_arr[key_arr.length-1])!=null){
//			newConnections.add(history.get(key_arr[key_arr.length-1]));
//		}

			
		return newConnections;	
	}
	//测试效用值
		private double returnUtility(HashMap<DTNHost,HashMap<Integer,Integer>> oun,DTNHost to) {
				
			int timenum=(int)(SimClock.getTime()/secondsInTimeUnit);//转换
			
			int oun_total=0;
			int max=comparetimes;
			if(timenum<comparetimes){
				max=timenum;
			}
			
			HashMap<Integer,Integer> oun_hash=oun.get(to);//其他
		
				
		
			//比较到max次间隔时候
			for(int i=0; i<max;i++)
			{
				if(oun_hash.containsKey(timenum-i))
					oun_total+=oun_hash.get(timenum-i);

			}
			
//			System.out.println("主机平均"+(double)hun_total/comparetimes);
//			System.out.println("邻居平均"+(double)oun_total/times);
				
			return oun_total;	
		}
	/**
	 * FIN
	 * 根据目的节点移动的范围选择合适的中继节点
	 */
	protected List<Connection>  getConnectionByRange(HashMap<String,Double>  destinationInfo, List<Connection> connections) {
		List<Connection> newConnections=new ArrayList<Connection>();
		//计算目的节点现在的范围
		double  desavgSpeed=(double)destinationInfo.get("avgSpeed");
		double  desx=(double)destinationInfo.get("x");
		double  desy=(double)destinationInfo.get("y");
		double  desupdateTime=(double)destinationInfo.get("updateTime");
		double  desavgDirection=(double)destinationInfo.get("avgDirection");
		double  timediff=SimClock.getTime()-desupdateTime;
		double  radius=timediff*desavgSpeed;//直径
		//判断源节点是否在目的节点的活动范围内
		DTNHost   source=getHost();
		HashMap<String,Double> sourceinfo=source.getFinUtility().get(source);
		double sourcex=(double)sourceinfo.get("x");
		double sourcey=(double)sourceinfo.get("y");
		double sourceDistance= Math.sqrt(Math.pow(sourcex - desx,2)+Math.pow(sourcey - desy,2));//斜边
		//源节点不在活动范围内(有效)
		if(sourceDistance>radius){
			
		HashMap<Double,Connection> upperBound=new HashMap<Double,Connection>();//上界集合
		HashMap<Double,Connection> lowerBound=new HashMap<Double,Connection>();//下界集合
		double directionUS=0;
		double directionLS=0;
		double otheravgSpeedS=0;
		double distanceS=0;
//		for (Connection con : connections) {
//			DTNHost other= con.getOtherNode(source);
//			HashMap<String,Double> otherinfo=source.getFinUtility().get(other);
//			
//			double otherx=(double)otherinfo.get("x");
//			double othery=(double)otherinfo.get("y");
//			double otheravgSpeed=(double)otherinfo.get("avgSpeed");
//			double otheravgDirection=(double)otherinfo.get("avgDirection");
//			
//			double centerAngle=Math.atan2(othery-desy,otherx-desx)*(180/Math.PI);//中心角度
//			double hypotenuse= Math.sqrt(Math.pow(otherx - desx,2)+Math.pow(othery - desy,2));//斜边
//			double offsetAngle=Math.asin(radius/hypotenuse)*(180/Math.PI);//角度范围
//			
//			double distanceTmp=Math.pow(hypotenuse,2)-Math.pow(radius,2);
//			double distance=Math.sqrt(Math.abs(distanceTmp));//到切点的距离
//			//建立两个集合，集合根据移动方向，速度，距离
//
//			double upperAngle=centerAngle+offsetAngle;//上边界角度
//			double lowerAngle=centerAngle-offsetAngle;//下边界角度
//			
//			otheravgSpeedS+=otheravgSpeed;
//			distanceS+=distance;
//			if(distanceTmp>0){//邻居不在目的节点活动范围内
//				
//			  if(otheravgDirection<=upperAngle&&otheravgDirection>=lowerAngle){
//				  directionUS+=Math.abs(upperAngle-otheravgDirection);
//				  directionLS+=Math.abs(lowerAngle-otheravgDirection);
//			     
//			  }
//			}
//		}
		//分割---------------------------------------------
		for (Connection con : connections) {
			DTNHost other= con.getOtherNode(source);
			HashMap<String,Double> otherinfo=source.getFinUtility().get(other);
			
			double otherx=(double)otherinfo.get("x");
			double othery=(double)otherinfo.get("y");
			double otheravgSpeed=(double)otherinfo.get("avgSpeed");
			double otheravgDirection=(double)otherinfo.get("avgDirection");
			
			double centerAngle=Math.atan2(othery-desy,otherx-desx)*(180/Math.PI);//中心角度
			double hypotenuse= Math.sqrt(Math.pow(otherx - desx,2)+Math.pow(othery - desy,2));//斜边
			double offsetAngle=Math.asin(radius/hypotenuse)*(180/Math.PI);//角度范围
			
			double distanceTmp=Math.pow(hypotenuse,2)-Math.pow(radius,2);
			double distance=Math.sqrt(Math.abs(distanceTmp));//到切点的距离
			//建立两个集合，集合根据移动方向，速度，距离
			
			double upperAngle=centerAngle+offsetAngle;//上边界角度
			double lowerAngle=centerAngle-offsetAngle;//下边界角度
//			System.out.println("斜边"+Math.abs(upperAngle-otheravgDirection)+"半径"+1/otheravgSpeed+"距离"+distance);
			
			double commonUtility=1/otheravgSpeed+distance;
			
			if(distanceTmp<0){//邻居不在目的节点活动范围内
				
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

		//选出两个集合第一个
		Connection tmpCon=null;
		if(desavgDirection>0){
			//上界集合不为空
			if(!upperBound.isEmpty()){
				Object[] key_upper = upperBound.keySet().toArray();   
				Arrays.sort(key_upper); 
				 tmpCon=upperBound.get(key_upper[0]);
				if(tmpCon!=null ){
					 newConnections.add(tmpCon);
				}
			}
			
			//下界集合不为空
			if(!lowerBound.isEmpty()){
				Object[] key_lower = lowerBound.keySet().toArray();   
				Arrays.sort(key_lower); 
				
				if(lowerBound.get(key_lower[0])!=null ){
				   if(tmpCon !=lowerBound.get(key_lower[0])){
					   newConnections.add(lowerBound.get(key_lower[0]));
					  
				   }else if(key_lower.length>1){
					   newConnections.add(lowerBound.get(key_lower[1]));
					  
				   }
				}
			}	
		}else{
			//下界集合不为空
			if(!lowerBound.isEmpty()){
				Object[] key_lower = lowerBound.keySet().toArray();   
				Arrays.sort(key_lower); 
				 tmpCon=lowerBound.get(key_lower[0]);
				if(tmpCon!=null ){
					
					 newConnections.add(tmpCon);
				}
			}
			
			//上界集合不为空
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
	
//		if(keyOne!=0&&keyTwo!=0){
//			copyNumber=1-(keyOne/(keyOne+keyTwo));
//			copyFlag=true;
//		}
	}
		
		return newConnections;
		
	
	}
	/**
	 * FIN
	 *等待阶段再次喷发策略 
	 */
	protected Message trySprayAgainMessages(Connection con, List<Message> messages) throws Exception
	{//根据效用复制
		DTNHost otherNode =con.getOtherNode(getHost());//获得对方节点
		
		for (Message m : messages) {
			
//			Integer sprayFlag = (Integer)m.getProperty(SPRAY_FLAG);
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
			Integer sprayFlag = (Integer)m.getProperty(SPRAY_FLAG);//得到消息转发次数
			Integer copies=(Integer)m.getProperty(MSG_COUNT_PROPERTY);
			if(sprayFlag==1){
				copies=resetCopies((2),m.getSize(),otherNode);//获得副本数
			}
			
			if(copies != 0){		
			if(otherNode.getFinUtility().containsKey(m.getTo()))//判断邻的效用是否存在
			{
				if(getHost().getFinUtility().containsKey(m.getTo()))//判断本的效用是否存在
				{
					double otherCount=otherNode.getFinUtility().get(m.getTo()).get("count");
					double hostCount=getHost().getFinUtility().get(m.getTo()).get("count");
					
					boolean utility_check=hostCount<otherCount;
				
					if(utility_check){
//						if(sprayFlag==1)
//						m.setCreationTime(SimClock.getTime());//重置生存周期
//						Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
						m.updateProperty(MSG_COUNT_PROPERTY,copies);
						int retVal = startTransfer(m, con); 
						if (retVal == RCV_OK) {
							m.updateProperty(SPRAY_FLAG,0);//开始持续喷发
//							System.out.println("邻大转发");
							return m;	
						}else if (retVal > 0) { 
							return null; 
						}
						
					}else{//本大于邻
//						System.out.println("本大不转发");
						return null;
					}
				}else{//本为空
						
						m.updateProperty(MSG_COUNT_PROPERTY,copies);
						int retVal = startTransfer(m, con); 
						if (retVal == RCV_OK) {
							m.updateProperty(SPRAY_FLAG,0);//开始持续喷发
							return m;	// accepted a message, don't try others
						}else if (retVal > 0) { 
							return null; // should try later -> don't bother trying others
						}
				}
			}else{//邻为空
				//System.out.println("邻为空不转");
				return null;
			}
			}
		}
		
		return null; // no message was accepted		
	}
	
	
	/**
	 * sw5用
	 * 
	 */
	protected Connection tryMessagesToConnectionsUn(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started = tryAllMessagesUn(con, messages); 
			if (started != null) { 
				return con;
			}
		}
//		System.out.println(this.toTrans);
		this.toTrans.clear();//清空消息-连接表
		return null;
	}
	
	/**
	 * 节点会把副本数为一的消息再次喷发swa4
	 * 
	 */
	protected Connection tryMessagesToConnectionsSomeCopy(List<Message> messages,
			List<Connection> connections)  {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started;
			try {
				started = trySomeCopyMessages(con, messages);
				if (started != null) { 
					return con;
				}
			} catch (Exception e) {
				// TODO 自动生成的 catch 块
				e.printStackTrace();
			} 
			
		}
		
		return null;
	}
	/**
	 * 节点会把副本数为一的消息再次喷发swa3
	 * 
	 */
	protected Connection tryMessagesToConnectionsCopy(List<Message> messages,
			List<Connection> connections)  {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started;
			try {
				started = tryCopyMessages(con, messages);
				if (started != null) { 
					return con;
				}
			} catch (Exception e) {
				// TODO 自动生成的 catch 块
				e.printStackTrace();
			} 
			
		}
		
		return null;
	}
	/**
	 * 节点会把副本数为一的消息传给效用值更高的邻居节点swa2
	 */
	protected Connection tryMessagesToConnectionsSome(List<Message> messages,
			List<Connection> connections) {
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			Message started = trySomeMessages(con, messages); 
			if (started != null) { 
				return con;
			}
		}
		
		return null;
	}
	
	/********************/
	
	/**
	 * Tries to send all messages that this router is carrying to all
	 * connections this node has. Messages are ordered using the 
	 * {@link MessageRouter#sortByQueueMode(List)}. See 
	 * {@link #tryMessagesToConnections(List, List)} for sending details.
	 * @return The connections that started a transfer or null if no connection
	 * accepted a message.
	 */
	protected Connection tryAllMessagesToAllConnections(){
		List<Connection> connections = getConnections();
		if (connections.size() == 0 || this.getNrofMessages() == 0) {
			return null;
		}

		List<Message> messages = 
			new ArrayList<Message>(this.getMessageCollection());//取得缓冲区所有消息
		this.sortByQueueMode(messages);

		return tryMessagesToConnections(messages, connections);
	}
		
	/**
	 * Exchanges deliverable (to final recipient) messages between this host
	 * and all hosts this host is currently connected to. First all messages
	 * from this host are checked and then all other hosts are asked for
	 * messages to this host. If a transfer is started, the search ends.
	 * @return A connection that started a transfer or null if no transfer
	 * was started
	 */
	protected Connection exchangeDeliverableMessages() {
		List<Connection> connections = getConnections();

		if (connections.size() == 0) {
			return null;
		}
		
		@SuppressWarnings(value = "unchecked")
		Tuple<Message, Connection> t =
			tryMessagesForConnected(sortByQueueMode(getMessagesForConnected()));

		if (t != null) {
			return t.getValue(); // started transfer，返回链接
		}
		
		//如果本节点没有消息的目的节点是邻居节点，那么看看邻居节点是否有消息的目的节点在本节点
		// didn't start transfer to any node -> ask messages from connected
		for (Connection con : connections) {
			if (con.getOtherNode(getHost()).requestDeliverableMessages(con)) {
				return con;
			}
		}
		
		return null;
	}


	
	/**
	 * Shuffles a messages list so the messages are in random order.
	 * @param messages The list to sort and shuffle
	 */
	protected void shuffleMessages(List<Message> messages) {
		if (messages.size() <= 1) {
			return; // nothing to shuffle
		}
		
		Random rng = new Random(SimClock.getIntTime());
		Collections.shuffle(messages, rng);	
	}
	
	/**
	 * Adds a connections to sending connections which are monitored in
	 * the update.
	 * @see #update()
	 * @param con The connection to add
	 */
	protected void addToSendingConnections(Connection con) {
		this.sendingConnections.add(con);
	}
		
	/**
	 * Returns true if this router is transferring something at the moment or
	 * some transfer has not been finalized.
	 * @return true if this router is transferring something
	 */
	public boolean isTransferring() {
		//情形1：本节点正在传输
		if (this.sendingConnections.size() > 0) {
			return true; // sending something
		}
		  //情型2：没有连接
		if (this.getHost().getConnections().size() == 0) {
			return false; // not connected
		}
		
		//情型3：有邻居节点，但有链路正在传输
		List<Connection> connections = getConnections();
		for (int i=0, n=connections.size(); i<n; i++) {
			Connection con = connections.get(i);
			if (!con.isReadyForTransfer()) {
				return true;	// a connection isn't ready for new transfer
			}
		}
		
		return false;		
	}
	
	/**
	 * Returns true if this router is currently sending a message with 
	 * <CODE>msgId</CODE>.
	 * @param msgId The ID of the message
	 * @return True if the message is being sent false if not
	 */
	public boolean isSending(String msgId) {
		for (Connection con : this.sendingConnections) {
			if (con.getMessage() == null) {
				continue; // transmission is finalized
			}
			if (con.getMessage().getId().equals(msgId)) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Checks out all sending connections to finalize the ready ones 
	 * and abort those whose connection went down. Also drops messages
	 * whose TTL <= 0 (checking every one simulated minute).
	 * @see #addToSendingConnections(Connection)
	 */
	@Override
	public void update() {
		
		super.update();
		
		/* in theory we can have multiple sending connections even though
		  currently all routers allow only one concurrent sending connection */
		/*理论上我们可以有多个发送连接，目前所有路由器只允许一个并发发送连接*/
		for (int i=0; i<this.sendingConnections.size(); ) {
			boolean removeCurrent = false;
			Connection con = sendingConnections.get(i);//得到第i个连接
			
			/** 1. 处理已完成传输的数据包 **/
			/* finalize ready transfers */
			if (con.isMessageTransferred()) {//如果当前连接消息传输完成
				if (con.getMessage() != null) {//如果当前连接是有完成的消息
					transferDone(con);//更新发送端
					con.finalizeTransfer();//更新接收端
				} /* else: some other entity aborted transfer */
				removeCurrent = true;
			}
			 /*** 2. 中止那些断开链路上的数据包 ***/
			/* remove connections that have gone down */
			else if (!con.isUp()) {//连接断开
				if (con.getMessage() != null) {
					transferAborted(con);//连接中断？
					con.abortTransfer();//中断当前的消息的传输
				}
				removeCurrent = true;
			} 
			  /*** 3. 必要时，删除那些最早接收到且不正在传输的消息 ***/
			if (removeCurrent) {//删除当前连接的为True
				// if the message being sent was holding excess buffer, free it
				//如果正在发送的消息持有多余的缓冲区，请释放它
				if (this.getFreeBufferSize() < 0) {
					this.makeRoomForMessage(0);
					
				}
				sendingConnections.remove(i);
			}
			else {
				/* index increase needed only if nothing was removed */
				i++;
			}
		}

	    /*** 4. 丢弃那些TTL到期的数据包(只在没有消息发送的情况下) ***/
		
		/* time to do a TTL check and drop old messages? Only if not sending */
		if (SimClock.getTime() - lastTtlCheck >= TTL_CHECK_INTERVAL && 
				sendingConnections.size() == 0) {
			dropExpiredMessages();
			lastTtlCheck = SimClock.getTime();
		}
	}
	
	/**
	 * Method is called just before a transfer is aborted at {@link #update()} 
	 * due connection going down. This happens on the sending host. 
	 * Subclasses that are interested of the event may want to override this. 
	 * @param con The connection whose transfer was aborted
	 */
	protected void transferAborted(Connection con) { }
	
	/**
	 * Method is called just before a transfer is finalized 
	 * at {@link #update()}.
	 * Subclasses that are interested of the event may want to override this.
	 * @param con The connection whose transfer was finalized
	 */
	protected void transferDone(Connection con) { }
	
}

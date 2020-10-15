/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import movement.MovementModel;
import movement.Path;
import routing.MessageRouter;
import routing.RoutingInfo;

/**
 * A DTN capable host.
 */
public class DTNHost implements Comparable<DTNHost> {
	private static int nextAddress = 0;
	private int address;

	private Coord location; 	// where is the host
	private Coord destination;	// where is it going

	private MessageRouter router;
	private MovementModel movement;
	private Path path;
	private double speed;
	private double nextTimeToMove;
	private String name;
	private List<MessageListener> msgListeners;
	private List<MovementListener> movListeners;
	private List<NetworkInterface> net;
	private ModuleCommunicationBus comBus;
	
	
	/**我写的变量**/
	private int drop_num;//丢包数
	private HashMap<DTNHost ,HashMap<Integer,Integer>>history_utility;//历史效用
	private HashMap<DTNHost ,HashMap<String,Double>> location_utility;//位置历史效用
	private HashMap<DTNHost ,HashMap<String,Double>> fin_utility;//fin效用表
	private double lastAgeUpdate=0;
	/**************************/
	static {
		DTNSim.registerForReset(DTNHost.class.getCanonicalName());
		reset();
	}
	/**
	 * Creates a new DTNHost.
	 * @param msgLs Message listeners
	 * @param movLs Movement listeners
	 * @param groupId GroupID of this host
	 * @param interf List of NetworkInterfaces for the class
	 * @param comBus Module communication bus object
	 * @param mmProto Prototype of the movement model of this host
	 * @param mRouterProto Prototype of the message router of this host
	 */
	public DTNHost(List<MessageListener> msgLs,
			List<MovementListener> movLs,
			String groupId, List<NetworkInterface> interf,
			ModuleCommunicationBus comBus, 
			MovementModel mmProto, MessageRouter mRouterProto) {
		this.comBus = comBus;
		this.location = new Coord(0,0);
		this.address = getNextAddress();
		this.name = groupId+address;
		this.net = new ArrayList<NetworkInterface>();
		
		/**我写的初始化**/
		this.drop_num=0;
		this.history_utility=new HashMap<DTNHost ,HashMap<Integer,Integer>>();
		this.location_utility=new HashMap<DTNHost ,HashMap<String,Double>>();
		this.fin_utility=new HashMap<DTNHost ,HashMap<String,Double>>();

		/**********/
		for (NetworkInterface i : interf) {
			NetworkInterface ni = i.replicate();
			ni.setHost(this);
			net.add(ni);
		}	

		// TODO - think about the names of the interfaces and the nodes
		//this.name = groupId + ((NetworkInterface)net.get(1)).getAddress();

		this.msgListeners = msgLs;
		this.movListeners = movLs;

		//通过复制原型创建实例
		// create instances by replicating the prototypes
		this.movement = mmProto.replicate();
		this.movement.setComBus(comBus);
		setRouter(mRouterProto.replicate());

		this.location = movement.getInitialLocation();

		this.nextTimeToMove = movement.nextPathAvailable();
		this.path = null;

		if (movLs != null) { // inform movement listeners about the location
			for (MovementListener l : movLs) {
				l.initialLocation(this, this.location);
			}
		}
	}
	
	/**
	 * 返回新的网络接口地址
	 * Returns a new network interface address and increments the address for
	 * subsequent calls.
	 * @return The next address.
	 */
	private synchronized static int getNextAddress() {
		return nextAddress++;	
	}

	/**
	 * 重置主机及其接口
	 * Reset the host and its interfaces
	 */
	public static void reset() {
		nextAddress = 0;
	}

	/**
	 * Returns true if this node is active (false if not)
	 * @return true if this node is active (false if not)
	 */
	public boolean isActive() {
		return this.movement.isActive();
	}

	/**
	 * 为该主机设置路由
	 * Set a router for this host
	 * @param router The router to set
	 */
	private void setRouter(MessageRouter router) {
		router.init(this, msgListeners);
		this.router = router;
	}

	/**
	 * Returns the router of this host
	 * @return the router of this host
	 */
	public MessageRouter getRouter() {
		return this.router;
	}

	/**
	 * 返回此主机的网络层地址
	 * Returns the network-layer address of this host.
	 */
	public int getAddress() {
		return this.address;
	}
	
	/**
	 * Returns this hosts's ModuleCommunicationBus
	 * @return this hosts's ModuleCommunicationBus
	 */
	public ModuleCommunicationBus getComBus() {
		return this.comBus;
	}
	
    /**
     * 各个子类可能会重写
	 * Informs the router of this host about state change in a connection
	 * object.
	 * @param con  The connection object whose state changed
	 */
	public void connectionUp(Connection con) {
		this.router.changedConnection(con);
	}

	public void connectionDown(Connection con) {
		this.router.changedConnection(con);
	}

	/**
	 * 返回此主机与其他主机的连接列表的副本
	 * Returns a copy of the list of connections this host has with other hosts
	 * @return a copy of the list of connections this host has with other hosts
	 */
	public List<Connection> getConnections() {
		List<Connection> lc = new ArrayList<Connection>();

		for (NetworkInterface i : net) {
			lc.addAll(i.getConnections());
		}

		return lc;
	}

	/**
	 * 返回此主机的当前位置。
	 * Returns the current location of this host. 
	 * @return The location
	 */
	public Coord getLocation() {
		return this.location;
	}
	//自写返回下一个目的节点
	public Coord getNext() {
		return this.destination;
	}

	/**
	 * 返回此节点正在运行的路径，如果目前没有使用路径，则返回null。
	 * Returns the Path this node is currently traveling or null if no
	 * path is in use at the moment.
	 * @return The path this node is traveling
	 */
	public Path getPath() {
		return this.path;
	}


	/**
	 * 设置节点的位置，覆盖由运动模型设置的任何位置
	 * Sets the Node's location overriding any location set by movement model
	 * @param location The location to set
	 */
	public void setLocation(Coord location) {
		this.location = location.clone();
	}

	/**
	 * Sets the Node's name overriding the default name (groupId + netAddress)
	 * @param name The name to set
	 */
	public void setName(String name) {
		this.name = name;
	}

	/**
	 * Returns the messages in a collection.
	 * @return Messages in a collection
	 */
	public Collection<Message> getMessageCollection() {
		return this.router.getMessageCollection();
	}

	/**
	 * 返回此节点携带的消息数量。
	 * Returns the number of messages this node is carrying.
	 * @return How many messages the node is carrying currently.
	 */
	public int getNrofMessages() {
		return this.router.getNrofMessages();
	}

	/**
	 * 返回缓冲区占用率
	 * Returns the buffer occupancy percentage. Occupancy is 0 for empty
	 * buffer but can be over 100 if a created message is bigger than buffer 
	 * space that could be freed.
	 * @return Buffer occupancy percentage
	 */
	public double getBufferOccupancy() {
		double bSize = router.getBufferSize();
		double freeBuffer = router.getFreeBufferSize();
		return 100*((bSize-freeBuffer)/bSize);
	}

	/**
	 * 返回路由信息
	 * Returns routing info of this host's router.
	 * @return The routing info.
	 */
	public RoutingInfo getRoutingInfo() {
		return this.router.getRoutingInfo();
	}

	/**
	 * Returns the interface objects of the node
	 */
	public List<NetworkInterface> getInterfaces() {
		return net;
	}

	/**
	 * 根据索引查找网络接口
	 * Find the network interface based on the index
	 */
	protected NetworkInterface getInterface(int interfaceNo) {
		NetworkInterface ni = null;
		try {
			ni = net.get(interfaceNo-1);
		} catch (IndexOutOfBoundsException ex) {
			System.out.println("No such interface: "+interfaceNo);
			System.exit(0);
		}
		return ni;
	}

	/**
	 * 根据接口类型查找网络接口
	 * Find the network interface based on the interfacetype
	 */
	protected NetworkInterface getInterface(String interfacetype) {
		for (NetworkInterface ni : net) {
			if (ni.getInterfaceType().equals(interfacetype)) {
				return ni;
			}
		}
		return null;	
	}

	/**
	 * Force a connection event
	 */
	public void forceConnection(DTNHost anotherHost, String interfaceId, 
			boolean up) {
		NetworkInterface ni;
		NetworkInterface no;

		if (interfaceId != null) {
			ni = getInterface(interfaceId);
			no = anotherHost.getInterface(interfaceId);

			assert (ni != null) : "Tried to use a nonexisting interfacetype "+interfaceId;
			assert (no != null) : "Tried to use a nonexisting interfacetype "+interfaceId;
		} else {
			ni = getInterface(1);
			no = anotherHost.getInterface(1);
			
			assert (ni.getInterfaceType().equals(no.getInterfaceType())) : 
				"Interface types do not match.  Please specify interface type explicitly";
		}
		
		if (up) {
			ni.createConnection(no);
		} else {
			ni.destroyConnection(no);
		}
	}

	/**
	 * 测试用
	 * for tests only --- do not use!!!
	 */
	public void connect(DTNHost h) {
		System.err.println(
				"WARNING: using deprecated DTNHost.connect(DTNHost)" +
		"\n Use DTNHost.forceConnection(DTNHost,null,true) instead");
		forceConnection(h,null,true);
	}

	/**
	 * 更新节点网络层和路由
	 * Updates node's network layer and router.
	 * @param simulateConnections Should network layer be updated too
	 */
	public void update(boolean simulateConnections) {
		if (!isActive()) {
			return;
		}
		
		if (simulateConnections) {
			for (NetworkInterface i : net) {//调用路由协议的update
				i.update();
			}
		}
		this.router.update();
	}

	/**
	 * 将节点移动到下一个航路点，或如果它已经没有时间去移动就等待
	 * Moves the node towards the next waypoint or waits if it is
	 * not time to move yet
	 * @param timeIncrement How long time the node moves
	 */
	public void move(double timeIncrement) {		
		double possibleMovement;
		double distance;
		double dx, dy;

		if (!isActive() || SimClock.getTime() < this.nextTimeToMove) {
			return; 
		}
		if (this.destination == null) {
			if (!setNextWaypoint()) {
				return;
			}
		}

		//移动时间乘移动速度
		possibleMovement = timeIncrement * speed;
		distance = this.location.distance(this.destination);

		while (possibleMovement >= distance) {//可能移到的距离大于停靠点的距离
			// node can move past its next destination
			this.location.setLocation(this.destination); // snap to destination
			possibleMovement -= distance;
			if (!setNextWaypoint()) { // get a new waypoint
				return; // no more waypoints left
			}
			distance = this.location.distance(this.destination);
		}
		
		//移动到下一个停靠点
		// move towards the point for possibleMovement amount
		dx = (possibleMovement/distance) * (this.destination.getX() -
				this.location.getX());
		dy = (possibleMovement/distance) * (this.destination.getY() -
				this.location.getY());
		this.location.translate(dx, dy);
	}	

	/**
	 * 设置下一个目的地和速度，以对应路径上的下一个航路点。
	 * Sets the next destination and speed to correspond the next waypoint
	 * on the path.
	 * @return True if there was a next waypoint to set, false if node still
	 * should wait
	 */
	private boolean setNextWaypoint() {
		if (path == null) {
			path = movement.getPath();
		}

		if (path == null || !path.hasNext()) {
			this.nextTimeToMove = movement.nextPathAvailable();
			this.path = null;
			return false;
		}

		this.destination = path.getNextWaypoint();
		this.speed = path.getSpeed();

		if (this.movListeners != null) {
			for (MovementListener l : this.movListeners) {
				l.newDestination(this, this.destination, this.speed);
			}
		}

		return true;
	}

	/**
	 * 从该主机发送消息到另一个主机
	 * Sends a message from this host to another host
	 * @param id Identifier of the message
	 * @param to Host the message should be sent to
	 */
	public void sendMessage(String id, DTNHost to) {
		this.router.sendMessage(id, to);
	}

	/**
	 * Start receiving a message from another host
	 * @param m The message
	 * @param from Who the message is from
	 * @return The value returned by 
	 * {@link MessageRouter#receiveMessage(Message, DTNHost)}
	 */
	public int receiveMessage(Message m, DTNHost from) {
		int retVal = this.router.receiveMessage(m, from); 

		if (retVal == MessageRouter.RCV_OK) {
			m.addNodeOnPath(this);	// add this node on the messages path
		}

		return retVal;	
	}

	/**
	 * 通过连接发送来自该主机的可交付消息的请求。
	 * Requests for deliverable message from this host to be sent trough a
	 * connection.
	 * @param con The connection to send the messages trough
	 * @return True if this host started a transfer, false if not
	 */
	public boolean requestDeliverableMessages(Connection con) {
		return this.router.requestDeliverableMessages(con);
	}

	/**
	 * Informs the host that a message was successfully transferred.
	 * @param id Identifier of the message
	 * @param from From who the message was from
	 */
	public void messageTransferred(String id, DTNHost from) {
		this.router.messageTransferred(id, from);
	}

	/**
	 * 通知主机消息传输已中止
	 * Informs the host that a message transfer was aborted.
	 * @param id Identifier of the message
	 * @param from From who the message was from
	 * @param bytesRemaining Nrof bytes that were left before the transfer
	 * would have been ready; or -1 if the number of bytes is not known
	 */
	public void messageAborted(String id, DTNHost from, int bytesRemaining) {
		this.router.messageAborted(id, from, bytesRemaining);
	}

	/**
	 * 向此主机的路由器创建新消息
	 * Creates a new message to this host's router
	 * @param m The message to create
	 */
	public void createNewMessage(Message m) {
		this.router.createNewMessage(m);
	}

	/**
	 * Deletes a message from this host
	 * @param id Identifier of the message
	 * @param drop True if the message is deleted because of "dropping"
	 * (e.g. buffer is full) or false if it was deleted for some other reason
	 * (e.g. the message got delivered to final destination). This effects the
	 * way the removing is reported to the message listeners.
	 */
	public void deleteMessage(String id, boolean drop) {
		this.router.deleteMessage(id, drop);
	}

	/**
	 * Returns a string presentation of the host.
	 * @return Host's name
	 */
	public String toString() {
		return name;
	}

	/**
	 * Checks if a host is the same as this host by comparing the object
	 * reference
	 * @param otherHost The other host
	 * @return True if the hosts objects are the same object
	 */
	public boolean equals(DTNHost otherHost) {
		return this == otherHost;
	}

	/**
	 * 比较两个主机的地址。
	 * Compares two DTNHosts by their addresses.
	 * @see Comparable#compareTo(Object)
	 */
	public int compareTo(DTNHost h) {
		return this.getAddress() - h.getAddress();
	}
	
	/*****我写的代码******/
	/**
	 * 计算角度
	 */
	public double countAngle() {
		double angle=(this.destination.getY()-this.location.getY()) / (this.destination.getX()-this.location.getX());
		return angle;
	}
	//判断运动方向
	public boolean is_Angle() {
		boolean check=false;
				if((this.destination.getY()-this.location.getY())>0){
					 check=true;
				}
		
		return check;
	}
	/**
	 * 更新和获得节点的丢包数。
	 */
	public void updatedrop() {
		this.drop_num++;
	}

	public int getdrop() {
		return this.drop_num;
	}
	
	
	/**
	 * 更新效用表。
	 * @param DTNHost 哪一个邻居主机
	 * @param interval 第几个时间间隔
	 */
	public void updatehu(DTNHost key,int interval) {
		//该主机不存在
		if(!this.history_utility.containsKey(key))
		{
			
			HashMap<Integer,Integer> dtnhash=new HashMap<Integer,Integer>();
			dtnhash.put(interval, 1);
			this.history_utility.put(key, dtnhash);
		}else{
			HashMap<Integer,Integer> dtnhash=this.history_utility.get(key);
			//该间隔不存在
			if(!dtnhash.containsKey(interval))
			{
				dtnhash.put(interval, 1);	
			}
			else
			{	
				int value=dtnhash.get(interval)+1;
				dtnhash.put(interval,value);
				
			}
			
			this.history_utility.put(key, dtnhash);
		}
		
	}

	public HashMap<DTNHost, HashMap<Integer,Integer> > gethu() {
		return this.history_utility;
	}
	
	/**
	 * 新版效用表。
	 * @param DTNHost 哪一个邻居主机
	 * @param interval 第几个时间间隔
	 */
	public void updateUtility(DTNHost key) {
		
		HashMap<String,Double> data=new HashMap<String,Double>();
		if(key.path==null){
			data.put("speed",(double)0);
		}else{
			data.put("speed", key.getPath().getSpeed());
		}
		
		data.put("time", SimClock.getTime());
		data.put("x", key.getLocation().getX());
		data.put("y", key.getLocation().getY());
		this.location_utility.put(key, data);
		
	}

	public HashMap<DTNHost, HashMap<String,Double>> getUtility() {
		return this.location_utility;
	}
	
	/*-------------------华丽分割-------------------------**/
	/**
	 * FIN效用表。
	 * @param DTNHost 哪一个邻居主机
	 * @param interval 第几个时间间隔
	 */
	public void updateFinUtility(DTNHost host,DTNHost other) {
		
		//更新本地节点的信息
		updateFinUtilityItem(host);
		//更新本地邻居的信息
		updateFinUtilityItem(other);
	
		
	}

	
	//更新节点效用的方法
	public void updateFinUtilityItem(DTNHost other) {
		//更新本节点的效用信息,坐标，时间，平均速度，平均方向，更新时间，通信范围？
//		HashMap<String, Double> tmpHost=getFinUtility().get(other);
		HashMap<String, Double> otherHost=other.getFinUtility().get(other);//获得另一个节点的效用表
		
		double hostX =other.getLocation().getX();
		double hostY =other.getLocation().getY();
		double hostSpeed=other.speed;
		double avgDirection=0;
		double count=updateDeliveryPredFor(other);//投递预测值
		
		if(otherHost!=null){
			if((double)otherHost.get("updateTime")<SimClock.getTime()){
				double lastX=otherHost.get("x");
				double lastY=otherHost.get("y");
				hostSpeed=(hostSpeed+otherHost.get("avgSpeed"))/2;
				avgDirection=Math.atan2(hostY-lastY,hostX-lastX)*(180/Math.PI);
			}else{
				avgDirection=(double)otherHost.get("avgDirection");
				hostSpeed=(double)otherHost.get("avgSpeed");
			}				
		}
		
		HashMap<String,Double> newData=new HashMap<String,Double>();
		newData.put("avgSpeed", hostSpeed);
		newData.put("x", hostX);
		newData.put("y", hostY);
//		System.out.println("节点"+other+"角度"+avgDirection);
		newData.put("avgDirection", avgDirection);
		newData.put("updateTime", SimClock.getTime());
		newData.put("count",count);
		
		this.fin_utility.put(other, newData);//更新	
		
	}
	
	
	//更新历史投递效用
	private double updateDeliveryPredFor(DTNHost other) {
				
				double oldValue = getPredFor(other);//得到旧的投递值
				double newValue = oldValue + (1 - oldValue) * 0.75;
				return newValue;
	}
	//计算传递效率
	public double getPredFor(DTNHost other) {
				
				ageDeliveryPreds(); // 建立连接时更新衰减投递预测值
				HashMap<String, Double> tmpHost=getFinUtility().get(other);
				if (tmpHost !=null) {
					return tmpHost.get("count");
				}else {
					return 0;
				}
			}
	//更新传递效率
	private void ageDeliveryPreds() {
		  
				double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 180;
//        		System.out.println("可以更新"+timeDiff);
				if (timeDiff == 0) {
					return;
				}
				double mult = Math.pow(0.98, timeDiff);
				for (HashMap.Entry<DTNHost, HashMap<String,Double>>e : getFinUtility().entrySet()) {
					e.getValue().put("count",e.getValue().get("count")*mult);
				}
		 this.lastAgeUpdate = SimClock.getTime();
		}
	//更新传递投递值
	private void updateTransitivePreds(DTNHost host,DTNHost other) {
		
		
		double pForHost = getPredFor(other); // P(a,b)
		
		HashMap<DTNHost, HashMap<String,Double>>  othersPreds = other.getFinUtility();//获得对方路由的投递预测值集合
		for (Map.Entry<DTNHost, HashMap<String,Double>> e : othersPreds.entrySet()) {
			if (e.getKey() == host) {
				continue; // don't add yourself
			}
			
			double pOld = getPredFor(e.getKey()); // P(a,c)_old
			double pNew = pOld + ( 1 - pOld) * pForHost * e.getValue().get("count") * 0.25;//beta
//			System.out.println("old"+e.getValue().get("count")+"::new"+pNew);
			e.getValue().put("count",pNew);
		}
	}
	//历史投递效用结束	
	
	
	//获得效用表
		public HashMap<DTNHost, HashMap<String,Double>> getFinUtility() {
			return this.fin_utility;
		}
		
	/**********/
}


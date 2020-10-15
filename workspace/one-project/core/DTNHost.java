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
	
	
	/**��д�ı���**/
	private int drop_num;//������
	private HashMap<DTNHost ,HashMap<Integer,Integer>>history_utility;//��ʷЧ��
	private HashMap<DTNHost ,HashMap<String,Double>> location_utility;//λ����ʷЧ��
	private HashMap<DTNHost ,HashMap<String,Double>> fin_utility;//finЧ�ñ�
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
		
		/**��д�ĳ�ʼ��**/
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

		//ͨ������ԭ�ʹ���ʵ��
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
	 * �����µ�����ӿڵ�ַ
	 * Returns a new network interface address and increments the address for
	 * subsequent calls.
	 * @return The next address.
	 */
	private synchronized static int getNextAddress() {
		return nextAddress++;	
	}

	/**
	 * ������������ӿ�
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
	 * Ϊ����������·��
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
	 * ���ش�������������ַ
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
     * ����������ܻ���д
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
	 * ���ش����������������������б�ĸ���
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
	 * ���ش������ĵ�ǰλ�á�
	 * Returns the current location of this host. 
	 * @return The location
	 */
	public Coord getLocation() {
		return this.location;
	}
	//��д������һ��Ŀ�Ľڵ�
	public Coord getNext() {
		return this.destination;
	}

	/**
	 * ���ش˽ڵ��������е�·�������Ŀǰû��ʹ��·�����򷵻�null��
	 * Returns the Path this node is currently traveling or null if no
	 * path is in use at the moment.
	 * @return The path this node is traveling
	 */
	public Path getPath() {
		return this.path;
	}


	/**
	 * ���ýڵ��λ�ã��������˶�ģ�����õ��κ�λ��
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
	 * ���ش˽ڵ�Я������Ϣ������
	 * Returns the number of messages this node is carrying.
	 * @return How many messages the node is carrying currently.
	 */
	public int getNrofMessages() {
		return this.router.getNrofMessages();
	}

	/**
	 * ���ػ�����ռ����
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
	 * ����·����Ϣ
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
	 * ����������������ӿ�
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
	 * ���ݽӿ����Ͳ�������ӿ�
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
	 * ������
	 * for tests only --- do not use!!!
	 */
	public void connect(DTNHost h) {
		System.err.println(
				"WARNING: using deprecated DTNHost.connect(DTNHost)" +
		"\n Use DTNHost.forceConnection(DTNHost,null,true) instead");
		forceConnection(h,null,true);
	}

	/**
	 * ���½ڵ�������·��
	 * Updates node's network layer and router.
	 * @param simulateConnections Should network layer be updated too
	 */
	public void update(boolean simulateConnections) {
		if (!isActive()) {
			return;
		}
		
		if (simulateConnections) {
			for (NetworkInterface i : net) {//����·��Э���update
				i.update();
			}
		}
		this.router.update();
	}

	/**
	 * ���ڵ��ƶ�����һ����·�㣬��������Ѿ�û��ʱ��ȥ�ƶ��͵ȴ�
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

		//�ƶ�ʱ����ƶ��ٶ�
		possibleMovement = timeIncrement * speed;
		distance = this.location.distance(this.destination);

		while (possibleMovement >= distance) {//�����Ƶ��ľ������ͣ����ľ���
			// node can move past its next destination
			this.location.setLocation(this.destination); // snap to destination
			possibleMovement -= distance;
			if (!setNextWaypoint()) { // get a new waypoint
				return; // no more waypoints left
			}
			distance = this.location.distance(this.destination);
		}
		
		//�ƶ�����һ��ͣ����
		// move towards the point for possibleMovement amount
		dx = (possibleMovement/distance) * (this.destination.getX() -
				this.location.getX());
		dy = (possibleMovement/distance) * (this.destination.getY() -
				this.location.getY());
		this.location.translate(dx, dy);
	}	

	/**
	 * ������һ��Ŀ�ĵغ��ٶȣ��Զ�Ӧ·���ϵ���һ����·�㡣
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
	 * �Ӹ�����������Ϣ����һ������
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
	 * ͨ�����ӷ������Ը������Ŀɽ�����Ϣ������
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
	 * ֪ͨ������Ϣ��������ֹ
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
	 * ���������·������������Ϣ
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
	 * �Ƚ����������ĵ�ַ��
	 * Compares two DTNHosts by their addresses.
	 * @see Comparable#compareTo(Object)
	 */
	public int compareTo(DTNHost h) {
		return this.getAddress() - h.getAddress();
	}
	
	/*****��д�Ĵ���******/
	/**
	 * ����Ƕ�
	 */
	public double countAngle() {
		double angle=(this.destination.getY()-this.location.getY()) / (this.destination.getX()-this.location.getX());
		return angle;
	}
	//�ж��˶�����
	public boolean is_Angle() {
		boolean check=false;
				if((this.destination.getY()-this.location.getY())>0){
					 check=true;
				}
		
		return check;
	}
	/**
	 * ���ºͻ�ýڵ�Ķ�������
	 */
	public void updatedrop() {
		this.drop_num++;
	}

	public int getdrop() {
		return this.drop_num;
	}
	
	
	/**
	 * ����Ч�ñ�
	 * @param DTNHost ��һ���ھ�����
	 * @param interval �ڼ���ʱ����
	 */
	public void updatehu(DTNHost key,int interval) {
		//������������
		if(!this.history_utility.containsKey(key))
		{
			
			HashMap<Integer,Integer> dtnhash=new HashMap<Integer,Integer>();
			dtnhash.put(interval, 1);
			this.history_utility.put(key, dtnhash);
		}else{
			HashMap<Integer,Integer> dtnhash=this.history_utility.get(key);
			//�ü��������
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
	 * �°�Ч�ñ�
	 * @param DTNHost ��һ���ھ�����
	 * @param interval �ڼ���ʱ����
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
	
	/*-------------------�����ָ�-------------------------**/
	/**
	 * FINЧ�ñ�
	 * @param DTNHost ��һ���ھ�����
	 * @param interval �ڼ���ʱ����
	 */
	public void updateFinUtility(DTNHost host,DTNHost other) {
		
		//���±��ؽڵ����Ϣ
		updateFinUtilityItem(host);
		//���±����ھӵ���Ϣ
		updateFinUtilityItem(other);
	
		
	}

	
	//���½ڵ�Ч�õķ���
	public void updateFinUtilityItem(DTNHost other) {
		//���±��ڵ��Ч����Ϣ,���꣬ʱ�䣬ƽ���ٶȣ�ƽ�����򣬸���ʱ�䣬ͨ�ŷ�Χ��
//		HashMap<String, Double> tmpHost=getFinUtility().get(other);
		HashMap<String, Double> otherHost=other.getFinUtility().get(other);//�����һ���ڵ��Ч�ñ�
		
		double hostX =other.getLocation().getX();
		double hostY =other.getLocation().getY();
		double hostSpeed=other.speed;
		double avgDirection=0;
		double count=updateDeliveryPredFor(other);//Ͷ��Ԥ��ֵ
		
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
//		System.out.println("�ڵ�"+other+"�Ƕ�"+avgDirection);
		newData.put("avgDirection", avgDirection);
		newData.put("updateTime", SimClock.getTime());
		newData.put("count",count);
		
		this.fin_utility.put(other, newData);//����	
		
	}
	
	
	//������ʷͶ��Ч��
	private double updateDeliveryPredFor(DTNHost other) {
				
				double oldValue = getPredFor(other);//�õ��ɵ�Ͷ��ֵ
				double newValue = oldValue + (1 - oldValue) * 0.75;
				return newValue;
	}
	//���㴫��Ч��
	public double getPredFor(DTNHost other) {
				
				ageDeliveryPreds(); // ��������ʱ����˥��Ͷ��Ԥ��ֵ
				HashMap<String, Double> tmpHost=getFinUtility().get(other);
				if (tmpHost !=null) {
					return tmpHost.get("count");
				}else {
					return 0;
				}
			}
	//���´���Ч��
	private void ageDeliveryPreds() {
		  
				double timeDiff = (SimClock.getTime() - this.lastAgeUpdate) / 180;
//        		System.out.println("���Ը���"+timeDiff);
				if (timeDiff == 0) {
					return;
				}
				double mult = Math.pow(0.98, timeDiff);
				for (HashMap.Entry<DTNHost, HashMap<String,Double>>e : getFinUtility().entrySet()) {
					e.getValue().put("count",e.getValue().get("count")*mult);
				}
		 this.lastAgeUpdate = SimClock.getTime();
		}
	//���´���Ͷ��ֵ
	private void updateTransitivePreds(DTNHost host,DTNHost other) {
		
		
		double pForHost = getPredFor(other); // P(a,b)
		
		HashMap<DTNHost, HashMap<String,Double>>  othersPreds = other.getFinUtility();//��öԷ�·�ɵ�Ͷ��Ԥ��ֵ����
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
	//��ʷͶ��Ч�ý���	
	
	
	//���Ч�ñ�
		public HashMap<DTNHost, HashMap<String,Double>> getFinUtility() {
			return this.fin_utility;
		}
		
	/**********/
}


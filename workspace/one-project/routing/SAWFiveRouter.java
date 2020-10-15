/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
public class SAWFiveRouter extends ActiveRouter {
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

	

	public SAWFiveRouter(Settings s) {
		super(s);
		Settings snwSettings = new Settings(SPRAYANDWAIT_NS);
		initialNrofCopies = snwSettings.getInt(NROF_COPIES);
		isBinary = snwSettings.getBoolean( BINARY_MODE);
		
		
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected SAWFiveRouter(SAWFiveRouter r) {
		super(r);
		this.initialNrofCopies = r.initialNrofCopies;
		this.isBinary = r.isBinary;
		
	}
	//DTNHOS中调用
	public void changedConnection(Connection con) { 
		if (con.isUp()) {
		
//			checkAck(con);
		countUtility( con);
		
	}}
	
	//更新ack表并删除多余副本
	private void checkAck(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
		List<String> hostAck=getHost().getAck();
		List<String> otherAck=otherHost.getAck();
		//更新方法需要优化
		hostAck.removeAll(otherAck);
		hostAck.addAll(otherAck);
		otherAck.clear();
		otherAck.addAll(hostAck);
		//对方节点删除
		for(Message m :otherHost.getMessageCollection()){
			if(m !=null){
				if(otherAck.contains(m.getId())){
					System.out.println(m.getId()+"对方丢包");
					otherHost.deleteMessage(m.getId(), false);
				}
			}
			
		}
		
		//我方节点删除
		for(Message m :getHost().getMessageCollection()){
			if(m !=null){
				if(hostAck.contains(m.getId())){
					System.out.println(m.getId()+"我方丢包");
					getHost().deleteMessage(m.getId(), false);
				}
			}
			
		}
	}
	
	
	//更新历史效用值
	private void countUtility(Connection con) {
		DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
		double timeDiff = (SimClock.getTime()/super.secondsInTimeUnit);
		getHost().updatehu(otherHost,(int)timeDiff);//主机更新历史效用表
		otherHost.updatehu(getHost(),(int)timeDiff);//对方更新历史效用表
		
		getHost().updateUtility(otherHost);//主机更新地理效用表
		otherHost.updateUtility(getHost());//对方更新地理效用表
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
		return msg;
	}

	
	/*重写父类，增加消息副本数量字段*/
	@Override 
	public boolean createNewMessage(Message msg) {
		
		makeRoomForNewMessage(msg.getSize());
		
		msg.setTtl(this.msgTtl);
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
		@SuppressWarnings(value = "unchecked")
		List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());//需要转发的消息
//		@SuppressWarnings(value = "unchecked")
//		List<Connection> ConnectionLeft = getConnectionsWithCopiesLeft();//需要转发的消息
		 
		if (copiesLeft.size() > 0 ) {
			getConnectionsWithCopiesLeftT(copiesLeft);//需要转发的消息
			this.tryMessagesToConnectionsUn(copiesLeft, getConnections());
		
		}
	}
	
	/**
	 * 创建并返回当前路由携带和需要转发的消息副本的消息列表（节点副本数>1）
	 * Creates and returns a list of messages this router is currently
	 * carrying and still has copies left to distribute (nrof copies > 1).
	 * @return A list of messages that have copies left
	 */
	protected List<Message> getMessagesWithCopiesLeft() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			if (nrofCopies > 1) {
				list.add(m);
			}
			
		}
		
		return list;
	}
	
	protected List<Message> getMessagesWithCopiesOne() {
		List<Message> list = new ArrayList<Message>();

		for (Message m : getMessageCollection()) {
			Integer nrofCopies = (Integer)m.getProperty(MSG_COUNT_PROPERTY);//得到消息副本数量的属性
			double buffertime = SimClock.getTime()-m.getReceiveTime();//得到消息副本数量的属性
			
			assert nrofCopies != null : "SnW message " + m + " didn't have " + 
				"nrof copies property!";
			Integer sprayTimes = (Integer)m.getProperty(SPRAY_TIMES);//得到消息转发次数
			if (nrofCopies == 1 && buffertime>super.t_buffertime && sprayTimes<8) {
				
				m.updateProperty(SPRAY_TIMES,sprayTimes+1);
				list.add(m);
					
			}		
		}
		
		return list;
	}
	
	
	
	//通过效用获得邻居节点
		protected void getConnectionsWithCopiesLeftT(List<Message> messages) {
			
			for (Message m : messages) {
				double lastTime=0;
			
				HashMap<String,Double> last_data = null;
				Connection bestcCon=null;
				for (Connection con: getConnections()) {
					DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
					HashMap<DTNHost, HashMap<String,Double>>otherHostU=otherHost.getUtility();
					if(otherHostU.containsKey(m.getTo())){//判断该节点遇到过消息的目的节点
						if(otherHostU.get(m.getTo()).get("time")>lastTime){//当前的比较新
							
							last_data=otherHostU.get(m.getTo());
							
						}
					}//判断结束,得到目的节点最新的位置
				
				}//连接循环
				
				//第二次循环找出最合适的点
				if(last_data !=null){
					double speed=0.0;
					double best=0;
				for (Connection con: getConnections()) {
					
					DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
					HashMap<DTNHost, HashMap<String,Double>>otherHostU=otherHost.getUtility();
					otherHostU.put(m.getTo(), last_data);
					boolean is_add=false;
					//路径不为空下
					if(otherHost.getPath()!=null){
						speed =otherHost.getPath().getSpeed();			
						
						double ax=last_data.get("x")-otherHost.getLocation().getX();
						double ay=last_data.get("y")-otherHost.getLocation().getY();
						
						double cx=last_data.get("x")-otherHost.getNext().getX();
						double cy=last_data.get("y")-otherHost.getNext().getY();
						
						double bx=otherHost.getNext().getX()-otherHost.getLocation().getX();
						double by=otherHost.getNext().getY()-otherHost.getLocation().getY();
						if(Math.pow(ax,2)+Math.pow(ay,2)+Math.pow(bx,2)+Math.pow(by,2)>Math.pow(cx,2)+Math.pow(cy,2))
						{
							is_add=true;
						}
						
					}
					//同向添加
					if(is_add){
						
					double _x = Math.abs(last_data.get("x") - otherHost.getNext().getX());
					double _y = Math.abs(last_data.get("y")- otherHost.getNext().getY());
					double temp = Math.sqrt(_x*_x+_y*_y)*0.4+speed*0.6;
					if(temp>best){//当前的比较			
						best =temp;
						bestcCon=con;
					}	
				}
			}//连接循环
				super.toTrans.put(m, bestcCon);	
			}
				
		}//消息循环

	}
	
	
	//通过角度获得邻居节点
	protected List<Connection> getConnectionsWithCopiesLeft() {
		List<Connection> list = new ArrayList<Connection>();
		
		Map<Double,Connection> LF=new HashMap<Double,Connection>();
		Map<Double,Connection> L=new HashMap<Double,Connection>();
		Map<Double,Connection>  LR=new HashMap<Double,Connection>();
		if(getConnections().size()<3){
			return getConnections();
		}else{ 
			
		for (Connection con: getConnections()) {
			DTNHost otherHost = con.getOtherNode(getHost());//获得连接的对方主机
			double k1=otherHost.countAngle();
			double k2=getHost().countAngle();			
		
			double theAngle=Math.atan((Math.abs(k1-k2))/Math.abs((1+k1*k2)));;//相对于主机节点的角度
			System.out.println(theAngle);
			
			if((double)35/180*Math.PI<=theAngle && theAngle<=(double)55/180*Math.PI){
				double min=Math.abs(theAngle-1/4*Math.PI);
				LF.put(min, con);
			
				
			}else if((double)80/180*Math.PI<=theAngle && theAngle<=(double)100/180*Math.PI){
				double min=Math.PI-Math.abs(theAngle -1/2*Math.PI);
				L.put(min, con);
		
			
			}else if((double)125/180*Math.PI<=theAngle && theAngle<=(double)145/180*Math.PI){
				double min=Math.abs(theAngle-3/4*Math.PI);
				LR.put(min, con);
				
			}
		}
	
		Connection LF_cony=this.get_minCon(LF,"y");
		if(LF_cony!=null){
			list.add(LF_cony);
		}
		Connection LF_conx=this.get_minCon(LF,"x");
		if(LF_conx!=null){
			list.add(LF_conx);
		}
		Connection L_cony=this.get_minCon(L,"y");
		if(L_cony!=null){
			list.add(L_cony);
		}
		Connection L_conx=this.get_minCon(L,"x");
		if(L_conx!=null){
			list.add(L_conx);
		}
		Connection LR_cony=this.get_minCon(LR,"y");
		if(LR_cony!=null){
			list.add(LR_cony);
		}
		Connection LR_conx=this.get_minCon(LR,"x");
		if(LR_conx!=null){
			list.add(LR_conx);
		}
		}
		return list;
	}
	//获得最小的节点数
	protected Connection get_minCon(Map<Double,Connection>map,String type){
		
		double min = 0 ;
		boolean flag=false;
		Connection min_con = null;
		
		for (Double key : map.keySet()) {
			Connection ycon=map.get(key);
			DTNHost otherHost = ycon.getOtherNode(getHost());//获得连接的对方主
			if(flag==false){
				min=key;
				flag=true;
			}
			
			if(type=="y"){
				if (otherHost.is_Angle()&& key < min) {
					min = key;
				}
			}else{
				if (!otherHost.is_Angle()&& key < min) {
					min = key;
			}
			}	
		}
		
		if(map.containsKey(min)){
			min_con=map.get(min);
		};
		
		return min_con;
		
	}
	
	/**
	 * 更新节点消息副本数
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
	public SAWFiveRouter replicate() {
		return new SAWFiveRouter(this);
	}
}

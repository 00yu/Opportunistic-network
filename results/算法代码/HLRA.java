/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;
/**基于历史与位置信息的容迟网络路由算法-王夫沭*/
import java.util.*;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import util.Tuple;

/**
 * Epidemic message router with drop-oldest buffer and only single transferring
 * connections at a time.
 */
public class HLRA extends ActiveRouter {
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public HLRA(Settings s) {
		super(s);
		//TODO: read&use epidemic router specific settings (if any)
	}
	
	public void changedConnection(Connection con) {
		super.changedConnection(con);
		
		if (con.isUp()) {
			DTNHost otherHost = con.getOtherNode(getHost());
			/**更新节点相遇次数*/
			getHost().updateEncounterNum(otherHost);
		}
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected HLRA(HLRA r) {
		super(r);
		//TODO: copy epidemic settings here (if any)
	}
			
	@Override
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {
			return; // transferring, don't try other connections yet
		}
		
		// Try first the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {
			return; // started a transfer, don't try others (yet)
		}
		
		// then try any/all message to any/all connection
		tryOtherMessages();
	}
	
	private Tuple<Message, Connection> tryOtherMessages() {
		/**用于存放两个邻居节点组成的节点集和移动方向夹角*/
		Map<List<Connection>,Double> angle=new HashMap<List<Connection>,Double>();
		/**用于存放与当前节点移动方向夹角*/
		Map<List<Connection>,Double> θ=new HashMap<List<Connection>,Double>();
		/**用于存放比消息次数等级高的连接和消息*/
		List<Tuple<Message, Connection>> tuples=new ArrayList<Tuple<Message, Connection>>();
		/**用于存放地理位置信息选出的两个连接*/
		List<Connection> selectedConnections=new ArrayList<Connection>();
		/**当前节点所有连接*/
		List<Connection> connections = getConnections();
		if (connections.size() == 0 || this.getNrofMessages() == 0) {
			return null;
		}
		List<Message> messages = 
				new ArrayList<Message>(this.getMessageCollection());
		for (Message m : messages) {
			Map<Connection,Integer> conn=new HashMap<Connection,Integer>();
			int s=m.getS();
			for(Connection con:getConnections()) {
				DTNHost otherHost=con.getOtherNode(this.getHost());
				if(otherHost.getEncounterNum(m.getTo())>s) {
					conn.put(con,otherHost.getEncounterNum(m.getTo()));
				}
			}
			if(conn.isEmpty()!=true) {
				List<Map.Entry<Connection, Integer>> list = new ArrayList<Map.Entry<Connection,Integer>>(conn.entrySet());
		        //list.sort()
		        list.sort(new Comparator<Map.Entry<Connection,Integer>>() {
		            @Override
		            public int compare(Map.Entry<Connection,Integer> o1, Map.Entry<Connection,Integer> o2) {
		                return o2.getValue().compareTo(o1.getValue());
		            }
		        });
		        Connection conn1=list.get(0).getKey();
		        int max=list.get(0).getValue();
		        m.setS(max);
		        tuples.add(new Tuple<Message,Connection>(m,conn1));
			}
			else {
				if(connections.size()<=2) {
					for(Connection con:connections) {
						tuples.add(new Tuple<Message,Connection>(m,con));
					}
				}
				else {
				/**地理位置信息选择*/
				int length=connections.size();
				DTNHost ni=getHost();
				double xi=ni.getLocation().getX();
				double yi=ni.getLocation().getY();
				double xi1=ni.getDestination().getX();
				double yi1=ni.getDestination().getY();
				double θi=Math.acos((xi-xi1)/Math.sqrt(Math.pow(xi-xi1,2)+Math.pow(yi-yi1,2)));
				for(int i=0;i<length-1;i++) {
					for(int j=i+1;j<=length-1;j++) {
						DTNHost nj=connections.get(i).getOtherNode(getHost());
						double xj=nj.getLocation().getX();
						double yj=nj.getLocation().getY();
						double xj1=nj.getDestination().getX();
						double yj1=nj.getDestination().getY();
						DTNHost nk=connections.get(j).getOtherNode(getHost());
						double xk=nk.getLocation().getX();
						double yk=nk.getLocation().getY();
						double xk1=nk.getDestination().getX();
						double yk1=nk.getDestination().getY();
						double θj=Math.acos((xj-xj1)/Math.sqrt(Math.pow(xj-xj1,2)+Math.pow(yj-yj1,2)));
						double θk=Math.acos((xk-xk1)/Math.sqrt(Math.pow(xk-xk1,2)+Math.pow(yk-yk1,2)));
						List<Connection> list=new ArrayList<Connection>();
						list.add(connections.get(i));
						list.add(connections.get(j));
						angle.put(list, Math.abs(θj-θk));
					}
				}
				 List<Map.Entry<List<Connection>, Double>> list = new ArrayList<Map.Entry<List<Connection>,Double>>(angle.entrySet());
			        //list.sort()
			        list.sort(new Comparator<Map.Entry<List<Connection>, Double>>() {
			            @Override
			            public int compare(Map.Entry<List<Connection>, Double> o1, Map.Entry<List<Connection>, Double> o2) {
			                return o2.getValue().compareTo(o1.getValue());
			            }
			        });
			        for(int i=0;i<=2;i++) {
			        	List<Connection> list1=list.get(i).getKey();
			        	DTNHost nj=list1.get(0).getOtherNode(getHost());
				        DTNHost nk=list1.get(1).getOtherNode(getHost());
				        double xj=nj.getLocation().getX();
						double yj=nj.getLocation().getY();
						double xj1=nj.getDestination().getX();
						double yj1=nj.getDestination().getY();
						double xk=nk.getLocation().getX();
						double yk=nk.getLocation().getY();
						double xk1=nk.getDestination().getX();
						double yk1=nk.getDestination().getY();
				        double θj=Math.acos((xj-xj1)/Math.sqrt(Math.pow(xj-xj1,2)+Math.pow(yj-yj1,2)));
						double θk=Math.acos((xk-xk1)/Math.sqrt(Math.pow(xk-xk1,2)+Math.pow(yk-yk1,2)));
						double w=Math.abs(θi-(θj+θk)/2);
						θ.put(list1,w);
			        }
			        List<Map.Entry<List<Connection>, Double>> list2 = new ArrayList<Map.Entry<List<Connection>,Double>>(θ.entrySet());
			        //list.sort()
			        list2.sort(new Comparator<Map.Entry<List<Connection>, Double>>() {
			            @Override
			            public int compare(Map.Entry<List<Connection>, Double> o1, Map.Entry<List<Connection>, Double> o2) {
			                return o2.getValue().compareTo(o1.getValue());
			            }
			        });
			        List<Connection> list3=list2.get(0).getKey();
			        tuples.add(new Tuple<Message,Connection>(m,list3.get(0)));
			        tuples.add(new Tuple<Message,Connection>(m,list3.get(1)));
				}
			}
		}
		return tryMessagesForConnected(tuples);	// try to send messages
	}
	
	@Override
	public HLRA replicate() {
		return new HLRA(this);
	}

}
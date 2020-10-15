/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;
/**基于邻居节点位置的受控传染DTN路由算法*/
import java.util.*;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;

/**
 * Epidemic message router with drop-oldest buffer and only single transferring
 * connections at a time.
 */
public class LCEpidemicRouter extends ActiveRouter {
	public static final double θ = Math.PI/18;
	/**
	 * Constructor. Creates a new message router based on the settings in
	 * the given Settings object.
	 * @param s The settings object
	 */
	public LCEpidemicRouter(Settings s) {
		super(s);
		//TODO: read&use epidemic router specific settings (if any)
	}
	
	/**
	 * Copy constructor.
	 * @param r The router prototype where setting values are copied from
	 */
	protected LCEpidemicRouter(LCEpidemicRouter r) {
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
		this.tryOtherMessages();
	}
	
	private Connection tryOtherMessages() {
		Map<List<Connection>,Double> angle=new HashMap<List<Connection>,Double>();
		List<Connection> selectedConnections=new ArrayList<Connection>();
		List<Connection> connections = getConnections();
		if (connections.size() == 0 || this.getNrofMessages() == 0) {
			return null;
		}
		else if(connections.size()<=2) {
			selectedConnections=connections;
		}
		else{
		int length=connections.size();
		DTNHost ni=getHost();
		double xi=ni.getLocation().getX();
		double yi=ni.getLocation().getY();
		for(int i=0;i<length-1;i++) {
			for(int j=i+1;j<=length-1;j++) {
				DTNHost nj=connections.get(i).getOtherNode(getHost());
				double xj=nj.getLocation().getX();
				double yj=nj.getLocation().getY();
				DTNHost nk=connections.get(j).getOtherNode(getHost());
				double xk=nk.getLocation().getX();
				double yk=nk.getLocation().getY();
				double ag=((xj-xi)*(xk-xi)+(yj-yi)*(yk-yi))/(Math.sqrt(Math.pow((xj-xi),2)+Math.pow((yj-yi),2))*Math.sqrt(Math.pow((xk-xi),2)+Math.pow((yk-yi),2)));
				List<Connection> list=new ArrayList<Connection>();
				list.add(connections.get(i));
				list.add(connections.get(j));
				angle.put(list,ag);
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
	        List<Connection> list1=list.get(0).getKey();
	        List<Connection> list2=list.get(1).getKey();
	        double ag1=list.get(0).getValue();
	        double ag2=list.get(1).getValue();
	        DTNHost nj=list1.get(0).getOtherNode(getHost());
	        DTNHost nk=list1.get(1).getOtherNode(getHost());
	        DTNHost nm=list2.get(0).getOtherNode(getHost());
	        DTNHost nn=list2.get(1).getOtherNode(getHost());
	        double xj=nj.getLocation().getX();
	        double yj=nj.getLocation().getY();
	        double xk=nk.getLocation().getX();
	        double yk=nk.getLocation().getY();
	        double xm=nm.getLocation().getX();
	        double ym=nm.getLocation().getY();
	        double xn=nn.getLocation().getX();
	        double yn=nn.getLocation().getY();
	        double d1=(Math.sqrt(Math.pow((xj-xi),2)+Math.pow((yj-yi),2))+Math.sqrt(Math.pow((xk-xi),2)+Math.pow((yk-yi),2)));
	        double d2=(Math.sqrt(Math.pow((xm-xi),2)+Math.pow((ym-yi),2))+Math.sqrt(Math.pow((xn-xi),2)+Math.pow((yn-yi),2)));
	        if(ag1-ag2<θ && d1<d2)
	        {
	        	selectedConnections=list2;
	        }
	        else {
	        	selectedConnections=list1;
	        }
		}
		List<Message> messages = 
				new ArrayList<Message>(this.getMessageCollection());
		return tryMessagesToConnections(messages,selectedConnections);	// try to send messages
	}
	
	@Override
	public LCEpidemicRouter replicate() {
		return new LCEpidemicRouter(this);
	}

}
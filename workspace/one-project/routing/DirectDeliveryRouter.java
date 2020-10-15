/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import core.Settings;

/**
 * Router that will deliver messages only to the final recipient.
 */
public class DirectDeliveryRouter extends ActiveRouter {

	public DirectDeliveryRouter(Settings s) {
		super(s);
	}
	
	protected DirectDeliveryRouter(DirectDeliveryRouter r) {
		super(r);
	}
	
	@Override
	public void update() {
		super.update();
		if (isTransferring() || !canStartTransfer()) {//判断能否进行传输
			return; // can't start a new transfer
		}
		
		// Try only the messages that can be delivered to final recipient
		if (exchangeDeliverableMessages() != null) {//若有目的节点就在本节点或者邻居节点的消息
			return; // started a transfer
		}
	}
	
	@Override
	public DirectDeliveryRouter replicate() {
		return new DirectDeliveryRouter(this);
	}
}

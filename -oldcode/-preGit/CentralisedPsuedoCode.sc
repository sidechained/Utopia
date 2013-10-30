Server {

	var serverPort, clientStartingPort;
	var addrBook;
	var eventLoop, period;
	var announceClientResponder;

	*new {
		^super.newCopyArgs(serverPort, clientStartingPort).init;
	}

	init {
		// if serverPort arg not provided, initialise serverPort variable to 50000
		// if clientStartingPort arg not provided, initialise serverPort variable to 60000
		// create new address book, assign to addrBook variable
		// initialise announceClientResponder
		// initialise and start eventLoop
	}

	startEventLoop {
		// eventLoop = Routine {
		//  - broadcast an '/server-announceServer' [serverPort] message
		//  - wait a set amount of time (period)
		// }.play
	}

	initAnnounceClientResponder {
		// * don't fix this responder, as can be sent from any client
		// if /client-announceClient [me.id, me.name, me.ip, me.port] received:
		//  - announce new client to itself
		//  - announce new client to existing clients
		//  - announce existing clients to new client
		// if ID is already in address book {
		//  - add new client to server's address book
		// }
		// else {
		//  - get existingClient from address book based on ID
		//  - set existingClient's lastResponse time of peer to new time
		// }
	}

	announceNewClientToItself {
		// - send \server-announceClientToItself [tempID, permID, peer.name, peer.addr.ip, peer.port, lastResponse] to new client address
	}

	announceNewClientToExistingClients {
		// - send \server-announceClient [newClient.id, newClient.name, newClient.addr.ip, newClient.port, peer.lastResponse] to all clients in address book
	}

	announceExistingClientsToNewClient {
		// iterate through all peers in address book {
		//  - send \server-announceClient [peer.id, peer.name, peer.addr.ip, peer.port, peer.lastResponse] to new client
		// }
	}

	checkIfClientsOnline {
		// iterate through peers in address book {
		//  - if ((time now - time last heard from peer) > period) {
		// 	 - set peer as offline
		//   - tell all clients that this peer is offline
		//  }
		// }
	}

	decommission {
		// simulate a crash:
		// stop the event loop
		// free responders
		// reset variables to nil
	}

}

Client {

	var addrBook;
	var eventLoop, period;
	var announceClientResponder;
	var serverPeer;

	*new {arg autoName = false;
		// create new address book, assign to addrBook variable
		// initialise eventLoop
	}

	init {
		// create a new address book
		// set the period
		// initialise the event loop
		// initialise serverAnnounceServerResponder
	}

	initServerAnnounceServerResponder {
		// on receipt of an /server-announceServer [serverPort] message:
		// if serverPeer does not exist {
		//  - server is online for the first time, so me:
		//  - create a new peer and assign to serverPeer (name: server, addr: NetAddr(addr, serverPort))
		//  - this.makeMe
		// }
		//  - this.serverCameOnline(time)
	}

	makeMe {
		// - if (autoName = true) {me.name = derive name from computers name}
		// - create new peer with name and temp ID and assign to 'me'
	}

	serverCameOnline {arg timeCameOnline;
		// - set serverPeer to online
		// - lastResponse: time
		// - init serverAnnounceClientResponder
		// - init serverAnnounceClientToSelfResponder
		// - start event loop
		// - inform that server came online
	}

	serverWentOffline {
		// - set serverPeer to offline
		// - free serverAnnounceClientResponder
		// - free serverAnnounceClientToSelfResponder
		// - stop event loop
		// - warn that server went offline
	}

	initEventLoop {
		// loop {
		//  - send /client-announceClient [me.id, me.name, me.ip, me.port] to server
		//  - wait for a set period
		// }
	}

	initServerAnnounceClientToSelfResponder {
		// * fix responder to the servers address and port:
		// if /server-announceClientToItself [tempID, permID, name, ip, port, lastResponse] message received {
		//  if received id = id of client {
		//  - set my id to received id
		//  - set my name to received name
		//  - set my address to received ip and port
		//  - set my last response time to received lastResponse
		//  - open UDP port at port
		//  - initialise announceClientResponder
		//  }
		// }
	}

	initServerAnnounceClientResponder {
		// * fix responder to the client's unique port:
		// if /server-announceClient [id, name, ip, port, lastResponse] message received from server {
		// - if (id exists in address book) {
		//  - create peer
		//  -- set online to true
		//  -- set lastResponse to lastResponse (same time as on server)
		//  - add peer to address book
		//  }
		// else {
		//  - get peer from address book (by id)
		//  - set name of peer to [name]
		//  - set lastResponse of peer to lastResponse (same time as on server)
		// }
	}

	checkIfServerOnline {
		// if ((time now - time last heard from server) > period) {
		// this.serverWentOffline
		// }
	}

	register {arg proposedName;
		// if proposed name not in address book {
		// - set me.name to proposedName
		// - send '/client-announceClient' [me.id, me.name, me.ip, me.port] to server
		// }
	}

	deregister {
		// if proposed name is in address book {
		// - set me.name to nil
		// - send '/client-announceClient' [me.id, me.name, me.ip, me.port] to server
		// }
	}

	decommission {
		// stop the event loop
		// free responders
		// reset variables to nil
	}

}
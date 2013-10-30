// me should not be a property of an address book
// it should be a property of a registrar or a decentralised node
// me just means looking up the peers id in the address book

// autoregistration:
// once the peer has been verified as coming online


// me should be set when own peer has come online
// how do you know when this is?
// * when a peer with your own id appears in the address book
// how to detect this?
// set up an address book dependency that looks for added peers with an id = myId

// me just means looking up the peers id in the address book
// what processes are based on this?

// decentralised autoregistration:
// as soon as the player has an id, autoregistration can occur (i.e. a name can be set)
// in fact we don't even have to wait for that


// - player provides a proposed name
// - we see if this name is in use
// - we don't need to see the name appear in the address book to know that the this is the player's name

// in a decentralised context...
// registering just means setting a name
// it doesn't mean sending any kind of registration
// this will happen automatically when the player has been given an id
//


// APPROACH

// all announcement are client announcements
// if the id is not in the address book, it must be a tempId
// therefore

// Q: call nodes clients or peers? A: decentralised = peers, centralised = client, server
// Q: should it be possible to change name? not really, you should have to deregister and register again
// Q: is 'monitoring' possible? yes, it is the default state. it is not possible to stop listening, and still keep registering though (use case?)

// clarification of online/offline behaviour
// - there are two states, online and registered
// - online is simply whether you can be contacted on the network
// - registered is whether you have a name or not
// - address book can return peers, onlinePeers, or registeredPeers (or even registeredOnlinePeers)

CentralisedServer {

	// new centralised approach:
	// - clients start by creating a mePeer with a random ID
	// - the server receives an /getId message, and as the random ID isn't in the address book it:
	// - allocates a 'permanent ID' based on the IDs already in use in the address book
	// - server sends an /setId [tempID, permID, clientStartingPort] back to address
	// - back on the client the /serverSetClientID is filtered by it's own temp Id, before switching the tempID for the permID
	// - at this stage responders are set up and fix to the clients allocated port
	// - me is now just a reference to my peer in the address book (saves maintaining two identical things)

	// centralised registration is a three stage process:
	// 1. find server
	// 2. get id from server
	// 3. start announcing

	var serverPort, clientStartingPort, period, surviveCmdPeriod;
	var <addrBook;
	var eventLoop, eventLoopName;
	var broadcastAddr;
	var getIdResponder, registrationResponder, deregistrationResponder, announceClientResponder;

	*new {arg serverPort = 50000, clientStartingPort = 60000, period = 1, surviveCmdPeriod = true;
		^super.newCopyArgs(serverPort, clientStartingPort, period, surviveCmdPeriod).init;
	}

	init {
		this.checkForExistingServer;
	}

	checkForExistingServer {
		inform("SERVER: checking for existing server...");
		fork {
			var announceServerResponder, serverFound;
			serverFound = false;
			announceServerResponder = OSCFunc({arg msg, time, addr;
				serverFound = true;
			}, '/server-announceServer').permanent_(surviveCmdPeriod);
			(period * 2).wait;
			announceServerResponder.free;
			if (serverFound) { warn("cannot initialise server, another server is already running...") } { this.existingServerNotFound };
		};
	}

	existingServerNotFound {
		inform("SERVER: no existing server found, initialising...");
		thisProcess.openUDPPort(serverPort);
		addrBook = NMLAddrBook.new;
		this.initBroadcastAddr;
		this.initGetIdResponder;
		this.listenForRegistrationsAndDeregistrationsFromClients;
		this.listenForAnnouncementsFromClients;
		this.initAndStartEventLoop;
	}

	initBroadcastAddr {
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initGetIdResponder {
		getIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId, name, client;
			# tempId, name = msg.drop(1);
			permId = addrBook.getNextFreeID;
			\nnn.postln;
			name.postln;
			if (name == 0) {name == nil }; // osc reconversion
			name.postln;
			addr.sendMsg('/server-setId', tempId, permId, clientStartingPort);
			// server needs to add the peer to the address book here or?
			// yes definitely, as if we wait to add the peer in this time someone else good sent a get-id and take the same id
			client = NMLPeer(
				id: permId,
				name: name, // if a name is provided at the automatic registration stage, it will be allocated here, else will be nil
				addr: addr,
				online: true,
				lastResponse: time
			);
			// PROPOGATE THE CHANGES:
			this.announceExistingClientsToClient(client); // sync when new
			addrBook.add(client);
			this.announceClientToExistingClients(client); // includes telling client about itself
		}, '/client-getId').permanent_(surviveCmdPeriod);
	}

	listenForRegistrationsAndDeregistrationsFromClients {
		registrationResponder = OSCFunc({arg msg;
			var receivedId, receivedProposedName;
			# receivedId, receivedProposedName = msg.drop(1);
			\addrBook.postln;
			addrBook.peers.postln;
			// ADDRESS BOOK IS BLANK HERE
			if (addrBook.names.includes(receivedProposedName).not) {
				addrBook.registerPeerIfDeregistered(receivedId, receivedProposedName);
				addrBook.at(receivedId).addr.sendMsg('/registerClient-reply', true)
			}
			{
				addrBook.at(receivedId).addr.sendMsg('/registerClient-reply', false)
			}
		}, '\registerClient', recvPort: serverPort);
		deregistrationResponder = OSCFunc({arg msg;
			var receivedId;
			# receivedId = msg.drop(1);
			addrBook.deregisterPeerIfRegistered(receivedId);
			addrBook.at(receivedId).addr.sendMsg('/deregisterClient-reply')
		}, '\deregisterClient', recvPort: serverPort);
	}

	listenForAnnouncementsFromClients {
		announceClientResponder = OSCFunc({arg msg, time, addr;
			var id, name, port, client;
			msg.postln;
			# id, name, port = msg.drop(1);
			if (name == 0) { name = nil }; // correct OSC conversion of nil's to 0's
			// presuming that clients were put into address book at getID time, and that all client announcements MUST be updates
			addrBook.peers.postln;
			// UPDATE EXISTING CLIENT BASED ON ID:
			client = addrBook.at(id);
			if (name.notNil) {
				addrBook.registerPeerIfDeregistered(id, name); // if a name is set manually (i.e. registration) it will be set as an update here
			};
			addrBook.updatePeerLastResponseTimeIfNotNil(id, time);
			// PROPOGATE THE CHANGES:
			this.announceClientToExistingClients(client);
		}, '/client-announceClient', recvPort: serverPort).permanent_(surviveCmdPeriod); // only accept messages targeted at server's port
	}

	initAndStartEventLoop {
		eventLoopName = \centralisedServer_eventLoop;
		// not a problem to have a non-instance-specific name here (as there should only ever be one server)
		eventLoop = SkipJack({
			this.announceServer;
			this.checkIfClientsOnline;
		}, period, name: eventLoopName, clock: SystemClock); // NOTE: starts automatically
		if (surviveCmdPeriod.not) {
			CmdPeriod.add({
				SkipJack.stop(eventLoopName);
		})};
	}

	announceServer {
		broadcastAddr.sendMsg('/server-announceServer', serverPort);
	}

	announceClientToExistingClients {arg client;
		addrBook.sendAll('/server-announceClient', client.id, client.name, client.addr.ip, client.addr.port, client.online, client.lastResponse)
	}

	announceExistingClientsToClient {arg client;
		addrBook.peers.do{arg peer;
			client.addr.sendMsg('/server-announceClient', peer.id, peer.name, peer.addr.ip, peer.addr.port, peer.online, peer.lastResponse);
		}
	}

	checkIfClientsOnline {
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do{arg client;
			if (client.online) {
				if ((now - client.lastResponse) > (period * 2)) {
					inform("%: % % went offline".format(\server, client.id, client.name));
					addrBook.takePeerOfflineIfOnline(client.id);
					this.announceClientToExistingClients(addrBook.at(client.id)); // tell all clients that this peer is offline
					// TODO: once is not enough, need to do this repeatedly to ensure receipt...
				}
			}
		}
	}

	decommission {
		// simulate a crash:
		getIdResponder.free;
		registrationResponder.free;
		deregistrationResponder.free;
		announceClientResponder.free;
		SkipJack.stop(eventLoopName);
		// reset all variables:
		serverPort = nil;
		clientStartingPort = nil;
		addrBook = nil;
		eventLoop = nil;
		period = nil;
		announceClientResponder = nil;
	}

}

CentralisedClient {

	var period, autoName, surviveCmdPeriod, verbose, hasGui;
	var <addrBook;
	var <id, <name, <port;
	var eventLoop, eventLoopName;
	var announceServerResponder, setIdResponder, announceClientResponder;
	var <serverPeer;
	var reporter, gui;

	*new {arg period = 1, autoName = false, surviveCmdPeriod = true, verbose = false, hasGui = false;
		^super.newCopyArgs(period, autoName, surviveCmdPeriod, verbose, hasGui).initAnnounceServerResponder;
	}

	announceMe {
		serverPeer.addr.sendMsg('/client-announceClient', id, name, port); // could we send on the port we're using?
	}

	initAnnounceServerResponder {
		inform("waiting for server to come online...");
		announceServerResponder = OSCFunc({arg msg, time, addr;
			var serverPort;
			# serverPort = msg.drop(1);
			if (serverPeer.isNil) { // if server does not exist:
				inform("new server found at: %".format([addr.ip, serverPort]));
				this.newServerFound(addr.ip, serverPort, time);
			}
			{
				if (serverPeer.addr.ip != addr.ip) { // - if server exists but it's ip has changed (not considering port for now):
					inform("server address has changed to %, assuming new server".format([addr.ip, serverPort]));
					this.newServerFound(addr.ip, serverPort, time);
				}
				{
					if (serverPeer.online.not) { // - if server exists, it address hasn't changed but it is offline:
						// server came back online (from being offline):
						inform("server came back online at: %".format([addr.ip, serverPort]));
						serverPeer.online = true;
						serverPeer.lastResponse = time;
					}
					{ // - if server exists, it address hasn't changed and it is online:
						// just update its last response time
						// inform("server ping received, updating last response time");
						serverPeer.lastResponse = time;
					}
				}
			}
		}, '/server-announceServer').permanent_(surviveCmdPeriod); // receiving broadcasts, no need to fix responder
	}

	newServerFound {arg serverIp, serverPort, time;
		serverPeer = NMLPeer(
			addr: NetAddr(serverIp, serverPort),
			online: true,
			lastResponse: time
		);
		inform("registering with new server...");
		setIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId, clientStartingPort;
			# tempId, permId, clientStartingPort = msg.drop(1);
			if ( id == tempId ) {
				id = permId;
				port = permId + clientStartingPort;
				this.init;
			};
		}, '/server-setId').permanent_(surviveCmdPeriod).oneShot;
		id = rand2(-2147483647, 2147483647);
		serverPeer.addr.sendMsg('/client-getId', id, name);
	}

	init {
		addrBook = NMLAddrBook.new;
		if (verbose) { reporter = NMLAddrBookReporter.new(addrBook); };
		if (hasGui) { defer { gui = NMLAddrBookGUI.new(addrBook); } };
		if (autoName) { this.register(this.getComputerName); };
		thisProcess.openUDPPort(port);
		// start listening for other peers:
		this.listenForAnnouncementsFromServer;
		// start sending own peer:
		this.initEventLoop(id);
		eventLoop.play(SystemClock);
	}

	listenForAnnouncementsFromServer {
		announceClientResponder = OSCFunc({arg msg, time, addr;
			var id, name, ip, port, online, lastResponse;
			# id, name, ip, port, online, lastResponse = msg.drop(1);
			if (name == 0) { name = nil }; // correct OSC conversion of nil's to 0's
			ip = ip.asString; // correct OSC conversion of ip string to symbol
			online = online.asBoolean; // correct OSC conversion of booleans to integers (CHECK: Utopia converts before sending)
			if (addrBook.ids.includes(id).not) { // if id doesn't exist in address book
				var newPeer;
				newPeer = NMLPeer(
					id: id,
					name: name, // copy from server
					addr: NetAddr(ip, port), // copy from server
					online: true,
					lastResponse: lastResponse // copy from server
				);
				addrBook.add(newPeer);
			}
			{
				// if id does exist in address book:
				// via addrBook to ensure dependencies react:
				if (online) { addrBook.takePeerOnlineIfOffline(id) } { addrBook.takePeerOfflineIfOnline(id) };
				if (name.notNil) { addrBook.registerPeerIfDeregistered(id, name) } { addrBook.deregisterPeerIfRegistered(id) };
				addrBook.updatePeerLastResponseTimeIfNotNil(id, lastResponse);
			};
		}, '/server-announceClient', recvPort: port).permanent_(surviveCmdPeriod); // fix to the client's unique port
	}

	initEventLoop {arg id;
		eventLoopName = \centralisedClient_eventLoop ++ id;
		eventLoop = SkipJack({
			this.announceMe;
			this.checkIfServerOnline;
		}, period, name: eventLoopName, clock: SystemClock, autostart: false); // don't start
		if (surviveCmdPeriod.not) {
			CmdPeriod.add({
				SkipJack.stop(eventLoopName);
		})};
	}

	getComputerName {
		var computerName;
		computerName = "whoami".unixCmdGetStdOut;
		if(computerName.last == Char.nl, {computerName = computerName.drop(-1)});
		inform("auto-registering with name %".format(computerName));
		^computerName.asString;
	}

	checkIfServerOnline {
		var now;
		now = Main.elapsedTime;
		if (serverPeer.online) {
			if ((now - serverPeer.lastResponse) > (period * 2)) {
				warn("server went offline");
				serverPeer.online = false;
			}
		}
	}

	register {arg proposedName;
		// in a centralised context you can only register if the server is online:
		if (serverPeer.notNil) {
			if (name.isNil) {
				if (proposedName != 0) {
					OSCFunc({arg msg;
						var nameFree;
						# nameFree = msg.drop(1);
						nameFree = nameFree.asBoolean; // osc converts booleans to integers, convert back here
						if (nameFree) {
							name = proposedName;
							inform("successfully registered with name %".format(name));
						}
						{
							warn("name % is in use".format(proposedName));
						}
					}, '/registerClient-reply', recvPort: port).oneShot.permanent_(surviveCmdPeriod); // oneshot is correct approach here?
					serverPeer.addr.sendMsg('/registerClient', id, proposedName);
				}
				{
					warn("cannot use name 0");
				}
			}
			{
				warn("you already have a name");
			}
		}
		{
			warn("server not online");
		}
	}

	deregister {
		if (name.notNil) {
			OSCFunc({arg msg;
				name = nil;
				inform("deregistration successful");
			}, '/deregisterClient-reply', recvPort: port).oneShot.permanent_(surviveCmdPeriod); // oneshot is correct approach here?
			serverPeer.addr.sendMsg('/deregisterClient', id);
		}
		{
			inform("you are not registered");
		}
	}

	decommission {
		// simulate a crash:
		gui !? { gui.destroy };
		reporter !? { reporter.decommission };
		// - free responders:
		announceClientResponder.free;
		setIdResponder.free;
		announceServerResponder.free;
		SkipJack.stop(eventLoopName);
		// - reset all variables:
		autoName = nil;
		period = nil;
		addrBook = nil;
		eventLoop = nil;
		announceClientResponder = nil;
		setIdResponder = nil;
		announceServerResponder = nil;
		serverPeer = nil;
	}

	me {
		^addrBook.atId(myId) ?? { warn("me not yet in address book") };
	}

}

DecentralisedNode { // node, or peer?

	var peerStartingPort, autoName, period, surviveCmdPeriod, verbose, hasGui;
	var <addrBook;
	var <id, <name, <port;
	var broadcastAddr;
	var eventLoop, eventLoopName;
	var announcePeerResponder;
	var reporter, gui;

	*new {arg peerStartingPort = 60000, autoName = false, period = 1, surviveCmdPeriod = true, verbose = false, hasGui = false;
		^super.newCopyArgs(peerStartingPort, autoName, period, surviveCmdPeriod, verbose, hasGui).init;
	}

	init {
		addrBook = NMLAddrBook.new;
		if (verbose) { reporter = NMLAddrBookReporter.new(addrBook); };
		if (hasGui) { defer { gui = NMLAddrBookGUI.new(addrBook); } };
		if (autoName) { this.register(this.getComputerName); };
		this.initBroadcastAddr;
		this.goOnline;
	}

	goOnline {
		fork{
			this.listenForAnnouncementsFromPeers;
			(period * 2).wait;
			id = addrBook.getNextFreeID;
			//this.checkAddSelf;
			this.initEventLoop(id);
			eventLoop.play(SystemClock);
		};
	}

	getComputerName {
		var computerName;
		computerName = "whoami".unixCmdGetStdOut;
		if(computerName.last == Char.nl, {computerName = computerName.drop(-1)});
		^computerName;
	}

	isOnline {
		addrBook.at(id).online;
	}

	register {arg proposedName;
		if (name.isNil) {
			if (proposedName != 0) {
				// in decentralised approach, to check if a name exists we just consult the address book before registering
				if (addrBook.names.includes(proposedName).not) {
					name = proposedName;
					//this.announcePeer; // immediately announce (or restart eventloop?)
				}
			}
			{
				warn("cannot use name 0");
			}
		}
		{
			warn("you have already chosen a name, please deregister first");
		};
	}

	deregister {
		if (name.notNil) {
			if (addrBook.names.includes(name)) {
				name = nil;
				//this.announcePeer; // immediately announce (or restart eventloop?)
			} {
				warn("name % not in address book".format(name));
			}
		}
		{
			warn("you don't have a name to deregister");
		}
	}

	initBroadcastAddr {
		// set broadcast address:
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initEventLoop {arg id;
		eventLoopName = \decentralisedNode_eventLoop ++ id;
		eventLoop = SkipJack({
			this.announcePeer;
			this.checkIfPeersStillOnline;
		}, period, name: eventLoopName, clock: SystemClock, autostart: false); // don't start
		if (surviveCmdPeriod.not) {
			CmdPeriod.add({
				SkipJack.stop(eventLoopName);
		})};
	}

	announcePeer {
		broadcastAddr.sendMsg('/announcePeer', id, name);
	}

	checkIfPeersStillOnline {
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.online) {
				if((now - peer.lastResponse) > (period * 2), {
					inform("% %: % went offline".format(id, name, peer));
					addrBook.takePeerOfflineIfOnline(peer.id);
					// addrBook.deregisterPeer(peer.id); // reset name so it can be reused - better yet would be to allow names and ids to be resued once the player has gone offline
				});
			};
		});
	}

	listenForAnnouncementsFromPeers {
		announcePeerResponder = OSCFunc({arg msg, time, addr;
			var receivedId, receivedName;
			# receivedId, receivedName = msg.drop(1);
			if (receivedName == 0) { receivedName = nil}; // reconvert;
			if (addrBook.ids.includes(receivedId).not) {
				// add
				var newPeer;
				port = receivedId + peerStartingPort;
				newPeer = NMLPeer(
					id: receivedId,
					name: receivedName,
					addr: NetAddr(addr.ip, port),
					online: true,
					lastResponse: time
				);
				addrBook.add(newPeer);
				// open port for self:
				if (receivedId == id) {
					thisProcess.openUDPPort(port);
				};
			}
			{
				// update (via addrBook to ensure dependencies react):
				addrBook.takePeerOnlineIfOffline(receivedId);
				if (receivedName.notNil) { addrBook.registerPeerIfDeregistered(receivedId, receivedName) } { addrBook.deregisterPeerIfRegistered(receivedId) };
				addrBook.updatePeerLastResponseTimeIfNotNil(receivedId, time);
			}
		}, '/announcePeer').permanent_(surviveCmdPeriod);
	}

	decommission {
		// simulate a crash
		gui !? { gui.destroy };
		reporter !? { reporter.decommission };
		announcePeerResponder.free; // stop listening
		SkipJack.stop(eventLoopName); // stop sending
		addrBook = nil;
		broadcastAddr = nil;
		eventLoop = nil;
		period = nil;
		peerStartingPort = nil;
	}

	me {
		^addrBook.atId(myId) ?? { warn("me not yet in address book") };
	}

	/*	checkAddSelf {
	addrBook.addDependant({arg addrBook, what, peer;
	if (peer.id == id) {
	case
	{ what == \add } {
	\selfAddedToAddrBook.postln;
	// trigger auto registration
	if (autoName) { this.register(this.getComputerName); };
	}
	/*				{ what == \remove } {
	\selfRemovedFromAddrBook.postln;
	}*/
	}
	});
	}*/

}

NMLPeer {

	// refactor to add id's
	// need to add back in other stuff from original Utopia Peer Class

	var <>id, <>name, <>addr, <online, <>lastResponse;

	*new {arg id, name, addr, online, lastResponse;
		^super.newCopyArgs(id, name, addr, online, lastResponse);
	}

	online_ {|bool| if(bool != online, { online = bool; this.changed(\online) }) }

	== {|other|
		var result;
		// don't consider last response time here
		result = (id == other.id) && (name == other.name) && (addr == other.addr) && (online == other.online);
		^result;
	}

	hash {
		^this.instVarHash(#[\id, \name, \addr, \online, \lastResponse])
	}

	printOn {arg stream;
		// post pretty
		stream << this.class.name << "(" <<* [id, name, addr, online, lastResponse] << ")"
	}

}

NMLAddrBook {

	// refactor of NMLAddrBook to make ID the primary key
	// TODO: address book could warn rather than throw errors if an id or name is not found

	var <dict, <me; // remove getter from dict

	*new { ^super.new.init }

	init { dict = IdentityDictionary.new; }

	sendId {|id ...msg| dict[id].addr.sendMsg(*msg) }

	sendName {|name ...msg| this.atName(name).addr.sendMsg(*msg) }

	sendAll {|...msg| dict.do({|peer| peer.addr.sendMsg(*msg); }); }

	sendAllBundle {|time ...msg| dict.do({|peer| peer.addr.sendBundle(time, *msg); }); }

	sendExcludingId {|id ...msg| dict.reject({|peer, peerId| peerId == id }).do({|peer| peer.addr.sendMsg(*msg); });}

	sendExcludingName {|name ...msg| dict.reject({|peer, peerName| peerName == name }).do({|peer| peer.addr.sendMsg(*msg); });}

	add {|peer|
		dict[peer.id] = peer;
		peer.addDependant(this);
		this.changed(\add, peer);
		peer.online !? { if (peer.online) { this.changed(\cameOnline, peer) }; };
		if (peer.name.notNil) { this.changed(\registeredName, peer) };
		if (peer.lastResponse.notNil) { this.changed(\updatedLastResponseTime, peer) };
	}

	remove {|peer|
		dict[peer.id] = nil;
		peer.removeDependant(this);
		peer.online !? { if (peer.online) { this.changed(\wentOffline, peer) }; };
		if (peer.name.notNil) { this.changed(\deregisteredName, peer) };
		this.changed(\remove, peer)
	}

	// removed addMe method for now

	at {|id| ^dict[id] } // if id doesn't exist, warn rather than throwing error

	atId {|id| ^this.at(id) }

	atName {|name| ^dict.values.detect({|peer| peer.name == name }) } // if name doesn't exist, warn rather than throwing error

	removeAt {|id| this.remove(dict[id]) }

	removeAtId {|id| this.removeAt(id) }

	removeAtName {|name| var peerToRemove; peerToRemove = this.atName(name); this.removeAt(peerToRemove.id) }

	update {|changed, what| this.changed(what, changed) }

	ids { ^dict.keys }

	names { ^dict.collect({|peer| peer.name}) }

	addrs { ^dict.values.collect({|peer| peer.addr }) }

	peers { ^dict.values }

	namedPeers { ^dict.reject({|peer| peer.name.isNil }).values }

	registeredPeers { ^this.namedPeers }

	onlinePeers { ^dict.reject({|peer| peer.online.not }).values }

	onlineNamedPeers { ^dict.reject({|peer| peer.name.isNil || peer.online.not }).values }

	onlineRegisteredPeers { ^this.registeredPeers }

	getNextFreeID {
		if (dict.keys.notEmpty) {
			^dict.keys.maxItem + 1;
		}
		{
			^0;
		}
	}

	takePeerOnlineIfOffline {|id|
		var peer;
		peer = this.at(id);
		if (peer.online == false) {
			peer.online = true;
			this.changed(\cameOnline, peer);
		};
	}

	takePeerOfflineIfOnline {|id|
		var peer;
		peer = this.at(id);
		if (peer.online == true) {
			peer.online = false;
			this.changed(\wentOffline, peer);
		};
	}

	registerPeerIfDeregistered {|id, name|
		var peer;
		peer = this.at(id);
		if (peer.name.isNil) {
			peer.name = name;
			this.changed(\registeredName, peer);
		};
	}

	deregisterPeerIfRegistered {|id|
		var peer;
		peer = this.at(id);
		if (peer.name.notNil) {
			peer.name = nil;
			this.changed(\deregisteredName, peer);
		};
	}

	updatePeerLastResponseTimeIfNotNil {|id, time|
		var peer;
		peer = this.at(id);
		if (peer.lastResponse.notNil) {
			peer.lastResponse = time;
			this.changed(\updatedLastResponseTime, peer);
		};
	}

}

NMLAddrBookReporter {

	var addrBook, dependancyFunc;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		dependancyFunc = {arg addrBook, what, peer;
			case
			{ what == \add } {
				inform("% added".format(peer))
			}
			{ what == \remove } {
				inform("% removed".format(peer))
			}
			{ what == \cameOnline} {
				inform("% came online".format(peer))
			}
			{ what == \wentOffline } {
				inform("% went offline".format(peer))
			}
			{ what == \registeredName } {
				inform("% registered".format(peer))
			}
			{ what == \deregisteredName } {
				inform("% deregistered".format(peer))
			}
			{ what == \updatedLastResponseTime } {
				inform("% updated last response time".format(peer))
			};
		};
		addrBook.addDependant(dependancyFunc)
	}

	decommission {
		addrBook.removeDependant(dependancyFunc);
		addrBook = nil;
	}

}

NMLAddrBookGUI {

	// a gui showing a list of currently online peers
	// TODO: identify self in address book with different colour
	// better defer approach

	var addrBook, <peerRows, mainView, guiDict;
	var rowWidth;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		guiDict = IdentityDictionary.new;
		this.initAddrBookDep;
		^this.makeMainView;
	}

	makeMainView {
		peerRows = View().layout_(VLayout(*[nil])
			.margins_(0)
			.spacing_(0)
		);
		mainView = ScrollView(nil, Rect(600, 500, 300, 50))
		.alwaysOnTop_(true)
		.front;
		mainView.canvas = View()
		.background_(Color.white);
		rowWidth = mainView.bounds.width/4;
		mainView.layout_(VLayout(*[this.makeTitleRow, peerRows, nil])
			.margins_(0)
			.spacing_(0)
		);
		^mainView
	}

	makeTitleRow {
		^View().layout_(HLayout(*[
			StaticText().fixedWidth_(rowWidth).string_("ID:"),
			StaticText().fixedWidth_(rowWidth).string_("Name:"),
			StaticText().fixedWidth_(rowWidth).string_("IP:"),
			StaticText().fixedWidth_(rowWidth).string_("Port:"),
			StaticText().fixedWidth_(rowWidth).string_("Response:")
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	makePeerRow {arg peer;
		^View().layout_(HLayout(*[
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.id),
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.name),
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.addr.ip),
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.addr.port),
			Button().fixedWidth_(rowWidth).states_([[nil, nil, Color.black()], [nil, nil, Color.magenta()]])
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	initAddrBookDep {
		addrBook.addDependant({arg addrBook, what, peer;
			defer { // deferring the whole damn thing, for now!

				case
				{ what == \add } {
					this.addRow(peer);
				}
				{ what == \remove } {
					this.removeRow(peer);
				}
				{ what == \cameOnline} {
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \wentOffline } {
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \registeredName } {
					this.setName(peer);
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \deregisteredName } {
					this.setName(peer);
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \updatedLastResponseTime } {
					this.flashRowButton(peer);
				};
			}
		})
	}

	setBackgroundAccordingToOnlineRegisteredState {arg peer;
		case
		{ peer.online && peer.name.isNil } { this.setRowBackgroundColor(peer, Color.red); }
		{ peer.online && peer.name.notNil } { this.setRowBackgroundColor(peer, Color.green); }
		{ peer.online.not && peer.name.isNil } { this.setRowBackgroundColor(peer, Color.white); }
		{ peer.online.not && peer.name.notNil } {
			this.setRowBackgroundColor(peer, Color.white);
			warn("offline and registered, this should never happen")
		};
	}

	makeRow {
		^StaticText().background_(Color.rand);
	}

	addRow {arg peer;
		var rowToAdd;
		rowToAdd = this.makePeerRow(peer);
		guiDict.put(peer.id, rowToAdd);
		peerRows.layout.add(rowToAdd);
	}

	removeRow {arg peer;
		var rowToRemove;
		rowToRemove = guiDict.at(peer.id);
		peerRows.children.remove(rowToRemove).destroy;
	}

	setRowBackgroundColor {arg peer, color;
		var rowToSet;
		rowToSet = guiDict.at(peer.id).background_(color);
	}

	setName {arg peer;
		var rowToSet;
		rowToSet = guiDict.at(peer.id).children[1].string_(peer.name);
	}

	flashRowButton {arg peer;
		var rowToFlash;
		rowToFlash = guiDict.at(peer.id);
		Routine({
			1.do{
				rowToFlash.children[4].value_(1);
				0.1.wait;
				rowToFlash.children[4].value_(0);
			};
		}).play(AppClock);
	}

}


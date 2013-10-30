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

	var serverPort, clientStartingPort, period;
	var <addrBook;
	var eventLoop;
	var broadcastAddr;
	var getIdResponder, announceClientResponder;

	*new {arg serverPort = 50000, clientStartingPort = 60000, period = 1;
		^super.newCopyArgs(serverPort, clientStartingPort, period).init;
	}

	init {
		thisProcess.openUDPPort(serverPort);
		addrBook = NMLAddrBook.new;
		this.initBroadcastAddr;
		this.initAnnounceClientResponder;
		this.initGetIdResponder;
		this.initAndStartEventLoop;
	}

	initBroadcastAddr {
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initGetIdResponder {
		getIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId;
			# tempId = msg.drop(1);
			permId = addrBook.getNextFreeID;
			addr.sendMsg('/server-setId', tempId, permId, clientStartingPort);
		}, '/client-getId').permanent;
	}

	initAnnounceClientResponder {
		announceClientResponder = OSCFunc({arg msg, time, addr;
			var id, name, port, client;
			# id, name, port = msg.drop(1);
			if (name == 0) { name = nil }; // correct OSC conversion of nil's to 0's
			if (addrBook.ids.includes(id).not) { // if id is not already in address book
				client = NMLPeer(
					id: id,
					name: name, // if a name is provided at the automatic registration stage, it will be allocated here
					addr: NetAddr(addr.ip, port),
					online: true,
					lastResponse: time
				);
				// PROPOGATE THE CHANGES:
				this.announceClientToItself(client);
				this.announceClientToExistingClients(client);
				this.announceExistingClientsToClient(client);
				// finally, update the server's own address book
				addrBook.add(client);
			}
			{
				// RETRIEVE EXISTING CLIENT AT ID:
				client = addrBook.atId(id);
				client.name = name; // if a name is set manually (i.e. registration) it will be set as an update here
				client.lastResponse = time;
				// PROPOGATE THE CHANGES:
				this.announceClientToItself(client);
				this.announceClientToExistingClients(client);
				this.announceExistingClientsToClient(client);
			};
		}, '/client-announceClient', recvPort: serverPort).permanent // only accept messages targeted at server's port
	}

	initAndStartEventLoop {
		eventLoop = SkipJack({
			this.announceServer;
			this.checkIfClientsOnline;
		}, period, name: \centralisedServer_eventLoop, clock: SystemClock);
		// eventLoop = Routine({
		// 	inf.do{
		// 		this.announceServer;
		// 		this.checkIfClientsOnline;
		// 		period.wait;
		// 	}
		// }).play(SystemClock)
	}

	announceServer {
		broadcastAddr.sendMsg('/server-announceServer', serverPort);
	}

	announceClientToItself {arg client;
		client.addr.sendMsg('/server-announceClient', client.id, client.name, client.addr.ip, client.addr.port, client.online, client.lastResponse);
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
					client.online = false;
					inform("%: % went offline".format(\server, client));
					// peer.name = nil; // auto deregister (good idea? CHECK!)
					this.announceClientToExistingClients(client); // tell all clients that this peer is offline (CHECK need to do this repeatedly to ensure receipt?)
				}
			}
		}
	}

	decommission {
		// simulate a crash:
		announceClientResponder.free;
		eventLoop.stop;
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

	var autoName, period;
	var <addrBook;
	var <id, <name, <port;
	var eventLoop;
	var announceServerResponder, setIdResponder, announceClientResponder;
	var <serverPeer;

	*new {arg autoName = false, period = 1;
		^super.newCopyArgs(autoName, period).init;
	}

	init {
		addrBook = NMLAddrBook.new;
		this.initEventLoop;
		this.initAnnounceServerResponder;
	}

	getId {
		setIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId, clientStartingPort;
			# tempId, permId, clientStartingPort = msg.drop(1);
			if ( id == tempId ) {
				id = permId;
				port = permId + clientStartingPort;
				thisProcess.openUDPPort(port);
				// start listening for other peers:
				this.initAnnounceClientResponder;
				// start sending own peer:
				eventLoop.play(SystemClock);
				// if autoName = true, wait for incoming clients to be received, then autoRegister
				if (autoName) {
					fork {
						(period * 2).wait;
						this.autoRegister;
					};
				};
			};
		}, '/server-setId').permanent.oneShot;
		id = rand2(-2147483647, 2147483647);
		NetAddr.localAddr.sendMsg('/client-getId', id);
	}

	initEventLoop {
		eventLoop = SkipJack({
			this.announceMe;
			this.checkIfServerOnline;
		}, period, name: \centralisedClient_eventLoop, clock: SystemClock);
		// eventLoop = Routine({
		// 	inf.do{
		// 		this.announceMe;
		// 		this.checkIfServerOnline;
		// 		period.wait;
		// 	}
		// })
	}

	announceMe {
		serverPeer.addr.sendMsg('/client-announceClient', id, name, port); // could we send on the port we're using?
	}

	initAnnounceServerResponder {
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
						inform("server ping received, updating last response time");
						serverPeer.lastResponse = time;
					}
				}
			}
		}, '/server-announceServer').permanent // receiving broadcasts, no need to fix responder
	}

	newServerFound {arg serverIp, serverPort, time;
		serverPeer = NMLPeer(
			addr: NetAddr(serverIp, serverPort),
			online: true,
			lastResponse: time
		);
		inform("auto-registering with new server...");
		this.getId;
	}

	initAnnounceClientResponder {
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
					name: name,
					addr: NetAddr(ip, port), // copy from server
					online: online,
					lastResponse: lastResponse // copy from server
				);
				addrBook.add(newPeer);
			}
			{
				// if id does exist in address book
				var existingPeer;
				// no peers in address book here, why?
				existingPeer = addrBook.atId(id);
				existingPeer.name = name;
				existingPeer.online = online;
				existingPeer.lastResponse = lastResponse; // copy from server
			};
		}, '/server-announceClient', recvPort: port).permanent // fix to the client's unique port
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

	autoRegister {
		var computerName;
		computerName = "whoami".unixCmdGetStdOut;
		if(computerName.last == Char.nl, {computerName = computerName.drop(-1)});
		this.register(computerName);
	}


	register {arg proposedName;
		if (proposedName != 0) {
			if (name.isNil) {
				if (addrBook.names.includes(proposedName).not) { // if proposed name not in address book
					name = proposedName;
					this.announceMe; // immediately update, don't wait for eventLoop
				}
				{
					warn("name % in use".format(proposedName));
				}
			}
			{
				warn("you are already registered as %, please deregister first".format(name))
			}
		}
		{
			warn("cannot use name 0");
		}
	}

	deregister {
		if (name.notNil) {
			name = nil;
			this.announceMe; // immediate update, don't wait for eventLoop
		}
		{
			inform("you have already deregistered");
		}
	}

	decommission {
		// - free responders:
		announceClientResponder.free;
		setIdResponder.free;
		announceServerResponder.free;
		// simulate a crash:
		eventLoop.stop;
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
		^addrBook.atId(id);
	}

}

DecentralisedNode { // node, or peer?

	var autoName, peerStartingPort, period;
	var <addrBook;
	var <id, <name, <port;
	var broadcastAddr;
	var eventLoop;
	var announcePeerResponder;

	*new {arg autoName = false, peerStartingPort = 60000, period = 1;
		^super.newCopyArgs(autoName, peerStartingPort, period).init;
	}

	init {
		addrBook = NMLAddrBook.new;
		this.initBroadcastAddr;
		this.initEventLoop;
		fork{
			this.listenForAnnouncementsFromPeers;
			(period * 2).wait;
			id = addrBook.getNextFreeID;
			eventLoop.play(SystemClock);
		};
	}

	initBroadcastAddr {
		// set broadcast address:
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initEventLoop {
		eventLoop = SkipJack({
			this.announcePeer;
			this.checkIfPeersStillOnline;
		}, period, name: \decentralisedNode_eventLoop, clock: SystemClock);
		/*		eventLoop = Routine({
		inf.do{
		this.announcePeer;
		this.checkIfPeersStillOnline;
		period.wait;
		};
		});*/
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
					addrBook[peer.id].online = false;
					addrBook[peer.id].name = nil; // reset name so it can be reused - better yet would be to allow names and ids to be resued once the player has gone offline
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
				if (receivedId == id) {
					thisProcess.openUDPPort(port);
				};
			}
			{
				// update
				var existingPeer;
				existingPeer = addrBook.at(receivedId);
				existingPeer.name = receivedName;
				// update address here or not?
				existingPeer.online = true;
				existingPeer.lastResponse = time;
			}
		}, '/announcePeer').permanent
	}

	register {arg proposedName;
		if (proposedName != 0) {
			if (name.isNil) {
				if (addrBook.names.includes(proposedName).not) {
					name = proposedName;
				}
				{
					warn("name % is in use".format(name));
				}
			}
			{
				warn("you are already registered as %, please deregister first".format(name))
			}
		}
		{
			warn("cannot use name 0");
		}
	}

	deregister {
		if (addrBook.names.includes(name)) {
			name = nil;
		} {
			warn("name % not in address book".format(name));
		}
	}

	decommission {
		// simulate a crash
		announcePeerResponder.free; // stop listening
		eventLoop.stop; // stop sending
		addrBook = nil;
		broadcastAddr = nil;
		eventLoop = nil;
		period = nil;
		peerStartingPort = nil;
	}

	me {
		^addrBook.atId(id);
	}

}


NMLPeer {

	// refactor to add id's
	// need to add back in other stuff from original Utopia Peer Class

	var <>id, <>name, <>addr, <>online, <>lastResponse;

	*new {arg id, name, addr, online, lastResponse, autoName = false;
		^super.newCopyArgs(id, name, addr, online, lastResponse).init(autoName);
	}

	init {arg autoName;
		if (autoName) {
			name = "whoami".unixCmdGetStdOut;
			if(name.last == Char.nl, {name = name.drop(-1)});
		};
	}

	printOn {arg stream;
		// post pretty
		stream << this.class.name << "(" <<* [name, id, addr, online, lastResponse] << ")"
	}

}

NMLAddrBook {

	// refactor of NMLAddrBook to make ID the primary key

	var <dict, <me; // remove getter from dict

	*new { ^super.new.init }

	init { dict = IdentityDictionary.new; }

	sendId {|id ...msg| dict[id].addr.sendMsg(*msg) }

	sendName {|name ...msg| this.atName(name).addr.sendMsg(*msg) }

	sendAll {|...msg| dict.do({|peer| peer.addr.sendMsg(*msg); }); }

	sendAllBundle {|time ...msg| dict.do({|peer| peer.addr.sendBundle(time, *msg); }); }

	sendExcludingId {|id ...msg| dict.reject({|peer, peerId| peerId == id }).do({|peer| peer.addr.sendMsg(*msg); });}

	sendExcludingName {|name ...msg| dict.reject({|peer, peerName| peerName == name }).do({|peer| peer.addr.sendMsg(*msg); });}

	add {|peer| dict[peer.id] = peer; peer.addDependant(this); this.changed(\add, peer) }

	// removed addMe method for now

	at {|id| ^dict[id] } // if id doesn't exist, warn rather than throwing error

	atId {|id| ^this.at(id) }

	atName {|name| ^dict.values.detect({|peer| peer.name == name }) } // if name doesn't exist, warn rather than throwing error

	remove {|peer| dict[peer.id] = nil; peer.removeDependant(this); this.changed(\remove, peer) }

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

}

NMLAddrBookGUI {

	// a gui showing a list of currently online peers
	// TODO: identify self in address book with different colour

	var addrBook, <mainView, peerRows, rowWidth;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		this.initAddrBookDep;
		this.makeMainView;
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
		mainView.layout_(VLayout(*[this.makeTitleRow, peerRows])
			.margins_(0)
			.spacing_(0)
		);
		^mainView
	}

	makeTitleRow {
		^View().layout_(HLayout(*[
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("Name:"),
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("ID:"),
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("IP:"),
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("Port:")
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	makePeerRow {arg peer;
		^View().layout_(HLayout(*[
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.name),
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.id),
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.addr.ip),
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.addr.port)
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	initAddrBookDep {
		var prevIds; // used to store last state of ids, as can't look up index once id has been removed
		addrBook.addDependant({arg addrBook, what, who;
			case
			{ what == \add } {
				var row, index;
				defer { row = this.makePeerRow(who) };
				index = addrBook.ids.asArray.sort.indexOf(who.id);
				defer { peerRows.layout.insert(row, index); };
			}
			{ what == \remove } {
				var index;
				index = prevIds.asArray.sort.indexOf(who.id);
				defer { peerRows.children.removeAt(index).destroy; };
			};
			prevIds = addrBook.ids.copy;
		})
	}

}

// DONE: ability to modify period

CentralisedServer {

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
		}, '/client-getId');
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
		}, '/client-announceClient', recvPort: serverPort) // only accept messages targeted at server's port
	}

	initAndStartEventLoop {
		eventLoop = Routine({
			inf.do{
				this.announceServer;
				this.checkIfClientsOnline;
				period.wait;
			}
		}).play(SystemClock)
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
					inform("% went offline".format(client));
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

	// reinstate autonaming:

	// three stage process:
	// 1. find server
	// 2. get id from server
	// 3. start announcing

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

	me {
		^addrBook.atId(id);
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
		}, '/server-setId').oneShot;
		id = rand2(-2147483647, 2147483647);
		NetAddr.localAddr.sendMsg('/client-getId', id);
	}

	initEventLoop {
		eventLoop = Routine({
			inf.do{
				this.announceMe;
				this.checkIfServerOnline;
				period.wait;
			}
		})
	}

	announceMe {
		serverPeer.addr.sendMsg('/client-announceClient', id, name, port); // could we send on the port we're using?
	}

	initAnnounceServerResponder {
		announceServerResponder = OSCFunc({arg msg, time, addr;
			var serverPort;
			# serverPort = msg.drop(1);
			if (serverPeer.isNil) {
				// new server comes online for the first time:
				inform("new server discovered at: %".format(addr));
				serverPeer = NMLPeer(
					addr: NetAddr(addr.ip, serverPort),
					online: true,
					lastResponse: time
				);
				//
				this.getId;
			}
			{
				if (serverPeer.online.not) { // could check if address is name here
					// server came back online (from being offline):
					inform("server came back onine");
					serverPeer.online = true;
					serverPeer.lastResponse = time;
				} {
					// server already online (just update last response time)
					//inform("server ping received, updating last response time");
					serverPeer.lastResponse = time;
				};
			};
		}, '/server-announceServer') // receiving broadcasts, no need to fix responder
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
		}, '/server-announceClient', recvPort: port) // fix to the client's unique port
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
		if (name.isNil) {
			if (addrBook.names.includes(proposedName).not) { // if proposed name not in address book
				name = proposedName;
				this.announceMe; // immediately update, don't wait for eventLoop
			} {
				warn("name % in use".format(proposedName));
			}
		} {
			warn("you are already registered as %, please deregister first".format(name))
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

}

NMLPeer {

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

// rethinking NMLAddrBook to make ID the primary key

NMLAddrBook {

	// primary key is ID

	var dict, <me;

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

	at {|id| ^dict[id] }

	atId {|id| ^this.at(id) }

	atName {|name| ^dict.values.detect({|peer| peer.name == name }) }

	remove {|peer| dict[peer.id] = nil; peer.removeDependant(this); this.changed(\remove, peer) }

	removeAt {|id| this.remove(dict[id]) }

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
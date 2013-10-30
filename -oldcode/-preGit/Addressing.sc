CentralisedServer : AbstractNode {

	// a centralised server which handles registrations
	// IS a concrete implentation of an abstract NMLServer

	// Q: once a peer is added or goes online, should an 'reinforcing' announcement be sent straight away?
	// Q: should the registrar check to see if existing registrars exist?

	var broadcastAddr;
	var port;

	*new {
		^super.new.init;
	}

	init {
		super.init(
			{this.announceAllPeers},
			{this.announceRegistrar},
			{this.checkClientsOnline}
		);
		this.initAndStartEventLoop;
		//
		broadcastAddr = this.initBroadcastAddr;
		port = 60000;
		thisProcess.openUDPPort(port);
		NMLServer(addrBook);
	}
	//
	initBroadcastAddr {
		NetAddr.broadcastFlag = true;
		^NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	announceAllPeers {
		// broadcast details of all peers contained in the Registrar's address book
		addrBook.peers.do{arg peer; this.announcePeer(peer, broadcastAddr)};
	}

	announceRegistrar {
		// broadcast a registrar announcement
		var msgToSend;
		msgToSend = ['/announceRegistrar', port];
		inform("% sending msg: %".format(this.class, msgToSend));
		broadcastAddr.sendMsg(*msgToSend);
	}

	checkClientsOnline {
		// where should this go?
		// Q: do registrars, registrants and decentralised nodes all need to check if players are online?
		// A: no, only registrars and decentralised nodes, so put this functionality elsewhere
		// but registrant needs to check if registrar is online
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.onlineStatus == true) {
				if((now - peer.lastResponse) > (period * 2), {
					inform("% stopped receiving responses, taking [%] offline".format(this.class, peer.name));
					addrBook[peer.name].onlineStatus = false;
					addrBook[peer.name].id = nil;
				});
			};
		});
	}

}

NMLServer {

	// listens for broadcasts from existing clients
	// and add them to the address book
	// Registrar and DecentralisedNode's need

	var addrBook;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		this.listenForAnnouncementsFromPeers;
	}

	listenForAnnouncementsFromPeers {
		// listen for existing peers on the network and add them to the address book
		OSCFunc({arg msg, time;
			var name, id, ip, port, peer;
			inform("% receiving %".format(this.class, msg));
			# name, id, ip, port = msg.drop(1);
			peer = NMLPeer.new(
				name: name,
				id: id,
				addr: NetAddr(ip.asString, port),
				onlineStatus: true,
				lastResponse: time
			);
			addrBook.add(peer);
		}, '/announcePeer');
	}

}

CentralisedClient : AbstractNode {

	// a client which:
	// - announces itself as a peer to a registrar
	// - listens to announcements (about other peers, including self) from a registrar
	// IS a concrete implementation of an abstract NMLClient

	// - addr will be the addr of the registrar
	// - announcePeer will be called once for each peer in the address book

	// TO DO: not enough to poll registrar once, need to check that it stays online (pinging)

	var client;
	var registrarAddr, registrarLastResponse;

	*new {
		^super.new.init;
	}

	init {
		funcArray = [
			{this.announceMe},
			{this.checkRegistrarOnline}
		];
		super.init;
		this.listenForAnnouncementsFromRegistrar;
	}

	listenForAnnouncementsFromRegistrar {
		// receive '/announceRegistrar' [port] broadcasts coming from the registrar
		// set registrar address based on the receiving address + port
		OSCFunc({arg msg, time, addr;
			var registrarPort = msg.drop(1);
			if (registrarAddr.isNil) {
				inform("% registrar found at %".format(this.class, addr));
				this.makeClient;
			};
			registrarAddr = NetAddr(addr.ip, registrarPort);
			registrarLastResponse = time;
		}, '/announceRegistrar');
	}

	makeClient {
		client = NMLClient(addrBook, startingPort: 60001); // leaves 60000 free for registrar

	}

	register {arg proposedName;
		client.register(proposedName);
	}

	deregister {
		client.deregister;
	}

	announceMe {
		this.announcePeer(client.me, registrarAddr);
	}

	checkRegistrarOnline {
		var now;
		now = Main.elapsedTime;
		if (registrarAddr.notNil) {
			if((now - registrarLastResponse) > (period * 2), {
				inform("% stopped receiving responses from registrar, clearing registrar address".format(this.class));
				registrarAddr = nil;
			});
		};
	}

}

NMLClient {

	// things that both a Registrant and DecentralisedNode need, i.e.
	// - initialising a peer which represents the client
	// - stores
	// - announces

	var addrBook;
	var startingPort;
	var <me;

	*new {arg addrBook, startingPort;
		^super.newCopyArgs(addrBook, startingPort).preRegister;
	}

	preRegister {
		// - initialise my own peer (with no name)
		// - start the event loop
		// Q: should registration occur on startup even without a name, then name given later
		Routine({
			1.do{
				inform("waiting for incoming registrations");
				//(period * 2).wait;
				this.initMe;
				this.initAndStartEventLoop;
			};
		}).play
	}

	initMe {arg proposedName;
		// initialise my own peer, using the proposed name and other details (id, ip, port, onlineStatus):
		// - determine the next available ID
		// - determine the port (based on the ID)
		// - open the port
		// what address should be used here (local?)
		var myID, myPort;
		myID = this.getNextID;
		myPort = myID + startingPort;
		me = NMLPeer(
			name: nil,
			id: myID,
			addr: NetAddr("127.0.0.1", myPort), // is "127.0.0.1" right thing to put here?
			onlineStatus: false,
			lastResponse: nil
		);
		thisProcess.openUDPPort(me.addr.port);
	}

	getNextID {
		// determine next available ID
		var existingIDs, nextID;
		existingIDs = addrBook.peers.collect{arg peer; peer.id};
		if (existingIDs.isEmpty)
		{ ^0 }
		{ ^existingIDs.maxItem + 1 };
	}

	register {arg proposedName;
		// simply a process of allocating a name to the peer
		// check if propsed name is in use, if not
		if (addrBook.names.includes(proposedName).not) {
			me.name = nil;
			me.onlineStatus = true;
		};
		warn("name in use");
	}

	deregister {
		// set my own peers status to offline (broadcast will continue)
		me.onlineStatus = false; // deregistration doesn't stop announcing, just announces as offline
	}

}

AbstractNode {

	// implements an address book and event loop, that registrars, registrants and decentralised nodes all use
	// is an abstract class, i.e. not meant to be used directly

	var <addrBook;
	var period;
	var funcArray;

	init {arg ... args;
		funcArray = args;
		addrBook = AddrBook();
		period = 1;
	}

	initAndStartEventLoop {
		// TO DO: needs to survive cmd period
		// initialise a routine which will repeat at a given period
		// inside the loop, specific functionality is called (funcArray)
		// set the event loop playing
		var eventLoop;
		eventLoop = Routine({
			inf.do{
				funcArray.do{arg func; func.value};
				period.wait;
			};
		});
		eventLoop.play(SystemClock);
	}

	announcePeer {arg peer, addr;
		// announce a peer:
		// for centralised registrants
		// - addr will be the addr of the registrar
		// - announcePeer will be called once for each peer in the address book
		// for decentralised nodes
		// - addr will be a broadcast addr
		// - announcePeer will be called once only for the
		// for centralised registrants addr will be the addr of the registrar
		// for decentralised nodes addr will be a broadcast addr
		var msgToSend;
		msgToSend = ['/announcePeer', peer.name, peer.id, peer.addr.ip, peer.addr.port, peer.onlineStatus];
		inform("% sending: %".format(this.class, msgToSend));
		addr.sendMsg(*msgToSend);
	}

}

NMLPeer {

	var <name, <>id, <addr, <>onlineStatus, <>lastResponse;

	*new {arg name, id, addr, onlineStatus, lastResponse;
		^super.newCopyArgs(name, id, addr, onlineStatus, lastResponse);
	}

	printOn {arg stream;
		// post pretty
		stream << this.class.name << "(" <<* [name, id, addr, onlineStatus, lastResponse] << ")"
	}

}


/*		// wait
if (registrarAddr.notNil) {

}
{
"registrar not yet found, try again later...".postln;
}*/
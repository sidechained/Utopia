// NOTE: it might still make sense to split AbstractNode into Listener and CommonRegistratant, with duplicate functions contained in another superclass AbstractNode

// Q: should registration occur on startup even without a name, then name given later


// Centralised registration - broadcast approach

// 1:
// Registrant asks Registar if name is in use
// send: /checkRegister [bob]
// receive: /checkRegister-reply [bob] [free/inUse]
// if free, /register, if inUse, give error

// PROBLEM is we have to be able to reply to a specific registrant
// (one solution would be to send on a random port)

// 2:
// Registrant tries to register with given name
// send: /register [name]
// if free,
// if inUse, send /error

// either way we still have be able to send replies to the registrant before they are registered

// if the Registrar broadcasts the address book, we can collect these up and determine our port before sending

// For decentralised registration, new peers gather up the broadcasts from others before registering
// - broadcasts are received individually from existing peers

// For centralised registration, new peers gather up the broadcasts from the registrar
// - broadcasts are received from the registrar

// the process would be similar, on registration:
// 1. wait 2 * period to receive existing registrations
// 2. based on existing registrations
// - check if name in use, if not
// -- choose an ID
// -- repeatedly send a registration message \register [name] [ID]

// ANNOUNCEMENT

// once it is determined that the registration is valid:
// - the Registant should constantly send registration messages to the Registrar to keep the player alive
// - the Registrar should pass these on to all Registrants

// key point is that the Registrant does the management, ie.
// - sending the announcements and
// - checking if players are still online
// deregistration will stop this signal and the player will go offline

CentralisedRegistrar : Listener {

	// once a peer is added or goes online, should an 'reinforcing' announcement be sent straight away?
	// should the registrar check to see if existing registrars exist?
	var registrarPort;

	*new {
		^super.new.init;
	}

	init {
		super.init;
		registrarPort = 60000;
		thisProcess.openUDPPort(registrarPort);
		funcArray = [{this.announceAllPeers}, {this.announceRegistrar}, {this.checkOnline}]; // this will always be called
		this.startEventLoop;
	}

	announceAllPeers {
		// iterate through address book, for each item broadcast /update [name] [id]
		// optimisation: is it better to send these as one message rather than many separate messages? yes
		// this state is the most current state of the address book and should replace the previous state on Registrants
		addrBook.peers.do{arg peer; this.announcePeer(peer, broadcastAddr)};
	}

	announceRegistrar {
		var msgToSend;
		msgToSend = ['/announceRegistrar', registrarPort];
		//inform("% sending msg: %".format(this.class, msgToSend));
		broadcastAddr.sendMsg(*msgToSend);
	}

}

Listener : AbstractNode {

	*new {
		^super.new.init;
	}

	init {
		super.init;
		this.listenForPeerAnnouncements;
	}

	listenForPeerAnnouncements {
		OSCFunc({arg msg, time;
			var name, id, ip, port, peer;
			inform("% receiving %".format(this.class, msg));
			# name, id, ip, port = msg.drop(1);
			peer = GPeer.new(
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

DecentralisedNode : AbstractNode {

	// should consist of a registrar and registrant together

	var listener, sender;

	*new {
		^super.new.init;
	}

	init {
		clientStartingPort = 60000;
		funcArray = [{this.announcePeer(mePeer, broadcastAddr)},{this.checkOnline}];
		listener = Listener();
		sender = Sender();
	}

	register {
		sender.register
	}

}

CentralisedRegistrant : AbstractNode {

	// TO DO: not enough to poll registrar once, need to check that it stays online (pinging)

	var registrarAddr;
	var <mePeer;

	*new {
		^super.new.init;
	}

	init {
		super.init;
		clientStartingPort = 60001; // baseport must = Registrant base port + 1;
		funcArray = [{this.announcePeer(mePeer, registrarAddr)}, {this.checkOnline}];
		this.listenForRegistrarAnnouncement;
		this.listenForPeerAnnouncements;
	}

	listenForRegistrarAnnouncement {
		// responds to announce broadcasts coming from the registrar
		OSCFunc({arg msg, time, addr;
			var registrarPort = msg.drop(1);
			if (registrarAddr.isNil) {
				inform("% setting registrar address to %".format(this.class, addr));
			};
			registrarAddr = NetAddr(addr.ip, registrarPort);
		}, '/announceRegistrar');
	}

	register {arg proposedName;
		if (registrarAddr.notNil) {
			super.register(proposedName);
		}
		{
			"registrar not yet found, try again later../".postln;
		}
	}

}


Sender : AbstractNode {

	*new {
		^super.new.init;
	}

	initMePeer {arg proposedName;
		var myID, myPort;
		myID = this.getNextID;
		myPort = myID + clientStartingPort;
		mePeer = GPeer(
			name: proposedName,
			id: myID,
			addr: NetAddr("127.0.0.1", myPort), // is "127.0.0.1" right thing to put here?
			onlineStatus: true,
			lastResponse: nil
		);
		thisProcess.openUDPPort(mePeer.addr.port);
	}

	register {arg proposedName;
		if (addrBook.names.includes(proposedName).not) {
			// Q: should registration occur on startup even without a name, then name given later
			this.initMePeer(proposedName);
			this.startEventLoop;
		}
		{
			warn("name in use");
		};
	}

	deregister {
		mePeer.onlineStatus = false; // deregistration doesn't stop announcing, just announces as offline
	}

	announcePeer {arg peer, addr;
		// for centralised registrants addr will be the addr of the registrar
		// for decentralised nodes addr will be a broadcast addr
		var msgToSend;
		msgToSend = ['/announcePeer', peer.name, peer.id, peer.addr.ip, peer.addr.port, peer.onlineStatus];
		inform("% sending: %".format(this.class, msgToSend));
		addr.sendMsg(*msgToSend);
	}

	getNextID {
		var existingIDs, nextID;
		existingIDs = addrBook.peers.collect{arg peer; peer.id};
		if (existingIDs.isEmpty)
		{ ^0 }
		{ ^existingIDs.maxItem + 1 };
	}

}

AbstractNode {

	// stuff that both listeners and senders do

	var <addrBook, broadcastAddr, period;
	var funcArray;
	var clientStartingPort;
	var <mePeer;
	var eventLoop;

	init {
		addrBook = AddrBook.new;
		period = 1;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		this.initEventLoop;
	}

	initEventLoop {
		eventLoop = Routine({
			inf.do{
				funcArray.do{arg func; func.value};
				period.wait;
			};
		});
	}

	startEventLoop {
		// TO DO: needs to survive cmd period
		eventLoop.play(SystemClock);
	}

	// once started this never stops unless the object is destroyed
	checkOnline {
		var now;
		//[this, \checking].postln;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.onlineStatus == true) {
				if((now - peer.lastResponse) > (period * 2), {
					inform("% % stopped receiving responses, taking [%] offline".format(this.class, mePeer !? {mePeer.name}, peer.name));
					addrBook[peer.name].onlineStatus = false;
					addrBook[peer.name].id = nil;
				});
			};
		});
	}

}

GPeer {

	var <name, <>id, <addr, <>onlineStatus, <>lastResponse;

	*new {arg name, id, addr, onlineStatus, lastResponse;
		^super.newCopyArgs(name, id, addr, onlineStatus, lastResponse);
	}

	printOn {arg stream;
		// post pretty
		stream << this.class.name << "(" <<* [name, id, addr, onlineStatus, lastResponse] << ")"
	}

}
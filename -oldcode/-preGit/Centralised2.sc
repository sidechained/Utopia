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

GRegistrar {

	// once a peer is added or goes online, should an 'reinforcing' announcement be sent straight away?
	// should the registrar check to see if existing registrars exist?

	var registrarPort, <addrBook, broadcastAddr, period;

	*new {
		^super.new.init;
	}

	init {
		registrarPort = 60000;
		thisProcess.openUDPPort(registrarPort);
		addrBook = AddrBook.new;
		period = 1;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		this.initRegisterResponder;
		this.startEventLoop;
	}

	startEventLoop {
		Routine({
			inf.do{
				this.updatePeers;
				this.announceRegistrar;
				this.checkOnline;
				period.wait;
			};
		}).play;
	}

	// sending:

	updatePeers {
		// iterate through address book, for each item broadcast /update [name] [id]
		// optimisation: is it better to send these as one message rather than many separate messages? yes
		// this state is the most current state of the address book and should replace the previous state on Registrants
		addrBook.peers.do{arg peer;
			var msgToSend;
			msgToSend = ['/announcePeer', peer.name, peer.id, peer.addr.ip, peer.addr.port, peer.onlineStatus];
			//inform("% sending: %".format(this.class, msgToSend));
			broadcastAddr.sendMsg(*msgToSend);
		};
	}

	announceRegistrar {
		var msgToSend;
		msgToSend = '/announceRegistrar';
		//inform("% sending msg: %".format(this.class, msgToSend));
		broadcastAddr.sendMsg(msgToSend);
	}

	// receiving methods:

	initRegisterResponder {
		OSCFunc({arg msg, time, addr;
			var name, id, port;
			var peer;
			//inform("% receiving: %".format(this.class, msg));
			# name, id, port = msg.drop(1);
			peer = GPeer.new(name, id, NetAddr(addr.ip, port), true, time);
			addrBook.add(peer);
		}, '\register')
	}

	// others:

	checkOnline {
		// everybody still there?
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.onlineStatus == true) {
				if((now - peer.lastResponse) > (period * 2), {
					inform("% taking [%] offline".format(this.class, peer.name));
					addrBook[peer.name].onlineStatus = false;
				});
			};
		});
	}

}

GRegistrant {

	var registrarAddr, registrarPort;
	var clientStartingPort, <addrBook, broadcastAddr, period;
	var <myName, <myID, <myPort;
	var eventLoop;

	*new {
		^super.new.init;
	}

	init {
		registrarPort = 60000;
		clientStartingPort = 60001; // baseport must = Registrant base port + 1;
		addrBook = AddrBook.new;
		period = 1;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		this.initAnnouncePeerResponder;
		this.initAnnounceRegistrarResponder;
	}

	initAnnounceRegistrarResponder {
		// responds to announce broadcasts coming from the registrar
		OSCFunc({arg msg, time, addr;
			if (registrarAddr.isNil) {
				inform("% setting registrar address to %".format(this.class, addr));
			};
			registrarAddr = NetAddr(addr.ip, registrarPort);
		}, '/announceRegistrar');
	}

	initAnnouncePeerResponder {
		OSCFunc({arg msg;
			var name, id, ip, online, port, peer;
			//inform("% receiving %".format(this.class, msg));
			# name, id, ip, port, online = msg.drop(1);
			peer = GPeer.new(name, id, NetAddr(ip.asString, port), online.asBoolean); // lastResponse not relevant here
			addrBook.add(peer);
		}, '/announcePeer');
	}

	register {arg proposedName;
		if (registrarAddr.notNil) {
			if (addrBook.names.includes(proposedName).not) {
				myName = proposedName;
				myID = this.getNextID;
				myPort = myID + clientStartingPort;
				thisProcess.openUDPPort(myPort);
				this.startEventLoop;
			}
			{
				warn("name in use");
			};
		}
		{
			"registrar not yet found, try again later".postln;
		}
	}

	getNextID {
		var existingIDs, nextID;
		existingIDs = addrBook.peers.collect{arg peer; peer.id};
		if (existingIDs.isEmpty)
		{ ^0 }
		{ ^existingIDs.maxItem + 1 };
	}

	startEventLoop {
		eventLoop = Routine({
			inf.do{
				this.sendRegister;
				period.wait;
			};
		}).play;
	}

	sendRegister {
		registrarAddr.sendMsg('\register', myName, myID, myPort);
	}

	deregister {
		eventLoop.stop;
		myName = nil;
		myID = nil;
	}

}

GPeer {

	var <name, <id, <addr, <>onlineStatus, <>lastResponse;

	*new {arg name, id, addr, onlineStatus, lastResponse;
		^super.newCopyArgs(name, id, addr, onlineStatus, lastResponse);
	}

	printOn {arg stream;
		// post pretty
		stream << this.class.name << "(" <<* [name, id, addr, onlineStatus, lastResponse] << ")"
	}

}
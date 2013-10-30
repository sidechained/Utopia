// DON'T FORGET TO OPEN UDP PORTS!
// TODO: eventloop must survive cmd period

CentralisedClient {

	//

	var <addrBook;
	var me;
	var serverAddr, serverLastResponse;
	var eventLoop, period;

	*new {
		super.new.init;
	}

	init {
		// make new address book:
		addrBook = NMLAddrBook.new;
		this.initEventLoop; // init but don't start
		this.listenForServerAnnouncementsFromServer;
		this.listenForClientAnnouncementsFromServer; // should be called in preregister?
	}

	initEventLoop {
		period = 1;
		eventLoop = Routine({
			inf.do{
				this.announceMe;
				this.checkIfServerStillOnline;
				period.wait;
			}
		})
	}

	listenForServerAnnouncementsFromServer { // in form: \announceServer [port]
		OSCFunc({arg msg, time, addr;
			// when \announceServer message received:
			var port;
			# port = msg.drop(1);
			// - set serverAddr:
			if (serverAddr.isNil) {
				// add new:
				serverAddr = NetAddr(addr.ip, port);
				inform("Found server at %".format(serverAddr));
				// - set server last response:
				serverLastResponse = time;
				this.preRegister;
			} {
				// update:
				// - set server last response:
				serverLastResponse = time;
			};
		}, '/announceServer')
	}

	preRegister {
		// automatically register without a name
		// - start the event loop:
		eventLoop.play(SystemClock);
	}

	listenForClientAnnouncementsFromServer { // \announcePeer [name, id, ip, port, online, lastResponse]
		OSCFunc({arg msg, time, addr;
			// when \announceClient message received:
			var name, id, ip, port, online, lastResponse;
			var peerToAdd;
			// - make peer from \announceClient message:
			# name, id, ip, port, online, lastResponse = msg.drop(1);
			peerToAdd = NMLPeer(name, id, NetAddr(ip, port), online, lastResponse);
			// - add peer to address book:
			addrBook.add(peerToAdd);
		}, '/announceClient')
	}

	announceMe {
		// send \announceClient msg to server:
		var msgToSend;
		msgToSend = ['/announceClient'];
		inform("sending: % to %".format(msgToSend, serverAddr));
		serverAddr.sendMsg(*msgToSend);
	}

	register {arg proposedName;
		// set me.name to proposedName
		me.name = proposedName;
		// set online to true
		me.online = true;
	}

	deregister {
		// set me.name to nil
		me.name = nil;
		// set online to false
		me.online = false;
	}

	checkIfServerStillOnline {
		// only perform check if server exists:
		if (serverAddr.notNil) {
			var now;
			now = Main.elapsedTime;
			// if serverPeer hasn't been seen in a while:
			if((now - serverLastResponse) > (period * 2), {
				inform("% stopped receiving responses from registrar, clearing server address".format(this.class));
				// - clear serverAddr:
				serverAddr = nil;
				// - stop eventLoop:
				eventLoop.stop;
			});
		};
	}

}

CentralisedServer {

	var <addrBook;
	var serverPort;
	var broadcastAddr;
	var clientStartingPort;
	var eventLoop, period;

	*new {
		^super.new.init;
	}

	init {
		// make new address book:
		addrBook = NMLAddrBook.new;
		this.initBroadcastAddr;
		// set server port:
		serverPort = 60000;
		thisProcess.openUDPPort(serverPort);
		// set client starting port:
		clientStartingPort = 60001;
		// listen for client registrations
		this.listenForAnnouncementsFromClients;
		// init and start event loop
		this.initEventLoop;
		eventLoop.play(SystemClock);
	}

	initBroadcastAddr {
		// set broadcast address:
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initEventLoop {
		period = 1;
		eventLoop = Routine({
			inf.do {
				this.announceServer;
				this.announceClients;
				this.checkIfClientsStillOnline;
				period.wait;
			}
		})
	}

	announceServer {
		// broadcast /announceServer [serverPort]:
		var msgToSend;
		msgToSend = ['/announceServer', serverPort];
		//inform("%".format(msgToSend));
		broadcastAddr.sendMsg(*msgToSend);
	}

	announceClients {
		addrBook.peers.do {arg peer;
			broadcastAddr.sendMsg('/announceClient',
				peer.name,
				peer.id,
				peer.addr.ip,
				peer.addr.port,
				peer.online,
				peer.lastResponse
			)
		}
	}

	checkIfClientsStillOnline {
		// remove peers who haven't been seen in 2 * period seconds:
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.online == true) {
				if((now - peer.lastResponse) > (period * 2), {
					inform("% stopped receiving responses, taking [%] offline".format(this.class, peer.name));
					addrBook[peer.name].online = false;
				});
			};
		});
	}

	listenForAnnouncementsFromClients {
		// receives: \announceClient (addr.ip and lastResponseTime taken from OSC message args)
		OSCFunc({arg msg, time, addr;
			var id, port, peerToAdd;
			inform("receiving: %".format(msg));
			// if ID doesn't exist:
			// - allocate next ID:
			id = this.getNextID;
			port = id + clientStartingPort;
			// - make peer:
			peerToAdd = NMLPeer(
				name: nil,
				id: id,
				addr: NetAddr(addr.ip, port),
				online: true,
				lastResponse: time
			);
			peerToAdd.postln;
			// - add peer to address book (name, id, addr, port, online, lastResponseTime):
			// (cannot put peer with a name of nil into address book)
			addrBook.add(peerToAdd);
		}, '/announceClient')
	}

	getNextID {
		// determine next available ID
		var existingIDs, nextID;
		existingIDs = addrBook.peers.collect{arg peer; peer.id};
		if (existingIDs.isEmpty)
		{ ^0 }
		{ ^existingIDs.maxItem + 1 };
	}

}
DecentralisedNode {

	var <addrBook;
	var eventLoop, period;
	var client, server;

	*new {
		^super.new.init;
	}

	init {
		period = 1;
		addrBook = AddrBook.new;
		server = DecentralisedServer(addrBook, period);
		fork {
			// wait for existing peers to be received:
			(period * 2).wait;
			client = DecentralisedClient(addrBook);
			this.initEventLoop;
			eventLoop.play(SystemClock);
		};
	}

	initEventLoop {
		eventLoop = Routine({
			inf.do{
				client.announceMe;
				server.checkIfClientsStillOnline;
			}
		})
	}

	register {arg proposedName;
		client.register(proposedName);
	}

	deregister {
		client.deregister;
	}

}

DecentralisedClient {

	var <addrBook;
	var me;
	var clientStartingPort;
	var broadcastAddr;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		this.initMe;
		this.initBroadcastAddr;
	}

	initBroadcastAddr {
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	announceMe {
		broadcastAddr.sendMsg('/announceClient', me.name, me.id, me.addr.port);
	}

	register {arg proposedName;
		if (addrBook.names.contains(proposedName).not) {
			me.name = proposedName;
			me.online = true;
		}
		{
			inform("name in use");
		}
	}

	deregister {
		if (me.name.notNil) {
			me.name = nil;
			me.online = false;
		}
		{
			inform("not registered");
		}
	}

	initMe {
		// pre-registration:
		var id, port;
		id = this.getNextID;
		port = id + clientStartingPort;
		me = NMLPeer(
			name: nil,
			id: id,
			addr: NetAddr(nil, port), // addr is empty until lan-side address received
			online: false // clients only really go 'online' when a name is set
		)
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

DecentralisedServer {

	var <addrBook;
	var period;

	*new {arg addrBook, period;
		^super.newCopyArgs(addrBook, period).init;
	}

	init {
		this.listenForAnnouncementsFromClients;
	}

	listenForAnnouncementsFromClients {
		// listen for /announceClient [name, id, port] broadcasts
		OSCFunc({arg msg, time, addr;
			var name, id, port;
			var peerToAdd;
			# name, id, port = msg.drop(1);
			peerToAdd = NMLPeer(
				name: name,
				id: id,
				addr: NetAddr(addr.ip, port),
				online: true,
				lastResponse: time
			);
			addrBook.add(peerToAdd);
		}, '\announceClient')
	}

	checkIfClientsStillOnline {
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
}
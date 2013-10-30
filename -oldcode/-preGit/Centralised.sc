// centralised

TRegistrar {

	var basePort, addrBook, broadcastAddr, period;

	*new {
		^super.new.init;
	}

	init {
		basePort = 60000;
		addrBook = ();
		period = 1;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		this.initRegisterResponder;
		this.startEventLoop;
	}

	initRegisterResponder {
		// /register [name]
		// -- checks to see if name exists in address book, if not
		// -- get next ID
		// -- add peer to address book with received name and addr
		// -- (addr will be ip + port based on next id)

		OSCFunc({arg msg, time, addr;
			var name;
			inform("% receiving %".format(this.class, msg));
			# name = msg.drop(1);
			if (addrBook.keys.includes(name).not) { // if name not in address book
				var nextID, collectedIDs;
				addrBook.put(name, (
					id: nextID,
					addr: NetAddr(addr.ip, nextID + basePort),
					lastResponse: time,
					online: true
				))
			} {
				[addrBook[name].addr, addr].postln;
				if (addrBook[name].addr == addr) { // if existing addr is same as sender address
					\updating.postln;
					addrBook[name].online = true;
				}
				{
					warn("% name % in use by another peer".format(this.class, name));
				};
			};
		}, '\register');
	}

	// updatePeers and announceRegistar could be in one loop:

	startEventLoop {
		Routine({
			inf.do{
				this.updatePeers;
				this.announceRegistrar;
				//this.checkOnline;
				period.wait;
			};
		}).play;
	}

	updatePeers {
		// iterate through address book, for each item broadcast /update [name] [id]
		addrBook.keysValuesDo{arg k, v;
			var msgToSend;
			msgToSend = ['/update', k, v.id];
			//inform("% sending msg: %".format(this.class, msgToSend));
			broadcastAddr.sendMsg(*msgToSend);
		};
	}

	announceRegistrar {
		var msgToSend;
		msgToSend = '/announce';
		//inform("% sending msg: %".format(this.class, msgToSend));
		broadcastAddr.sendMsg(msgToSend);
	}

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		addrBook.keysValuesDo({arg k, v;
			if (addrBook[k].online == true) {
				if((now - v.lastResponse) > (period * 2), {
					inform("% taking [%] offline".format(this.class, k));
					addrBook[k].online = false;
				});
			};
		});
	}

}

TRegistrant {

	var registrarAddr;
	var basePort, <addrBook, broadcastAddr, period;

	*new {
		^super.new.init;
	}

	init {
		basePort = 60000;
		addrBook = ();
		period = 1;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		this.initUpdateResponder;
		this.initAnnounceResponder;
	}

	// -responds to
	// /update [name id]
	// -- add name and ID to address book

	initAnnounceResponder {
		// responds to announce messages coming from the registrar
		OSCFunc({arg msg, time, addr;
			if (registrarAddr.isNil) {
				inform("% setting registrar address to %".format(this.class, addr));
			};
			registrarAddr = addr;
		}, '/announce');
	}

	initUpdateResponder {
		OSCFunc({arg msg;
			var name, id;
			//inform("% receiving %".format(this.class, msg));
			# name, id = msg.drop(1);
			addrBook.put(name, (
				id: id,
				addr: NetAddr("127.0.0.1", id + basePort),
				// lastResponse not relevant here
				online: true
			));
		}, '/update');
	}

	// -sends

	register {arg proposedName;
		// initialise add responder

		(period*2).wait;

		if (addrBook.keys.includes(proposedName)) { })

/*		// /register [name]
		if (registrarAddr.notNil) {
			this.startRegister(proposedName);
		}
		{
			"registrar not yet found, try again later".postln;
		}*/
	}

	startRegister {arg proposedName;
		Routine({
			inf.do{
				var msgToSend;
				msgToSend = ['/register', proposedName];
				inform("% sending: %".format(this.class, msgToSend));
				registrarAddr.sendMsg(*msgToSend);
				period.wait;
			};
		}).play;
	}

}

// \adding.postln;
// if (addrBook == () ) { nextID = 0 } { nextID = addrBook.collect{arg peer; peer.id}.maxItem + 1 };
// nextID.postln;
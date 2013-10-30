Decentral {

	// this works
	// add ability to join (i.e. give a name)
	// Peer might be best rewritten
	// why did we need to filter by port again?
	// deregistration

	classvar period = 0.5, keepAlivePeriod;
	var <addrBook, <mePeer;
	var announceSignal, checkOnlineSignal;

	*initClass {
		keepAlivePeriod = period * 2;
	}

	*new {
		^super.new.init;
	}

	init {
		fork {
			addrBook = AddrBook.new;
			this.initReceiveAnnounce;
			this.startCheckOnline;
			inform("n: waiting for incoming registrations");
			keepAlivePeriod.wait;
		};
	}

	join {arg name;
		// enforce minimum wait time here
		if (name.notNil, {
			if (addrBook.names.includes(name).not, {
				var name, id;
				id = this.getNextID;
				mePeer = Peered(name, id, NetAddr.localAddr);
				// open port here or inside Peered class?
				thisProcess.openUDPPort(mePeer.addr.port);
				inform("% %: allocated".format(mePeer.name, mePeer.id));
				this.startAnnounce;
			});
		});
	}

	getNextID {
		var nextID;
		nextID = addrBook.peers.collect{arg peer; peer.id }.maxItem;
		if (nextID == nil) { nextID = 0 } { nextID = nextID + 1 };
		^nextID
	}

	leave {
		if (addrBook.names.includes(mePeer.name), {
			announceSignal.stop;
			mePeer.name = nil;
		});
	}

	initReceiveAnnounce {
		inform("n: initialising announcement responder");
		OSCFunc({arg msg, time, incomingAddr;
			var incomingName, incomingID, peerToAdd;
			incomingName = msg[1];
			incomingID = msg[2];
			// should ID or name be used as the primary key?
			// ID is the basis
			// name is something we just tack on
			// also, how to show peers that came back online?
			if (addrBook.at(incomingName).isNil) {
				// if peer doesn't exist, create a new one:
				peerToAdd = Peered(incomingName, incomingID, incomingAddr, time, true);
				addrBook.add(peerToAdd);
				inform("% %: added peer: %".format(mePeer !? {mePeer.name}, mePeer !? {mePeer.id}, peerToAdd));
			} {
				// if peer does exist just update lastHeardFromTime:
				addrBook[incomingName].lastHeardFrom = time;
				addrBook[incomingName].online = true;
			};
		}, '/announce')
	}

	startAnnounce {
		var broadcastAddr;
		NetAddr.broadcastFlag = true;
		//broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		broadcastAddr = NetAddr.localAddr;
		announceSignal = Routine({
			inf.do{
			var msgToSend;
			msgToSend = ['/announce', mePeer.name, mePeer.id];
			//inform("% %: sending %".format(mePeer.name, mePeer.id, msgToSend));
			broadcastAddr.sendMsg(*msgToSend);
			period.wait;
			};
		}).play;
	}

	startCheckOnline {
		SystemClock.sched(0, {
			var now;
			now = Main.elapsedTime;
			//inform("% %: checking online status of peers".format(mePeer.name, mePeer.id));
			addrBook.peers.do{arg peer;
				if((now - peer.lastHeardFrom) > keepAlivePeriod) {
					if ( addrBook[peer.name].online == true ) {
						inform("% %: % went offline".format(mePeer.name, mePeer.id, peer.name));
						addrBook[peer.name].online = false;
					};
				};
			};
			period;
		});
	}

}

Peered {

	classvar basePort = 60000;
	var <name, <id, <addr, <>lastHeardFrom, <online;

	*new {|name, id, addr, lastHeardFrom, online = true|
		addr = NetAddr(addr.ip, id + basePort); // only use ip from address, and allocate port based on ID
		^super.newCopyArgs(name.asSymbol, id, addr, lastHeardFrom, online);
	}

	online_ {|bool| if(bool != online, { online = bool; this.changed(\online) }) }

	== {|other|
		var result;
		result = (name == other.name) && (addr == other.addr) && (online == other.online);
		^result;
	}

	// what is this for?
	hash {
		// include lastHeardFrom in here?
		^this.instVarHash(#[\name, \id, \addr, \online])
	}

	// post pretty
	printOn { |stream|
		// include lastHeardFrom in here?
		stream << this.class.name << "(" <<* [name, id, addr, online] << ")"
	}
}
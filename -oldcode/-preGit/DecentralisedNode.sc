DecentralisedNode { // node, or peer?

	// DONE: me set to lan side me
	// TODO: allow IDs to be reused if peer is offline and name is same
	// Q: should it be possible to change name? not really, you should have to deregister and register again
	// Q: is 'monitoring' possible? yes, it is the default state. it is not possible to stop listening, and still keep registering though (use case?)

	var autoName;
	var <addrBook;
	var broadcastAddr;
	var eventLoop, period;
	var peerStartingPort;
	var announcePeerResponder;
	var <me;

	*new {arg autoName = false;
		^super.newCopyArgs(autoName).init;
	}

	init {
		peerStartingPort = 60000;
		addrBook = NMLAddrBook.new;
		period = 1;
		me = NMLPeer(autoName: autoName); // set blank me, so that name can be set before registering if need be
		this.initBroadcastAddr;
		this.initEventLoop;
		fork{
			var myId;
			this.listenForAnnouncementsFromPeers;
			(period * 2).wait;
			me.id = addrBook.getNextFreeID;
			eventLoop.play;
		};
	}

	initBroadcastAddr {
		// set broadcast address:
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initEventLoop {
		eventLoop = Routine({
			inf.do{
				this.sendAnnouncement;
				this.checkIfPeersStillOnline;
				period.wait;
			};
		});
	}

	sendAnnouncement {
		broadcastAddr.sendMsg('/announcePeer', me.id, me.name);
	}

	checkIfPeersStillOnline {
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.online) {
				if((now - peer.lastResponse) > (period * 2), {
					inform("% stopped receiving responses from [% %], taking them offline".format(this.class, peer.name, peer.id));
					addrBook[peer.id].online = false;
					addrBook[peer.id].name = nil; // reset name so it can be reused - better yet would be to allow names and ids to be resued once the player has gone offline
				});
			};
		});
	}

	listenForAnnouncementsFromPeers {
		announcePeerResponder = OSCFunc({arg msg, time, addr;
			var id, name;
			# id, name = msg.drop(1);
			if (name == 0) { name = nil}; // reconvert;
			if (addrBook.ids.includes(id).not) {
				// add
				var port, newPeer;
				port = id + peerStartingPort;
				newPeer = NMLPeer(
					id: id,
					name: name,
					addr: NetAddr(addr.ip, port),
					online: true,
					lastResponse: time
				);
				addrBook.add(newPeer);
				if (id == me.id) {
					me = newPeer;
					thisProcess.openUDPPort(port);
				};
			}
			{
				// update
				var existingPeer;
				existingPeer = addrBook.at(id);
				existingPeer.name = name;
				// update address here or not?
				existingPeer.online = true;
				existingPeer.lastResponse = time;
				if (id == me.id) {
					me = existingPeer;
				}
			}
		}, '/announcePeer')
	}

	register {arg proposedName;
		if (proposedName != 0) {
			if (addrBook.names.includes(proposedName).not) {
				me.name = proposedName;
			}
			{
				warn("name % already in use".format(me.name));
			}
		}
		{
			warn("cannot use name 0".format(me.name));
		}
	}

	deregister {
		if (addrBook.names.includes(me.name)) {
			me.name = nil;
		} {
			warn("name % not in address book".format(me.name));
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
		me = nil;
	}

}

NMLPeer {

	// need to add back in other stuff from original Utopia Peer Class

	var <>name, <>id, <addr, <>online, <>lastResponse;

	*new {arg name, id, addr, online, lastResponse, autoName = false;
		^super.newCopyArgs(name, id, addr, online, lastResponse).init(autoName);
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



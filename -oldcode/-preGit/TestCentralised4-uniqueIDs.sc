// test classes, local only, no networking, no IDs
// peers should only let through comms that match their own ID

TestRegistrar {

	var <addrBook;
	var period = 1;

	*new {
		^super.new.init;
	}

	init {
		addrBook = AddrBook.new;
		this.initReceivers;
		this.announce;
	}

	announce {
		var broadcastAddr;
		broadcastAddr = NetAddr.localAddr;
		SystemClock.sched(0, {
			broadcastAddr.sendMsg('/announce');
			//this.checkOnline;
			period;
		});
	}

	// OSC API

	// receivers:

	initReceivers {
		this.initCheckIfNameFree;
		this.initReceiveRegister;
		this.initReceiveDeregister;
	}

	initCheckIfNameFree {
		OSCFunc(this.receiveCheckIfNameFree, '/checkIfNameFree')
	}

	receiveCheckIfNameFree  {
		^{arg msg, time, addr;
			var nameToCheck, result;
			nameToCheck = msg[1];
			result = addrBook.names.includes(nameToCheck).not;
			addr.sendMsg('/checkIfNameFree-reply', nameToCheck, result.asInteger);
		}
	}

	initReceiveRegister {
		OSCFunc(this.receiveRegister, '/register')
	}

	receiveRegister {
		^{arg msg, time, addr, recvPort;
			var proposedName;
			proposedName = msg[1];
			this.register(proposedName, addr);
		}
	}

	initReceiveDeregister {
		OSCFunc(this.receiveDeregister, '/deregister')
	}

	receiveDeregister {
		^{arg msg, time, addr, recvPort;
			var proposedName;
			proposedName = msg[1];
			this.deregister(proposedName, addr);
		}
	}

	// senders:

	sendAdd {arg nameOfPeerToSendTo, nameOfPeerToAdd, ipOfPeerToAdd, portOfPeerToAdd;
		addrBook.send(nameOfPeerToSendTo, '/add', nameOfPeerToAdd, ipOfPeerToAdd, portOfPeerToAdd);
	}

	sendRemove {arg nameOfPeerToSendTo, nameOfPeerToRemove;
		addrBook.send(nameOfPeerToSendTo, '/remove', nameOfPeerToRemove)
	}

	// from registrant

	register {arg name, addr;
		var nameNotInUse;
		nameNotInUse = addrBook.names.includes(name).not;
		if (nameNotInUse) {
			this.reallyRegister(name, addr);
		} {
			inform("REGISTRAR: cannot register, name already in use")
			//registrant.registrationFailedNameInUse;
			// what should we do here?
			// tell the registrant that name already in use?
		};
	}

	reallyRegister {arg name, addr;
		var peerToAdd;
		peerToAdd = Peer(name, addr);
		this.tellExistingPeersToAddNewPeer(peerToAdd);
		addrBook.add(peerToAdd);
		this.tellNewPeerToAddExistingPeers(peerToAdd);
	}

	deregister {arg nameOfPeerToRemove;
		this.tellExistingPeersToRemoveNewPeer(nameOfPeerToRemove);
		addrBook.removeAt(nameOfPeerToRemove);
		this.tellNewPeerToRemoveExistingPeers(nameOfPeerToRemove);
	}

	// to registrant
	tellExistingPeersToAddNewPeer {arg peerToAdd;
		addrBook.peers.do{arg existingPeer;
			this.sendAdd(existingPeer.name, peerToAdd.name, peerToAdd.addr.ip, peerToAdd.addr.port)
		};
	}

	tellNewPeerToAddExistingPeers {arg peerToAdd;
		addrBook.peers.do{arg existingPeer;
			this.sendAdd(peerToAdd.name, existingPeer.name, existingPeer.addr.ip, existingPeer.addr.port);
		};
	}

	tellExistingPeersToRemoveNewPeer {arg nameOfPeerToRemove;
		addrBook.peers.do{arg existingPeer;
			this.sendRemove(existingPeer.name, nameOfPeerToRemove)
		};
	}

	tellNewPeerToRemoveExistingPeers {arg nameOfPeerToRemove;
		addrBook.peers.do{arg existingPeer;
			this.sendRemove(nameOfPeerToRemove, existingPeer.name);
		};
	}

}

TestRegistrant {

	var <registrarAddr;
	var <addrBook;
	var <proposedName, <registeredName;

	var checkIfNameFreeResponder;

	*new {
		^super.new.init;
	}

	name {
		^registeredName;
	}

	init {
		addrBook = AddrBook.new;
		this.initReceivers;
	}

	initReceivers {
		this.initReceiveAnnounce;
		//this.initReceiveCheckNameReply;
		this.initReceiveAdd;
		this.initReceiveRemove;
	}

	// API

	// receive
	initReceiveAnnounce {
		OSCFunc(this.receiveAnnounce, '/announce')
	}

	receiveAnnounce {
		^{arg msg, time, addr, recvPort;
			this.announce(addr);
		}
	}

	initReceiveAdd {
		OSCFunc(this.receiveAdd, '/add')
	}

	receiveAdd {
		^{arg msg;
			var nameOfPeerToAdd, ipOfPeerToAdd, portOfPeerToAdd;
			# nameOfPeerToAdd, ipOfPeerToAdd, portOfPeerToAdd = msg.drop(1);
			this.add(nameOfPeerToAdd, ipOfPeerToAdd, portOfPeerToAdd);
		}
	}

	initReceiveRemove {
		OSCFunc(this.receiveRemove, '/remove')
	}

	receiveRemove {
		^{arg msg;
			var nameOfPeerToRemove;
			\receiveRemove.postln;
			nameOfPeerToRemove = msg[1];
			this.remove(nameOfPeerToRemove);
		}
	}

	// send
	sendRegister {arg name;
		registrarAddr.sendMsg('/register', name);
	}

	sendDeregister {arg name;
		registrarAddr.sendMsg('/deregister', name)
	}

	// from registrar
	announce {arg addr;
		registrarAddr = addr;
	}

	add {arg nameOfPeerToAdd, ipOfPeerToAdd, portOfPeerToAdd;
		var peerToAdd;
		peerToAdd = Peer(nameOfPeerToAdd, NetAddr(ipOfPeerToAdd.asString, portOfPeerToAdd));
		addrBook.add(peerToAdd);
	}

	remove {arg nameOfPeerToRemove;
		inform("REGISTRANT %: removing... %".format(registeredName, nameOfPeerToRemove));
		addrBook.removeAt(nameOfPeerToRemove);
		if (nameOfPeerToRemove == registeredName) { registeredName = nil } // should this be driven by local registration, or remote deregistration
	}

	// to registrar

	register {arg aName;
		proposedName = aName;
		if (registrarAddr.notNil) {
			checkIfNameFreeResponder ?? {OSCFunc({arg msg;
				var returnedName;
				returnedName = msg[1];
				if (returnedName == proposedName) {
					var nameFree;
					nameFree = msg[2].asBoolean;
					if (nameFree) {
						inform("REGISTRANT: okay to register with given name");
						registeredName = proposedName;
						this.sendRegister(registeredName);
					}
					{
						warn("REGISTRANT: name already in use");
					};
					proposedName = nil; // reset proposedName regardless of result
				}
				{
					inform("REGISTRANT: filtering out replies with non-matching names");
				}
			}, '/checkIfNameFree-reply');
			};
			inform("REGISTRANT: checking name % with registrar").format(proposedName);
			registrarAddr.sendMsg('/checkIfNameFree', proposedName);
		} {
			warn("REGISTRANT: no registrar found");
		};
	}

	deregister {
		if (registeredName.notNil) {
			inform("REGISTRANT %: deregistering...".format(registeredName));
			this.sendDeregister(registeredName);
		}
		{
			warning("REGISTRANT: you are not registered");
		}
	}

}

IDPeer : Peer {

	// extension of peer to give each peer a unique ID

	classvar maxNoOfPeers = 24;
	classvar availableIDs, <takenIDs;
	var <id;

	*initClass {
		takenIDs = IdentityDictionary.new;
		availableIDs = (0..maxNoOfPeers);
	}

	*new {|name, addr, online = true|
		^super.new(name.asSymbol, addr, online).allocateID;
	}

	allocateID {
		id = this.getNextFreeID;
		takenIDs.put(this.name, id);
	}

	deallocateID {
		takenIDs.removeAt(this.name);
	}

	free {
		this.deallocateID;
		this.free;
	}

	clearIDs {
		takenIDs.clear;
	}

	getNextFreeID {
		^availableIDs.removeAll(takenIDs.values).first;
	}

	// post pretty
	printOn { |stream|
		stream << this.class.name << "(" <<* [name, id, addr, online] << ")"
	}

}
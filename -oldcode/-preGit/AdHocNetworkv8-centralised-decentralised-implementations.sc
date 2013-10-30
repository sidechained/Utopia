// general question, should address books always contain local IP for local peers, or is the remote IP ever needed?
// the idea situation is if the addr in the book starts off as local, and is replaced by remote address once remote registration occurs
// because I took out the 'me' there is now no local addition to the address book
// for now concentrate on getting WAN side IPs in the address book


AbstractNode {
	classvar <>period, <>keepAlivePeriod; // time between hail announcements
	// newCopyArgs:
	var <oscPath;
	var <>basePort;
	var <registrationType;
	var <hasAddrBookGUI;
	var verbose; // in verbose mode the network will report joining and leaving of peers
	// otherArgs:
	var <name, <id, <port;
	var <addrBook, <authenticator;
	var <peerIDs;
	var <lastResponses;
	//	var me; // relevant?
	var <registrationInProgress;

	*new {arg oscPath, basePort = 60000, registrationType = \decentralised, hasAddrBookGUI = false, verbose = true;
		^super.newCopyArgs(oscPath, basePort, registrationType, hasAddrBookGUI, verbose).initAbstractNode
	}

	*initClass {
		period = 1;
		keepAlivePeriod = period * 2;
	}

	initAbstractNode {
		registrationInProgress = false;
		addrBook = AddrBook();
		peerIDs = PeerIDs();
		lastResponses = LastResponses();
		if (verbose, {this.postAddrBookChanges });
		if (hasAddrBookGUI) { AddrBookGUI(this) };
		this.makeNode(oscPath, basePort, hasAddrBookGUI);
	}

	makeNode {arg oscPath, basePort, hasAddrBookGUI;
		case
		{ registrationType == \centralised } { ^CentralisedNode.new(this, isRegistrant: true, isRegistrar: true) }
		{ registrationType == \decentralised} { /*^DecentralisedNode.new(this, oscPath++'hail')*/ };
	}

	postAddrBookChanges {
		addrBook.addDependant({arg addrBook, what, who;
			var meString;
			if (name == nil) {meString = "unregisitered peer"} {meString = name.asString};
			case
			{ what == \add } { ("--" + [who.name.asString] + "was added to the address book of" + [meString]).postln; }
			{ what == \remove } { ("--" + [who.name.asString] + "was removed from the address book of" + [meString]).postln; }
		})
	}

	// local joining
	join {arg name;
		if (registrationInProgress == false) {
			this.checkIfNameInUse(name);
		} {
			warn("registration already in progress");
		};
	}

	checkIfNameInUse {arg name;
		var notInAddrBook;
		notInAddrBook = addrBook.names.includes(name).not;
		if (notInAddrBook) {
			// depends on implementation:
			this.checkIfListenedForIncomingRegistrations(name);
		} {
			warn("name % already in use".format(name));
		}
	}

	// local leaving
	leave {
		// need to check if registration in progress?
		var inAddrBook;
		inAddrBook = addrBook.names.includes(name);
		if (inAddrBook) {
			this.leaveLocal; // CORRECT?
		}
		{
			warn("name not in address book")
		}
	}

	joinLocal {arg proposedName;
		"% joined locally".format(proposedName).postln;
		name = proposedName;
		id = peerIDs.getNextFreeID;
		port = basePort + id;
		thisProcess.openUDPPort(port);
	}

	leaveLocal {
		"% left locally".format(name).postln;
		name = nil;
		id = nil;
		port = nil;
	}

	// remote
	joinedRemote {arg who;
		var notInAddrBook;
		notInAddrBook = addrBook.names.includes(who.name).not;
		if (notInAddrBook) { // only add if not already present
			addrBook.add(who);
		};
	}

	leftRemote {arg who;
		var inAddrBook;
		inAddrBook = addrBook.names.includes(who.name);
		if (inAddrBook) { // only remove if not present
			addrBook.removeAt(who.name);
			lastResponses.remove(who.name);
			peerIDs.remove(who.name);
		}
	}

}

CentralisedNode {
	var abstractNode;
	var isRegistrant, isRegistrar; // is a centralised node always a registrant?
	var registrant, registrar;

	*new {arg abstractNode, isRegistrant = true, isRegistrar = false;
		^super.newCopyArgs(abstractNode, isRegistrant, isRegistrar).initCentralisedNode;
	}

	initCentralisedNode {
		if (isRegistrant) {registrant = IDRegistrant(
			addrBook: abstractNode.addrBook,
			//me: ???,
			//registrarAddr: abstractNode.registrarAddr,
			authenticator: abstractNode.authenticator,
			oscPath: abstractNode.oscPath
		)};
		if (isRegistrar) {registrar = IDRegistrar(
			addrBook: abstractNode.addrBook,
			period: abstractNode.class.period,
			authenticator: abstractNode.authenticator,
			oscPath: abstractNode.oscPath
		)};
	}

	free {
		// cleanup
		registrant ?? registrant.free;
		registrar ?? registrar.free;
	}

}

DecentralisedNode {
	var abstractNode;
	var local; // in local mode all traffic will be routed internally instead of using a broadcast address (useful for prototyping when you have no active network adapter)
	var broadcastAddr;
	var startedListeningTime;
	var hailingSignal, hailResponder, monitoringSignal;

	*new {arg abstractNode, local = false;
		^super.newCopyArgs(abstractNode, local).initDecentralisedNode;
	}

	initDecentralisedNode {
		this.initBroadcastAddr;
		this.startListeningForIncomingRegistrations;
	}

	initBroadcastAddr {
		if (local == true) {
			"running in local mode...using localhost address instead of broadcast address".postln;
			broadcastAddr = NetAddr("127.0.0.1", 57120);
		} {
			NetAddr.broadcastFlag = true;
			broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		}
	}

	startListeningForIncomingRegistrations {
		startedListeningTime = Main.elapsedTime;
		hailResponder = OSCFunc({arg msg, time, addr, recvPort;
			var nameToAdd, idToAdd, addrToAdd, peerToAdd;
			nameToAdd = msg[1];
			idToAdd = msg[2];
			abstractNode.peerIDs.add(nameToAdd, idToAdd);
			addrToAdd = NetAddr(addr.ip, abstractNode.basePort + idToAdd);
			peerToAdd = PeerWithID(nameToAdd, idToAdd, addrToAdd);
			this.joinedRemote(peerToAdd);
			abstractNode.lastResponses.add(nameToAdd, time);
		}, abstractNode.oscPath);
	}

	checkIfListenedForIncomingRegistrations {arg proposedName;
		var now, listenUntil;
		now = Main.elapsedTime;
		listenUntil = startedListeningTime + abstractNode.keepAlivePeriod;
		if (now > listenUntil) {
			this.joinLocal(proposedName);
		} {
			fork{
				var listenFor;
				listenFor = listenUntil - now;
				warn("listening for incoming registrations for % seconds before registering".format(listenFor));
				abstractNode.registrationInProgress = true;
				listenFor.wait;
				abstractNode.registrationInProgress = false;
				warn("waiting time over...registration continuing");
				this.joinLocal(proposedName);
			};
		};
	}

	// local
	joinLocal {arg proposedName;
		abstractNode.joinLocal(proposedName);
		this.startHailing;
		this.startMonitoring;
	}

	leaveLocal {
		// if the leaving player is me, reset some of my vars
		this.stopHailing;
		abstractNode.leaveLocal;
	}

	// hailing signal
	initHailingSignal {
		hailingSignal = Routine({
			inf.do{
				var msgToSend;
				msgToSend = [abstractNode.oscPath, abstractNode.name, abstractNode.id];
				//("sending" + msgToSend).postln;
				broadcastAddr.sendMsg(*msgToSend);
				abstractNode.period.wait;
			};
		});
	}

	startHailing {
		this.initHailingSignal;
		hailingSignal.play;
	}

	stopHailing {
		hailingSignal.stop;
	}

	// monitoring signal
	initMonitoringSignal {
		monitoringSignal = Routine({
			inf.do{
				this.checkOnline;
				abstractNode.period.wait;
			};
		});
	}

	checkOnline {
		if(abstractNode.period.notNil, {
			var now;
			now = Main.elapsedTime;
			abstractNode.lastResponses.dict.keysValuesDo({|name, lastHeardFrom|
				if((now - lastHeardFrom) > (abstractNode.keepAlivePeriod), {
					var leavingPeer;
					leavingPeer = abstractNode.addrBook.at(name);
					this.leftRemote(leavingPeer);
				});
			});
		});
	}

	startMonitoring {
		this.initMonitoringSignal;
		monitoringSignal.play;
	}

	/*	// we never actually stop monitoring
	stopMonitoring {
	monitoringSignal.stop;
	}*/



}

AddrBookGUI {

	// a gui showing a list of currently online peers
	// TODO: identify self in address book with different colour

	var abstractNode, <mainView, peerRows, rowWidth;

	*new {arg abstractNode;
		^super.newCopyArgs(abstractNode).init;
	}

	init {
		this.initAddrBookDep;
		this.makeMainView;
	}

	makeMainView {
		peerRows = View().layout_(VLayout(*[nil])
			.margins_(0)
			.spacing_(0)
		);
		mainView = ScrollView(nil, Rect(600, 500, 300, 50))
		.alwaysOnTop_(true)
		.front;
		mainView.canvas = View()
		.background_(Color.white);
		rowWidth = mainView.bounds.width/4;
		mainView.layout_(VLayout(*[this.makeTitleRow, peerRows])
			.margins_(0)
			.spacing_(0)
		);
		^mainView
	}

	makeTitleRow {
		^View().layout_(HLayout(*[
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("Name:"),
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("ID:"),
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("IP:"),
			StaticText().minWidth_(rowWidth).maxWidth_(rowWidth).string_("Port:")
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	makePeerRow {arg peer;
		^View().layout_(HLayout(*[
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.name),
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.id),
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.addr.ip),
			StaticText().background_(Color.grey).minWidth_(rowWidth).maxWidth_(rowWidth).string_(peer.addr.port)
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	initAddrBookDep {
		abstractNode.addrBook.addDependant({arg addrBook, what, who;
			var whoID = abstractNode.peerIDs.dict(who.name);
			[what, who].postln;
			defer {
				case
				{what == \add} {
					var row, index;
					row = this.makePeerRow(who);
					index = abstractNode.peerIDs.dict.values.sort.indexOf(who.id);
					peerRows.layout.insert(row, index);
				}
				{what == \remove } {
					var index;
					index = who.id;
					peerRows.children.removeAt(index).destroy;
				}
			};
		})
	}

}

IDRegistrar {

	// extension of Registrar to assign IDs to each peer
	// uses PeerIDs and LateResponses abstractions
	// also see extensions to Registrant

	classvar basePort = 60000;
	var <addrBook, period, authenticator, oscPath;
	var <peerIDs, lastResponses, pingRegistrarOSCFunc, registerOSCFunc, unRegisterOSCFunc, pingReplyOSCFunc;
	var broadcastAddr;
	var local = true;
	var myID, myPort; // not using a mePeer as registrar has no name and address (could use localhost address, but not required)

	*new { |addrBook, period = 1.0, authenticator, oscPath = '/register'|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, period, authenticator, oscPath).init;
	}

	init {
		peerIDs = PeerIDs();
		myID = peerIDs.getNextFreeID; // this should alway be 0 (allocated before registrar goes online, so registar will always be first)
		myPort = basePort + myID;
		lastResponses = LastResponses();
		authenticator = authenticator ?? { NonAuthenticator };
		this.makeOSCFuncs;
		period.notNil.if({ this.startAnnouncement; }); // ?? when might the period be nil and be valid?
	}

	initBroadcastAddr {
		if (local == true) {
			"running in local mode...using localhost address instead of broadcast address".postln;
			broadcastAddr = NetAddr("127.0.0.1", 57120);
		} {
			NetAddr.broadcastFlag = true;
			broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		}
	}

	startAnnouncement {
		// ping now announces keys and IDs of existing peers
		// this is also used by registrants to set the registrar address
		// if registrant is registered they will send a ping reply
		// registrants are not allowed to register until the registrar is up and running
		this.initBroadcastAddr;
		SystemClock.sched(0, {
			var msgToSend;
			msgToSend = addrBook.peers.collect{arg peer; [peer.name, peer.id] }.flatten;
			broadcastAddr.sendMsg(oscPath ++ "-ping", *msgToSend);
			this.checkOnline;
			period;
		});
	}

	makePeer {|name, id, addr|
		// make peer from id received from registrant
		peerIDs.add(name, id);
		^PeerWithID(name, id, addr);
	}

	removePeer {|name|
		addrBook.removeAt(name);
		lastResponses.remove(name); // MOD
		peerIDs.remove(name);
	}

	makeOSCFuncs {
		// registrar should also have a unique port and messages should be fixed to the registrars address and port

		// people are looking for me
		pingRegistrarOSCFunc = OSCFunc({|msg, time, addr|
			addr.sendMsg(oscPath ++ "-pingRegistrarReply"); // I'm here!
		}, oscPath ++ "-pingRegistrar").fix;

		registerOSCFunc = OSCFunc({|msg, time, addr, recvPort|
			var name, id, arrivingPeer;
			# name, id = msg[1..2];
			arrivingPeer = this.makePeer(name, id, NetAddr(addr.ip, recvPort));
			authenticator.authenticate(arrivingPeer, {
				\sendingAdd.postln;
				addrBook.peers.postln;
				// tell the new arrival about everyone
				addrBook.peers.do({|existingPeer|
					addr.sendMsg(oscPath ++ "-add", existingPeer.name, existingPeer.addr.ip, existingPeer.addr.port);
				});
				// add self:
				addrBook.add(arrivingPeer);
				addrBook.peers.postln;
				// tell everyone about the new arrival (including self, so that peer registers with self with remote address)
				// (mod here to send unique port based on ID)
				addrBook.sendAll(oscPath ++ "-add", arrivingPeer.name, arrivingPeer.id, arrivingPeer.addr.ip, arrivingPeer.addr.port);
			});
		}, oscPath, recvPort: myPort).fix; // should not be bound to a specific source ID addr, as this could be any peer

		unRegisterOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			this.removePeer(name);
			addrBook.sendExcluding(name, oscPath ++ "-remove", name);
		}, oscPath ++ "-unregister", recvPort: myPort).fix; // should not be bound to a specific source ID addr, as this could be any peer

		// make sure everyone is still online
		pingReplyOSCFunc = OSCFunc({|msg, time, addr|
			var name, peer;
			name = msg[1];
			peer = addrBook[name];
			peer.notNil.if({
				peer.online_(true);
				lastResponses.add(name, time);
				addrBook.sendAll(oscPath ++ "-online", name, true.binaryValue);
			});
		}, oscPath ++ "-pingReply", recvPort: myPort).fix; // should not be bound to a specific source ID addr, as this could be any peer
	}

	free { pingRegistrarOSCFunc.free; registerOSCFunc.free; unRegisterOSCFunc.free; pingReplyOSCFunc.free }

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		lastResponses.dict.keysValuesDo({|name, lastHeardFrom|
			if((now - lastHeardFrom) > (period * 2), {
				addrBook[name].online = false;
				addrBook.sendAll(oscPath ++ "-online", name, false.binaryValue);
			});
		});
	}

}

IDRegistrant {

	// all OSCFuncs are fixed to the registrants own port
	// this is normally supplied by 'me' argument, which I have removed
	// in the new form of registration, we cannot know this port number until the registrar has allocated an ID
	// (the port is based on the ID)
	// so we need an alternative means of
	// probably means significant rewrite

	// modified Registrant to handle ID
	// registrant allocates IDs and sends to IDRegistrant, see addOSCFunc

	classvar basePort = 60001;
	// newCopyArgs:
	var <addrBook, <registrarAddr, authenticator, oscPath;
	// otherArgs:
	var addOSCFunc, removeOSCFunc, onlineOSCFunc, pingOSCFunc, pinging;
	var peerIDs;
	var registrarAnnouncementResponder;
	var local = true;
	var myName, myID, myPort; // not using a mePeer as have no address (could use localhost address, but not required)

	// we pass an authenticator here but maybe it's unnecessary. It's simply there to respond, not challenge in this case.
	*new { |addrBook, registrarAddr, authenticator, oscPath = '/register'| // MOD removed me
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, registrarAddr, authenticator, oscPath).init;
	}

	init {
		this.listenForRegistrarAnnouncements;
	}

	listenForRegistrarAnnouncements {
		peerIDs = PeerIDs();
		registrarAnnouncementResponder = OSCFunc({arg msg, time, addr;
			var keyIDPairs;
			peerIDs.clear; // previous state is cleared, latest announcement is always the authority
			msg = msg.drop(1); // strip off osc tag and discard
			keyIDPairs = msg.clump(2);
			keyIDPairs.do{arg nameIDPair;
				var name, id;
				# name, id = nameIDPair;
				peerIDs.add(name, id);
			};
			peerIDs.dict.postln;
			// set registrar address (repeatedly)
			registrarAddr = NetAddr(addr.ip, basePort - 1);
		}, oscPath ++ "-ping") // ping-reply
	}

	makePeer {|name, id, hostname, port|
		^PeerWithID(name, id, NetAddr(hostname.asString, port));
	}

	addOSCFuncs {
		myPort.postln;
		addOSCFunc = OSCFunc({|msg, time, addr|
			var peer;
			\receivingAdd.postln;
			peer = this.makePeer(*msg[1..]);
			addrBook.add(peer);
		}, oscPath ++ "-add", registrarAddr, recvPort: myPort).fix;

		removeOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			addrBook.removeAt(name);
		}, oscPath ++ "-remove", registrarAddr, recvPort: myPort).fix;

		onlineOSCFunc = OSCFunc({|msg, time, addr|
			var name, peer;
			name = msg[1];
			peer = addrBook[name];
			peer.notNil.if({ peer.online_(msg[2].booleanValue) });
		}, oscPath ++ "-online", registrarAddr, recvPort: myPort).fix;

		pingOSCFunc = OSCFunc({|msg, time, addr|
			registrarAddr.sendMsg(oscPath ++ "-pingReply", myName);
		}, oscPath ++ "-ping", registrarAddr, recvPort: myPort).fix;
	}

	free {
		pinging = false;
		this.unregister; // needs a name arg here
		addOSCFunc.free;
		removeOSCFunc.free;
		onlineOSCFunc.free;
		pingOSCFunc.free;
	}

	// these should be subject to the same checks as the decentralised registrar (unify)

	join {arg name;
		// checks - do these in superclass or?
		// don't forget to enforce wait time, to allow registrar announcement to come in
	}

	joinCheckRegistrarOnline {arg name;
		if (registrarAddr.notNil) {
			this.successfulJoin(name);
		} {
			warn("cannot join, registrar is not online");
		}
	}

	successfulJoin {arg name;
		myName = name;
		myID = peerIDs.getNextFreeID;
		myPort = myID + basePort; // replace with basePort
		thisProcess.openUDPPort(myPort);
		this.addOSCFuncs;
		registrarAddr.sendMsg(oscPath, myName, myID);
	}

	leave {arg name;
		// checks - do these in superclass or?
	}

	successfulLeave {arg name;
		registrarAddr.sendMsg(oscPath ++ "-unregister", name);
	}

	initBroadcastAddr {
		if (local == true) {
			"running in local mode...using localhost address instead of broadcast address".postln;
			^NetAddr("127.0.0.1", 57120);
		} {
			NetAddr.broadcastFlag = true;
			^NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		}
	}

}

LastResponses {

	var <dict;

	*new {
		^super.new.init;
	}

	// last responses (used by both centralised and decentralised registration process)
	init {
		dict = IdentityDictionary.new;
	}

	add {arg nameOfPeer, time;
		dict.put(nameOfPeer, time);
	}

	remove {arg nameOfPeer;
		dict.removeAt(nameOfPeer);
	}

}

PeerIDs {

	// last responses (used by both centralised and decentralised registration process)
	classvar maxNoOfPeers = 24;
	var availableIDs, <dict;

	*new {
		^super.new.init;
	}

	init {
		dict = IdentityDictionary.new;
		availableIDs = (0..maxNoOfPeers);
	}

	add {arg nameToAdd, idToAdd;
		dict.put(nameToAdd, idToAdd);
	}

	remove {arg nameToRemove;
		dict.removeAt(nameToRemove);
	}

	clear {
		dict.clear;
	}

	getNextFreeID {
		^availableIDs.removeAll(dict.values).first;
	}

}

PeerWithID : Peer {

	// extension of peer to an an ID variable

	var <id;

	*new {|name, argID, addr, online = true|
		^super.newCopyArgs(name.asSymbol, addr, online).init(argID);
	}

	init {arg argID;
		// should be able to do this will newCopyArgs too, really
		id = argID;
	}

	// post pretty
	printOn { |stream|
		stream << this.class.name << "(" <<* [name, id, addr, online] << ")"
	}

}
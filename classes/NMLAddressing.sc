// Q: call nodes clients or peers? A: decentralised = peers, centralised = client, server
// Q: should it be possible to change name? not really, you should have to deregister and register again
// Q: is 'monitoring' possible? yes, it is the default state. it is not possible to stop listening, and still keep registering though (use case?)

// clarification of online/offline behaviour
// - there are two states, online and registered
// - online is simply whether you can be contacted on the network
// - registered is whether you have a name or not
// - address book can return peers, onlinePeers, or registeredPeers (or even registeredOnlinePeers)

// client server rethink
// not enforcing unique names for now (self managed)
// also names may be changed during performance (also okay, maybe gui should reflect it, if name exists, flash or something)

// how should server reinforce?
// - when clients are alive and repeatedly announcing, all will be well
// - if a message from a client doesn't get through, a future one probably will
// - when clients stop announcing, after time the server will note that the client is offline
// - at this stage, the server should REPEATED let others know that this client is offline

NMLRegistrar {

	// new centralised approach:
	// - clients start by creating a mePeer with a random ID
	// - the server receives an /getId message, and as the random ID isn't in the address book it:
	// - allocates a 'permanent ID' based on the IDs already in use in the address book
	// - server sends an /setId [tempID, permID, clientStartingPort] back to address
	// - back on the client the /serverSetClientID is filtered by it's own temp Id, before switching the tempID for the permID
	// - at this stage responders are set up and fix to the clients allocated port
	// - me is now just a reference to my peer in the address book (saves maintaining two identical things)

	// centralised registration is a three stage process:
	// 1. find server
	// 2. get id from server
	// 3. start announcing

	var serverPort, clientStartingPort, period, surviveCmdPeriod;
	var <addrBook;
	var broadcastAddr;
	var serverPort, clientStartingPort;
	var eventLoop, period = 1;
	var clientAnnouncementResponder;

	*new {arg serverPort = 50000, clientStartingPort = 60000, period = 1, surviveCmdPeriod = true;
		^super.newCopyArgs(serverPort, clientStartingPort, period, surviveCmdPeriod).init;
	}

	init {
		this.checkForExistingServer;
	}

	checkForExistingServer {
		inform("SERVER: checking for existing server...");
		fork {
			var announceServerResponder, serverFound;
			serverFound = false;
			announceServerResponder = OSCFunc({arg msg, time, addr;
				serverFound = true;
			}, '/server-announceServer').permanent_(surviveCmdPeriod);
			(period * 2).wait;
			announceServerResponder.free;
			if (serverFound) { warn("cannot initialise server, another server is already running...") } {
				warn("existing server not found, continuing as normal...");
				this.existingServerNotFound };
		};
	}

	existingServerNotFound {
		addrBook = NMLAddrBook.new;
		this.initAndStartEventLoop;
		this.listenForAnnoucementsFromClients;
	}

	initAndStartEventLoop {
		// not a problem to have a non-instance-specific name here (as there should only ever be one server)
		eventLoop = SkipJack({
			this.announceServer;
			this.checkIfClientsWentOffline;
		}, period, name: \NMLRegistrar_eventLoop, clock: SystemClock); // NOTE: starts automatically
		if (surviveCmdPeriod.not) {
			CmdPeriod.add({
				eventLoop.stop;
		})};
	}

	listenForAnnoucementsFromClients {
		clientAnnouncementResponder = OSCFunc({arg msg, time, addr;
			var id, name;
			# id, name = msg.drop(1);
			if (name == 0) { name = nil }; // correct OSC conversion of nil's to 0's
			if (addrBook.ids.includes(id).not) {
				var tempId, permId, bundle, existingPeers, newPeer;
				tempId = id; // if id not in address book, it must be temporary
				permId = addrBook.getNextFreeID;
				// first message in bundle will be a set id message
				bundle = [ ];
				bundle = bundle.add([ '/server-setId', tempId, permId, clientStartingPort ] );
				// subsequent messages in bundle tell the new peer about existing peers:
				addrBook.peers.do{arg peer;
					var msgToAdd;
					msgToAdd = ['/server-announceClient', peer.id, peer.name, peer.addr.ip, peer.addr.port, peer.online, peer.lastResponse];
					bundle = bundle.add(msgToAdd);
				};
				// final message in bundle tells the newPeer about itself
				newPeer = NMLPeer(permId, name, NetAddr(addr.ip, permId + clientStartingPort), true, time);
				bundle = bundle.add(['/server-announceClient', newPeer.id, newPeer.name, newPeer.addr.ip, newPeer.addr.port, newPeer.online, newPeer.lastResponse]);
				addr.sendBundle(nil, *bundle);
				// next, tell everyone about newPeer
				this.announceClientToExistingClients(newPeer); // all except self
				addrBook.add(newPeer);
			}
			{
				var existingName;
				existingName = addrBook.atId(id).name;
				if (existingName != name) {
					// if name differs from existing name, update it:
					addrBook.updatePeerName(id, name);
				};
				addrBook.updatePeerLastResponse(id, time);
				this.announceClientToExistingClients(addrBook.at(id));
			};
		}, '\client-announceClient', recvPort: serverPort).permanent_(surviveCmdPeriod);
	}

	announceClientToExistingClients {arg client;
		var msgToSend;
		msgToSend = ['/server-announceClient', client.id, client.name, client.addr.ip, client.addr.port, client.online, client.lastResponse];
		addrBook.sendAll(*msgToSend);
	}

	checkIfClientsWentOffline {
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do{arg peer;
			if ((now - peer.lastResponse) > (period * 2)) {
				if (peer.online) {
					addrBook.takePeerOffline(peer.id);
					if (peer.name.notNil) {
						// going offline automatically resets name
						addrBook.clearPeerName(peer.id);
					}
				};
				this.announceClientToExistingClients(addrBook.at(peer.id));
				// once peer goes offline, the server will *repeatedly (tell all clients that this is the case);
			};
		};
	}

	announceServer {
		broadcastAddr ?? {
			// init broadcast addr if not already
			NetAddr.broadcastFlag = true;
			broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		};
		broadcastAddr.sendMsg('\server-announceServer', serverPort);
	}

	decommission {
		clientAnnouncementResponder.free;
		eventLoop.stop;
	}

}

NMLRegistrant {

	var period, autoName, surviveCmdPeriod, verbose, hasGui;
	var <addrBook;
	var <myId, <myName, <myPort;
	var <serverPeer;
	var eventLoop, period = 1;
	var announceServerResponder, setIdResponder, announceClientResponder;
	var reporter, gui;

	*new {arg period = 1, autoName = false, surviveCmdPeriod = true, verbose = true, hasGui = true;
		^super.newCopyArgs(period, autoName, surviveCmdPeriod, verbose, hasGui).listenForServerPing;
	}

	listenForServerPing {
		announceServerResponder = OSCFunc({arg msg, time, addr;
			var serverPort;
			# serverPort = msg.drop(1);
			if (serverPeer.isNil) { // if server does not exist:
				inform("new server found at: %".format([addr.ip, serverPort]));
				serverPeer = NMLPeer(nil, nil, NetAddr(addr.ip, serverPort), true, time);
				this.init; // do when new server comes online
			}
			{
				if (serverPeer.addr.ip != addr.ip) { // - if server exists but it's ip has changed (not considering port for now):
					inform("server address has changed to %, assuming new server".format([addr.ip, serverPort]));
					this.newServerFound(addr.ip, serverPort, time);
				}
				{
					if (serverPeer.online.not) { // - if server exists, it address hasn't changed but it is offline:
						// server came back online (from being offline):
						inform("server came back online at: %".format([addr.ip, serverPort]));
						serverPeer.online = true;
						serverPeer.lastResponse = time;
					}
					{ // - if server exists, it address hasn't changed and it is online:
						// just update its last response time
						// inform("server ping received, updating last response time");
						serverPeer.lastResponse = time;
					}
				}
			}
		}, '\server-announceServer').permanent_(surviveCmdPeriod);
	}

	init {
		addrBook = NMLAddrBook.new;
		myId = rand2(-2147483647, 2147483647);
		if (verbose) { reporter = NMLAddrBookReporter.new(addrBook); };
		if (hasGui) { defer { gui = NMLAddrBookGUI.new(addrBook); } };
		if (autoName) { this.register(this.getComputerName); };
		this.listenForSetIdMessageFromServer;
		eventLoop ?? { this.initEventLoop; };
		eventLoop.play;
	}

	getComputerName {
		var computerName;
		computerName = "whoami".unixCmdGetStdOut;
		if(computerName.last == Char.nl, {computerName = computerName.drop(-1)});
		inform("auto-registering with name %".format(computerName));
		^computerName.asString;
	}

	initEventLoop {
		// eventLoop will be based on initial random ID
		eventLoop = SkipJack({
			this.announceSelf;
			this.checkIfServerWentOffline;
		}, period, name: \NMLRegistrant_eventLoop ++ myId, clock: SystemClock, autostart: false); // don't start
		if (surviveCmdPeriod.not) {
			CmdPeriod.add({
				eventLoop.stop;
		})};
	}

	announceSelf {
		var msgToSend;
		msgToSend = ['\client-announceClient', myId, myName];
		serverPeer.addr.sendMsg(*msgToSend);
	}

	listenForSetIdMessageFromServer {
		setIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId, clientStartingPort;
			# tempId, permId, clientStartingPort = msg.drop(1);
			if (myId == tempId) {
				myId = permId;
				myPort = permId + clientStartingPort;
				thisProcess.openUDPPort(myPort);
				// this is when I know I am registered
				// but it doesn't mean I exist in my own address book (yet)
				this.listenForClientAnnouncementsFromServer;
			};
		}, '/server-setId').permanent_(surviveCmdPeriod);
	}

	listenForClientAnnouncementsFromServer {
		announceClientResponder = OSCFunc({arg msg;
			var id, name, ip, port, online, time;
			# id, name, ip, port, online, time = msg.drop(1);
			if (name == 0) { name = nil }; // correct OSC conversion of nil's to 0's
			ip = ip.asString; // correct OSC conversion of ip string to symbol
			online = online.asBoolean; // correct OSC conversion of booleans to integers (CHECK: Utopia converts before sending)
			if (addrBook.atId(id).isNil) {
				var newPeer;
				newPeer = NMLPeer(id, name, NetAddr(ip, port), online, time);
				addrBook.add(newPeer);
			}
			{
				var existingPeer;
				existingPeer = addrBook.at(id);
				if (name != existingPeer.name) { // if name differs from existing name, update it:
					addrBook.updatePeerName(existingPeer.id, name);
				};
				if (online != existingPeer.online) { // if peers online status has changed, update it:
					addrBook.takePeerOffline(existingPeer.id, name);
				};
				if (time != existingPeer.lastResponse) { // if last response time changed, update it:
					addrBook.updatePeerLastResponse(existingPeer.id, time);
				}
			}
		}, '/server-announceClient', recvPort: myPort).permanent_(surviveCmdPeriod)
	}

	register {arg proposedName;
		if (proposedName != 0) {
			myName = proposedName;
		}
		{
			warn("cannot use name 0");
		}
	}

	deregister {arg proposedName;
		myName = nil;
	}

	checkIfServerWentOffline {
		var now;
		now = Main.elapsedTime;
		if (serverPeer.online) {
			if ((now - serverPeer.lastResponse) > (period * 2)) {
				warn("server went offline");
				serverPeer.online = false;
			}
		}
	}

	decommission {
		// simulate a crash:
		gui !? { gui.destroy };
		reporter !? { reporter.decommission };
		announceServerResponder.free;
		setIdResponder.free;
		announceClientResponder.free;
		eventLoop.stop;
	}

	me {
		^addrBook.atId(myId) ?? { warn("me not yet in address book") };
	}


}

NMLDecentralisedNode { // node, or peer?

	var peerStartingPort, autoName, period, surviveCmdPeriod, verbose, hasGui;
	var <addrBook;
	var <myId, <myName, <myPort;
	var broadcastAddr;
	var announceSelfLoop, checkLastResponsesLoop;
	var announceSelfResponder;
	var reporter, gui;

	*new {arg peerStartingPort = 50000, autoName = false, period = 1, surviveCmdPeriod = true, verbose = false, hasGui = false;
		^super.newCopyArgs(peerStartingPort, autoName, period, surviveCmdPeriod, verbose, hasGui).init;
	}

	init {
		addrBook = NMLAddrBook.new;
		if (verbose) { reporter = NMLAddrBookReporter.new(addrBook); };
		if (hasGui) { defer { gui = NMLAddrBookGUI.new(addrBook); } };
		if (autoName) { this.register(this.getComputerName); };
		this.initBroadcastAddr;
		this.goOnline;
	}

	goOnline {
		fork{
			this.listenForAnnouncementsFromPeers;
			(period * 2).wait;
			myId = addrBook.getNextFreeID;
			this.checkForSelf;
			this.initAnnounceSelfLoop;
			this.initCheckLastResponsesLoop;
		};
	}

	getComputerName {
		var computerName;
		computerName = "whoami".unixCmdGetStdOut;
		if(computerName.last == Char.nl, {computerName = computerName.drop(-1)});
		^computerName;
	}

	isOnline {
		this.me !? {^_.online}; // WORKS?
	}

	register {arg proposedName;
		if (proposedName != 0) {
			myName = proposedName;
		}
		{
			warn("cannot use name 0");
		}
	}

	deregister {
		myName = nil;
	}

	initBroadcastAddr {
		// set broadcast address:
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	initAnnounceSelfLoop {
		announceSelfLoop = SkipJack({
			\announcingSelf.postln;
			this.announceSelf;
		}, period, name: \decentralisedNode_announceSelfLoop ++ myId, clock: SystemClock, autostart: true);
		if (surviveCmdPeriod.not) {
			\announcingSelf.postln;
			CmdPeriod.add({
				announceSelfLoop.stop;
		})};
	}

	initCheckLastResponsesLoop {
		checkLastResponsesLoop = SkipJack({
			\checkingLastResponses.postln;
			this.checkLastResponses;
		}, period, name: \decentralisedNode_checkLastResponsesLoop ++ myId, clock: SystemClock, autostart: true);
		if (surviveCmdPeriod.not) {
			CmdPeriod.add({
				checkLastResponsesLoop.stop;
		})};
	}

	announceSelf {
		broadcastAddr.sendMsg('/announceSelf', myId, myName);
	}

	checkLastResponses {
		var now;
		now = Main.elapsedTime;
		addrBook.peers.do({arg peer;
			if (peer.online) {
				if((now - peer.lastResponse) > (period * 2), {
					if (peer.online) {
						addrBook.takePeerOffline(peer.id);
						if (peer.name.notNil) {
							// going offline automatically resets name
							addrBook.clearPeerName(peer.id);
						}
					};
				});
			};
		});
	}

	listenForAnnouncementsFromPeers {
		announceSelfResponder = OSCFunc({arg msg, time, addr;
			var id, name;
			# id, name = msg.drop(1);
			if (name == 0) { name = nil}; // reconvert;
			if (addrBook.ids.includes(id).not) {
				// add
				var newPeer;
				myPort = id + peerStartingPort;
				newPeer = NMLPeer(
					id: id,
					name: name,
					addr: NetAddr(addr.ip, myPort),
					online: true,
					lastResponse: time
				);
				addrBook.add(newPeer);
				// open port for self:
				if (id == myId) {
					thisProcess.openUDPPort(myPort);
				};
			}
			{
				var existingPeer;
				existingPeer = addrBook.atId(id);
				if (name != existingPeer.name) { // if name differs from existing name, update it:
					addrBook.updatePeerName(existingPeer.id, name);
				};
				if (existingPeer.online.not) { // if peers online status has changed, update it:
					addrBook.takePeerOnline(existingPeer.id, name);
				};
				if (time != existingPeer.lastResponse) { // if last response time changed, update it:
					addrBook.updatePeerLastResponse(existingPeer.id, time);
				};
			}
		}, '/announceSelf').permanent_(surviveCmdPeriod);
	}

	goOffline {
		// stop sending, keep listening
		announceSelfLoop.stop;
	}

	decommission {
		// stop sending, stop listening (simulate a crash)
		gui !? { gui.destroy };
		reporter !? { reporter.decommission };
		announceSelfResponder.free; // stop listening
		announceSelfLoop.stop;
		checkLastResponsesLoop.stop; // stop sending
	}

	me {
		^addrBook.atId(myId) ?? { warn("me not yet in address book") };
	}

	checkForSelf {
		addrBook.addDependant({arg addrBook, what, peer;
			if (peer.id == myId) {
				case
				{ what == \add } {
					\selfAddedToAddrBook.postln;
					this.me.postln;
				}
				{ what == \remove } {
					\selfRemovedFromAddrBook.postln;
				}
			}
		});
	}

}

NMLPeer {

	// refactor to add id's
	// need to add back in other stuff from original Utopia Peer Class

	var <>id, <>name, <>addr, <online, <>lastResponse;

	*new {arg id, name, addr, online, lastResponse;
		^super.newCopyArgs(id, name, addr, online, lastResponse);
	}

	online_ {|bool| if(bool != online, { online = bool; this.changed(\online) }) }

	== {|other|
		var result;
		// don't consider last response time here
		result = (id == other.id) && (name == other.name) && (addr == other.addr) && (online == other.online);
		^result;
	}

	hash {
		^this.instVarHash(#[\id, \name, \addr, \online, \lastResponse])
	}

	printOn {arg stream;
		// post pretty
		stream << this.class.name << "(" <<* [id, name, addr, online, lastResponse] << ")"
	}

}

NMLAddrBook {

	// refactor of NMLAddrBook to make ID the primary key
	// TODO: address book could warn rather than throw errors if an id or name is not found

	var <dict, <me; // remove getter from dict

	*new { ^super.new.init }

	init { dict = IdentityDictionary.new; }

	sendId {|id ...msg| dict[id].addr.sendMsg(*msg) }

	sendName {|name ...msg| this.atName(name).addr.sendMsg(*msg) }

	sendAll {|...msg| dict.do({|peer| peer.addr.sendMsg(*msg); }); }

	sendAllBundle {|time ...msg| dict.do({|peer| peer.addr.sendBundle(time, *msg); }); }

	sendExcludingId {|id ...msg| dict.reject({|peer, peerId| peerId == id }).postln.do({|peer| peer.addr.sendMsg(*msg); });}

	sendExcludingName {|name ...msg| dict.reject({|peer, peerName| peerName == name }).do({|peer| peer.addr.sendMsg(*msg); });}

	add {|peer|
		dict[peer.id] = peer;
		peer.addDependant(this);
		this.changed(\add, peer);
		peer.online !? { if (peer.online) { this.changed(\cameOnline, peer) }; };
		if (peer.name.notNil) { this.changed(\registeredName, peer) };
		if (peer.lastResponse.notNil) { this.changed(\updatedLastResponseTime, peer) };
	}

	remove {|peer|
		dict[peer.id] = nil;
		peer.removeDependant(this);
		peer.online !? { if (peer.online) { this.changed(\wentOffline, peer) }; };
		if (peer.name.notNil) { this.changed(\deregisteredName, peer) };
		this.changed(\remove, peer)
	}

	// removed addMe method for now

	at {|id| ^dict[id] } // if id doesn't exist, warn rather than throwing error

	atId {|id| ^this.at(id) }

	atName {|name| ^dict.values.detect({|peer| peer.name == name }) } // if name doesn't exist, warn rather than throwing error

	removeAt {|id| this.remove(dict[id]) }

	removeAtId {|id| this.removeAt(id) }

	removeAtName {|name| var peerToRemove; peerToRemove = this.atName(name); this.removeAt(peerToRemove.id) }

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

	takePeerOnline {|id|
		var peer;
		peer = this.at(id);
		peer.online = true;
		this.changed(\cameOnline, peer);
	}

	takePeerOffline {|id|
		var peer;
		peer = this.at(id);
		peer.online = false;
		this.changed(\wentOffline, peer);
	}

	updatePeerName {|id, name|
		var peer;
		peer = this.at(id);
		peer.name = name;
		this.changed(\registeredName, peer);
	}

	clearPeerName {|id|
		var peer;
		peer = this.at(id);
		peer.name = nil;
		this.changed(\deregisteredName, peer);
	}

	updatePeerLastResponse {|id, time|
		var peer;
		peer = this.at(id);
		peer.lastResponse = time;
		this.changed(\updatedLastResponseTime, peer);
	}

}


NMLAddrBookReporter {

	var addrBook, dependancyFunc;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		dependancyFunc = {arg addrBook, what, peer;
			case
			{ what == \add } {
				inform("% added".format(peer))
			}
			{ what == \remove } {
				inform("% removed".format(peer))
			}
			{ what == \cameOnline} {
				inform("% came online".format(peer))
			}
			{ what == \wentOffline } {
				inform("% went offline".format(peer))
			}
			{ what == \registeredName } {
				inform("% registered".format(peer))
			}
			{ what == \deregisteredName } {
				inform("% deregistered".format(peer))
			}
			{ what == \updatedLastResponseTime } {
				inform("% updated last response time".format(peer))
			};
		};
		addrBook.addDependant(dependancyFunc)
	}

	decommission {
		addrBook.removeDependant(dependancyFunc);
		addrBook = nil;
	}

}

NMLAddrBookGUI {

	// a gui showing a list of currently online peers
	// TODO: identify self in address book with different colour
	// better defer approach

	var addrBook, <peerRows, mainView, guiDict;
	var rowWidth;

	*new {arg addrBook;
		^super.newCopyArgs(addrBook).init;
	}

	init {
		guiDict = IdentityDictionary.new;
		this.initAddrBookDep;
		^this.makeMainView;
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
		mainView.layout_(VLayout(*[this.makeTitleRow, peerRows, nil])
			.margins_(0)
			.spacing_(0)
		);
		^mainView
	}

	makeTitleRow {
		^View().layout_(HLayout(*[
			StaticText().fixedWidth_(rowWidth).string_("ID:"),
			StaticText().fixedWidth_(rowWidth).string_("Name:"),
			StaticText().fixedWidth_(rowWidth).string_("IP:"),
			StaticText().fixedWidth_(rowWidth).string_("Port:"),
			StaticText().fixedWidth_(rowWidth).string_("Response:")
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	makePeerRow {arg peer;
		^View().layout_(HLayout(*[
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.id),
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.name),
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.addr.ip),
			StaticText().background_(Color.grey).fixedWidth_(rowWidth).string_(peer.addr.port),
			Button().fixedWidth_(rowWidth).states_([[nil, nil, Color.black()], [nil, nil, Color.magenta()]])
		])
		.margins_(3)
		.spacing_(3)
		)
	}

	initAddrBookDep {
		addrBook.addDependant({arg addrBook, what, peer;
			defer { // deferring the whole damn thing, for now!

				case
				{ what == \add } {
					this.addRow(peer);
				}
				{ what == \remove } {
					this.removeRow(peer);
				}
				{ what == \cameOnline} {
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \wentOffline } {
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \registeredName } {
					this.setName(peer);
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \deregisteredName } {
					this.setName(peer);
					this.setBackgroundAccordingToOnlineRegisteredState(peer);
				}
				{ what == \updatedLastResponseTime } {
					this.flashRowButton(peer);
				};
			}
		})
	}

	setBackgroundAccordingToOnlineRegisteredState {arg peer;
		case
		{ peer.online && peer.name.isNil } { this.setRowBackgroundColor(peer, Color.red); }
		{ peer.online && peer.name.notNil } { this.setRowBackgroundColor(peer, Color.green); }
		{ peer.online.not && peer.name.isNil } { this.setRowBackgroundColor(peer, Color.white); }
		{ peer.online.not && peer.name.notNil } {
			this.setRowBackgroundColor(peer, Color.white);
			warn("offline and registered, this should never happen")
		};
	}

	makeRow {
		^StaticText().background_(Color.rand);
	}

	addRow {arg peer;
		var rowToAdd;
		rowToAdd = this.makePeerRow(peer);
		guiDict.put(peer.id, rowToAdd);
		peerRows.layout.add(rowToAdd);
	}

	removeRow {arg peer;
		var rowToRemove;
		rowToRemove = guiDict.at(peer.id);
		peerRows.children.remove(rowToRemove).destroy;
	}

	setRowBackgroundColor {arg peer, color;
		var rowToSet;
		rowToSet = guiDict.at(peer.id).background_(color);
	}

	setName {arg peer;
		var rowToSet;
		rowToSet = guiDict.at(peer.id).children[1].string_(peer.name);
	}

	flashRowButton {arg peer;
		var rowToFlash;
		rowToFlash = guiDict.at(peer.id);
		Routine({
			1.do{
				rowToFlash.children[4].value_(1);
				0.1.wait;
				rowToFlash.children[4].value_(0);
			};
		}).play(AppClock);
	}

}

// implements a NetAddr that can have multiple ports...
// this is the same as in Republic, but we duplicate here for now in order to avoid the dependancy
NMLNetAddrMP : NetAddr {

        var <>ports;

        *new { arg hostname, ports;
                ports = ports.asArray;
                ^super.new(hostname, ports.first).ports_(ports)
        }

        sendRaw{ arg rawArray;
                ports.do{ |it|
                        this.port_( it );
                        ^super.sendRaw( rawArray );
                }
        }

        sendMsg { arg ... args;
                ports.do{ |it|
                        this.port_( it );
                        super.sendMsg( *args );
                }
        }

        sendBundle { arg time ... args;
                ports.do{ |it|
                        this.port_( it );
                        super.sendBundle( *([time]++args) );
                }
        }
}

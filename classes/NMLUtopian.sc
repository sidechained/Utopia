NMLUtopian {

	// a node which can be many things
	// interface to a network, with cherries on top (server)
	// and ability to add own functionality

	// Q: should server names be based on player names?
	// - if so then we have to wait until players have actually been given a name
	// - or they could be based on node id and then have some kind of dictionary to look them up

	// server should come online when local node appears in the address book i.e.
	// what == \cameOnline and who.id = the node's id

	// server should go offline when?
	// it probably shouldn't, but also a new

	// Q: is it possible to get servers to broadcast their presence
	// - or must this always be done through the lang?
	// - would be good to have langless nodes

	// adds server functionality
	// - clientID and serverPort taken from peer.id

	// general comment: some functions such as syncing rely on the address book being up to date (i.e. remote registrations) which take time to happen
	// if we put them after local server boot, then time passes, but this may not be the best solution in the long run, instead maybe there should be a shorter wait time after simple republic joining
	// it comes back down to a design issue in Hail that there is no initial blocking wait time whilst we find out who is on the network
	// a way to keep the address book updated while you're offline is to not free the inOSCFunc when leaving

	// TO DO:
	// find a way to exclude local/system synthdefs for the sync calls (maybe metadata, see approach in Republic)
	// for example, after booting: update the metadata of any existing synthdesc to contain an 'excludeFromSync = true' field
	// when syncing, any synthdescs with this field will not be sent
	// any synthdefs added after this point will not have the excludeFromSync field within them, and so will be sent

	// sharedServerAddresses is an OSCObjectSpace of synced remote addresses and should not be accessible to the user
	// servers is the user-friendly list of servers, built on top of sharedServerAdddresses and within this the local players server will always have a local address

	var topology, isRegistrar, isRegistrant, verbose, hasAddrBookGui, hasServer, seesServers, sharesSynthDefs;
	var <node, <registrar;
	var <server;
	var serverStartingPort = 10000; // 80000 doesnt work, lower numbers seem better for some reason
	var <servers, <sharedServerAddrs;
	var synthDescRelay;

	*new {arg topology = \decentralised, isRegistrar = false, isRegistrant = false, verbose = false, hasAddrBookGui = false, hasServer = true, seesServers = true, sharesSynthDefs = true;
		^super.newCopyArgs(topology, isRegistrar, isRegistrant, verbose, hasAddrBookGui, hasServer, seesServers, sharesSynthDefs).init
	}

	init {
		// add functionality based on incoming args:
		case
		{ topology == \decentralised } { node = DecentralisedNode.new(verbose: verbose, hasGui: hasAddrBookGui); }
		{ topology == \centralised } {
			if (isRegistrar) { registrar = CentralisedServer.new; };
			if (isRegistrant) { node = CentralisedClient.new(verbose: verbose, hasGui: hasAddrBookGui); };
			if (isRegistrar.not && isRegistrant.not) { warn("neither registrar or registrant"); };
		};
		if (hasServer) { this.initServerAddrBookDependencies; };
	}

	// access to implementing topology: polymorphism

	register {arg name;
		node.register(name);
	}

	deregister {
		node.deregister;
	}

	decommission {
		node.decommission;
	}

	// server functionality:

	initServerAddrBookDependencies {
		// once node's own appears in the address book
		node.addrBook.addDependant({arg addrBook, what, who;
			if (what == \cameOnline) {
				if (who.id == node.myId) {
					if (server.isNil) {
						inform("creating and booting local server");
						this.addLocalServer;
					}
					{
						inform("connecting to existing local server - not implemented");
						// what if node id has changed?
						//this.useExistingServer;
					}
				}
			}
		});
	}

	addLocalServer {
		// server is local, but remote peers use a remote address to connect to it
		// TO DO: kill (server side) and remove (lang side)

		// serverPort and clientID based on unique node id:
		server ?? {
			var serverName; // named after node id
			serverName = node.myId.asSymbol;
			server = SharedServer(serverName, NetAddr("127.0.0.1", serverStartingPort + node.myId), clientID: node.myId);
			SynthDescLib.new(serverName, server);
		};
		Server.default = server;
		//Main.interpreter.s = Server.default; // doesn't work for now
		fork {
			inform("booting local server...");
			server.boot;
			server.bootSync;
			if (seesServers) { this.initSharedServerAddrs; };
			if (sharesSynthDefs) {	this.initSynthDescRelay; };
		};
	}

	removeLocalServer {
		// not called from anywhere right now...
		server.quit; // any checking that quit occurred successfully?
		server = nil; // or free it, but that doesn't do much?
		if (seesServers) { this.decommissionSharedServerAddrs; };
	}

	// shared server addresses:

	initSharedServerAddrs {
		// the serverAddrs objectspace needs to be created here so that my peer will receive updates from others
		// why do we need sharedServerAddrs AND servers?
		// should be rewritten so as to work out whether a server is local when addressing it
		var myRemoteAddress;
		inform("initialising shared server addresses");
		sharedServerAddrs = OSCObjectSpace(node.addrBook, node.me, oscPath: '/sharedServerAddrs');
		servers = IdentityDictionary.new;
		// shared server address dependencies:
		sharedServerAddrs.addDependant({arg objectSpace, what, key, val;
			// TODO: shouldn't we check 'what' here!?
			if (val.notNil) {
				inform("% was added to the serverAddrs objectspace".format(key.asString));
				if (key == node.myId, { // if server is remote, connect to it remotely and assume it is running:
					servers.put(key, server);
				}, {
					var remoteServer;
					("connecting to remote server" + key.asString + "(assuming it is running)").postln;
					remoteServer = SharedServer(key, val, clientID: node.myId);
					remoteServer.serverRunning = true;
					remoteServer.initTree; // init its myGroup
					servers.put(key, remoteServer);
				});
			} {
				inform("% was removed from the serverAddrs objectspace".format(key.asString));
				servers.put(key, nil);
			};
		});
		myRemoteAddress = NetAddr(node.me.addr.ip, server.addr.port);
		inform("updating shared server addresses with: % %".format(node.myId, myRemoteAddress.asString));
		sharedServerAddrs.put(node.myId, myRemoteAddress);
		sharedServerAddrs.sync; // if joining after others, this will pull in the keys and values from one of the other players
	}

	decommissionSharedServerAddrs {
		// when should this be called?
		sharedServerAddrs.put(node.myId, nil); // tidy up, remove your own server address from the space before leaving
		sharedServerAddrs.free; // or remove dependent
	}

	// shared synthdefs:
	// sharing your own is one thing, receiving others is another: should separate these processes

	initSynthDescRelay {
		inform("initialising synthDescRelay");
		synthDescRelay = SynthDescRelay(node.addrBook, node.me, libName: node.me.id.asSymbol); // could choose a libname other than global
		synthDescRelay.addDependant({|descRelay, what, desc, defcode|
			("%: Adding new SynthDef to server, called % ".format(node.me.id, desc.name)).postln;
			[desc.class, server.addr].postln;
			desc.send(server);
		});
		synthDescRelay.sync; // pull in synths from an existing peers when joining
	}

	decommissionSynthDescRelay {
		// when to do this?
		synthDescRelay.free;
	}

}


SharedServerOptions : ServerOptions {

	var <>numReservedControlBuses = 0;
	var <>numReservedAudioBuses = 0;
	var <>numReservedBuffers = 0;
	var <>numClients;

	*configArgs {
		^[
			\numOutputBusChannels, \numInputBusChannels,
			\numReservedControlBuses, \numReservedAudioBuses,
			\numReservedBuffers, \numClients
		]
	}

	*fromConfig {|... args|

		var res = this.new;
		this.configArgs.do { |key, i|
			var val = args.at(i);
			val !? { res.instVarPut(key, val) }
		};

		^res
	}

	asConfig {
		^this.class.configArgs.collect { |key| this.instVarAt(key) }
	}

}

SharedServer : Server {

	// TO MOD from Republic

	var <myGroup;
	var <buffers;

	init { | argName, argAddr, argOptions, argClientID |
		super.init(argName, argAddr, argOptions, argClientID);
		myGroup = Group.basicNew(this, 100 + argClientID);
	}

	initTree {
		nodeAllocator = NodeIDAllocator(clientID, options.initialNodeID);
		this.bind {
			"initTree % : myGroup should come back.
Others have to call initTree as well, e.g. by hitting Cmd-Period.\n".postf(name);
			this.sendMsg("/g_new", 1, 0, 0);
			this.sendMsg("/g_new", myGroup.nodeID, 1, 1);
		};
		tree.value(this);
		ServerTree.run(this);
	}

	asTarget { ^myGroup }
	asGroup { ^myGroup }
	asNodeID { ^myGroup.nodeID }

	// for now
	numClients_ { |numClients| options.numClients = numClients }
	numClients { ^options.numClients }

	newBusAllocators {
		var numControl, numAudio;
		var controlBusOffset, audioBusOffset;
		var offset = this.calcOffset;
		var n = options.numClients ? 1;

		numControl = options.numControlBusChannels div: n;
		numAudio = options.numAudioBusChannels div: n;

		controlBusOffset = options.numReservedControlBuses + (numControl * offset);
		audioBusOffset = options.firstPrivateBus + options.numReservedAudioBuses
		+ (numAudio * offset);

		controlBusAllocator =
		ContiguousBlockAllocator.new(
			numControl + controlBusOffset,
			controlBusOffset
		);
		audioBusAllocator =
		ContiguousBlockAllocator.new(
			numAudio + audioBusOffset,
			audioBusOffset
		);
		"SharedServer % audio buses: % control buses %\n"
		.postf(name, numAudio, numControl);
	}

	newBufferAllocators {
		var bufferOffset;
		var offset = this.calcOffset;
		var n = options.numClients ? 1;
		var numBuffers = options.numBuffers div: n;
		bufferOffset = options.numReservedBuffers + (numBuffers * offset);
		bufferAllocator =
		ContiguousBlockAllocator.new(
			numBuffers + bufferOffset,
			bufferOffset
		);
		"SharedServer % buffers: %\n".postf(name, numBuffers);
	}

	calcOffset {
		if(options.numClients.isNil) { ^0 };
		if(clientID > options.numClients) {
			"Some buses and buffers may overlap for remote server: %".format(this).warn;
		};
		^clientID % options.numClients;
	}

	myOuts {
		var numEach = options.numOutputBusChannels div: options.numClients;
		^(0 .. (numEach - 1)) + (numEach * clientID);
	}

	freeAll { |hardFree = false|
		if (hardFree) {
			super.freeAll(false);
		} {
			myGroup.freeAll;
		}
	}

	getBuffers { |action|
		var dur = (options.numBuffers * options.blockSize / sampleRate * 5);
		var newbuffers = Array(32);
		var resp = OSCresponder(nil, 'bufscan', { |time, resp, msg|
			var bufnum, frames, chans, srate;
			#bufnum, frames, chans, srate = msg.keep(-4);
			if (chans > 0) {
				newbuffers = newbuffers.add(
					Buffer(this, frames, chans, srate, bufnum: bufnum))
			};
		}).add;

		{
			var bufnum = Line.kr(0, options.numBuffers, dur, doneAction: 2).round(1);
			var trig = HPZ1.kr(bufnum);
			SendReply.kr(trig, 'bufscan',
				[
					bufnum,
					BufFrames.kr(bufnum),
					BufChannels.kr(bufnum),
					BufSampleRate.kr(bufnum)
			]);
		}.play(this);

		fork {
			(dur + 0.5).wait;
			resp.remove;
			buffers = newbuffers;
			(action ? { |bufs|
				"\t SharedServer - found these buffers: ".postln; bufs.printAll
			}).value(buffers);
		}
	}
}




+ ServerOptions {

	numReservedControlBuses { ^0 }
	numReservedAudioBuses { ^0 }
	numReservedBuffers { ^0 }
	numClients { ^nil }

}



+ Server {

	remove {
		Server.all.remove(this);
		Server.named.removeAt(name);
		SynthDescLib.global.removeServer(this);
		try { this.window.close };
	}

	nodeAllocator_ { |allocator|
		nodeAllocator = allocator
	}

	controlBusAllocator_ { |allocator|
		controlBusAllocator = allocator
	}

	audioBusAllocator_ { |allocator|
		audioBusAllocator = allocator
	}

	bufferAllocator_ { |allocator|
		bufferAllocator = allocator
	}

}



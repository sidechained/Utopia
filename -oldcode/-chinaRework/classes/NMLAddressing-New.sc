// not sure about addMe business...
// registrar uses loopback option immediately so address changes once registered; does this matter
// might be nice if addition to the AddrBook only took place once registered

// uses dependancy to update interested parties
Peer {
	var <name, <addr, <online;

	*new {|name, addr, online = true|
		^super.newCopyArgs(name.asSymbol, addr, online);
	}

	online_ {|bool| if(bool != online, { online = bool; this.changed(\online) }) }

	== {|other|
		var result;
		result = (name == other.name) && (addr == other.addr) && (online == other.online);
		^result;
	}

	hash {
		^this.instVarHash(#[\name, \addr, \online])
	}

	// post pretty
	printOn { |stream|
		stream << this.class.name << "(" <<* [name, addr, online] << ")"
	}
}

AddrBook {
	var dict, <me;

	*new { ^super.new.init }

	init { dict = IdentityDictionary.new; }

	send {|name ...msg| dict[name].addr.sendMsg(*msg) }

	sendAll {|...msg| dict.do({|peer| peer.addr.sendMsg(*msg); }); }

	sendAllBundle {|time ...msg| dict.do({|peer| peer.addr.sendBundle(time, *msg); }); }

	sendExcluding {|name ...msg| dict.reject({|peer, peerName| peerName == name }).do({|peer| peer.addr.sendMsg(*msg); });}

	add {|peer| dict[peer.name] = peer; peer.addDependant(this); this.changed(\add, peer) }

	addMe {|mePeer|
		mePeer = mePeer ?? {
			var name;
			name = "whoami".unixCmdGetStdOut;
			if(name.last == Char.nl, {name = name.drop(-1)});
			Peer(name, NetAddr.localAddr)};
		this.add(mePeer);
		me = mePeer;
	}

	at {|name| ^dict[name] }

	remove {|peer| dict[peer.name] = nil; peer.removeDependant(this); this.changed(\remove, peer) }

	removeAt {|name| this.remove(dict[name]) }

	update {|changed, what| this.changed(what, changed) }

	names { ^dict.keys }

	addrs { ^dict.values.collect({|peer| peer.addr }) }

	peers { ^dict.values }

	onlinePeers { ^dict.reject({|peer| peer.online.not }).values }
}

// who's there?
// my new version
Hail {

	classvar startingPort = 9000;
	var <addrBook, period, oscPath, authenticator, me;
	var replyPath; // add this above later, but make sure it doesn't conflict with newCopyArgs
	var myName, myPort, portsInUse, portAssigned;
	// choose me or myname, probably not both
	var hailResponder, hailReplyResponder;
	var hailingSignal;
	var lastResponses;

	*new { |addrBook, period = 1.0, me, authenticator, oscPath|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, period, oscPath, authenticator).init(me);
	}

	// not totally sure about this me business...
	init {|argMe|
		if(argMe.notNil, {addrBook.addMe(argMe)}, { if(addrBook.me.isNil, {addrBook.addMe }) });
		oscPath = '/hail';
		replyPath = (oscPath ++ "-reply").asSymbol;
		me = addrBook.me;
		myName = me.name;
		portsInUse = [];
		portAssigned = false;
		lastResponses = IdentityDictionary.new;
		authenticator = authenticator ?? { NonAuthenticator };
		this.listenForHails;
	}

	listenForHails {
		this.initHailResponder;
		Routine({
			(myName.asString + "waiting for incoming hail messages:").postln;
			(period * 2).wait;
			this.determineMyPort;
			(myName.asString + "was assigned port:" + myPort.asString).postln;
			this.initHailReplyResponder; // now my port has been assigned, set up to receive hail replies on it
			// other funcs go here
			this.startHailingSignal;
		}).play
	}

	initHailResponder {
		hailResponder = OSCFunc({arg msg, time, senderAddr;
			var senderName, senderPort;
			senderName = msg[1];
			senderPort = msg[2];
			// (myName.asString + "received:" + msg.asString).postln;
			if (myPort.isNil, { // if my port hasn't yet been assigned
				this.collectPortsInUse(senderPort);
			}, {
				this.sendHailReply(senderAddr.ip, senderPort);
			});
		}, oscPath, recvPort: 57120).fix; // which port here?
	}

	sendHailReply {arg senderIP, senderPort;
		var targetAddr;
		targetAddr = NetAddr(senderIP, senderPort);
		// (myName.asString + "sent:" + '/hail-reply'.asString + myName + myPort.asString).postln;
		targetAddr.sendMsg(replyPath, myName, myPort);
	}

	initHailReplyResponder {
		hailReplyResponder = OSCFunc({arg msg, time, senderAddr;
			// senderAddr passed by OSCFunc always uses default SC port, so need to reconstitute an address using senderAddr & senderPort
			var senderName, senderPort, reconstitutedSenderNetAddr;
			// (myName.asString + "received:" + msg.asString).postln;
			senderName = msg[1];
			senderPort = msg[2];
			reconstitutedSenderNetAddr = NetAddr(senderAddr.ip, senderPort);
			this.addOrUpdatePeer(senderName, reconstitutedSenderNetAddr, time);
		}, replyPath, recvPort: myPort).fix //  recvPort: addrBook.me.addr.port
	}

	addOrUpdatePeer {arg name, netAddr, time;
		var peer;
		if(lastResponses[name].isNil, {
			peer = Peer(name, netAddr);
			// (myName.asString + "added peer:" + peer.asString).postln;
			authenticator.authenticate(peer, {
				addrBook.add(peer);
				addrBook[name].online = true;
				lastResponses[name] = time;
			});
		}, {
			// (myName.asString + "updated peer:" + addrBook[name].asString).postln;
			addrBook[name].online = true;
			lastResponses[name] = time;
		});
	}

	startHailingSignal {
		hailingSignal = Routine({
			inf.do{
				this.sendHail;
				if(period.notNil, { this.checkOnline; });
				period.wait;
			};
		}).play(SystemClock)
	}

	sendHail {
		var broadcastAddr;
		//NetAddr.broadcastFlag = true;
		//broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		broadcastAddr = NetAddr("localhost", 57120); // using localhost to test for now instead of broadcasting
		// (myName.asString + "sent:" + '/hail'.asString + myName + myPort.asString).postln;
		broadcastAddr.sendMsg(oscPath, myName, myPort);
	}

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		lastResponses.keysValuesDo({|name, lastHeardFrom|
			var notHeardFrom, nameExists;
			notHeardFrom = (now - lastHeardFrom) > (period * 2);
			nameExists = addrBook[name].notNil;
			if(notHeardFrom && nameExists, {
				// addrBook[name].online = false
				// ("removing " + name).postln;
				addrBook.removeAt(name) // changed approach so that peers are removed, not just set to offline...
				// ...(otherwise old peers persist with outdated port)
			});
		});
	}

	collectPortsInUse {arg senderPort;
		portsInUse = portsInUse.add(senderPort);
	}

	determineMyPort {
		if (portsInUse.isEmpty, {
			// \empty.postln;
			myPort = startingPort;
		}, {
			// \notEmpty.postln;
			myPort = portsInUse.maxItem + 1;
		});
		portAssigned = true;
	}

	free {
		hailResponder.free;
		hailReplyResponder.free;
		hailingSignal.stop;
		addrBook.removeAt(myName);
	}

}

// Centralised
Registrar {
	var <addrBook, period, authenticator, oscPath, lastResponses, pingRegistrarOSCFunc, registerOSCFunc, unRegisterOSCFunc, pingReplyOSCFunc;

	*new { |addrBook, period = 1.0, authenticator, oscPath = '/register'|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, period, authenticator, oscPath).init;
	}

	init {
		lastResponses = IdentityDictionary.new;
		authenticator = authenticator ?? { NonAuthenticator };
		this.makeOSCFuncs;
		period.notNil.if({ this.ping; });
	}

	makePeer {|addr, name|
		^Peer(name, addr);
	}

	makeOSCFuncs {
		// people are looking for me
		pingRegistrarOSCFunc = OSCFunc({|msg, time, addr|
			addr.sendMsg(oscPath ++ "-pingRegistrarReply"); // I'm here!
		}, oscPath ++ "-pingRegistrar").fix;

		registerOSCFunc = OSCFunc({|msg, time, addr|
			var peer;
			peer = this.makePeer(addr, msg[1]);
			authenticator.authenticate(peer, {
				// tell everyone about the new arrival
				addrBook.sendAll(oscPath ++ "-add", peer.name, addr.ip, addr.port);
				// tell the new arrival about everyone
				addrBook.peers.do({|peer|
					addr.sendMsg(oscPath ++ "-add", peer.name, peer.addr.ip, peer.addr.port);
				});
				addrBook.add(peer);
			});
		}, oscPath).fix;

		unRegisterOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			addrBook.removeAt(name);
			lastResponses[name] = nil;
			addrBook.sendExcluding(name, oscPath ++ "-remove", name);
		}, oscPath ++ "-unregister").fix;

		// make sure everyone is still online
		pingReplyOSCFunc = OSCFunc({|msg, time, addr|
			var name, peer;
			name = msg[1];
			peer = addrBook[name];
			peer.notNil.if({
				peer.online_(true);
				lastResponses[name] = time;
				addrBook.sendAll(oscPath ++ "-online", name, true.binaryValue);
			});
		}, oscPath ++ "-pingReply").fix;
	}

	free { pingRegistrarOSCFunc.free; registerOSCFunc.free; unRegisterOSCFunc.free; pingReplyOSCFunc.free }

	ping {
		SystemClock.sched(0, {
			addrBook.sendAll(oscPath ++ "-ping");
			this.checkOnline;
			period;
		});
	}

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		lastResponses.keysValuesDo({|name, lastHeardFrom|
			if((now - lastHeardFrom) > (period * 2), {
				addrBook[name].online = false;
				addrBook.sendAll(oscPath ++ "-online", name, false.binaryValue);
			});
		});
	}

}

Registrant {
	var <addrBook, registrarAddr, authenticator, oscPath, me, addOSCFunc, removeOSCFunc, onlineOSCFunc, pingOSCFunc, pinging;

	// we pass an authenticator here but maybe it's unnecessary. It's simply there to respond, not challenge in this case.
	*new { |addrBook, me, registrarAddr, authenticator, oscPath = '/register'|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, registrarAddr, authenticator, oscPath).init(me);
	}

	// not totally sure about this me business...
	init {|argMe|
		if(argMe.notNil, {addrBook.addMe(argMe)}, { if(addrBook.me.isNil, {addrBook.addMe }) });
		me = addrBook.me;
		this.addOSCFuncs;
		if(registrarAddr.isNil, { this.pingRegistrar }, { this.register });
	}

	makePeer {|name, hostname, port|
		^Peer(name, NetAddr(hostname.asString, port));
	}

	addOSCFuncs {
		addOSCFunc = OSCFunc({|msg, time, addr|
			var peer;
			peer = this.makePeer(*msg[1..]);
			addrBook.add(peer);
		}, oscPath ++ "-add", registrarAddr, recvPort: addrBook.me.addr.port).fix;

		removeOSCFunc = OSCFunc({|msg, time, addr|
			var name;
			name = msg[1];
			addrBook.removeAt(name);
		}, oscPath ++ "-remove", registrarAddr, recvPort: addrBook.me.addr.port).fix;

		onlineOSCFunc = OSCFunc({|msg, time, addr|
			var name, peer;
			name = msg[1];
			peer = addrBook[name];
			peer.notNil.if({ peer.online_(msg[2].booleanValue) });
		}, oscPath ++ "-online", registrarAddr, recvPort: addrBook.me.addr.port).fix;

		pingOSCFunc = OSCFunc({|msg, time, addr|
			registrarAddr.sendMsg(oscPath ++ "-pingReply", me.name);
		}, oscPath ++ "-ping", registrarAddr, recvPort: addrBook.me.addr.port).fix;
	}

	free { pinging = false; this.unregister; addOSCFunc.free; removeOSCFunc.free; onlineOSCFunc.free; pingOSCFunc.free; }

	register {
		registrarAddr.sendMsg(oscPath, me.name);
	}

	unregister {
		registrarAddr.sendMsg(oscPath ++ "-unregister", me.name);
	}

	// automatically search for registrar...
	pingRegistrar {
		var broadcastAddr, registrarPingOSCFunc;
		pinging = true;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		registrarPingOSCFunc = OSCFunc({|msg, time, addr|
			pinging = false;
			registrarAddr = addr;
			this.register;
		}, oscPath ++ "-pingRegistrarReply", recvPort: addrBook.me.addr.port).oneShot;

		{
			while( { pinging }, {
				broadcastAddr.sendMsg(oscPath ++ "-pingRegistrar");
				1.wait;
			});
		}.fork;
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


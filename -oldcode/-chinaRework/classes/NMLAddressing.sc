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
Hail {
	var <addrBook, period, oscPath, authenticator, me, inOSCFunc, outOSCFunc, lastResponses;

	*new { |addrBook, period = 1.0, me, authenticator, oscPath = '/hail'|
		addrBook = addrBook ?? { AddrBook.new };
		^super.newCopyArgs(addrBook, period, oscPath, authenticator).init(me);
	}

	// not totally sure about this me business...
	init {|argMe|
		if(argMe.notNil, {addrBook.addMe(argMe)}, { if(addrBook.me.isNil, {addrBook.addMe }) });
		me = addrBook.me;
		lastResponses = IdentityDictionary.new;
		authenticator = authenticator ?? { NonAuthenticator };
		this.makeOSCFuncs;
		this.hailingSignal;
	}

	makeOSCFuncs {
		var replyPath;
		replyPath = (oscPath ++ "-reply").asSymbol;
		inOSCFunc = OSCFunc({|msg, time, addr, recvPort|
			var senderPeerName, senderPort; // MOD
			senderPeerName = msg[1];
			senderPort = msg[2];
			if (recvPort == me.addr.port, {
				// filter out misdirected messages that occur locally when two peers use the same ip
				// each players port should be unique
				("'hail reply" + senderPeerName + senderPort ++ "' received by:" + me.name + "on port" + recvPort).postln;
				if(lastResponses[senderPeerName].isNil, {
					var reconstitutedAddress, newPeer;
					reconstitutedAddress = NetAddr(addr.ip, senderPort);
					newPeer = Peer(senderPeerName, reconstitutedAddress);
					authenticator.authenticate(newPeer, {
						("adding peer" + newPeer.name.asString + newPeer.addr.port.asString + "to the address book of" + me.name).postln;
						addrBook.add(newPeer);
						addrBook[senderPeerName].online = true;
						lastResponses[senderPeerName] = time;
					});
				}, {
					addrBook[senderPeerName].online = true;
					lastResponses[senderPeerName] = time;
				});
			});
		}, replyPath, recvPort: addrBook.me.addr.port).fix; // only receive hail-replies on my own port (this should be unique in local situations)

		outOSCFunc = OSCFunc({|msg, time, addr, recvPort|
			var targetPeerPort, senderPeerName, senderPeerPort, targetAddr; // MOD
			targetPeerPort = msg[1]; // MOD
			senderPeerName = me.name; // MOD
			senderPeerPort = me.addr.port; // MOD
			("'hail" + targetPeerPort.asString ++ "' received by:" + senderPeerName.asString + "on port" + recvPort.asString).postln;
			("sending 'hail-reply" + senderPeerName ++ "' back to sender on port" + targetPeerPort).postln;
			targetAddr = NetAddr(addr.ip, targetPeerPort);
			targetAddr.sendMsg(replyPath, senderPeerName, senderPeerPort); // MOD
		}, oscPath).fix; // removed ", recvPort: addrBook.me.addr.port" as me.addr.port may not be 57120
	}

	free { inOSCFunc.free; outOSCFunc.free; }

	hailingSignal {
		var broadcastAddr;
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		SystemClock.sched(0, {
			broadcastAddr.sendMsg(oscPath, me.addr.port); // me should always exist
			("'hail" + me.addr.port ++ "' sent by:" + me.name.asString + "on ports 57120 + (0..7)").postln;
			if(period.notNil, { this.checkOnline; });
			period;
		});
	}

	// everybody still there?
	checkOnline {
		var now;
		now = Main.elapsedTime;
		lastResponses.keysValuesDo({|name, lastHeardFrom|
			if((now - lastHeardFrom) > (period * 2), { addrBook[name].online = false });
		});
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


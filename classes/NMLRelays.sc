// for history, etc.
CodeRelay {
	var addrBook, <>post, oscPath, encryptor, codeDumpFunc, oscFunc;

	*new {|addrBook, post = false, oscPath = '/codeRelay', encryptor, codeDumpFunc|
		^super.newCopyArgs(addrBook, post, oscPath, encryptor, codeDumpFunc).init;
	}

	init {
		var interpreter;
		encryptor = encryptor ?? { NonEncryptor }; // NonEncryptor uses noops
		codeDumpFunc = codeDumpFunc ? { |code|
			addrBook.sendAll(oscPath, addrBook.me.name, encryptor.encryptText(code));
		};
		interpreter = thisProcess.interpreter;
		interpreter.codeDump = interpreter.codeDump.addFunc(codeDumpFunc);
		this.makeOSCFunc;
	}

	makeOSCFunc {
		oscFunc = OSCFunc({|msg, time, addr|
			var name, code;
			if(addrBook.addrs.includesEqual(addr), {
				name = msg[1];
				code = encryptor.decryptText(msg[2]);
				this.changed(\code, name, code);
				if(post, {
					(name.asString ++ ":\n" ++ code).postln;
					Char.nl.post;
				});
			}, {"CodeRelay access attempt from unrecognised addr: %\n".format(addr).warn;});
		}, oscPath, recvPort: addrBook.me.addr.port).fix;
	}

	free {
		var interpreter;
		oscFunc.free;
		interpreter = thisProcess.interpreter;
		interpreter.codeDump = interpreter.codeDump.removeFunc(codeDumpFunc);
	}
}

// This now uses binaryArchive for safety reasons, as this avoids the use of the interpreter
// However, this could cause problems if you send to someone with a different version of SC
// Possibly using the textArchive should be an option
SynthDescRelay {

	// DONE: added sync functionality
	// TODO:

	var addrBook, mePeer, oscPath, libName, encryptor, lib, oscFunc, syncRequestOSCFunc;
	var justAddedRemote = false;

	*new {|addrBook, mePeer, oscPath = '/synthDefRelay', libName = \global, encryptor|
		^super.newCopyArgs(addrBook, mePeer, oscPath, libName).init;
	}

	init {
		lib = SynthDescLib.getLib(libName);
		lib.addDependant(this);
		encryptor = encryptor ?? { NonEncryptor }; // NonEncryptor uses noops
		this.makeOSCFunc;
		this.makeSyncRequestOSCFunc; // MOD
	}

	makeOSCFunc {
		oscFunc = OSCFunc({|msg, time, addr|
			var defCompileString, defBytes, port, stream;
			# defCompileString, defBytes, port = msg.drop(1);
			if(addrBook.addrs.includesEqual(NetAddr(addr.ip, port)), {
				var desc, defcode;
				stream = CollStream(encryptor.decryptBytes(defBytes));
				stream.getInt32; // 'SCgf'
				stream.getInt32; // version
				stream.getInt16; // 1
				desc = SynthDesc.new.readSynthDef2(stream, true);
				defcode = encryptor.decryptText(defCompileString);
				if(desc.isKindOf(SynthDesc), { // check for safety
					justAddedRemote = true;
					lib.add(desc);
					this.changed(\synthDesc, desc, defcode.asString);
				}, { "SynthDescRelay received non-SynthDesc object: %. Object discarded".format(desc).warn; });
			}, {"SynthDescRelay access attempt from unrecognised addr: %\n".format(addr).warn;});
		}, oscPath, recvPort: mePeer.addr.port).fix;
	}

	free {
		oscFunc.free;
		lib.removeDependant(this);
	}

	update {|changed, what ...moreArgs|
		this.updateFromLib(what, *moreArgs);
	}

	updateFromLib {|what ...moreArgs|
		switch(what,
			\synthDescAdded, {
				// If we've just received one from somebody else, don't let that trigger another send and a never ending loop
				if(justAddedRemote.not, {
					var desc, def;
					desc = moreArgs[0];
					if(desc.isKindOf(SynthDesc), { // check for safety
						def = desc.def;
						addrBook.sendExcludingId(mePeer.id, oscPath, encryptor.encryptText(def.asCompileString), encryptor.encryptBytes(def.asBytes), mePeer.addr.port);
					}, { "SynthDescRelay updated with non-SynthDesc object: %".format(desc).warn; });
				}, { justAddedRemote = false });
			}
		)
	}

	sync {|addr|
		var syncAddr;
		// look for the first online one who's not me
		var firstOnlinePeer = addrBook.peers.reject{|peer| peer == mePeer }.detect{|peer| peer.online };
		if (firstOnlinePeer.notNil, {
			syncAddr = addr ?? { firstOnlinePeer.addr };
			syncAddr.sendMsg(oscPath ++ "-sync", mePeer.addr.port);
		});
	}

	makeSyncRequestOSCFunc {
		// sends back to the existing oscFunc i.e. see makeOSCFunc above
		syncRequestOSCFunc = OSCFunc({|msg, time, addr|
			var port, includedSynthDescs, msgToSend;
			# port = msg.drop(1);
			// only send synthDescs which are not excluded from sync:
			includedSynthDescs = SynthDescLib.all.at(mePeer.id.asSymbol).synthDescs;
			// includedSynthDescs = SynthDescLib.global.synthDescs.reject{arg desc; desc.metadata == \excludedFromSync};
			includedSynthDescs.do{arg desc;
				var def;
				def = desc.def;
				msgToSend = [oscPath, encryptor.encryptText(def.asCompileString), encryptor.encryptBytes(def.asBytes), mePeer.addr.port];
				NetAddr(addr.ip, port).sendMsg(*msgToSend);
			};
		}, oscPath ++ "-sync", recvPort: mePeer.addr.port).fix;
	}

}

// shared network dataspaces

AbstractOSCDataSpace {
	var addrBook, mePeer, oscPath, oscFunc, syncRecOSCFunc, syncRequestOSCFunc, dict;

	init {|argAddrBook, argMePeer, argOSCPath|
		addrBook = argAddrBook;
		mePeer = argMePeer;
		oscPath = argOSCPath;
		dict = IdentityDictionary.new;
		this.makeSyncRequestOSCFunc;
		this.makeOSCFunc;
	}

	put {|key, value| dict[key] = value; this.changed(\val, key, value); this.updatePeers(key, value);}

	at {|key| ^dict[key] }

	keys { ^dict.keys }

	values { ^dict.values }

	makeOSCFunc { this.subclassResponsibility }

	makeSyncRequestOSCFunc {
		syncRequestOSCFunc = OSCFunc({|msg, time, addr|
			var port, pairs;
			# port = msg.drop(1);
			pairs = this.getPairs;
			NetAddr(addr.ip, port).sendMsg(*([oscPath ++ "-sync-reply"] ++ pairs));
		}, oscPath ++ "-sync", recvPort: mePeer.addr.port).fix;
	}

	getPairs { this.subclassResponsibility }

	updatePeers {|key, value| this.subclassResponsibility }

	free { oscFunc.free; syncRecOSCFunc.free; }

	sync {|addr| this.subclassResponsibility }
}

OSCDataSpace : AbstractOSCDataSpace {

	*new {|addrBook, oscPath = '/oscDataSpace'|
		^super.new.init(addrBook, oscPath);
	}

	makeOSCFunc {
		oscFunc = OSCFunc({|msg, time, addr|
			var key, val;
			if(addrBook.addrs.includesEqual(addr), {
				key = msg[1];
				val = msg[2];
				dict[key] = val;
				this.changed(\val, key, val);
			}, {"OSCDataSpace access attempt from unrecognised addr: %\n".format(addr).warn;});
		}, oscPath, recvPort: addrBook.me.addr.port).fix;
	}

	getPairs { ^dict.getPairs }

	updatePeers {|key, value| addrBook.sendExcluding(addrBook.me.name, oscPath, key, value); }

	sync {|addr|
		var syncAddr;
		syncAddr = addr ?? { addrBook.peers.reject({|peer| peer == addrBook.me }).detect({|peer| peer.online }).addr }; // look for the first online one who's not me
		syncAddr.notNil.if({
			syncRecOSCFunc = OSCFunc({|msg, time, addr|
				var pairs;
				pairs = msg[1..];
				pairs.pairsDo({|key, val|
					if(dict[key] != val, {
						dict[key] = val;
						this.changed(\val, key, val);
					});
				});
			}, oscPath ++ "-sync-reply", syncAddr).oneShot;
			syncAddr.sendMsg(oscPath ++ "-sync");
		});
	}
}

// the following represents a security risk, since people could use a pseudo-object to inject undesirable code
// It is thus best used on a secure network with trusted peers, or with an authenticated addrBook (e.g. using ChallengeAuthenticator)
// and/or using password protected encryption
// It also has the option to reject instances of Event and subclasses (rejects by default)
// This currenly uses binaryArchive for safety reasons, as this avoids the use of the interpreter, which could execute arbitrary code
// However, this could cause problems if you send to someone with a different version of SC
// Possibly using the textArchive should be an option
OSCObjectSpace : AbstractOSCDataSpace {

	var <>acceptEvents, encryptor;

	*new {|addrBook, mePeer, acceptEvents = false, oscPath = '/oscObjectSpace', encryptor|
		^super.newCopyArgs.acceptEvents_(acceptEvents).init(addrBook, mePeer, oscPath, encryptor);
	}

	init {|argAddrBook, argMePeer, argOSCPath, argEncryptor|
		encryptor = argEncryptor ?? { NonEncryptor }; // NonEncryptor uses noops
		super.init(argAddrBook, argMePeer, argOSCPath);
	}

	makeOSCFunc {
		oscFunc = OSCFunc({|msg, time, addr, recvPort|
			// recvPort is the wrong thing to use here - what we need is the sender's port
			// probably have to provide this explicitly, as not given by address
			var key, val, port;
			# key, val, port = msg.drop(1);
			if(addrBook.addrs.includesEqual(NetAddr(addr.ip, port)), {
				val = encryptor.decryptBytes(val).unarchive;
				if(acceptEvents || val.isKindOf(Event).not, {
					dict[key] = val;
					this.changed(\val, key, val);
				}, { "OSCObjectSpace rejected event % from addr: %\n".format(val, addr).warn; });
			}, {"OSCObjectSpace access attempt from unrecognised addr: %\n".format(addr).warn;});
		}, oscPath, recvPort: mePeer.addr.port).fix;
	}

	getPairs { ^dict.asSortedArray.collect({|pair| [pair[0], encryptor.encryptBytes(pair[1].asBinaryArchive)]}).flatten }

	updatePeers {|key, value|
		addrBook.sendExcludingId(mePeer.id, oscPath, key, encryptor.encryptBytes(value.asBinaryArchive), mePeer.addr.port);
	}

	sync {|peerToSyncWith|
		if (peerToSyncWith.isNil) {
			// look for the first online one who's not me:
			var peersExcludingSelf;
			peersExcludingSelf = addrBook.peers.reject({|peer| peer == mePeer });
			if (peersExcludingSelf.notEmpty) {
				peerToSyncWith = peersExcludingSelf.detect({|peer| peer.online})
			};
		};
		peerToSyncWith.notNil.if({
			syncRecOSCFunc = OSCFunc({|msg, time, addr|
				var pairs;
				pairs = msg[1..];
				pairs.pairsDo({|key, val|
					val = encryptor.decryptBytes(val).unarchive;
					if(dict[key] != val, {
						dict[key] = val;
						this.changed(\val, key, val);
					});
				});
			}, oscPath ++ "-sync-reply", recvPort: peerToSyncWith.addr.port).oneShot;
			peerToSyncWith.addr.sendMsg(oscPath ++ "-sync", peerToSyncWith.addr.port);
		});
	}

	put {|key, value|
		value.checkCanArchive; // at least this warns
		if(acceptEvents || value.isKindOf(Event).not, {
			super.put(key, value);
		}, { "OSCObjectSpace rejected event %\n".format(value).warn; });
	}

}
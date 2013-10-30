BasicHail {

	// local prototype for revisions that I intend to make to hail
	// easy to grasp, tested and works as desired

	// joins automatically on instantiation
	// to leave, call free on the instance

	classvar startingPort = 9000;
	var <addrBook, myName, myPort, portsInUse, period = 1;
	var hailResponder, hailReplyResponder;

	*new {arg argMyName;
		^super.new.init(argMyName);
	}

	init {arg argMyName;
		Routine({
			myName = argMyName;
			addrBook = AddrBook.new;
			portsInUse = [];
			this.initHailResponder;
			//(myName.asString + "waiting for incoming hail messages:").postln;
			(period * 2).wait;
			this.assignMyPort;
			//(myName.asString + "was assigned port:" + myPort.asString).postln;
			this.initHailReplyResponder; // now my port has been assigned, set up to receive hail replies on it
			this.sendHailingSignal;
		}).play
	}

	sendHailingSignal {
		Routine({
			inf.do{
				this.sendHail;
				period.wait;
			};
		}).play(SystemClock)
	}

	sendHail {
		var broadcastAddr;
		//NetAddr.broadcastFlag = true;
		//broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
		broadcastAddr = NetAddr("localhost", 57120);
		// (myName.asString + "sent:" + '/hail'.asString + myName + myPort.asString).postln;
		broadcastAddr.sendMsg('/hail', myName, myPort);
	}

	initHailResponder {
		hailResponder = OSCFunc({arg msg;
			var senderName, senderPort;
			senderName = msg[1];
			senderPort = msg[2];
			// (myName.asString + "received:" + msg.asString).postln;
			if (myPort.isNil, { // if my port hasn't yet been assigned
				this.collectPortsInUse(senderPort);
			}, {
				this.sendHailReply(senderPort);
			});
		}, '/hail', recvPort: 57120);
	}

	sendHailReply {arg senderPort;
		var targetAddr;
		targetAddr = NetAddr("localhost", senderPort);
		// (myName.asString + "sent:" + '/hail-reply'.asString + myName).postln;
		targetAddr.sendMsg('/hail-reply', myName);
	}

	initHailReplyResponder {
		hailReplyResponder = OSCFunc({arg msg, time, senderAddr, senderPort;
			var senderName, peerToAdd;
			// (myName.asString + "received:" + msg.asString).postln;
			senderName = msg[1];
			peerToAdd = Peer(senderName, NetAddr(senderAddr.ip, senderPort));
			// (myName.asString + "added peer:" + peerToAdd.asString).postln;
			addrBook.add(peerToAdd);
		}, '/hail-reply', recvPort: myPort)
	}

	collectPortsInUse {arg senderPort;
		portsInUse = portsInUse.add(senderPort);
	}

	assignMyPort {
		if (portsInUse.isEmpty, {
			myPort = startingPort;
		}, {
			myPort = portsInUse.maxItem + 1;
		});
	}

	free { hailResponder.free; hailReplyResponder.free; }

}
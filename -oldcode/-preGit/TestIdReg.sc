TestClient {

	var <addrBook;
	var <id, <port;
	var setIdResponder;

	*new {
		^super.new.init;
	}

	init {
		addrBook = NMLAddrBook.new;
		this.initSetIdResponder;
		this.getId;
	}

	getId {
		id = rand2(-2147483647, 2147483647);
		NetAddr.localAddr.sendMsg('/client-getId', id);
	}

	initSetIdResponder {
		setIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId, clientStartingPort;
			# tempId, permId, clientStartingPort = msg.drop(1);
			if ( id == tempId ) {
				id = permId;
				port = permId + clientStartingPort;
				[id, port].postln;
				thisProcess.openUDPPort(port);
			};
		}, '/server-setId').oneShot;
	}

	me {
		^addrBook.atId(id);
	}

}


// how and where to open up port

TestServer {

	var <addrBook;
	var clientStartingPort;
	var getIdResponder;

	*new {
		^super.new.init;
	}

	init {
		clientStartingPort = 50000;
		addrBook = NMLAddrBook.new;
		this.initGetIdResponder;
	}

	initGetIdResponder {
		getIdResponder = OSCFunc({arg msg, time, addr;
			var tempId, permId;
			# tempId = msg.drop(1);
			\here.postln;
			permId = addrBook.getNextFreeID;
			addr.sendMsg('/server-setId', tempId, permId, clientStartingPort);
		}, '/client-getId');
	}

}
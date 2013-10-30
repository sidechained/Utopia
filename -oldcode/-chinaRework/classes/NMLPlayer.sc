NMLPlayer {

	var <name, <port, <clientID, <serverPort;
	var <peer, <addrBook, <server, <serverAddrs, <remoteServers;

	*new {arg name, port = 57120, clientID = 1;
		^super.newCopyArgs(name, port, clientID).init;
	}

	init {
		this.addPlayer;
	}

	addPlayer {
		thisProcess.openUDPPort(port);
		peer = Peer(name, NetAddr("127.0.0.1", port));
		addrBook = AddrBook();
		Hail(addrBook, me: peer);
	}

	addServer {arg serverPort = 57111;
		server = Server(name, NetAddr("127.0.0.1", serverPort), clientID: clientID);
		server.waitForBoot({
			serverAddrs = OSCObjectSpace(addrBook, oscPath:'/serverAddrs');
			remoteServers = IdentityDictionary.new;
			serverAddrs.addDependant({|objectSpace, what, key, val|
				var newServer;
				if(key != addrBook.me.name, {
					(name.asString + "adding new server:" + [key, val]).postln;
					newServer = Server(key, val, clientID: clientID);
					remoteServers[key] = newServer;
					SynthDescLib.global.addServer(newServer);
				};
				)
			});
			serverAddrs.put(name, server.addr);
		})
	}

}

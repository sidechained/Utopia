DecentralisedClientServer : AbstractNode {

	// a decentralised node consisting of both a server and client
	// - the server listens for announcements from others
	// - the client announces itself to others
	// IS a concrete implementation of a NMLServer and and NMLClient

	var client;
	var funcArray;

	*new {
		^super.new.init;
	}

	init {
		clientStartingPort = 60000;
		funcArray = [{this.announcePeer(mePeer, this.initBroadcastAddr)},{this.checkPeersOnline}];
		NMLServer();
		client = NMLClient();
		super.init;
	}

	initBroadcastAddr {
		NetAddr.broadcastFlag = true;
		^NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	register {arg proposedName;
		client.register(proposedName);
	}

	deregister {
		client.deregister(proposedName);
	}

}




// NOTE: it might still make sense to split AbstractNode into Listener and CommonRegistratant, with duplicate functions contained in another superclass AbstractNode

// Q: should registration occur on startup even without a name, then name given later


// Centralised registration - broadcast approach

// 1:
// Registrant asks Registar if name is in use
// send: /checkRegister [bob]
// receive: /checkRegister-reply [bob] [free/inUse]
// if free, /register, if inUse, give error

// PROBLEM is we have to be able to reply to a specific registrant
// (one solution would be to send on a random port)

// 2:
// Registrant tries to register with given name
// send: /register [name]
// if free,
// if inUse, send /error

// either way we still have be able to send replies to the registrant before they are registered

// if the Registrar broadcasts the address book, we can collect these up and determine our port before sending

// For decentralised registration, new peers gather up the broadcasts from others before registering
// - broadcasts are received individually from existing peers

// For centralised registration, new peers gather up the broadcasts from the registrar
// - broadcasts are received from the registrar

// the process would be similar, on registration:
// 1. wait 2 * period to receive existing registrations
// 2. based on existing registrations
// - check if name in use, if not
// -- choose an ID
// -- repeatedly send a registration message \register [name] [ID]

// ANNOUNCEMENT

// once it is determined that the registration is valid:
// - the Registant should constantly send registration messages to the Registrar to keep the player alive
// - the Registrar should pass these on to all Registrants

// key point is that the Registrant does the management, ie.
// - sending the announcements and
// - checking if players are still online
// deregistration will stop this signal and the player will go offline


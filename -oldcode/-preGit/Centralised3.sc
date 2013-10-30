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



CentralisedRegistrant {

	// does the registrant need to checkOnline? If not we have a funcArray problem

	var registrarAddr, registrarPort;
	var <mePeer;

	*new {
		^super.new.initRegistrant;
	}

	initRegistrant {
		registrarPort = 60000;
		clientStartingPort = 60001; // baseport must = Registrant base port + 1;
		this.initAnnouncePeerResponder;
		this.initAnnounceRegistrarResponder;
	}

	// centralised registrant specific:
	initAnnounceRegistrarResponder {
		// responds to announce broadcasts coming from the registrar
		OSCFunc({arg msg, time, addr;
			if (registrarAddr.isNil) {
				inform("% setting registrar address to %".format(this.class, addr));
			};
			registrarAddr = NetAddr(addr.ip, registrarPort);
		}, '/announceRegistrar');
	}

	// centralised registrant specific (check registrar exists before trying to register)
	register {
		if (registrarAddr.notNil) {
			this.register2([{this.sendRegister}]);
		}
		{
			"registrar not yet found, try again later".postln;
		}
	}

	// centralised registrant specific
	// - registrants send to registrars
	// - decentralised peers send to
	sendRegister {
		registrarAddr.sendMsg('\register', mePeer.name, mePeer.id, mePeer.addr.port);
	}

}


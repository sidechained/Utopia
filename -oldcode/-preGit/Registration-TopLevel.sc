Registrar : AbstractNode {

	// a centralised server which handles registrations
	// IS a concrete implentation of an abstract NMLServer

	var broadcastAddr;
	var registrarPort;
	var funcArray;

	*new {
		^super.new.init;
	}

	init {
		super.init;
		this.initBroadcastAddr;
		NMLServer()
		// setup and open a registrar port
		// declare the functions that will be called in the event loop
		funcArray = [{this.announceAllPeers}, {this.announceRegistrar}, {this.checkPeersOnline}];
		this.startEventLoop; // start the event loop
	}

	initbroadcastAddr {
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	announceAllPeers {
		// broadcast details of all peers contained in the Registrar's address book
	}

	announceRegistrar {
		// broadcast a registrar announcement
	}

}

Registrant : AbstractNode {

	// a client which:
	// - announces itself as a peer to a registrar
	// - listens to announcements (about other peers, including self) from a registrar
	// IS a concrete implementation of an abstract NMLClient

	var broadcastAddr;
	var funcArray;
	var client;

	*new {
		^super.new.init;
	}

	init {
		super.init;
		this.initBroadcastAddr;
		client = NMLClient();
		// client starting port will be 60001 (leaves 60000 free for registrar)
		// - addr will be the addr of the registrar
		// - announcePeer will be called once for each peer in the address book
		funcArray = [{this.announcePeer(mePeer, registrarAddr)}, {this.checkRegistrarOnline}];
		this.listenForAnnouncementsFromRegistrar;
	}

	initbroadcastAddr {
		NetAddr.broadcastFlag = true;
		broadcastAddr = NMLNetAddrMP("255.255.255.255", 57120 + (0..7));
	}

	listenForAnnouncementsFromRegistrar {
		// receive '/announceRegistrar' [port] broadcasts coming from the registrar
		// set registrar address based on the receiving address + port
	}

	register {arg proposedName;
		// check if registrar (address) exists, if so
		// - call registration func - client.register(proposedName);
	}

	checkRegistrarOnline {

	}

}

DecentralisedNode : AbstractNode {

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
		super.init;
		// for decentralised nodes
		// - startingPort will be 60000 (no registrar);
		// - addr will be a broadcast addr
		// - announcePeer will be called once only for the
		funcArray = [{this.announcePeer(mePeer, broadcastAddr)},{this.checkPeersOnline}];
		NMLServer();
		client = NMLClient();
	}

	register {arg proposedName;
		client.register(proposedName);
	}

	deregister {
		client.deregister(proposedName);
	}

}

NMLServer {

	// things that both a Registrar and DecentralisedNode need

	// listens for broadcasts from existing clients
	// and add them to the address book

	*new {
		^super.new.init;
	}

	init {
		this.listenForAnnouncementsFromPeers;
	}

	listenForAnnouncementsFromPeers {
		// listen for existing peers on the network and add them to the address book
	}

}

NMLClient {

	// things that both a Registrant and DecentralisedNode need, i.e.
	// - initialising a peer which represents the client
	// - stores
	// - announces

	var startingPort;
	var <mePeer;

	*new {
		^super.new.init;
	}

	initMePeer {arg proposedName;
		// initialise my own peer, using the proposed name and other details (id, ip, port, onlineStatus):
		// - determine the next available ID
		// - determine the port (based on the ID)
		// - open the port
		// what address should be used here (local?)
	}

	register {arg proposedName;
		// check if propsed name is in use, if not
		// - initialise my own peer
		// - start the event loop
	}

	deregister {
		// set my own peers status to offline (broadcast will continue)
	}

	announcePeer {arg peer, addr;
		// announce a peer:
		// for centralised registrants
		// - addr will be the addr of the registrar
		// - announcePeer will be called once for each peer in the address book
		// for decentralised nodes
		// - addr will be a broadcast addr
		// - announcePeer will be called once only for the
	}

	getNextID {
		// determine next available ID
	}

}

AbstractNode {

	// contains variables and methods that registrars, registrants and decentralised nodes all use i.e.
	// - an address book
	// - an event loop and period at which to go round the loop

	// Q: do registrars, registrants and decentralised nodes all need a broadcast address?
	// A: no, only registrars and decentralised nodes, so put this functionality elsewhere

	// Q: do registrars, registrants and decentralised nodes all need to check if players are online?
	// A: no, only registrars and decentralised nodes, so put this functionality elsewhere
	// but registrant needs to check if registrar is online

	var <addrBook;
	var eventLoop, period;

	init {
		addrBook = AddrBook.new;
		period = 1;
		this.initEventLoop;
	}

	initEventLoop {
		// initialise a routine which will repeat at a given period
		// inside the loop, specific functionality is called (funcArray)
	}

	startEventLoop {
		// set the event loop playing
	}

	checkPeersOnline {
		// iterate through address book:
		// - if lastResponse of peer is > (now + 2 * period):
		// -- set peer's online status to false
		// -- set peer's id to nil
	}

}
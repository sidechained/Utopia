// * NML-Adressing Refactor

// - this rethink of NML-Addressing.sc makes it possible to simulate centralised and decentralised networks on a single machine
// - the biggest change that instead of going online manually using a name, peers go online automatically with a unique id
// - this unique id is allocated based on the existing peers on the network, no matter whether using a decentralised or centralised approach (different implentations of the same basic approach)
// - in this new approach, naming of peers comes later (and is called 'registration'...for now)
// - automatic registration is possible by setting autoName: true
// - currently, two peers may have the same name (unique names are not enforced)

// BENEFITS:
// - the main benefit of the new system (as I see it) is that communications take place on a port which is based on the peer's unique id. This means that even if all peers have the same IP (i.e. localhost, or a single LAN-side IP), their communications will not conflict (as they did previously). This makes it easier to develop networked systems using the other features of the Utopia library (OSCObjectSpaces, Relays, etc) and to bring this code to a group, without changes. This way, we can be confident that the system will work (less group debugging sessions, in theory!).
// - a secondary benefit is that this id can be used as a unique client ID for playing on each other's servers and for creating peer groups on servers (as SharedServer does in Republic).

// TESTS:
// - if all the above holds true, the following code should work on a single machine (please check)
// - please experiment with all the initialisation arguments for NMLDecentralisedNode, NMLRegistrar and NMLRegistrant.
// - cmdPeriod should not destroy the network

// ** DECENTRALISED NETWORKING

// create a single peer:
a = NMLDecentralisedNode(autoName: false, verbose: true, hasGui: true);
a.register(\peerA); // give peer a name
a.deregister; // set name back to nil
a.decommission; // simulate a crash
// introspection:
a.addrBook.peers; // all peers
a.addrBook.onlinePeers; // only peers who have recently been 'seen'
a.addrBook.registeredPeers; // only peers with names
a.myName;
a.myId;
a.myPort;
a.me; // 'me' should exist only once peer has come online for first time

// and another peer:
b = NMLDecentralisedNode(autoName: false, verbose: true, hasGui: true);
b.register(\peerB); // give peer a name
b.deregister; // set name back to nil
b.decommission; // simulate a crash
// introspection:
b.addrBook.peers; // all peers
b.addrBook.onlinePeers; // only peers who have recently been 'seen'
b.addrBook.registeredPeers; // only peers with names
a.myName;
a.myId;
a.myPort;
b.me; // should only exist once the player is online in their own address book

// Message Tests (simplest example of building a collaborative API on top of the basic functionality offered by the address book):
// peers 'a' and 'b' setup a responder:
OSCFunc({arg msg, time, addr, port; inform("% received %".format(a.me, msg)) }, '/testMessage', recvPort: a.me.addr.port);
OSCFunc({arg msg, time, addr, port; inform("% received %".format(b.me, msg)) }, '/testMessage', recvPort: b.me.addr.port);
// peer 'a' sends messages (either based on id, or if the player has register, then a name):
a.addrBook.sendId(0, '/testMessage', "aMessageFromTest");
a.addrBook.sendId(1, '/testMessage', "aMessageFromTest");
a.addrBook.sendName(\peerA, '/testMessage', "aMessageFromTest");
a.addrBook.sendName(\peerB, '/testMessage', "aMessageFromTest");
// peer 'b' sends messages (either based on id, or if the player has register, then a name):
b.addrBook.sendId(0, '/testMessage', "aMessageFromTest");
b.addrBook.sendId(1, '/testMessage', "aMessageFromTest");
b.addrBook.sendName(\peerA, '/testMessage', "aMessageFromTest");
b.addrBook.sendName(\peerB, '/testMessage', "aMessageFromTest");

// ** CENTRALISED NETWORKING

// first, we need a server:
c = NMLRegistrar();
c.addrBook.peers;
c.decommission;

// it shouldn't be possible to run a second server
// (the first server should recognise that another server is online and stop this one from starting)
d = NMLRegistrar();
d.addrBook.peers

// now we add  a client:
n = NMLRegistrant(autoName: true, verbose: true, hasGui: true);
n.addrBook.peers;
n.register(\clientN);
n.deregister;
n.decommission;
// introspection:
n.serverPeer; // a peer which contains the NetAddr of the server
n.addrBook.peers;
n.addrBook.onlinePeers;
n.addrBook.registeredPeers;
n.myName;
n.myId;
n.myPort;
n.me; // should only exist once the player is online in their own address book

// and another client:
m = NMLRegistrant(autoName: true, verbose: true, hasGui: true);
m.register(\clientM);
m.deregister;
m.decommission;
// introspection:
m.serverPeer;
m.addrBook.peers;
m.addrBook.onlinePeers;
m.addrBook.registeredPeers;
m.name;
m.id;
m.port;
m.me;

// Message Tests (simplest example of building a collaborative API on top of the basic functionality offered by the address book):
// peers 'n' and 'm' setup a responder:
OSCFunc({arg msg, time, addr, port; inform("% received %".format(n.me, msg)) }, '/testMessage', recvPort: n.me.addr.port);
OSCFunc({arg msg, time, addr, port; inform("% received %".format(m.me, msg)) }, '/testMessage', recvPort: m.me.addr.port);
// peer 'n' sends messages (either based on id, or if the player has register, then a name):
n.addrBook.sendId(0, '/testMessage', "aMessageFromTest");
n.addrBook.sendId(1, '/testMessage', "aMessageFromTest");
n.addrBook.sendName(\clientA, '/testMessage', "aMessageFromTest");
n.addrBook.sendName(\clientB, '/testMessage', "aMessageFromTest");
// peer 'm' sends messages (either based on id, or if the player has register, then a name):
m.addrBook.sendId(0, '/testMessage', "aMessageFromTest");
m.addrBook.sendId(1, '/testMessage', "aMessageFromTest");
m.addrBook.sendName(\clientA, '/testMessage', "aMessageFromTest");
m.addrBook.sendName(\clientB, '/testMessage', "aMessageFromTest");

// ** NMLAddrBook and NMLReporter tests
// - these are two simple classes for monitoring registrations and network activity
// - code below might not work, as I have recently made changes

// NMLAddrBookReporter and NMLAddrBookGUI tests
a = NMLAddrBook.new; r = NMLAddrBookReporter.new(a);
//g = NMLAddrBookGUI.new(a);
a.add(NMLPeer(id: 0, name: nil, addr: NetAddr("1.1.1.1", 50000), online: true, lastResponse: 100));
a.add(NMLPeer(id: 1, name: \grm, addr: NetAddr("1.1.1.1", 50000), online: true, lastResponse: 100));
a.add(NMLPeer(id: 2, name: nil, addr: NetAddr("1.1.1.1", 50000), online: false, lastResponse: nil));
a.registerPeer(0, \ana);
a.registerPeer(1, \grm);
a.registerPeer(2, \bob);
a.deregisterPeer(0);
a.deregisterPeer(1);
a.deregisterPeer(2);
a.takePeerOnline(2);
a.takePeerOffline(0);
a.takePeerOffline(1);
a.takePeerOffline(2);
a.updatePeerLastResponseTime(0, 100);
a.updatePeerLastResponseTime(1, 100);
a.updatePeerLastResponseTime(2, 100);
a.removeAt(0);
a.removeAt(1);
a.removeAt(2);

// NOTES:

// GENERAL TODO:
// TODO: probem: what if setId message doesn't get there - client will keep sending,
// TODO: clafify online/offline, reg/unreg behaviour and fix autonaming

// LATER:
// TODO: gui should work with server also
// TODO: centralised server should not be solely driven by client updates (should repeat offline messages)
// TODO: peers who come back online should reiuse IDs
// TODO: allow IDs to be reused if peer is offline and name is same
// TODO: simplify logic of server coming back online

// DONE: centralised pings only update once (check with many participants)
// DONE: rework elements back in from original Peer class
// DONE: decommissioning / recompiling destroys gui (locally) removes peers correctly (remotely)
// DONE: made it impossible to run two servers at once
// DONE: eventloop and responders survive cmd period (using skipjack for now: eventLoop immediately restarts)
// DONE: make surviveCmdPeriod an option
// DONE: centralised and decentralised now use id, name, port to store attributes prior to registration
// - then me is shorthand for looking up id in addrBook.
// DONE: unify inform and warn messages between classes
// DONE: reinstate checking of names in use before registration

// * DECENTRALISED NETWORKING
// TESTS:
// ^auto registration works:
// - when clients join after server clients get individual ids
// - NOTE: if two peers join within same cycle they will likely get the same ID
// ^autonaming
// ^modifying period
// ^setting up responders on top of the address book works:
// - using sendId or sendName, clients can successfully send messages to individual members of the address book
// ^modifying server and clientStartingPort works
// ^.register and .deregister works:
// - addrBook.registeredPeers returns only named peers
// ^.decommision:

// * CENTRALISED NETWORKING
// FIXES/ADDITIONS:
// TODO: rename classes to registrar and registrant
// TODO: make it possible to register direct after creating NMLRegistrant
// DONE: me now looks up address book entry (will always be the same)
// DONE: no warning when try to register when already registered
// DONE: no warning when try to deregister when not registered

// TESTS:
// * cannot tack .register method onto NMLRegistrant.new (would be good to be able to register on creation)
// ^autonaming works, but needs some checks:
// - clients are given a name based on the computer's own name
// - humans cannot register again using .register, without deregistering first
// - PROBLEM: two clients can register with the same autoname, because remote peers haven't yet appeared in the address book when autonaming happens - this means that autoname is happening too early

// modifying period works (period should no doubt be same for client and server):
// setting up responders on top of the address book works:
// - using sendId or sendName, clients can successfully send messages to individual members of the address book
// - note: if we try to set up a responder before a peer is registered, n.me throws an error (it should, probably)
// ^modifying server and clientStartingPort works
// ^client discovery of server works
// ^auto registration works:
// - when clients join after server clients get individual ids
// - no problems when server joins after clients have been created (server discovered, client autojoins);

// ^.register and .deregister work:
// - clients are given names on the server
// - and back on the client
// - addrBook.registeredPeers returns only named peers
// ^client .decommision does
// - works, client is taken offline on server and other clients
// ^server .decommision and restart
// - clients recognise that server went offline
// - clients recognise that server came back online

// * MISC NOTES

// try to support this use case: autoOnline = true & autoRegister = true
// option 1: keep online and registration separate stages
// when node is created:
// - node goes online
// - determine computers name
// - node registers with this name
// option 2: conflate online and registration into one stage
// - node will

// centralised approach:
// when server found, start automatic registration (getid)
// send temp id, receive back permanent id, set online to true

// decentralised approach:
// listen to broadcasts for a while, set permanent id, set online to true

// Q: should we see other peers coming in, even
// A: no, only if we set autoOnline


// when server peer is found,

// STAGES OF REGISTRATION:
// listen for others (monitor)
// announce self (go online)
// name self (register)
// dename self (deregister)
// stop announcing self (go offline)
// stop listening for others (demonitor)

// ISSUE: is it possible to 'monitor' with the current centralised approach
// this is because in order to receive updates from the server, you must be registered with it

// so we can condense this to two stages:
// - going online is monitoring
// - naming is registering

// peers will always try to go online as soon as they are created

// two problems:
// - should be able to call .register
// - should be able to autoname

// in a decentralised approach:
// - we can set the name before the node makes its first announcement (no need to wait), because:
// - remote peers are added to the local address book first
// - then we can check that the name is not in use by querying the address book

// in a centralised approach:
// - we have to wait until the the

// - we can send the name as part of the the initial /getId request
// - but we only know if the name is in use once it hits the server

// once the name is set, the peer is registered


// Q: does it matter if two
// Q: should
// A: as with IDs, always go through the server


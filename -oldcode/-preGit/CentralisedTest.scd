// TODO:
// - add GUI

// centralised:

// to do:
// when a registrant deregisters peers are not removed, why not?

r = CentralisedRegistrar();
r.addrBook.peers;

n = CentralisedRegistrant();
n.register(\test);
OSCFunc({arg msg, time, addr, port; msg.postln; }, '/testMessage', recvPort: n.myPeer.addr.port);
n.deregister;
n.addrBook.peers;
n.addrBook.send(\rest, '/testMessage', "aMessageFromTest");

m = CentralisedRegistrant();
m.register(\rest);
OSCFunc({arg msg, time, addr, port; msg.postln; }, '/testMessage', recvPort: m.myPeer.addr.port);
m.deregister;
m.addrBook.peers;
m.addrBook.send(\test, '/testMessage', "aMessageFromRest");

// decentralised:
// TO DO:

a = DecentralisedNode();
a.register(\jj);
OSCFunc({arg msg, time, addr, port; msg.postln; }, '/testMessage', recvPort: a.myPeer.addr.port);
a.deregister;
a.addrBook.peers;

b = DecentralisedNode();
b.register(\kk);
OSCFunc({arg msg, time, addr, port; msg.postln; }, '/testMessage', recvPort: b.myPeer.addr.port);
b.deregister;
b.addrBook.peers;
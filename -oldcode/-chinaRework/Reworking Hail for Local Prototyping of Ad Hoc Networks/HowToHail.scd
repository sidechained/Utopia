// Fixing issue with RDLaptops and RDDevices which makes them respond to everyone
// - need to ensure that the responder funcs are only created after the local peer has been assigned a port number
// - and that they only accept traffic from this local port number (recvPort)
// - currently they are created before

// For now, we don't want each peer's local loopback address to appear in the address book
// (i.e. peers register using their WAN side address)
// (note: the WAN side address will still be the same for all peers in a local prototyping situation)
// To achieve this we first make sure we don't called addrBook.addMe (is called traditionally in the examples)
// Then when creating the Hail, we pass the addrBook and the named local peer (with local NetAddr)
// ~hail = Hail(~addrBook, me: Peer(\me, NetAddr.localAddr));

// this means we can now attach a responder to the addrBook which checks the name of the most recently added peer
// if this peer has the same name as the local peer (and the address is not == localhost) then we can start the responder

(
// modified from Examples.scd
// main change is that ~addrBook.addMe has been removed, meaning the loopback netaddr will never be placed in the addrBook

~addrBook = AddrBook.new;

~me1Peer = Peer(\me1, NetAddr.localAddr);

~addrBook.addDependant({|addrBook, what, who|
	var isMe, ipIsNotLocal;
	isMe = who.name == ~me1Peer.name;
	ipIsNotLocal = who.addr.ip != "127.0.0.1";
	if (isMe && ipIsNotLocal, {"me1 added myself" + who}).postln;
});

~hail = Hail(~addrBook, me: ~me1Peer);
)

//make another participant
(
~me2Peer = Peer(\me2, NetAddr.localAddr);
~hail2 = Hail(me: ~me2Peer);
)

// \me2 goes offline
~hail2.free;

//cleanup
~hail.free;


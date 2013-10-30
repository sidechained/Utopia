// Local Hail Tests
// - an attempt to get hail working in a local prototyping context

// 1. In the current examples this is done manually
// such as the follow example with two peers adding each other (no hail)

(
thisProcess.openUDPPort(3000);
thisProcess.openUDPPort(3001);
~peer1 = Peer(\me1, NetAddr("127.0.0.1", 3000));
~peer2 = Peer(\me2, NetAddr("127.0.0.1", 3001));
~addrBook1 = AddrBook();
~addrBook1.addDependant({|addrBook, what, who|
	[\addrBook1, addrBook, what, who].postln;
});
~addrBook1.addMe(~peer1);

~addrBook2 = AddrBook();
~addrBook2.addDependant({|addrBook, what, who|
	[\addrBook2, addrBook, what, who].postln;
});
~addrBook2.addMe(~peer2);

~addrBook1.add(~peer2);
~addrBook2.add(~peer1);
)
// check address books:
~addrBook1.peers
~addrBook2.peers


// 2. I modified hail so that it could also be used in a loca
// No additional ports need be opened
// Two peers, automatically adding each other (with hail)
(
~peer1 = Peer(\me1, NetAddr.localAddr);
~peer2 = Peer(\me2, NetAddr.localAddr);

~addrBook1 = AddrBook();
~addrBook1.addMe(~peer1);

~addrBook2 = AddrBook();
~addrBook2.addMe(~peer2);

~hail1 = Hail(~addrBook1, me: ~peer1, oscPath: '/hail');
~hail2 = Hail(~addrBook2, me: ~peer2, oscPath: '/hail');
)
// check address books:
~addrBook1.peers
~addrBook2.peers

// 3. Abstract version for x amount of peers

// Two peers, automatically adding each other (with hail)
(
~join = {arg name;
	var peer, addrBook, hail;
	peer = Peer(name, NetAddr.localAddr);
	addrBook = AddrBook();
	addrBook.addDependant({|addrBook, what, who|
		//[peer.name, addrBook, what, who].postln;
	});
	addrBook.addMe(peer);
	hail = Hail(addrBook, me: peer, oscPath: '/hail');
	addrBook;
};
)

// first peer joins
~addrs1 = ~join.value(\me1);
// check addresses
~addrs1.peers

// second peer joins
~addrs2 = ~join.value(\me2);
// check addresses
~addrs1.peers
~addrs2.peers
)

// 4. A class for this kind of registration?
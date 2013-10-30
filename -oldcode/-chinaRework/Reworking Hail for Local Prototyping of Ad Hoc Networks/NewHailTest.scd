// it seems odd for hail to require a peer as an argument
// given that the addr and port won't really be known until the hail has begun
// surely name is enough here?

~grahamHail = Hail(me: Peer(\graham));
~grahamHail.addrBook.peers;
~jonasHail = Hail(me: Peer(\jonas));
~jonasHail.addrBook.peers;

~grahamHail.free
~jonasHail.free

// next: check that freeing up the NewHail will remove peers from addr books
// it does, but there are issues re-registering (if one hail is freed and then recreated, errors occur)

~laptop1 = RDLaptop(\laptop1);
~device1 = RDDevice(\device1);

// adding multiple devices works fine
~laptop2 = RDLaptop(\laptop2);
~device2 = RDDevice(\device2);

~device1.startSensing
~device1.stopSensing

~laptop1.sendLEDValue(\device1, 1) // both devices shouldn't receive this!
~laptop2.sendLEDValue(\device2, 1)

~laptop1.hailer.addrBook.peers

// problem:
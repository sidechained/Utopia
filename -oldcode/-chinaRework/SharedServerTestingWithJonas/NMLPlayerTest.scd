// Graham's code:

(
a = NMLPlayer(\graham, 3000, 1);
a.addServer(57111);
)

a.addrBook.peers
a.server
a.serverAddrs.keys
a.remoteServers

// Jonas' code:
(
b = NMLPlayer(\jonas, 3001, 2);
b.addServer(57112);
)

b.addrBook.peers
b.server
b.serverAddrs.keys
b.remoteServers

// Server.killAll


// FIXED: WARNING: OSCObjectSpace access attempt from unrecognised addr: a NetAddr(192.168.2.1, 57120)
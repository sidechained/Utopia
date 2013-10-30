a = AdHocNetwork(verbose: true, local: true); c = AdHocNetworkGUI(a); a.join(\a)
a.addrBook.peers
a.myName
a.myID
a.myPort
a.takenIDDict
a.leave
a.addrBook.send(\a, '/testResp', "hello")
a.addrBook.send(\b, '/testResp', "hello")

// can still send to be even when a has left

b = AdHocNetwork(verbose: true, local: true); d = AdHocNetworkGUI(b); b.join(\b)
b.addrBook.peers
b.myName
b.myID
b.myPort
b.takenIDDict
b.leave
b.addrBook.send(\a, '/testResp', "hello")
b.addrBook.send(\b, '/testResp', "hello")

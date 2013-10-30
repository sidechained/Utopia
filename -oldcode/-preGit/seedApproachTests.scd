// Tests:
// with two clients, works if:
// - server created before clients, clients created one by one
// - server created before clients, two clients created together
// - server created after clients

r = CentralisedServer.new;
r.addrBook.ids;
r.addrBook.peers;
r.decomission;
r.init;
c = CentralisedClient.new;
c.me.id;
c.addrBook.peers;
c.register(\aName);
c.decomission
d = CentralisedClient.new;
d.me.id;
c.addrBook.peers;
c.register(\bName);
